'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync, spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../..');
const GENERATOR = path.join(__dirname, 'generate.js');
const template = fs.readFileSync(path.join(__dirname, 'secure_wallet_blogger_theme.xml'), 'utf8');

function generate(t, args = [], envelope) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'svp-theme-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const out = path.join(dir, 'theme.xml');
  if (envelope) {
    const input = path.join(dir, 'backup.enc');
    fs.writeFileSync(input, JSON.stringify(envelope));
    args = [...args, '--in', input];
  }
  execFileSync(process.execPath, [path.join(__dirname, 'generate.js'), '--out', out, ...args], { stdio: 'pipe' });
  return fs.readFileSync(out, 'utf8');
}

function script(xml) {
  return xml.split('//<![CDATA[')[1].split('//]]>')[0];
}

test('default Web OAuth client matches app project without importing secrets', t => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'google-services.json'), 'utf8'));
  const client = config.client.find(c => c.client_info.android_client_info.package_name === 'com.securevault.app');
  const web = client.oauth_client.find(c => c.client_type === 3).client_id;
  const xml = generate(t);
  assert.ok(xml.includes("var DEFAULT_CLIENT_ID = '" + web + "';"));
  // Composed at runtime so this guard does not itself contain the string it bans — same
  // technique the Kotlin UniversalKeyGuardTest uses.
  const retiredKey = 'SecureVaultPro_Universal' + '_Cloud_Key';
  assert.ok(!xml.includes(retiredKey));
  const fixtureSecret = 'test' + 'pass123';
  assert.ok(!xml.includes(fixtureSecret));
  assert.doesNotThrow(() => new vm.Script(script(xml)));
});

test('custom client and snapshot survive generator injection', t => {
  const envelope = { v: 3, salt: Buffer.alloc(16, 1).toString('base64'), data: Buffer.alloc(12, 2).toString('base64') + '.' + Buffer.alloc(32, 3).toString('base64') };
  const client = '1234-test.apps.googleusercontent.com';
  const xml = generate(t, ['--drive-client-id', client], envelope);
  const seedLine = script(xml).match(/var SEED = \{[^}]*\};/)[0];
  const seed = vm.runInNewContext(seedLine + '; SEED;');
  assert.equal(seed.clientId, client);
  assert.equal(seed.salt, envelope.salt);
  assert.equal(seed.iv + '.' + seed.ct, envelope.data);
  assert.equal(seed.iters, 600000);
});

test('title remains literal even with template interpolation and replacement tokens', t => {
  const title = 'Vault ${globalThis.compromised = true} ` \\ $& </script> & "';
  const xml = generate(t, ['--title', title]);
  const js = script(xml);
  assert.doesNotThrow(() => new vm.Script(js));
  const markupLine = js.match(/var UI_MARKUP = `[\s\S]*?`;\s*document\.body/)[0].replace(/\s*document\.body$/, '');
  const context = vm.createContext({});
  vm.runInContext(markupLine, context);
  assert.equal(context.compromised, undefined);
  assert.ok(context.UI_MARKUP.includes('${globalThis.compromised = true}'));
  assert.ok(context.UI_MARKUP.includes('` \\ $&amp; &lt;/script&gt; &amp; &quot;'));
});

test('malformed backup is rejected', t => {
  assert.throws(() => generate(t, [], { v: 3, salt: 'bad', data: 'invalid' }));
});

test('the Android asset is this project\u2019s generated theme, key included', t => {
  // The app's own theme export starts from the bundled asset and rewrites only the snapshot and the
  // title, so whatever the asset carries is what a phone-produced theme carries. An asset copied
  // straight from the template would have an empty Firebase web key, and the page would then never
  // draw its Google button - which is the bug this guard exists to make impossible.
  const xml = generate(t, ['--title', 'Secure Wallet Pro']);
  assert.equal(fs.readFileSync(path.join(root, 'app/src/main/assets/blogger_theme.xml'), 'utf8'), xml);
  const config = JSON.parse(fs.readFileSync(path.join(root, 'google-services.json'), 'utf8'));
  assert.ok(xml.includes("apiKey: '" + config.client[0].api_key[0].current_key + "'"),
    'the bundled theme must carry the project web key');
});

test('pairing QR panel ships markup, payload shape and encoder in one file', () => {
  assert.ok(template.includes("id='svp-qr-frame'"));
  assert.ok(template.includes("id='svp-pair-new'"));
  assert.ok(template.includes("return pageOrigin() + '#pair=' + code;"));
  assert.ok(template.includes('renderPairQr();'));
});

