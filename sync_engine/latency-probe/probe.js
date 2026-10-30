#!/usr/bin/env node
'use strict';
/**
 * SVP-1 transport latency probe — MEASURED, from this machine, with Node built-ins only.
 *
 * Times every stage of building the realtime connection to the provisioned Firebase RTDB host:
 *   DNS (OS cache + network resolve), TCP connect, TLS handshake, TLS session-resumption reuse,
 *   a hand-built RFC 6455 WebSocket upgrade against the RTDB `/.ws` endpoint (time to the 101
 *   response and RTT of the first server frame), and the HTTP event-stream TTFB the SDK falls
 *   back to. These are the phases a real push rides on; their numbers are the floor no
 *   phone-to-web sync can beat.
 *
 * Secrets: none. Anonymous requests are rejected by the per-uid rules, and the rejection is
 * served by the same edge that serves realtime traffic, so handshake timing is still valid.
 * Optionally set SVP_ID_TOKEN=<token> in the environment to attach `auth=` to the WS upgrade;
 * the token is never printed, logged, or stored, and absence changes nothing but the status code.
 *
 *   node sync_engine/latency-probe/probe.js [--samples 15] [--ws-samples 12] [--out FILE]
 */

const net = require('node:net');
const tls = require('node:tls');
const dns = require('node:dns');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const HOST = process.env.SVP_RMDB_HOST ||
  'vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app';
const PORT = 443;
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function parseArgs(argv) {
  const out = { samples: 15, wsSamples: 12, out: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--samples') out.samples = Math.min(30, Math.max(3, parseInt(next(), 10) || 15));
    else if (a === '--ws-samples') out.wsSamples = Math.min(30, Math.max(3, parseInt(next(), 10) || 12));
    else if (a === '--out') out.out = next();
    else if (a === '--help' || a === '-h') { console.log(usage()); process.exit(0); }
    else { console.error(`Unknown argument: ${a}`); process.exit(2); }
  }
  return out;
}
function usage() {
  return 'usage: node sync_engine/latency-probe/probe.js [--samples N] [--ws-samples N] [--out FILE]';
}

const now = () => Number(process.hrtime.bigint() / 1000n) / 1000; // ms, monotonic
const settle = (ms) => new Promise((r) => setTimeout(r, ms));

function stats(values) {
  const v = [...values].sort((a, b) => a - b);
  if (!v.length) return null;
  const at = (q) => v[Math.min(v.length - 1, Math.floor(q * (v.length - 1)))];
  const r2 = (x) => Math.round(x * 100) / 100;
  return { n: v.length, min: r2(v[0]), median: r2(v[Math.floor((v.length - 1) / 2)]), p95: r2(at(0.95)), max: r2(v[v.length - 1]) };
}

function timed(fn) {
  return (async () => {
    const t = now();
    const meta = await fn();
    return { ms: Math.round((now() - t) * 100) / 100, meta: meta || {} };
  })();
}

// ---------------------------------------------------------------- phases

function phaseDns(n) {
  return (async () => {
    const lookup = [];
    const resolve = [];
    for (let i = 0; i < n; i++) {
      lookup.push(await timed(() => new Promise((resolve, reject) =>
        dns.lookup(HOST, { verbatim: true }, (e, address) => e ? reject(e) : resolve({ address }))
      )).catch(() => ({ ms: -1, meta: { err: 'lookup failed' } })));
      resolve.push(await timed(() => new Promise((res) =>
        dns.resolve4(HOST, (e, a) => res(e ? null : { addresses: a && a.length }))
      )));
    }
    return { lookup, resolve };
  })();
}

function tcpConnect() {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host: HOST, port: PORT });
    sock.setTimeout(15000);
    sock.once('connect', () => { sock.destroy(); resolve(); });
    sock.once('timeout', () => { sock.destroy(); reject(new Error('tcp timeout')); });
    sock.once('error', (e) => { sock.destroy(); reject(e); });
  });
}

function tlsConnect(opts) {
  return new Promise((resolve, reject) => {
    const t0 = now();
    const sock = tls.connect(Object.assign({
      host: HOST, port: PORT, servername: HOST, ALPNProtocols: ['http/1.1'], timeout: 15000
    }, opts || {}), () => {
      if (opts && opts.__probeTcpStart) { /* full time includes TCP; caller subtracts */ }
      resolve({
        sock,
        totalMs: now() - t0,
        version: sock.getProtocol(),
        cipher: sock.getCipher() && sock.getCipher().name,
        reused: sock.isSessionReused(),
        session: (() => { try { return sock.getSession(); } catch (e) { return null; } })()
      });
    });
    sock.once('timeout', () => { sock.destroy(); reject(new Error('tls timeout')); });
    sock.once('error', (e) => { try { sock.destroy(); } catch (_) {} reject(e); });
  });
}

