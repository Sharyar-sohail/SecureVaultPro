'use strict';
/**
 * The SVP-1 realtime wire, in plain Node.
 *
 * Every constant and every byte order here is copied from the two frozen contracts —
 * `sync_engine/SPEC-v1.md` and `sync_engine/RECORD-FORMAT-v1.md` — and from `KeyringCodec.kt`,
 * which is the Android implementation. Nothing in this file is a second, Node-shaped version of the
 * protocol: it is the same protocol, and `protocol.test.js` plus the Kotlin interop vector test hold
 * the two implementations against each other.
 *
 * It uses only `node:crypto`, so the numbers it produces do not depend on an SDK that could drift
 * away from what the APK or the Blogger page actually speak.
 */

const crypto = require('node:crypto');

const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BITS = 128;
const TAG_BYTES = TAG_BITS / 8;
const SALT_BYTES = 32;
const PBKDF2_ITERATIONS = 600000;
const DIGEST = 'sha256';
const CIPHER = 'aes-256-gcm';
const ALGORITHM = 'AES-256-GCM';

/** The module tables from the spec, in the order the web renders them. */
const MODULES = ['notes', 'entries', 'cards', 'totps', 'contacts', 'cryptoWallets', 'files'];

/** `base64(iv).base64(ciphertext+tag)` — the exact shape the app, the backups and the page parse. */
function sealBytes(plain, key) {
  assertKey(key);
  const iv = crypto.randomBytes(IV_BYTES);
  const cipher = crypto.createCipheriv(CIPHER, key, iv, { authTagLength: TAG_BYTES });
  const ct = Buffer.concat([cipher.update(toBuffer(plain)), cipher.final()]);
  return `${iv.toString('base64')}.${Buffer.concat([ct, cipher.getAuthTag()]).toString('base64')}`;
}

/** Null for a malformed or non-authenticating envelope, mirroring `KeyringCodec.openBytes`. */
function openBytes(envelope, key) {
  assertKey(key);
  if (typeof envelope !== 'string') return null;
  const parts = envelope.trim().split('.');
  if (parts.length !== 2) return null;
  const iv = decodeBase64(parts[0]);
  const body = decodeBase64(parts[1]);
  if (iv === null || body === null) return null;
  if (iv.length !== IV_BYTES) return null;
  if (body.length <= TAG_BYTES) return null;
  try {
    const decipher = crypto.createDecipheriv(CIPHER, key, iv, { authTagLength: TAG_BYTES });
    decipher.setAuthTag(body.subarray(body.length - TAG_BYTES));
    return Buffer.concat([decipher.update(body.subarray(0, body.length - TAG_BYTES)), decipher.final()]);
  } catch {
    return null;
  }
}

/** PBKDF2-HMAC-SHA256 with the app's iteration count; the same 32-byte key Kotlin derives. */
function deriveKey(passphrase, salt, iterations = PBKDF2_ITERATIONS) {
  if (!passphrase.length) throw new Error('A passphrase is required');
  if (!salt.length) throw new Error('A salt is required');
  return crypto.pbkdf2Sync(Buffer.from(passphrase, 'utf8'), salt, iterations, KEY_BYTES, DIGEST);
}

/** The cloud keyring node: `{wrap, salt, alg, iters, updatedAt}`, sealed by the passphrase. */
function sealKeyring(vaultKey, passphrase, salt) {
  assertKey(vaultKey);
  const kek = deriveKey(passphrase, salt);
  return {
    wrap: sealBytes(vaultKey, kek),
    salt: salt.toString('base64'),
    alg: ALGORITHM,
    iters: PBKDF2_ITERATIONS,
    updatedAt: Date.now()
  };
}

/** The vault key inside a keyring node, or null — a wrong passphrase yields no key at all. */
function openKeyring(node, passphrase) {
  if (!node || typeof node.wrap !== 'string') return null;
  const salt = decodeBase64(node.salt);
  if (salt === null) return null;
  const iterations = Number(node.iters) > 0 ? Number(node.iters) : PBKDF2_ITERATIONS;
  const kek = deriveKey(passphrase, salt, iterations);
  return openBytes(node.wrap, kek);
}

/**
 * The `{e,u,r,d}` node the database stores per record.
 * `u` is the last-write-wins clock, `r` the monotonic tie-break, `d` a tombstone clock or null.
 */
function makeNode(plain, key, { u, r, deletedAt = null }) {
  return { e: sealBytes(plain, key), u: Number(u), r: Number(r), d: deletedAt === null ? null : Number(deletedAt) };
}

/**
 * Per-record last-write-wins, exactly as `MergePolicy.decide` implements it in the APK and as the
 * Blogger page's `better()` implements it: newer `u` wins; on equal `u` a tombstone beats a live
 * record (delete beats save); only when both sides are equally alive does the higher `r` win.
 * Returns the node that should be applied.
 */
function resolve(local, incoming) {
  if (!local) return { apply: true, reason: 'no-local' };
  if (incoming.u > local.u) return { apply: true, reason: 'newer-u' };
  if (incoming.u < local.u) return { apply: false, reason: 'older-u' };
  const incomingDeleted = isTombstone(incoming);
  const localDeleted = isTombstone(local);
  if (incomingDeleted && !localDeleted) return { apply: true, reason: 'tombstone-wins' };
  if (!incomingDeleted && localDeleted) return { apply: false, reason: 'already-deleted' };
  if (incoming.r !== local.r) {
    return incoming.r > local.r
      ? { apply: true, reason: 'newer-r' }
      : { apply: false, reason: 'older-r' };
  }
  return { apply: false, reason: 'identical' };
}

const isTombstone = (node) => node !== null && node !== undefined && node.d !== null && node.d !== undefined;

/** A Realtime Database key may not contain `. # $ [ ] /` or whitespace. */
function isSafeSegment(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 128) return false;
  return !/[.#$\[\]/\s]/.test(value);
}

function pathsFor(base, uid) {
  if (!isSafeSegment(uid)) throw new Error('uid is not usable as a database segment');
  const user = `${base}/users/${uid}`;
  return {
    user,
    keyring: `${user}/keyring`,
    data: `${user}/data`,
    module: (m) => {
      if (!MODULES.includes(m)) throw new Error(`unknown module ${m}`);
      return `${user}/data/${m}`;
    },
    record: (m, id) => {
      if (!isSafeSegment(id)) throw new Error('record id is not usable as a database segment');
      return `${pathsFor(base, uid).module(m)}/${id}`;
    },
    meta: `${user}/meta`
  };
}

function decodeBase64(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  if (!/^[A-Za-z0-9+/=\s]+$/.test(value.trim())) return null;
  try {
    return Buffer.from(value.trim(), 'base64');
  } catch {
    return null;
  }
}

function toBuffer(value) {
  return Buffer.isBuffer(value) ? value : Buffer.from(String(value), 'utf8');
}

function assertKey(key) {
  if (!Buffer.isBuffer(key) || key.length !== KEY_BYTES) {
    throw new Error(`a vault key is exactly ${KEY_BYTES} bytes`);
  }
}

module.exports = {
  KEY_BYTES, IV_BYTES, TAG_BYTES, TAG_BITS, SALT_BYTES, PBKDF2_ITERATIONS, DIGEST, CIPHER, ALGORITHM,
  MODULES,
  sealBytes, openBytes, deriveKey, sealKeyring, openKeyring,
  makeNode, resolve, isTombstone, isSafeSegment, pathsFor
};