test('embedded QR encoder builds scannable matrices without third-party code', () => {
  const block = template.match(/\/\* -- QR ENCODER BEGIN -- \*\/([\s\S]*?)\/\* -- QR ENCODER END -- \*\//);
  assert.ok(block, 'QR encoder block is missing from the theme');
  const api = vm.runInNewContext(block[1] + '\n;({ qrEncode: qrEncode, qrSvg: qrSvg });');

  const smallest = api.qrEncode('A');
  assert.equal(smallest.version, 1);
  assert.equal(smallest.size, 21);

  const payload = 'securewallet://pair?u=https%3A%2F%2Fsecurevaultpro.blogspot.com&c=ABC234';
  const qr = api.qrEncode(payload);
  assert.equal(qr.size, 37, 'a 72 character pairing link must stay inside one screen-sized code');
  assert.equal(qr.modules.length, 37);
  for (let y = 0; y < 7; y += 1) for (let x = 0; x < 7; x += 1) {
    const d = Math.max(Math.abs(x - 3), Math.abs(y - 3));
    assert.equal(qr.modules[y][x], d !== 2, 'finder pattern at ' + x + ',' + y);
  }
  assert.equal(qr.modules[3][7], false, 'separator must stay light');
  assert.equal(qr.modules[qr.size - 8][8], true, 'the always-dark format module is required');
  for (let i = 8; i < qr.size - 8; i += 1) assert.equal(qr.modules[6][i], i % 2 === 0, 'timing pattern at ' + i);

  const svg = api.qrSvg(qr);
  assert.ok(svg.startsWith('<svg') && svg.includes('<path d="M'), 'encoder must emit a drawable path');

  const tooLong = api.qrEncode('https://example.com/' + 'z'.repeat(400));
  assert.equal(tooLong, null, 'oversized payloads must be refused rather than truncated');
});

test('generated theme is wired to the same realtime backend as the app', t => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'google-services.json'), 'utf8'));
  const projectId = config.project_info.project_id;
  const xml = generate(t);
  assert.ok(xml.includes("projectId: '" + projectId + "'"), 'project id must come from google-services.json');
  assert.ok(
    xml.includes("databaseURL: 'https://" + projectId + "-default-rtdb.asia-southeast1.firebasedatabase.app'"),
    'database URL must be the https default instance'
  );
  assert.ok(xml.includes("'svp1/users/'"), 'nodes live under svp1/users/$uid');
  ['notes', 'entries', 'cards', 'totps', 'contacts', 'cryptoWallets', 'files'].forEach(module => {
    assert.ok(xml.includes("'" + module + "'"), 'module ' + module + ' must be listed');
  });
  ['onChildAdded', 'onChildChanged', 'onChildRemoved', 'serverValue', 'signInWithCredential',
    'GoogleAuthProvider', 'PBKDF2', 'AES-256-GCM', 'svp-sync-log'].forEach(marker => {
    assert.ok(xml.includes(marker), 'realtime client must reference ' + marker);
  });
  const cleartext = [...xml.matchAll(/http:\/\/[A-Za-z0-9./_-]+/g)].map(m => m[0])
    .filter(url => url.indexOf('http://www.w3.org') !== 0 && url.indexOf('http://www.google.com/2005/gml') !== 0);
  assert.deepEqual(cleartext, [], 'no cleartext endpoint may be embedded');
  assert.doesNotThrow(() => new vm.Script(script(xml)));
});

test('keyring unwrap refuses to guess and never claims an empty vault', t => {
  const xml = generate(t);
  assert.ok(xml.includes("!== 'AES-256-GCM'"), 'algorithm must be checked before any derivation');
  assert.ok(/iterations:\s*iterations,/.test(xml), 'the node iters must drive PBKDF2');
  assert.ok(xml.includes('coerceIters'), 'iters must be coerced from number or string');
  assert.ok(xml.includes("600000"), 'iteration default must be 600000');
  assert.ok(/wrong passphrase/i.test(xml), 'a failed unwrap must say wrong passphrase');
  assert.ok(!xml.includes('empty vault'), 'the theme must never report an empty vault on a bad passphrase');
});