async function phaseTcp(n) {
  const samples = [];
  for (let i = 0; i < n; i++) {
    let s;
    try { s = await timed(() => tcpConnect()); }
    catch (e) { s = { ms: -1, meta: { err: e.message } }; }
    samples.push(s);
  }
  return samples;
}

async function phaseTls(n) {
  const full = [];       // TCP+TLS together (cold, fresh socket)
  const versions = {};
  const ciphers = {};
  let resumeSample = null;
  for (let i = 0; i < n; i++) {
    let r;
    try { r = await tlsConnect({}); }
    catch (e) { full.push({ ms: -1, meta: { err: e.message } }); continue; }
    full.push({ ms: Math.round(r.totalMs * 100) / 100, meta: {} });
    versions[r.version] = (versions[r.version] || 0) + 1;
    ciphers[r.cipher] = (ciphers[r.cipher] || 0) + 1;
    // one session-resumption attempt per pass, on the first good session we can grab
    if (!resumeSample && r.session) {
      try {
        const rr = await tlsConnect({ session: r.session });
        resumeSample = {
          ms: Math.round(rr.totalMs * 100) / 100,
          reused: rr.reused, version: rr.version
        };
        rr.sock.destroy();
      } catch (e) { resumeSample = { ms: -1, err: e.message }; }
    }
    r.sock.destroy();
    await settle(60);
  }
  return { full, versions, ciphers, resumeSample };
}

// ---------------------------------------------------------------- websocket upgrade (RFC 6455, hand-built)

function buildUpgradeRequest(p, key) {
  return [
    `GET ${p} HTTP/1.1`,
    `Host: ${HOST}`,
    'Upgrade: websocket',
    'Connection: Upgrade',
    `Sec-WebSocket-Key: ${key}`,
    'Sec-WebSocket-Version: 13',
    'Origin: https://securevaultpro.blogspot.com',
    '', ''
  ].join('\r\n');
}

function maskClientFrame(opcode, payload) {
  const mask = crypto.randomBytes(4);
  const len = payload.length;
  let header;
  if (len < 126) header = Buffer.from([0x80 | opcode, 0x80 | len]);
  else if (len < 65536) {
    header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 0x80 | 126; header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 0x80 | 127; header.writeUInt32BE(0, 2); header.writeUInt32BE(len, 6);
  }
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
  return Buffer.concat([header, mask, masked]);
}

/** Incremental parser: feed chunks, get the first complete server frame (server frames are unmasked). */
function frameParser() {
  let buf = Buffer.alloc(0);
  return {
    feed(chunk) {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length < 2) return null;
      const b0 = buf[0], b1 = buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0; // illegal from a server
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (buf.length < off + 2) return null; len = buf.readUInt16BE(off); off += 2; }
      else if (len === 127) { if (buf.length < off + 8) return null; len = Number(buf.readUInt32BE(off + 4)); off += 8; }
      if (masked) return { error: 'server frame was masked' };
      if (buf.length < off + len) return null;
      const payload = buf.subarray(off, off + len);
      buf = buf.subarray(off + len);
      return { opcode, length: len, payload };
    }
  };
}

