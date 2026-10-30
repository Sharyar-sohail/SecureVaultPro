#!/usr/bin/env node
'use strict';
/**
 * SVP-1 latency probe — the measurement the program is graded on, without a handset.
 *
 * Two independent database connections, both holding the server's own change stream open on one
 * node. Each round writes a sealed `{e,u,r,d}` record from one connection and times how long the
 * *other* connection takes to be told about it. That is the phone-to-web and web-to-phone path: one
 * backend, two frontends, push in both directions, with no polling anywhere in the measurement.
 *
 * It speaks the identical protocol to the app (see `protocol.js`), so a number here is a number the
 * APK and the Blogger page are subject to, not a synthetic stand-in.
 *
 * The Realtime Database rules only let a uid touch its own tree, so this needs a Firebase id token.
 * It is read from an environment variable and never printed, logged, or stored:
 *
 *   SVP_ID_TOKEN=... node tools/sync-probe/probe.js --uid <uid> --runs 5
 *
 * Add `--passphrase-env SVP_PASSPHRASE` to also prove this Node code opens the account's real cloud
 * keyring — the same wrap the phone wrote and the browser reads.
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { RtdbClient, DEFAULT_BASE } = require('./client');
const P = require('./protocol');

const TARGET_MEDIAN_MS = 1000;
const PER_WRITE_TIMEOUT_MS = 20000;

function parseArgs(argv) {
  const out = {
    uid: null,
    tokenEnv: 'SVP_ID_TOKEN',
    passphraseEnv: null,
    base: DEFAULT_BASE,
    node: null,
    runs: 5,
    payloadBytes: 512,
    out: null,
    keep: false,
    floor: false
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--uid': out.uid = next(); break;
      case '--token-env': out.tokenEnv = next(); break;
      case '--passphrase-env': out.passphraseEnv = next(); break;
      case '--base': out.base = next(); break;
      case '--node': out.node = next(); break;
      case '--runs': out.runs = Math.max(1, parseInt(next(), 10) || 5); break;
      case '--payload-bytes': out.payloadBytes = Math.min(65536, parseInt(next(), 10) || 512); break;
      case '--out': out.out = next(); break;
      case '--keep': out.keep = true; break;
      case '--transport-floor': out.floor = true; break;
      case '--help':
      case '-h':
        usage();
        process.exit(0);
        break;
      default:
        console.error(`Unknown argument: ${a}`);
        usage();
        process.exit(2);
    }
  }
  return out;
}

function usage() {
  console.log(`usage: probe.js --uid <firebase-uid> [options]

  --token-env NAME        env var holding the Firebase id token (default SVP_ID_TOKEN)
  --passphrase-env NAME   env var holding the vault passphrase; verifies the cloud keyring opens
  --base URL              database url (default the provisioned Singapore instance)
  --node PATH             node to write to (default svp1/users/<uid>/probe - never a vault table)
  --runs N                rounds per direction (default 5)
  --payload-bytes N       synthetic record size to seal (default 512)
  --out FILE              log path (default sync_engine/logs/sync-probe-<utc>.txt)
  --keep                  leave the probe node in the database instead of deleting it
  --transport-floor       no token: time the network path to the database only, and say so
`);
}

/** A stream subscription that remembers the newest arrival per child key, with waiters. */
function createCollector() {
  const latest = new Map();
  const waiters = new Map();
  return {
    arrived(id) {
      return latest.get(id);
    },
    total: 0,
    onEvent(frame) {
      if (frame.type !== 'put' && frame.type !== 'patch' && frame.type !== 'mergeset') return;
      const rel = String(frame.path || '/').replace(/^\/+/, '');
      const id = rel.split('/')[0];
      if (!id || id === '') return;
      const stamp = Date.now();
      if (frame.data === null) {
        latest.delete(id);
        return;
      }
      latest.set(id, { at: stamp, frame });
      this.total += 1;
      const list = waiters.get(id);
      if (list) {
        for (const w of list) w({ at: stamp, frame });
        waiters.delete(id);
      }
    },
    /** Resolves with the arrival stamp, or null if the push never came. */
    waitFor(id, timeoutMs = PER_WRITE_TIMEOUT_MS) {
      const known = latest.get(id);
      if (known && known.frame.data && known.frame.data.u !== undefined) return Promise.resolve(known);
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          const list = waiters.get(id) || [];
          const idx = list.indexOf(once);
          if (idx >= 0) list.splice(idx, 1);
          resolve(null);
        }, timeoutMs);
        const once = (hit) => {
          clearTimeout(timer);
          resolve(hit);
        };
        if (!waiters.has(id)) waiters.set(id, []);
        waiters.get(id).push(once);
      });
    },
    clear(id) {
      latest.delete(id);
    }
  };
}

