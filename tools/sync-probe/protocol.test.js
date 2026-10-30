'use strict';
/**
 * Holds the Node half of the SVP-1 wire against its frozen contract.
 *
 * These are the numbers the APK and the Blogger page also obey, so a regression here is a regression
 * in what the two frontends agree on — a record the phone writes that the web cannot read, or a
 * delete that resurrects itself. `EncryptionContractVectorTest.kt` is the JVM mirror of the crypto
 * cases, and `sync_engine/RECORD-FORMAT-v1.md` is the source of the conflict rules.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');

const P = require('./protocol');
const { normalize } = require('./client');
const { createCollector } = require('./probe');
const V = require('./vector');

const key = () => crypto.randomBytes(P.KEY_BYTES);

test('the wire constants are the spec constants', () => {
  assert.equal(P.KEY_BYTES, 32);
  assert.equal(P.IV_BYTES, 12);
  assert.equal(P.TAG_BITS, 128);
  assert.equal(P.TAG_BYTES, 16);
  assert.equal(P.SALT_BYTES, 32);
  assert.equal(P.PBKDF2_ITERATIONS, 600000);
  assert.equal(P.DIGEST, 'sha256');
  assert.equal(P.CIPHER, 'aes-256-gcm');
  assert.equal(P.ALGORITHM, 'AES-256-GCM');
  assert.deepEqual(P.MODULES, ['notes', 'entries', 'cards', 'totps', 'contacts', 'cryptoWallets', 'files']);
});

test('an envelope is base64(iv).base64(ciphertext+tag)', () => {
  const envelope = P.sealBytes('hello vault', key());
  const parts = envelope.split('.');
  assert.equal(parts.length, 2);
  assert.equal(Buffer.from(parts[0], 'base64').length, P.IV_BYTES);
  const body = Buffer.from(parts[1], 'base64');
  assert.equal(body.length, Buffer.byteLength('hello vault') + P.TAG_BYTES);
});

test('seal then open round-trips, and every seal draws a fresh IV', () => {
  const k = key();
  const a = P.sealBytes('same text', k);
  const b = P.sealBytes('same text', k);
  assert.notEqual(a, b, 'a reused IV under the same key breaks GCM');
  assert.equal(P.openBytes(a, k).toString('utf8'), 'same text');
  assert.equal(P.openBytes(b, k).toString('utf8'), 'same text');
});

test('a wrong key, a flipped byte or a broken envelope yields no plaintext', () => {
  const k = key();
  const envelope = P.sealBytes('authenticated or nothing', k);
  assert.equal(P.openBytes(envelope, key()), null, 'another key must not open it');

  const [iv, body] = envelope.split('.');
  const raw = Buffer.from(body, 'base64');
  raw[0] ^= 1;
  assert.equal(P.openBytes(`${iv}.${raw.toString('base64')}`, k), null, 'tampering must be detected');

  assert.equal(P.openBytes('no-dot', k), null);
  assert.equal(P.openBytes('a.b.c', k), null);
  assert.equal(P.openBytes('.'.repeat(1), k), null);
  assert.equal(P.openBytes('!!!!.!!!!', k), null);
  assert.equal(P.openBytes(`${iv}.${iv}`, k), null, 'a body shorter than the tag is not a record');
  assert.equal(P.openBytes(null, k), null);
  assert.equal(P.openBytes(42, k), null);
});

test('PBKDF2 is deterministic, salted, and 256 bits wide', () => {
  const salt = Buffer.from(V.SALT_B64, 'base64');
  const once = P.deriveKey(V.KEK_LABEL, salt);
  const twice = P.deriveKey(V.KEK_LABEL, salt);
  assert.deepEqual(once, twice);
  assert.equal(once.length, P.KEY_BYTES);
  assert.notDeepEqual(once, P.deriveKey(V.KEK_LABEL, crypto.randomBytes(P.SALT_BYTES)));
  assert.throws(() => P.deriveKey('', salt), /passphrase/);
  assert.throws(() => P.deriveKey('x', Buffer.alloc(0)), /salt/);
});

test('a keyring node written by the phone opens with the passphrase and nothing else', () => {
  const vaultKey = key();
  const salt = crypto.randomBytes(P.SALT_BYTES);
  const node = P.sealKeyring(vaultKey, 'correct horse', salt);
  assert.equal(node.alg, P.ALGORITHM);
  assert.equal(node.iters, P.PBKDF2_ITERATIONS);
  assert.equal(Buffer.from(node.salt, 'base64').length, P.SALT_BYTES);
  assert.ok(node.updatedAt > 0);

  assert.deepEqual(P.openKeyring(node, 'correct horse'), vaultKey);
  assert.equal(P.openKeyring(node, 'wrong passphrase'), null);
  assert.equal(P.openKeyring({ ...node, iters: 1 }, 'correct horse'), null, 'a weakened iteration count must not open');
  assert.equal(P.openKeyring(null, 'correct horse'), null);
  assert.equal(P.openKeyring({ wrap: 'junk', salt: node.salt }, 'correct horse'), null);
});

test('the record node keeps its four fields and its tombstone slot', () => {
  const k = key();
  const live = P.makeNode('{"a":1}', k, { u: 100, r: 7 });
  assert.deepEqual(Object.keys(live).sort(), ['d', 'e', 'r', 'u']);
  assert.equal(live.d, null);
  assert.equal(P.isTombstone(live), false);
  const gone = P.makeNode('{"a":1}', k, { u: 101, r: 7, deletedAt: 101 });
  assert.equal(P.isTombstone(gone), true);
});

test('last-write-wins follows the frozen conflict table', () => {
  const k = key();
  const node = (u, r, d = null) => P.makeNode('payload', k, { u, r, deletedAt: d });

  assert.deepEqual(P.resolve(null, node(5, 1)), { apply: true, reason: 'no-local' });
  assert.deepEqual(P.resolve(node(5, 1), node(6, 0)), { apply: true, reason: 'newer-u' });
  assert.deepEqual(P.resolve(node(6, 9), node(5, 9)), { apply: false, reason: 'older-u' });
  assert.deepEqual(P.resolve(node(5, 1), node(5, 2)), { apply: true, reason: 'newer-r' });
  assert.deepEqual(P.resolve(node(5, 2), node(5, 1)), { apply: false, reason: 'older-r' });

  assert.deepEqual(P.resolve(node(5, 1), node(5, 1, 5)), { apply: true, reason: 'tombstone-wins' });
  assert.deepEqual(P.resolve(node(5, 1, 5), node(5, 1)), { apply: false, reason: 'already-deleted' });
  assert.deepEqual(P.resolve(node(5, 1), node(5, 1)), { apply: false, reason: 'identical' });
  assert.deepEqual(P.resolve(node(5, 1, 5), node(5, 1, 5)), { apply: false, reason: 'identical' });
});

test('a delete beats a save even when the save has the higher revision', () => {
  const k = key();
  const node = (u, r, d = null) => P.makeNode('payload', k, { u, r, deletedAt: d });
  // This is the ordering the APK's MergePolicy and the Blogger page's better() both use; if Node
  // breaks it, the probe is measuring a protocol neither frontend runs.
  assert.deepEqual(P.resolve(node(5, 1), node(5, 9, 5)), { apply: true, reason: 'tombstone-wins' });
  assert.deepEqual(P.resolve(node(5, 9, 5), node(5, 1)), { apply: false, reason: 'already-deleted' });
});

test('a delete never loses to the write it already killed', () => {
  const k = key();
  const tombstone = P.makeNode('payload', k, { u: 200, r: 1, deletedAt: 200 });
  const lateEdit = P.makeNode('payload', k, { u: 199, r: 99 });
  assert.equal(P.resolve(tombstone, lateEdit).apply, false, 'an offline edit must not resurrect a deleted record');
  assert.equal(P.resolve(lateEdit, tombstone).apply, true);
});

test('database segments reject the characters the Realtime Database forbids', () => {
  for (const bad of ['', 'has space', 'dot.dot', 'hash#tag', 'dollar$x', '[bracket]', '/slash', 'a'.repeat(129)]) {
    assert.equal(P.isSafeSegment(bad), false, `${JSON.stringify(bad)} is not a key`);
  }
  assert.equal(P.isSafeSegment('uid-ABC123'), true);
  assert.equal(P.isSafeSegment('note_1'), true);
});

test('the node layout is the spec layout, per uid', () => {
  const paths = P.pathsFor('svp1', 'U1');
  assert.equal(paths.user, 'svp1/users/U1');
  assert.equal(paths.keyring, 'svp1/users/U1/keyring');
  assert.equal(paths.data, 'svp1/users/U1/data');
  assert.equal(paths.meta, 'svp1/users/U1/meta');
  assert.equal(paths.module('notes'), 'svp1/users/U1/data/notes');
  assert.equal(paths.record('cards', 'abc'), 'svp1/users/U1/data/cards/abc');
  assert.throws(() => paths.module('nope'), /unknown module/);
  assert.throws(() => pathsForThrow(paths), /database segment/);
  assert.throws(() => P.pathsFor('svp1', 'bad uid'), /database segment/);
});

function pathsForThrow(paths) {
  return paths.record('notes', 'bad.key');
}

test('paths are normalised the way the REST client builds them', () => {
  assert.equal(normalize('svp1/users/U1'), '/svp1/users/U1');
  assert.equal(normalize('/svp1/users/U1/'), '/svp1/users/U1');
  assert.equal(normalize(''), '');
});

test('the contract vector on disk still opens here', () => {
  assert.ok(fs.existsSync(V.FIXTURE), `run node tools/sync-probe/vector.js --write`);
  const disk = JSON.parse(fs.readFileSync(V.FIXTURE, 'utf8'));
  assert.equal(V.matchesDisk(disk), true, 'Node and the stored vector disagree');
  assert.equal(P.openKeyring(disk.keyring, V.KEK_LABEL).equals(V.vaultKey()), true);
});

test('the probe collector timestamps the arrival, not the poll', () => {
  const collector = createCollector();
  const before = Date.now();
  collector.onEvent({ type: 'put', path: '/probe-1', data: { u: before, r: 1 } });
  const hit = collector.arrived('probe-1');
  assert.ok(hit.at >= before);
  assert.equal(hit.frame.data.u, before);
  assert.equal(collector.total, 1);

  collector.onEvent({ type: 'put', path: '/probe-2', data: null });
  assert.equal(collector.arrived('probe-2'), undefined, 'a delete is not an arrival');

  collector.clear('probe-1');
  assert.equal(collector.arrived('probe-1'), undefined);

  const other = createCollector();
  other.onEvent({ type: 'keep-alive', path: '/', data: null });
  assert.equal(other.total, 0);
});

test('a waiter resolves the moment the matching child arrives', async () => {
  const collector = createCollector();
  const pending = collector.waitFor('late', 2000);
  setTimeout(() => collector.onEvent({ type: 'put', path: '/late', data: { u: Date.now(), r: 1 } }), 20);
  const hit = await pending;
  assert.ok(hit && hit.frame.data.u > 0);

  const timedOut = await createCollector().waitFor('never', 30);
  assert.equal(timedOut, null, 'a push that never comes must report as never coming');
});