async function wsUpgradeOnce(pathname, token) {
  return new Promise((resolve) => {
    const outcome = {
      path: pathname, status: 0, statusText: '', upgradeMs: -1, firstFrameMs: -1,
      acceptOk: null, firstOpcode: null, firstLen: null, firstPreview: '', err: null, ss: null
    };
    let sock;
    const fail = (e) => { outcome.err = String((e && e.message) || e); try { sock && sock.destroy(); } catch (_) {} resolve(outcome); };
    tlsConnect({})
      .then(({ sock: tlsSock, totalMs }) => {
        sock = tlsSock;
        outcome.ss = Math.round(totalMs * 100) / 100; // TCP+TLS on this socket, for reference
        const key = crypto.randomBytes(16).toString('base64');
        const p = token ? `${pathname}${pathname.includes('?') ? '&' : '?'}auth=${encodeURIComponent(token)}` : pathname;
        let headersDone = false;
        let head = Buffer.alloc(0);
        const parser = frameParser();
        const tReq = now();
        sock.write(buildUpgradeRequest(p, key));
        const overall = setTimeout(() => fail(new Error('ws timeout')), 20000);
        sock.on('data', (chunk) => {
          if (!headersDone) {
            head = Buffer.concat([head, chunk]);
            const idx = head.indexOf('\r\n\r\n');
            if (idx < 0) { if (head.length > 65536) fail(new Error('header overflow')); return; }
            headersDone = true;
            const text = head.subarray(0, idx).toString('latin1');
            const m = /^HTTP\/1\.1 (\d{3})(.*)$/.exec(text.split('\r\n')[0] || '');
            outcome.status = m ? parseInt(m[1], 10) : 0;
            outcome.statusText = m ? m[2].trim() : '';
            outcome.upgradeMs = Math.round((now() - tReq) * 100) / 100;
            const acc = /sec-websocket-accept: *([^\r\n]+)/i.exec(text);
            if (outcome.status === 101 && acc) {
              outcome.acceptOk = acc[1].trim() ===
                crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
              // prove the client side of the frame codec too: send a masked ping, then close
              try { sock.write(maskClientFrame(0x9, Buffer.from('svp-probe'))); } catch (_) {}
            }
            const rest = head.subarray(idx + 4);
            if (rest.length) handleFrame(rest);
            if (outcome.status !== 101) { clearTimeout(overall); sock.destroy(); resolve(outcome); }
            return;
          }
          handleFrame(chunk);
        });
        function handleFrame(chunk) {
          const f = parser.feed(chunk);
          if (!f || f.error || outcome.firstFrameMs >= 0) { if (f && f.error) fail(new Error(f.error)); return; }
          outcome.firstFrameMs = Math.round((now() - tReq) * 100) / 100;
          outcome.firstOpcode = f.opcode;
          outcome.firstLen = f.length;
          outcome.firstPreview = f.payload.toString('latin1').slice(0, 90).replace(/[\x00-\x1f]/g, ' ');
          clearTimeout(overall);
          try { sock.write(maskClientFrame(0x8, Buffer.from([0x03, 0xe8]))); } catch (_) {}
          setTimeout(() => { try { sock.destroy(); } catch (_) {} resolve(outcome); }, 50);
        }
        sock.once('error', (e) => { if (!headersDone || outcome.firstFrameMs < 0) { clearTimeout(overall); fail(e); } });
      })
      .catch((e) => fail(e));
  });
}

async function phaseWs(n, token) {
  const paths = ['/.ws', '/svp1/.ws'];
  const results = [];
  for (let i = 0; i < n; i++) {
    const p = paths[i % paths.length];
    results.push(await wsUpgradeOnce(p, token));
  }
  return results;
}

// ---------------------------------------------------------------- SSE TTFB (SDK fallback transport)

function sseTtfb() {
  return new Promise((resolve) => {
    const outcome = { status: 0, ttfbMs: -1, err: null };
    const sock = tls.connect({ host: HOST, port: PORT, servername: HOST, ALPNProtocols: ['http/1.1'], timeout: 15000 }, () => {
      const t0 = now();
      sock.write([
        'GET /svp1/transport-probe.json?timeout=30s HTTP/1.1',
        `Host: ${HOST}`,
        'Accept: text/event-stream',
        'Cache-Control: no-store',
        '', ''
      ].join('\r\n'));
    });
    const done = () => { clearTimeout(cap); try { sock.destroy(); } catch (_) {} resolve(outcome); };
    const cap = setTimeout(() => { outcome.err = outcome.err || 'timeout'; done(); }, 20000);
    let started = false; let headBuf = Buffer.alloc(0);
    sock.on('data', (chunk) => {
      if (!started) {
        const t0 = 0; void t0;
        headBuf = Buffer.concat([headBuf, chunk]);
        if (headBuf.length < 15) return;
        outcome.ttfbMs = Math.round((now() - sock.__t0) * 100) / 100;
        const line = headBuf.subarray(0, 40).toString('latin1').split(' ')[1];
        outcome.status = parseInt(line, 10) || 0;
        started = true;
        done();
        return;
      }
    });
    sock.once('connect', () => { sock.__t0 = now(); });
    // move the timer start to request-write below; re-stamp precisely:
    const origWrite = sock.write.bind(sock);
    sock.write = (data) => { sock.__t0 = now(); return origWrite(data); };
    sock.once('error', (e) => { outcome.err = e.message; done(); });
    sock.once('timeout', () => { outcome.err = 'timeout'; done(); });
  });
}

async function phaseSse(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(await sseTtfb());
  return out;
}

// ---------------------------------------------------------------- report