function stats(values) {
  const v = [...values].sort((a, b) => a - b);
  if (v.length === 0) return null;
  const pick = (q) => v[Math.min(v.length - 1, Math.floor(q * (v.length - 1)))];
  return {
    n: v.length,
    min: v[0],
    median: v[Math.floor((v.length - 1) / 2)],
    mean: Math.round(v.reduce((a, b) => a + b, 0) / v.length),
    p95: pick(0.95),
    max: v[v.length - 1]
  };
}

function fingerprint(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 12);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.floor) return transportFloor(args);
  if (!args.uid) {
    usage();
    process.exit(2);
  }
  const token = (process.env[args.tokenEnv] || '').trim();
  if (!token) {
    console.error(`No id token in $${args.tokenEnv}. The database rules reject every anonymous request,`);
    console.error('so the probe cannot run without one. Sign in on the web companion and export the token:');
    console.error('  SVP_ID_TOKEN="$(await firebase.auth().currentUser.getIdToken())" node tools/sync-probe/probe.js --uid ...');
    console.error('');
    console.error('With no token you can still time the network path itself:');
    console.error('  node tools/sync-probe/probe.js --transport-floor');
    process.exit(2);
  }
  const nodePath = args.node || `svp1/users/${args.uid}/probe`;
  if (!nodePath.startsWith(`svp1/users/${args.uid}/`) && nodePath !== `svp1/users/${args.uid}`) {
    console.error(`Refusing to write outside the caller's own tree: ${nodePath}`);
    process.exit(2);
  }
  const vaultTables = ['keyring', 'data', 'meta'].some((leaf) => nodePath.endsWith(`/${leaf}`));
  if (vaultTables) {
    console.error('Refusing to probe a vault node; use a scratch path such as svp1/users/<uid>/probe.');
    process.exit(2);
  }

  const keyringCheck = args.passphraseEnv
    ? verifyKeyring(args, token)
    : Promise.resolve({ skipped: true });

  const clientA = new RtdbClient({ token, base: args.base });
  const clientB = new RtdbClient({ token, base: args.base });
  const streamA = createCollector();
  const streamB = createCollector();
  const abortA = new AbortController();
  const abortB = new AbortController();

  // The stream is opened *before* any write and held open, so the first round measures a live push.
  await clientA.get(nodePath).catch(() => null);
  const streams = [
    clientA.openStream(nodePath, { onEvent: (f) => streamA.onEvent(f), signal: abortA.signal }),
    clientB.openStream(nodePath, { onEvent: (f) => streamB.onEvent(f), signal: abortB.signal })
  ];
  streams.forEach((p, i) => p.catch((e) => {
    if (!/AbortError/.test(String(e))) console.error(`stream ${i} ended: ${e.message}`);
  }));
  await settle(250);

  const key = crypto.randomBytes(P.KEY_BYTES);
  const directions = [
    { name: 'connection-A -> connection-B', writer: clientA, listener: streamB, other: streamA },
    { name: 'connection-B -> connection-A', writer: clientB, listener: streamA, other: streamB }
  ];
  const rounds = [];
  let seq = 0;

  for (const direction of directions) {
    for (let i = 0; i < args.runs; i++) {
      seq += 1;
      const id = `probe-${Date.now()}-${seq}`;
      const plain = JSON.stringify({
        probe: true,
        seq,
        direction: direction.name,
        filler: 'x'.repeat(Math.max(0, args.payloadBytes - 40)),
        note: 'Synthetic probe record. Not vault data.'
      });
      const u = Date.now();
      const node = P.makeNode(plain, key, { u, r: seq });
      const tSend = Date.now();
      try {
        await direction.writer.put(`${nodePath}/${id}`, node);
      } catch (e) {
        console.error(`write ${seq} failed: ${e.message}`);
        process.exitCode = 1;
        break;
      }
      const tAck = Date.now();
      const hit = await direction.listener.waitFor(id);
      if (!hit) {
        console.error(`No push for write ${seq} within ${PER_WRITE_TIMEOUT_MS} ms on ${direction.name}.`);
        process.exitCode = 1;
        break;
      }
      rounds.push({
        seq,
        direction: direction.name,
        id,
        u,
        tSend,
        tAck,
        tReceive: hit.at,
        sendToAck: tAck - tSend,
        sendToReceive: hit.at - tSend,
        uToReceive: hit.at - u,
        ackToReceive: hit.at - tAck,
        echoed: hit.at === direction.other.arrived(id)?.at
      });
      const line = rounds[rounds.length - 1];
      console.log(
        `${String(seq).padStart(2)}. ${direction.name.padEnd(30)} ` +
        `ack ${String(line.sendToAck).padStart(4)} ms  ` +
        `push ${String(line.sendToReceive).padStart(4)} ms  ` +
        `(u->receive ${line.uToReceive} ms)`
      );
      await settle(120);
    }
  }

  const verified = await keyringCheck;

  abortA.abort();
  abortB.abort();
  if (!args.keep) {
    await clientA.remove(nodePath).catch((e) => console.error(`cleanup: ${e.message}`));
  }

  const perDirection = directions.map((d) => {
    const rs = rounds.filter((r) => r.direction === d.name);
    return {
      name: d.name,
      push: stats(rs.map((r) => r.sendToReceive)),
      ack: stats(rs.map((r) => r.sendToAck)),
      clockDelta: stats(rs.map((r) => r.uToReceive)),
      samples: rs
    };
  });

  const all = rounds.map((r) => r.sendToReceive);
  const overall = stats(all);
  const logPath = resolveOut(args.out);
  writeLog({ logPath, args, nodePath, overall, perDirection, verified, rounds });

  console.log('');
  for (const d of perDirection) {
    if (!d.push) {
      console.log(`${d.name}: no completed rounds`);
      continue;
    }
    console.log(
      `${d.name}: n=${d.push.n} median ${d.push.median} ms min ${d.push.min} max ${d.push.max} p95 ${d.push.p95}`
    );
  }
  if (overall) {
    console.log(`\nOverall median push ${overall.median} ms across ${overall.n} writes ` +
      `(target < ${TARGET_MEDIAN_MS} ms).`);
    if (overall.median >= TARGET_MEDIAN_MS) process.exitCode = 1;
  } else {
    process.exitCode = 1;
  }
  console.log(`Log: ${logPath}`);
}

