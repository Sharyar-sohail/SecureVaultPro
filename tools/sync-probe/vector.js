#!/usr/bin/env node
'use strict';
/**
 * The cross-language known-answer vector.
 *
 * One file, two readers: `protocol.test.js` opens it with Node (the Blogger page's maths) and
 * `EncryptionContractVectorTest.kt` opens it with the JVM (the APK's maths). Both must produce the
 * same plaintext from the same envelope, which is the only way to prove the sync carries the *old*
 * encryption untouched rather than a new one that happens to work on one frontend.
 *
 * Nothing secret is stored. The two "passphrases" are public labels baked into the code on both
 * sides, the salt is public, and the keys themselves are reduced to a sha256 fingerprint so the
 * fixture can pin PBKDF2 agreement without containing a key.
 *
 *   node tools/sync-probe/vector.js --write     regenerate the fixture
 *   node tools/sync-probe/vector.js --check     fail if the fixture no longer matches the code
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const P = require('./protocol');

const KEK_LABEL = 'SVP-CONTRACT-1-KEK';
const VAULT_KEY_LABEL = 'SVP-CONTRACT-1-VAULT-KEY';
const SALT_B64 = 'mYWn0K1vQ4c7ZEhL2pUaR1sXbN0yTqJ6dVgM3rCwPoI=';
const RECORD_PLAIN = JSON.stringify({
  id: 'contract-1',
  title: 'Contrato de cifrado',
  body: 'Sealed by Node, opened by the APK — and the other way round.',
  updatedAt: 1761724800000
});

// `sync_engine/fixtures/` belongs to the per-module realtime fixtures, which A2aRealtimeFixturesTest
// enumerates directory by directory; the cross-language vector therefore lives in its own folder.
const FIXTURE = path.resolve(__dirname, '../../sync_engine/contract/svp-contract-1.json');

/** The 256-bit vault key, derived from a public label so no key material is ever stored. */
function vaultKey() {
  return crypto.createHash('sha256').update(Buffer.from(VAULT_KEY_LABEL, 'utf8')).digest();
}

/** The key-encryption key: PBKDF2-HMAC-SHA256 with the app's iteration count over the public salt. */
function kek() {
  return P.deriveKey(KEK_LABEL, Buffer.from(SALT_B64, 'base64'));
}

function fingerprint(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 16);
}

function build() {
  const salt = Buffer.from(SALT_B64, 'base64');
  const key = vaultKey();
  const wrapping = kek();
  const wrap = P.sealBytes(key, wrapping);
  const record = P.sealBytes(RECORD_PLAIN, key);
  return {
    id: 'svp-contract-1',
    note: 'Synthetic known-answer vector. The two passphrases are public labels, not secrets: ' +
      'the fixture proves Node and the JVM implement the same PBKDF2/AES-256-GCM wire. ' +
      'Regenerate with `node tools/sync-probe/vector.js --write` only when the crypto contract changes.',
    algorithm: P.ALGORITHM,
    transformation: 'AES/GCM/NoPadding',
    keyBytes: P.KEY_BYTES,
    ivBytes: P.IV_BYTES,
    tagBits: P.TAG_BITS,
    envelopeShape: 'base64(iv).base64(ciphertext+tag)',
    pbkdf2: {
      prf: 'HMAC-SHA256',
      digest: P.DIGEST,
      iterations: P.PBKDF2_ITERATIONS,
      saltBytes: salt.length,
      salt: SALT_B64,
      derivedKeyBytes: P.KEY_BYTES,
      passphraseLabel: KEK_LABEL,
      kekFingerprint: fingerprint(wrapping)
    },
    vaultKey: {
      label: VAULT_KEY_LABEL,
      fingerprint: fingerprint(key)
    },
    keyring: {
      wrap,
      salt: SALT_B64,
      alg: P.ALGORITHM,
      iters: P.PBKDF2_ITERATIONS
    },
    record: {
      envelope: record,
      plain: RECORD_PLAIN
    },
    generatedAt: '2026-10-29'
  };
}

/**
 * Rebuild the deterministic half from the current code, then open what is on disk.
 *
 * The envelopes themselves are not byte-compared: GCM draws a fresh IV every time, so equality would
 * only prove the random source is broken. What has to agree is the contract — algorithm, sizes,
 * iteration count, salt, and the fingerprints of the keys both sides derive — and that the stored
 * ciphertext still opens to the stored plaintext.
 */
function matchesDisk(disk) {
  const fresh = build();
  const deterministic = ['algorithm', 'transformation', 'keyBytes', 'ivBytes', 'tagBits', 'envelopeShape', 'pbkdf2'];
  if (!deterministic.every((k) => JSON.stringify(disk[k]) === JSON.stringify(fresh[k]))) return false;
  if (disk.record.plain !== fresh.record.plain) return false;
  const key = vaultKey();
  const opened = P.openBytes(disk.record.envelope, key);
  if (!opened || opened.toString('utf8') !== disk.record.plain) return false;
  const unwrapped = P.openKeyring(disk.keyring, KEK_LABEL);
  return unwrapped !== null && unwrapped.equals(key);
}

function main(argv) {
  const write = argv.includes('--write');
  const check = argv.includes('--check');
  if (write) {
    fs.mkdirSync(path.dirname(FIXTURE), { recursive: true });
    fs.writeFileSync(FIXTURE, `${JSON.stringify(build(), null, 2)}\n`, 'utf8');
    console.log(`wrote ${FIXTURE}`);
    return 0;
  }
  if (check) {
    if (!fs.existsSync(FIXTURE)) {
      console.error(`missing fixture: ${FIXTURE}`);
      return 1;
    }
    const disk = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
    if (!matchesDisk(disk)) {
      console.error('The fixture no longer matches this code, so the two frontends are not on the same');
      console.error('crypto contract. Regenerate with --write only if the change is intended.');
      return 1;
    }
    console.log(`fixture matches the current protocol: ${FIXTURE}`);
    return 0;
  }
  const fresh = build();
  const openedByNode = P.openBytes(fresh.record.envelope, vaultKey());
  const unwrapped = P.openKeyring(fresh.keyring, KEK_LABEL);
  console.log(JSON.stringify({
    kekFingerprint: fresh.pbkdf2.kekFingerprint,
    vaultKeyFingerprint: fresh.vaultKey.fingerprint,
    nodeOpenedRecord: openedByNode !== null && openedByNode.toString('utf8') === fresh.record.plain,
    nodeOpenedKeyring: unwrapped !== null && unwrapped.equals(vaultKey())
  }, null, 2));
  return 0;
}

if (require.main === module) {
  process.exitCode = main(process.argv.slice(2));
}

module.exports = { build, matchesDisk, vaultKey, kek, fingerprint, KEK_LABEL, VAULT_KEY_LABEL, SALT_B64, FIXTURE };
