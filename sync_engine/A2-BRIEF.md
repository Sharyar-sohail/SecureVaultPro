# A2 briefing pack — verified integration points (read instead of exploring)

Manager-gathered facts, 2026-10-29. Every line here was read from the tree; trust it and do not
re-derive it. `SearchWorkspace` is unavailable inside agents — use Read/Grep only for what is
missing here.

## 1. Environment

- No emulator, no adb, no device, no signed-in Google account on this machine. Firebase network
  calls cannot be proven here. Gradle works; `--offline` is the normal mode (deps are fetched).
- Stop gate: `./gradlew testDebugUnitTest --offline --console=plain` then
  `./gradlew assembleDebug --offline --console=plain`. Both were green before A2 started
  (138 tests, 0 failures).
- RTDB rules are **published and enforced** (verified over the wire: anonymous GET and PUT on
  `svp1/...` both return `401 Permission denied`; scopes are `auth.uid == $uid` under `svp1/users/$uid`).

## 2. JSON library — decided

`app/build.gradle.kts:136` has Gson (`implementation(libs.gson)`); `org.json` is also imported in a
few files but **`org.json` is an Android stub in JVM unit tests** — using it in the record codec
makes the tests unrunnable. **Use Gson for the realtime record codec.**

## 3. Room: version 4, six entities — DO NOT CHANGE THE SCHEMA

`data/local/VaultDatabase.kt:20-38` — `version = 4`, daos `vaultDao, digitalCardDao, totpDao,
secureNoteDao, secureContactDao, cryptoWalletDao`. Room schema drift has crashed this app on the
owner's phone twice (past incidents). Sync state lives **outside** Room: no new columns, no version
bump. If a remote tombstone must be applied, delete through the DAO.

Module segment (`RealtimePaths.MODULES`) → source of truth:

| module         | entity / class                                   | dao / store |
|----------------|--------------------------------------------------|-------------|
| `notes`        | `SecureNoteEntity`                               | `secureNoteDao` |
| `entries`      | `VaultEntryEntity`                               | `vaultDao` |
| `cards`        | `DigitalCardEntity`                              | `digitalCardDao` |
| `totps`        | `TotpEntity`                                     | `totpDao` |
| `contacts`     | `SecureContactEntity`                            | `secureContactDao` |
| `cryptoWallets`| `CryptoWalletEntity`                             | `cryptoWalletDao` |
| `files`        | `VaultFile` (`data/file/FileVaultRepository.kt:21`) | FileVaultRepository (not Room) |

Entity fields (verbatim — these names become the record-JSON keys):

- `SecureNoteEntity(id, title, content, isPinned=false, color="DEFAULT", tagsJson="[]", createdAt, updatedAt)`
- `VaultEntryEntity(id, title, username, email, password, website, url, notes, category="WEBSITES",
  customFieldsJson="[]", tagsJson="[]", isFavorite=false, hasTotp=false, totpSecret="", createdAt,
  updatedAt, lastAccessedAt?, expiresAt?, strengthScore=0, deletedAt?, isHiddenInTravel=false)`
- `DigitalCardEntity(id, type, nickname, holderName, number, expiryMonth, expiryYear, cvv, pin,
  bankName, notes, colorGradientStart, colorGradientEnd, createdAt)` — **no `updatedAt`**
- `TotpEntity(id, issuer, accountName, secret, algorithm="SHA1", digits=6, period=30, createdAt)` — **no `updatedAt`**
- `SecureContactEntity(id, name, phone, email, address, notes, category="Personal", createdAt, updatedAt)`
- `CryptoWalletEntity(id, label, kind, secret, addressesJson, balancesJson="[]", totalUsd=0.0,
  lastSyncedAt=0L, createdAt, updatedAt)`
- `VaultFile(id, name, size, mimeType, category, createdAt, uploadedAt, noteId?)` — media bytes stay
  in Drive; the realtime node carries this metadata only.

`u` (LWW clock) = `updatedAt` where the entity has one, else `max(createdAt, last known)`. Cards and
TOTP have no `updatedAt`, so their `u` comes from the realtime layer's own knowledge, not Room.
Only `VaultEntryEntity` has a `deletedAt`; **tombstones are a realtime-layer concept for every
module**, independent of Room.

## 4. Mutation trigger — where to hook the push

`data/remote/google/AutoSyncManager.kt`:
- `:49` `class AutoSyncManager @Inject constructor(vaultRepository, cloudSyncManager, appLockManager, sessionManager)`
- `:76` `fun start()` — the app-wide entry point, already called at boot
- `:104` `fun markDirty()` — **this is the single mutation signal every screen already calls.** The
  realtime writer must be triggered from here (or from the same collector it feeds) so a change
  pushes in milliseconds instead of waiting for the 500 ms Drive debounce.
- `:109` `suspend fun syncNow(): Result<QuietSyncOutcome>`; `:74` `state: StateFlow<AutoSyncState>`;
  `:23` `SyncLogEntry(at, ...)` — the existing log shape to imitate, not to reuse for latency.

`data/remote/google/CloudSyncManager.kt` stays the Drive archive channel, unchanged in role. A1 put
`:810 sealingSecret()` there as the only writer secret. Realtime does not use `buildPayload()` — it
writes per record.

## 5. Downloads log convention — copy this, don't invent it

`diagnostics/CrashLogger.kt`:
- `:128` `const val REPORT_LOCATION = "Downloads/SecureVaultCrash.txt"`
- `:348` `private fun publishToDownloads(context, trace)` — MediaStore `Downloads` insert
  (API 29+, no runtime permission), `:360` matches on `DISPLAY_NAME` + `RELATIVE_PATH` to replace
  in place, `:385` `writeLegacyDownload()` falls back to
  `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
The sync log is `Downloads/SecureVaultSync.txt` and must be **append-only** (a crash report is
replace-on-write; a latency log is history). Reuse the same two-path structure and never let a
failed write throw into the sync path.

## 6. A1 API you must use (do not reinvent)

- `FirebaseBootstrap.reference(context, path): DatabaseReference?` — the only sanctioned database
  entry; null + `faultReason()` on failure. Never touch Firebase at class-load.
- `RealtimePaths` — `ROOT="svp1"`, `MODULES`, `user/keyring/dataRoot/module/record/meta`, every
  segment nullable, every builder returns null for an unsafe segment.
- `Keyring.deviceVaultKey(): VaultKey?` (null = cannot open, never invent a key), `Keyring.uid()`,
  `Keyring.status: StateFlow<KeyringStatus>`, `Keyring.attachKeyring(passphrase)`,
  `Keyring.clearDeviceKey()`.
- `KeyringCodec.sealBytes(plain: ByteArray, key: ByteArray): String` →
  `base64(iv).base64(ct+tag)`, and `openBytes(envelope: String, key: ByteArray): ByteArray?`
  (null when it does not authenticate). `VaultKey.kt:116/:126`. This is exactly the `e` field shape.
  32-byte key, 12-byte IV, 128-bit tag, `AES/GCM/NoPadding`.
- `SvpAuth.identity()/uid()/cachedUid()` — the uid is the **Firebase** uid, same one the browser gets.

## 7. Contract A3 depends on — keep it boring and stable

Node body is exactly `{e, u, r, d}` (spec §Node layout). Record plaintext is the entity's Gson JSON,
field names identical to the Kotlin properties, no Kotlin-only transient fields, `null` for absent
optionals. `d` is `null` or an epoch-ms tombstone. Anything the web cannot parse is a bug in A2, not
a limitation of the web.