function table(rows) {
  const w = [24, 5, 9, 9, 9, 9, 9];
  const head = ['phase', 'n', 'min', 'median', 'p95', 'max', ''];
  const lines = [head.map((h, i) => String(h).padEnd(w[i])).join(' ').trimEnd(), '-'.repeat(76)];
  for (const r of rows) {
    lines.push([r.name.padEnd(24), String(r.s.n).padEnd(5), String(r.s.min).padEnd(9),
      String(r.s.median).padEnd(9), String(r.s.p95).padEnd(9), String(r.s.max).padEnd(9)].join(' ').trimEnd());
  }
  return lines.join('\n');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const token = (process.env.SVP_ID_TOKEN || '').trim() || null;
  console.log(`SVP-1 transport latency probe — host ${HOST}:443`);
  console.log(`node ${process.version}  ${new Date().toISOString()}  samples=${args.samples} ws=${args.wsSamples} token=${token ? 'from env (not printed)' : 'none (anonymous: rules reject, handshake still timed)'}`);
  console.log('');

  const dnsPh = await phaseDns(args.samples);
  const tcpPh = await phaseTcp(args.samples);
  const tlsPh = await phaseTls(args.samples);
  const wsPh = await phaseWs(args.wsSamples, token);
  const ssePh = await phaseSse(Math.min(5, args.samples));

  const dnsOk = dnsPh.lookup.filter((s) => s.ms >= 0).map((s) => s.ms);
  const dnsNet = dnsPh.resolve.filter((s) => s.ms >= 0 && s.meta.addresses).map((s) => s.ms);
  const tcpOk = tcpPh.filter((s) => s.ms >= 0).map((s) => s.ms);
  const tlsOk = tlsPh.full.filter((s) => s.ms >= 0).map((s) => s.ms);
  const ws101 = wsPh.filter((r) => r.status === 101);
  const wsOther = wsPh.filter((r) => r.status !== 101 && r.status > 0);
  const rows = [];
  const push = (name, vals) => { if (vals.length) rows.push({ name, s: stats(vals) }); };
  push('dns lookup (cached)', dnsOk);
  push('dns resolve4 (network)', dnsNet);
  push('tcp connect', tcpOk);
  push('tcp+tls full connect', tlsOk);
  push('ws upgrade->response', wsPh.filter((r) => r.upgradeMs >= 0).map((r) => r.upgradeMs));
  if (ws101.length) push('ws -> first server frame', ws101.map((r) => r.firstFrameMs).filter((v) => v >= 0));
  push('http event-stream TTFB', ssePh.filter((r) => r.ttfbMs >= 0).map((r) => r.ttfbMs));

  console.log(table(rows));
  console.log('');
  console.log(`tls versions: ${JSON.stringify(tlsPh.versions)}  ciphers: ${JSON.stringify(Object.keys(tlsPh.ciphers))}`);
  console.log(`tls session resumption: ${tlsPh.resumeSample
    ? (tlsPh.resumeSample.reused ? `REUSED, connect ${tlsPh.resumeSample.ms} ms (${tlsPh.resumeSample.version})`
      : `server did not reuse session (${tlsPh.resumeSample.ms} ms)`)
    : 'no session ticket offered'}`);
  const byStatus = {};
  wsPh.forEach((r) => { const k = `${r.path} -> ${r.status || 'ERR'} ${r.statusText || r.err || ''}`.trim(); byStatus[k] = (byStatus[k] || 0) + 1; });
  console.log(`ws upgrade outcomes: ${JSON.stringify(byStatus, null, 0)}`);
  if (ws101.length) {
    const accOk = ws101.every((r) => r.acceptOk === true);
    console.log(`ws 101 count: ${ws101.length}/${wsPh.length}  Sec-WebSocket-Accept verified: ${accOk ? 'ALL OK' : 'MISMATCH'}`);
    const first = ws101[0];
    console.log(`first server frame sample: opcode ${first.firstOpcode} len ${first.firstLen} bytes, ${first.firstFrameMs} ms after upgrade, preview: "${first.firstPreview}"`);
  } else {
    console.log(`no 101: ${wsOther.length} non-101 responses (upgrade timing above still measures TLS-edge handshake cost; see status list)`);
  }

  if (args.out) {
    const payload = {
      captured: new Date().toISOString(), host: HOST, node: process.version,
      samples: args.samples, dns: dnsPh, tcp: tcpPh, tls: { versions: tlsPh.versions, full: tlsPh.full, resume: tlsPh.resumeSample },
      ws: wsPh.map((r) => ({ path: r.path, status: r.status, upgradeMs: r.upgradeMs, firstFrameMs: r.firstFrameMs, opcode: r.firstOpcode, len: r.firstLen, acceptOk: r.acceptOk, err: r.err })),
      sse: ssePh, summary: rows
    };
    fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true });
    fs.writeFileSync(args.out, JSON.stringify(payload, null, 2));
    console.log(`raw samples: ${args.out}`);
  }
}

main().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
