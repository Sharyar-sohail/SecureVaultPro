# PROJECT LEARNING REPORT — Secure Wallet Pro
Prepared by the Manager before any agent work. Read this before touching code.
Repo: `D:\sharyar\SMART VALUATE` · version in `app/build.gradle.kts:15-19` → `com.securevault.app`, versionCode **330**, versionName **3.2.1**, minSdk 26, targetSdk 35.

## 1. What this project is
A personal **encrypted vault** for one owner (and future users): passwords, cards, IDs, notes, 2FA codes, contacts, crypto wallets and sealed files on Android, with a **web clone on Blogger** as a companion. Everything is encrypted on-device (AES-256-GCM, PBKDF2-600k) before it leaves the phone. It is a product, not a demo: it ships signed release builds and is being prepared for Google Play.

## 2. How it works right now
**Android (Jetpack Compose + Material3 + Hilt + Room v4).** 19 screen groups under `presentation/screens/`:
`welcome` (3-option onboarding), `auth` (master passphrase + biometric gate), `lock`, `home` (4x3 module grid + Vault Composition pie), `vault` (passwords/entries), `wallet` (cards, Pay sheet), `notes`, `files` (sealed vault + WhatsApp import + scanner), `scanner` (CamScanner-style capture), `contacts`, `crypto` (wallets, receive, send with signing), `security` (dead-man switch, inactivity lock, decoy vault, clipboard range, wipe), `health`, `backup` (passphrase-encrypted export), `sync` (Sync Center), `settings` (theme switcher, Blogger theme export, pairing), `viewer` (in-app media/document), `more`, `diagnostics` (crash report + RescueScreen).
Room DB **version 4**, 6 entities: `secure_notes`, `vault_entries`, `digital_cards`, `totp_accounts`, `secure_contacts`, `crypto_wallets` (+ a separate encrypted file-vault container store).
**Themes:** exactly five palettes — `ios`, `oneui`, `aqua` (default), Material `light`/`dark` — sharing ONE generated 65-drawable icon board; geometry is meant to be identical, only colours differ.
**Sign-in:** Google Play Services account (`GoogleSignInOptions`, scopes `drive.appdata` + `drive.file`) used for storage authorisation, not as an identity store. There is **no user account system** — no uid, no profiles, no server-side user record.
**Web:** a Blogger XML theme generated from `app/src/main/assets/blogger_theme.xml`, plus `tools/blogger-theme/generate.js` as a separate desktop generator. It signs in with Google Identity Services and talks to **Google Drive REST** directly from the browser.

## 3. How data flows today
Local mutation → Room (field-encrypted) → `VaultRepository` emits on a SharedFlow (`VaultRepository.kt:87-99`) → `AutoSyncManager` debounces **500 ms** (`AutoSyncManager.kt:56,85-101`), retries 5x with 1500 ms linear backoff → `BackupEngine.buildPayload()` reads **all rows of all tables** into one `BackupPayloadDto` (`BackupEngine.kt:83-101`) → sealed into `{v:3, salt, data}` (`CloudSyncManager.kt:783-808`) → uploaded to Drive **appDataFolder** as `vault_backup.enc` + `vault_backup.xml.enc` + up to 4 rotating snapshots. Restore happens only at startup via `hydrateVault()` (`CloudSyncManager.kt:430-445`).
Web side: GIS token → Drive REST list appDataFolder → download → the user must **type the export passphrase** into `#svp-pass` → decrypt → render. Files ride inside the JSON as base64 up to 25 MB each.
**There is no push mechanism anywhere.** No socket, no listener, no FCM, no realtime DB. The web page never even polls.

## 4. Version history (from build artifacts and the task ledger)
`3.0.0` scaffold/crypto/Drive/Compose → `3.0.2`→`3.0.8` dashboard, scanner, contacts, autofill, crypto module → `3.0.10`→`3.0.18` Blogger web companion, pairing QR, CamScanner pipeline, backup export, 2 GB streaming uploads → `3.1.0`→`3.1.6` theme system, 4x3 grid, per-theme pie charts, crash logger → `3.1.8` five-theme lineup + unified icon board → `3.1.9` boot rescue screen, watchdog, WhatsApp evidence-grade archiver → `3.2.0`→**`3.2.1`** (current, signed, APK + AAB built, Play submission started but blocked on the US$25 fee).
`feature_backup/`, `theme_backup/`, `whatsapp_chat_backup/` are prior rollback checkpoints; `backup_2026-10-29_12-00/` is this program's checkpoint.

