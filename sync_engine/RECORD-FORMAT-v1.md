# SVP-1 record format v1 — the cross-client wire contract

Manager-authored so A2b (Android) and A3 (web) can build in parallel against one frozen shape.
This is binding. If either side needs a change, it changes HERE first, then in both clients.

Backend: `vault-35423` / RTDB `vault-35423-default-rtdb` / `https://vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app`
Root `svp1/users/$uid`, `$uid` = **Firebase** uid (same on both clients). Rules published and
enforced: `auth.uid == $uid` for read and write under `svp1/users/$uid`.

## 1. Node shape — exactly these four keys, nothing else

```
data/{module}/$recordId = {
  e: "<base64(12-byte IV)>.<base64(ciphertext||128-bit GCM tag)>",
  u: <epoch ms of last local change>,   # LWW clock
  r: <int from serverValue()>,          # monotonic per-user revision
  d: null | <epoch ms of delete>        # tombstone
}
```

`e` plaintext = **UTF-8 JSON**, one object, the record's fields at section 3.
Cipher = AES-256-GCM, `AES/GCM/NoPadding`, fresh 12-byte IV per write, 128-bit tag, key = the
32-byte per-user **VaultKey** from the keyring (`svp1/users/$uid/keyring`, wrapped by the user's
passphrase — see SPEC-v1 and A1's handoff). No universal key anywhere.

**Ciphertext only.** The database must never hold a plaintext record field. A node carrying a key
outside `{e,u,r,d}` is malformed → both clients ignore it and log it.

Numbers: RTDB hands back `Int`/`Long`/`Double` (and JS gives `number`); parse tolerantly, never
crash on a stringy number. `u`/`r`/`d` are integers in ms; a client writes them as JSON numbers.

## 2. Modules

`notes, entries, cards, totps, contacts, cryptoWallets, files` — this exact order and these exact
spelled segments (`RealtimePaths.MODULES`). Record id = the entity's own `id` (a UUID), so the same
record has the same key on every device.

## 3. Per-module plaintext JSON

Keys are the Kotlin/Room property names, camelCase, all present (use `null` for absent optionals —
do not omit keys). No Android-only or transient fields.

**notes** (SecureNoteEntity)
`id, title, content, isPinned, color, tagsJson, createdAt, updatedAt`

**entries** (VaultEntryEntity)
`id, title, username, email, password, website, url, notes, category, customFieldsJson, tagsJson,
isFavorite, hasTotp, totpSecret, createdAt, updatedAt, lastAccessedAt, expiresAt, strengthScore,
deletedAt, isHiddenInTravel`

**cards** (DigitalCardEntity)
`id, type, nickname, holderName, number, expiryMonth, expiryYear, cvv, pin, bankName, notes,
colorGradientStart, colorGradientEnd, createdAt`
(*no `updatedAt` column* — the node's `u` is set by the realtime layer at write time; the JSON
stays as listed.)

**totps** (TotpEntity)
`id, issuer, accountName, secret, algorithm, digits, period, createdAt`
(*no `updatedAt` column* — same rule as cards.)

**contacts** (SecureContactEntity)
`id, name, phone, email, address, notes, category, createdAt, updatedAt`

**cryptoWallets** (CryptoWalletEntity)
`id, label, kind, secret, addressesJson, balancesJson, totalUsd, lastSyncedAt, createdAt, updatedAt`

**files** (VaultFile — metadata ONLY, bytes never enter the database)
`id, name, size, mimeType, category, createdAt, uploadedAt, noteId, folder, driveId`
`driveId` is the Google Drive file id; the web fetches bytes on demand with the user's own Drive
session. Bytes stay in Drive exactly as today.

`folder` is the conversation a WhatsApp import filed the bytes under: `chats/<chat id>` for a chat's
own module, `null` for a file that predates per-chat containers. Both clients group by it, and both
pick the chat's mark with the same 32-bit FNV-1a over the id — `WhatsAppChatUnit.iconSlot` on the
phone, `chatSlot` in the theme — pinned by the vectors in `WhatsAppChatUnitIconTest` and
`generate.test.js`. A chat therefore wears one identity on the handset and in the browser; the
board has 16 members on each side, so the modulus must stay 16 in both.

`tagsJson`, `customFieldsJson`, `addressesJson`, `balancesJson` are JSON *strings* stored inside the
record JSON (double-encoded, matching Room). Parse them on the web with a second `JSON.parse`
inside a try/catch; default `"[]"`.

## 4. Conflict rule (identical in both clients)

Compare the incoming node against what you hold for the same `id`:
1. Higher `u` wins.
2. Equal `u` → higher `r` wins.
3. Equal `u` and one side has a tombstone (`d != null`) → **the tombstone wins**. Delete beats save.
4. Anything not winning is dropped and logged with both `u`/`r` values. Never silently.
5. An older `u` never overwrites a newer one — this is what fixes the historical
   delete-resurrection defect. Deleted items must not come back.

## 5. meta node

`svp1/users/$uid/meta = { rev, updatedAt, lastDevice }`. `rev` increments through an RTDB
transaction seeded from `serverValue()` so concurrent writers cannot collide.

## 6. Latency — the acceptance metric

Per event log `tWrite` (local ms at call), `tAck` (write honoured), `tReceive` (listener fired for a
remote change). The number that matters is measured on the **receiving** side as `tReceive - u`.
Android appends to `Downloads/SecureVaultSync.txt`; web keeps `localStorage['svp-sync-log']` and
shows an on-page chip. Median must be well under 1000 ms and must be measured, never asserted.

## 7. Modules added in this wave — what deliberately does **not** sync

The seven segments in section 2 are the whole wire contract, and `RealtimeModule` plus the web
client still enumerate exactly those. Three new features land in this release and are **device-local
on purpose**:

- **Mail accounts** (`data/email`) — host, user and the sealed credential. A mailbox is a live
  credential to a third-party service; the owner's rule for keys ("AES-256 encrypted locally, never
  sent to a server") is the stricter instruction here and it wins over the mirror-everything
  expectation. Syncing an account would put a decryptable-if-the-vault-key-leaks login into
  Firebase. The vault passphrase seals it on the device and that is where it stays.
- **Passkeys** (`security/passkey`) — same reasoning, one level stronger: a passkey's value is that
  the private half never leaves the authenticator. Exporting it to a realtime database would make it
  a normal secret, which is the thing FIDO2 exists to avoid.
- **Steganographic payloads** (`crypto/stego`) — the carrier images the owner generates are ordinary
  files and go through the existing `files` module if they are saved to the file vault; the hidden
  payload, the decoy payload and the two passwords are never a record type of their own. Deniability
  is destroyed by a database node that announces "this image contains a secret".

This is a decision, not an omission: adding any of them later means a new segment here first, then
`RealtimeModule`, then `RealtimePaths.MODULES`, then the web client's module list, then the per-user
security rules — in that order, with both clients refusing an unknown segment rather than guessing.