async function verifyKeyring(args, token) {
  const passphrase = process.env[args.passphraseEnv];
  if (!passphrase) return { attempted: false, reason: `$${args.passphraseEnv} is not set` };
  const client = new RtdbClient({ token, base: args.base });
  const node = await client.get(`svp1/users/${args.uid}/keyring`).catch((e) => {
    console.error(`keyring read failed: ${e.message}`);
    return null;
  });
  if (!node) return { attempted: true, opened: false, reason: 'no keyring node' };
  const opened = P.openKeyring(node, passphrase);
  return {
    attempted: true,
    opened: opened !== null,
    algorithm: node.alg,
    iterations: node.iters,
    saltBytes: P.SALT_BYTES,
    keyFingerprint: opened ? fingerprint(opened) : null,
    keyBytes: opened ? opened.length : null
  };
}

function resolveOut(explicit) {
  if (explicit) return path.resolve(explicit);
  const dir = path.resolve(__dirname, '../../sync_engine/logs');
  fs.mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(dir, `sync-probe-${stamp}.txt`);
}

function writeLog({ logPath, args, nodePath, overall, perDirection, verified, rounds }) {
  const lines = [];
  lines.push('SVP-1 realtime sync latency log');
  lines.push(`captured: ${new Date().toISOString()}`);
  lines.push(`database: ${args.base}`);
  lines.push(`node:     ${nodePath} (synthetic probe records; no vault table was written)`);
  lines.push(`rounds per direction: ${args.runs}, payload ${args.payloadBytes} bytes, sealed AES-256-GCM`);
  lines.push('channel: the database server-sent event stream held open on two connections (push, not polling)');
  lines.push('');
  lines.push('measurements');
  lines.push('  sendToAck     = the write call returns (server honoured it)');
  lines.push('  sendToReceive = another connection is told about it - this is the sync delay');
  lines.push('  uToReceive    = receive minus the clock the writer stamped into the record');
  lines.push('');
  for (const d of perDirection) {
    lines.push(`direction: ${d.name}`);
    if (d.push) {
      lines.push(`  writes completed : ${d.push.n}`);
      lines.push(`  push  median/min/max/p95 (ms): ${d.push.median} / ${d.push.min} / ${d.push.max} / ${d.push.p95}`);
      lines.push(`  write-ack median (ms): ${d.ack.median}`);
      lines.push(`  u->receive median (ms): ${d.clockDelta.median}`);
    } else {
      lines.push('  no completed writes');
    }
    d.samples.forEach((s) => {
      lines.push(
        `  #${s.seq} send ${s.tSend} ack ${s.tAck} receive ${s.tReceive} ` +
        `u ${s.u} push ${s.sendToReceive}ms ack ${s.sendToAck}ms`
      );
    });
    lines.push('');
  }
  lines.push(overall ? `overall median push: ${overall.median} ms over ${overall.n} writes` : 'overall: no writes');
  lines.push(`target: median under ${TARGET_MEDIAN_MS} ms`);
  if (verified && verified.attempted) {
    lines.push('');
    lines.push('cross-frontend crypto check (this Node code opening the app-written cloud keyring)');
    lines.push(`  algorithm: ${verified.algorithm || 'n/a'} iterations: ${verified.iterations || 'n/a'}`);
    lines.push(`  opened: ${verified.opened ? 'yes' : `no (${verified.reason || 'wrong passphrase'})`}`);
    if (verified.keyFingerprint) {
      lines.push(`  vault key fingerprint (sha256/12, not the key): ${verified.keyFingerprint}`);
      lines.push(`  key length: ${verified.keyBytes} bytes`);
    }
  } else if (verified && verified.skipped) {
    lines.push('');
    lines.push('cross-frontend crypto check: skipped (no --passphrase-env)');
  }
  lines.push('');
  lines.push(`raw rounds: ${rounds.length}`);
  lines.push(JSON.stringify(rounds.map((r) => ({
    seq: r.seq, direction: r.direction, u: r.u, tSend: r.tSend, tAck: r.tAck,
    tReceive: r.tReceive, pushMs: r.sendToReceive, ackMs: r.sendToAck
  })), null, 2));
  fs.mkdirSync(path.dirname(logPath), { recursive: true });
  fs.writeFileSync(logPath, `${lines.join('\n')}\n`, 'utf8');
}