test('firebase api key override is validated and secrets are rejected', t => {
  const usable = 'AIza' + 'Aa1_-'.repeat(8);
  const xml = generate(t, ['--firebase-api-key', usable]);
  assert.ok(xml.includes("apiKey: '" + usable + "'"), 'an explicit valid key must reach the theme');
  assert.throws(() => generate(t, ['--firebase-api-key', 'AbCdEfGhijKl--9xYyZz0123456789']));
  assert.throws(() => generate(t, ['--firebase-api-key', 'hunter2']));
});

test('sign-in offers one Google button and never a console manual', t => {
  const xml = generate(t);
  const body = script(xml);
  const idp = body.match(/function isIdpRefusal\(message\) \{[\s\S]*?\n  \}/);
  const domain = body.match(/function isDomainRefusal\(message\) \{[\s\S]*?\n  \}/);
  const config = body.match(/function fbConfig\(\) \{[\s\S]*?\n  \}/);
  assert.ok(idp, 'the audience refusal must still be recognised');
  assert.ok(domain, 'the unauthorized-origin refusal must still be recognised');
  assert.ok(config, 'the effective realtime config must stay readable');
  const audience = '908900914248-pse5k128rl0hoqb07oebhsnngt1i80uj.apps.googleusercontent.com';
  const refused = 'Firebase: Invalid Idp Response: the Google id_token is not allowed to be used with ' +
    'this application. Its audience (OAuth 2.0 client ID) is ' + audience + ', which is not authorized ' +
    'to be used in the project with project_number: 10151634026. (auth/invalid-credential).';
  const sandbox = vm.createContext({
    storeGet: key => (key === 'svp.webClientId' ? '111-typed.apps.googleusercontent.com' : ''),
    S: { clientId: '111-typed.apps.googleusercontent.com' },
    FB_CFG: { apiKey: '', webClientId: audience },
    DEFAULT_CLIENT_ID: 'baked-in',
    refused: refused,
    unrelated: 'Network request failed.',
    originRefused: 'Firebase: The current domain is not authorized for OAuth authentication. (auth/unauthorized-domain).'
  });
  new vm.Script(idp[0] + '\n' + domain[0] + '\n' + config[0] +
    '\nthis.aud = isIdpRefusal(refused); this.other = isIdpRefusal(unrelated);' +
    '\nthis.origin = isDomainRefusal(originRefused); this.originOnIdp = isDomainRefusal(refused);' +
    '\nthis.cfg = fbConfig();').runInContext(sandbox);
  assert.equal(sandbox.cfg.webClientId, '111-typed.apps.googleusercontent.com',
    'the client ID typed into Google setup must outrank the one baked into the theme');
  assert.equal(sandbox.aud, true, 'the audience refusal must be classified');
  assert.equal(sandbox.other, false, 'an unrelated failure must not be dressed up as a project mismatch');
  assert.equal(sandbox.origin, true, 'the unauthorized-origin refusal must be classified');
  assert.equal(sandbox.originOnIdp, false, 'and the two must not be confused with each other');

  // One way in at a time: the card shows Google's own button, and the vault-window button appears
  // only after that one is refused. Blogger renders this markup literally, so a repeated id would
  // silently bind only one of the two.
  const popup = body.match(/id='svp-popup-row'[\s\S]*?id='svp-live-popup'/);
  assert.ok(popup, "the page must carry its own 'vault window' sign-in row next to the Google button");
  assert.doesNotMatch(popup[0], /<\//, 'the row must hold just the button');
  assert.equal(body.match(/id='svp-live-popup'/g)?.length, 1, 'the vault-window button must have one id');
  assert.equal(body.match(/id='svp-gis-btn'/g)?.length, 1, 'the Google button host must have one id');
  assert.ok(body.includes("$('svp-live-popup').addEventListener('click', popupSignIn)"),
    'and it must run the project-authored sign-in, not a second client');
  assert.ok(/function showGoogleButton\(\) \{[\s\S]*?showPopupOffer\(false\)/.test(body),
    'the Google button is drawn alone, so the card never shows two sign-in choices at once');
  assert.equal(body.match(/id='svp-live-wait'/g)?.length, undefined,
    'the console-instruction panel is gone: a pending Google list is not the reader\'s problem');
  assert.equal(/probeAuthorizedDomains|getProjectConfig/.test(body), false,
    'and the page stops reading Google\'s domain list before anything is pressed');

  // The owner's complaint was a red error on every load. A refusal that only Google's own lists
  // decide must therefore empty the error paragraph, fold the server wording away, and leave one
  // calm line naming what to press instead.
  const fail = body.match(/function liveFail\(err, prefix\) \{[\s\S]*?\n  \}/);
  assert.ok(fail, 'the refusal router must ship');
  assert.ok(/liveErr\(''\);\s*liveErrRaw\(message\);/.test(fail[0]),
    'a Google-list refusal must never paint the red paragraph');
  assert.ok(/showPopupOffer\(true\)/.test(fail[0]),
    'and it must leave visible the button its own line tells the reader to press');
  assert.ok(/if \(RT\.popupFailed\) \{[\s\S]*?showPopupOffer\(false\)/.test(fail[0]),
    'once both routes are refused, no dead sign-in button is left on the card');
  assert.ok(/function popupSignIn\(\) \{[\s\S]*?RT\.popupFailed = true;\s*liveFail/.test(body),
    'the vault window must record its own refusal before routing it');
  assert.ok(/function retryLive\(\) \{[\s\S]*?RT\.popupFailed = false;/.test(body),
    'Try again starts clean, so a saved console entry works without a reload');
  assert.ok(/function retryLive\(\) \{[\s\S]*?storeSet\('svp.lastRefusal', ''\);[\s\S]*?showGoogleButton\(\);/.test(body),
    'and it brings the original button back');
  assert.equal(body.match(/id='svp-live-raw'/g)?.length, 1, 'the raw server text needs one collapsible home');
  assert.ok(/if \(storeGet\('svp.lastRefusal'\) === 'idp'\) \{/.test(body),
    'a button this vault already refused is not offered again as the way in');
  assert.ok(/storeSet\('svp.lastRefusal', idp \? 'idp'/.test(body) &&
      /storeSet\('svp.lastRefusal', ''\)/.test(body),
    'a refusal must be remembered across loads and forgotten on a good sign-in');
});

test('one chat is one module, and the web picks the same mark the phone does', () => {
  assert.ok(template.includes("files: ['id', 'name', 'size', 'mimeType', 'category', 'createdAt', 'uploadedAt', 'noteId', 'folder', 'driveId']"),
    'the files shape must carry the chat folder, or an app-pushed record is edited blind');
  const block = template.match(/var CHAT_ROOT[\s\S]*?function chatMark\(id\) \{[\s\S]*?\n  \}/);
  assert.ok(block, 'the chat board must ship in the theme');
  const api = vm.runInNewContext(
    block[0] + '\n;({ chatSlot: chatSlot, chatOf: chatOf, chatMark: chatMark, CHAT_MARKS: CHAT_MARKS });',
    { Math, Object }
  );

  // Vectors from WhatsAppChatUnitIconTest, which pins WhatsAppChatUnit.iconSlot.
  const vectors = {
    '0f8fad5b-d9cb-469f-a165-70867728950e': 1,
    '7c9e6679-7425-40de-944b-e07fc1f90ae7': 3,
    '5ac4af8d-1f2b-4c3a-9d7e-0b6f4a2c1d3e': 3,
    '11111111-2222-3333-4444-555555555555': 9,
    a: 12
  };
  for (const [id, slot] of Object.entries(vectors)) {
    assert.equal(api.chatSlot(id), slot, 'chat ' + id + ' must land on the phone slot');
    assert.equal(api.chatMark(id), api.CHAT_MARKS[slot], 'the mark must be the slot plate');
  }

  assert.equal(api.CHAT_MARKS.length, 16, 'the board must stay the size the app rotates over');
  assert.equal(api.chatOf({ folder: 'chats/0f8fad5b-d9cb-469f-a165-70867728950e/photo.jpg' }),
    '0f8fad5b-d9cb-469f-a165-70867728950e');
  assert.equal(api.chatOf({ folder: 'documents' }), null, 'a flat file names no chat');
  assert.equal(api.chatOf({}), null);
  assert.equal(api.chatOf({ folder: 'chats/' }), null, 'an empty segment is not a chat');
  assert.equal(api.chatOf({ folder: 'chats/not a uuid!' }), null);
});

test('the generator refuses to overwrite the template it reads', () => {
  const result = spawnSync(process.execPath,
    [GENERATOR, '--out', 'secure_wallet_blogger_theme.xml'],
    { cwd: __dirname, encoding: 'utf8' });
  assert.notEqual(result.status, 0, 'writing onto the template must fail');
  assert.match(result.stderr, /source of truth/, result.stderr.slice(0, 300));
  const template = fs.readFileSync(path.join(__dirname, 'secure_wallet_blogger_theme.xml'), 'utf8');
  assert.ok(template.includes("apiKey: ''"), 'the template must stay free of injected project values');
});