## 5. How problems were fixed before (patterns to respect)
- **Crash with no adb:** on-screen crash report → `Downloads/SecureVaultCrash.txt` → de-obfuscate with `mapping.txt`; two deaths in a row route to `RescueScreen`.
- **Room schema drift** killed Home on upgrade-install → every entity change now needs a version bump + migration. (Why no agent may touch the schema casually.)
- **R8 shrinking is off in release on purpose** — a shrunk build died in a Compose static-initialiser cycle on the owner's OPPO CPH2095. Any new SDK must be initialised lazily, never at class-load.
- **Delete resurrection** was fixed once for local+cloud wipe (task #77) but the *sync* merge still has no tombstones, so it can come back.
- **Universal cloud key** is banned from the Blogger theme by an assertion in `tools/blogger-theme/generate.test.js:36`.
- Biometric-only unlock (device credential caused a lock-screen PIN bug); pre-login screens must always have a timeout and an escape; archive to a visible Drive ZIP before any cloud erase.

## 6. Weak, missing or risky
1. **The core feature does not work.** APK and web cannot read each other's data: the APK seals with `PRIMARY_CLOUD_KEY` (`CloudSyncManager.kt:121-124`) which is (correctly) never shipped to the browser, so the web only opens a manually re-sealed export. **Sync is not instant, and is not even compatible.**
2. No identity layer: no uid, no per-user storage namespace, no shared account between the two frontends.
3. Whole-vault rewrite per sync — bandwidth and latency scale with the entire vault, so sub-second is structurally impossible.
4. No conflict rule: same-id cloud writes silently overwrite local; tombstones are dropped on import (`BackupEngine.kt:130-131`) → deleted items resurrect.
5. Files >25 MB never reach the web; the web has download-only rendering, no inline viewer.
6. Settings, theme choice and the sync log are not mirrored at all.
7. `google-services.json` (Firebase project `vault-35423`, cert hash matching `release.jks`) has been sitting in the repo **unused** — no Firebase dependency existed.
8. UI debt: white icon ink baked onto pastel plates (12/13 below 3:1 in the default theme), pie centre/legend text below AA and clipping on selection, per-theme tile geometry drift, dialogs without scroll containers.
9. WhatsApp import: ZIP transcript found by filename only, so a real `Name _chat.txt` can import as "0 messages"; Downloads picker hint is a content URI a documents UI cannot resolve; picked URI grant is not persisted; busy-gate could swallow a pick silently.

## 7. Improvement plan
**Keep:** the whole Compose app, Room store, crypto primitives, Drive as the *media/archive* store, the five-theme token system, the Blogger theme's rendering layer, the crash/rescue instrumentation.
**Add:** a real identity + realtime layer — Firebase Auth (Google) for a shared uid on both frontends, Realtime Database (already provisioned: `vault-35423-default-rtdb`, Singapore) for **per-record delta writes and push listeners**, a per-user **VaultKey** wrapped by the user's passphrase so the browser can decrypt without any universal key.
**Replace:** Drive-as-sync-transport, the whole-vault rewrite, the merge rule (per-record LWW + tombstones), filename-based transcript detection.
**Improve:** icon plate contrast at build time, pie legibility, tile geometry parity, dialog scroll containers.
**Why:** this makes one backend with two frontends, keeps end-to-end encryption honest, and turns sync from "re-upload everything, maybe compatible" into "write one record, both clients see it in the same second."

## 8. Core feature verdict
**Instant sync: NOT working today — and it cannot work in the current design.** It is the #1 acceptance test for this program. Contract: `sync_engine/SPEC-v1.md`. Ledger: `sync_engine/HANDOFF.md`.