/**
 * The network floor, with no token and no writes.
 *
 * The rules reject every anonymous request, so this cannot measure a sync — it measures the road a
 * sync travels: DNS + TLS + HTTP to the Singapore region and back, warm and cold, plus how long the
 * database takes to answer on an already-open connection. Those numbers are the lower bound the
 * phone-to-web delay can never beat, and they are the ones to compare against when a real token is
 * available. The log says plainly that it is a floor and not a sync measurement.
 */
async function transportFloor(args) {
  const samples = Math.max(3, args.runs * 2);
  const target = `${args.base}/svp1/transport-probe.json`;
  const timings = [];
  const streamTimings = [];

  console.log(`Probing ${args.base} (anonymous; the rules reject these, which is the point).`);
  for (let i = 0; i < samples; i++) {
    const started = performance.now();
    let status = 0;
    try {
      const res = await fetch(target, { method: 'GET', headers: { Accept: 'application/json' }, cache: 'no-store' });
      status = res.status;
      await res.arrayBuffer();
    } catch (e) {
      status = -1;
      console.error(`sample ${i + 1} failed: ${e.message}`);
    }
    const elapsed = Math.round(performance.now() - started);
    timings.push({ i: i + 1, ms: elapsed, status });
    console.log(`${String(i + 1).padStart(2)}. round trip ${String(elapsed).padStart(6)} ms  HTTP ${status}`);
  }

  const streamStarted = performance.now();
  try {
    const controller = new AbortController();
    const opened = fetch(`${args.base}/svp1/transport-probe.json`, {
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal
    }).then(async (res) => {
      streamTimings.push({ ttfb: Math.round(performance.now() - streamStarted), status: res.status });
      controller.abort();
    });
    await Promise.race([opened, settle(15000)]);
  } catch (e) {
    console.error(`stream open failed: ${e.message}`);
  }

  const values = timings.filter((t) => t.status > 0).map((t) => t.ms);
  const summary = stats(values);
  const logPath = resolveOut(args.out || defaultFloorPath());
  const lines = [
    'SVP-1 network floor (NOT a sync measurement)',
    `captured: ${new Date().toISOString()}`,
    `database: ${args.base}`,
    '',
    'The realtime rules require a signed-in uid, so every sample below is an anonymous request the',
    'server rejected. Rejection is served by the same Firebase edge that serves the realtime traffic,',
    'so the round-trip times are the region round trip this deployment sits on - the floor under any',
    'phone-to-web push. The push itself is measured with a token:',
    '  SVP_ID_TOKEN=... node tools/sync-probe/probe.js --uid <uid> --runs 5',
    '',
    `samples: ${values.length} (HTTP 401 expected)`
  ];
  if (summary) {
    lines.push(
      `round trip min/median/mean/p95/max (ms): ${summary.min} / ${summary.median} / ${summary.mean} / ${summary.p95} / ${summary.max}`
    );
  }
  if (streamTimings.length) {
    lines.push(`event-stream time to first byte (ms): ${streamTimings[0].ttfb} (HTTP ${streamTimings[0].status})`);
  }
  lines.push('');
  timings.forEach((t) => lines.push(`  #${t.i} ${t.ms} ms HTTP ${t.status}`));
  fs.mkdirSync(path.dirname(logPath), { recursive: true });
  fs.writeFileSync(logPath, `${lines.join('\n')}\n`, 'utf8');

  if (summary) {
    console.log(`\nFloor median ${summary.median} ms, min ${summary.min} ms, p95 ${summary.p95} ms to the database region.`);
    console.log('This is the road, not the delay. Run with --uid and a token for the sync numbers.');
  } else {
    process.exitCode = 1;
  }
  console.log(`Log: ${logPath}`);
}

function defaultFloorPath() {
  const dir = path.resolve(__dirname, '../../sync_engine/logs');
  fs.mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(dir, `transport-floor-${stamp}.txt`);
}

function settle(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

if (require.main === module) {
  main().catch((e) => {
    console.error(e && e.message ? e.message : e);
    process.exit(1);
  });
}

module.exports = { parseArgs, stats, createCollector };
