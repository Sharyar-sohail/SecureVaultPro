# Changelog — Secure Wallet Pro

All notable changes to this app, its Blogger web companion and the build tooling.
Version numbers are the shipped `versionName` / `versionCode` in `app/build.gradle.kts`;
every release keeps the same signing key, so a new APK installs over an old one and the
vault survives the upgrade.

Release artifacts for each version: `SecureWalletPro-<version>.apk`,
`Blogger-Theme-<version>.xml` (byte-identical to `app/src/main/assets/blogger_theme.xml`,
with a blank `var SEED`), and `app-release.aab` for Play.

## Repository

Version control starts here: this directory had no git repository through 3.4.0, so every
release existed only as a folder plus the APK/XML artifacts handed over by hand. The baseline
commit imports the 3.4.1 working tree — app source and tests, the Blogger theme asset, the
icon/theme tooling and the sync-engine contract — and deliberately excludes `release.jks`,
`keystore.properties`, `google-services.json`, `local.properties`, every built APK/AAB and
Blogger XML artifact, and the backup/checkpoint snapshots, which hold vault and WhatsApp user
data. One stale 3.3.4 theme export under `release-site/` is left on disk but untracked because
it embedded a Firebase `apiKey`; themes we ship carry a blank `var SEED` and no cloud config.

## [3.4.11] — 2026-10-31 (versionCode 356)

The 3.4.10 sweep still could not open the owner's Drive backup. Rather than guess at another key,
this release copies the **original app's decryption routine** found in `D:/sharyar/SecureVault Pro`
(the same-package `com.securevault.app` builds) and in the earlier Password protector writer. The
cipher primitives were already identical — PBKDF2-HMAC-SHA256, AES-256-GCM, 12-byte IV, 16-byte
tag, `base64(iv).base64(ciphertext+tag)` — but the original reader swept a wider matrix than the
new app did, in four concrete ways. All four are now reproduced exactly.

### Fixed — the original reader's key matrix is reproduced, not approximated
- **The Google account ID is tried first.** The original sealed its Drive backup with
  `account.id`, which is numeric and cannot be derived from the email address. `LegacyKeyPlan`
  gained `accountIds`; `CloudSyncManager.buildSecrets` passes `signedInAccountId()`.
- **100,000 / 10,000 / 1,000 rounds are offered.** The original reader swept
  `600_000, 100_000, 10_000, 1_000` for every secret, and the Node backend of that era derived at
  10,000. `LegacyEnvelopeOpener.ITERATION_PROFILES` now carries all six shipped counts, and
  address-era secrets use the original's own four-count order.
- **Short salts are padded to 32 bytes.** The original derived with `salt.copyOf(32)` as well as
  the salt as written, so a file sealed under a 16- or 48-byte salt could only ever open with the
  padded form. `LegacySaltPlan.variants` now sweeps both, written bytes first.
- **The five shared legacy literals are read candidates.** `SecureVaultPro_Master_Encryption_Key_2026`,
  `SecureVaultProKey`, `secure_vault_pro_secret`, `vault_default_key` and
  `SecureVaultProDefaultKey` are tried after the universal strings, before the address. The
  reader also hard-codes the three historical owner addresses and the bare handles it used.

### Fixed — the earliest salt-less backup shape opens
- The first Drive backup was a bare `iv.ciphertext` blob with no envelope and no salt; the writer
  kept the salt in a separate `key.salt` file. `decryptBackup` now recognises that shape, downloads
  `key.salt`, and falls back to the address padded to 32 bytes exactly as the original did
  (`LegacySaltPlan.fallback`). Previously this file was read as plain JSON and reported
  "not a Secure Wallet Pro backup".
- A file opened with a padded salt says so in the restore warnings, and the backup is re-sealed
  under this device's own vault key as before. No stored byte is modified.

### Unchanged on purpose
- PBKDF2, AES-GCM, salt/IV/tag widths, the envelope JSON and the writers are untouched. The
  account ID and shared literals are **read-only** candidates: `UniversalKeyGuardTest` fails the
  build if any of them reaches `createEnvelope(` or `sealPayload(`.
- Nothing is deleted, no slot is overwritten, and every failure sentence still names the measured
  mismatch (salt / IV / ciphertext) or says the key is what differs.

### Added — tests
- `LegacyKeyPlanTest` rewritten for the new order and the per-secret counts.
- `LegacySaltPlanTest` (8) pins padding, truncation, `key.salt` precedence and address fallback.
- `LegacyEnvelopeOpenerTest` gains an account-ID-at-100k file, a 16-byte salt that only opens
  padded, and a progress-callback proof.
- `UniversalKeyGuardTest` gains the shared-literal writer guard.

## [3.4.10] — 2026-10-31 (versionCode 355)

The 3.4.9 restore still failed with "the key that sealed this backup is not on this device
(envelope v3, 4 candidates tried)". The cipher was never the problem — every PBKDF2/AES-GCM
parameter is byte-identical to the original app. The problem was *which key was tried when*:
3.4.9 asked the keyring for the vault key, which **creates** one when none exists, so a fresh
key was derived and tried ahead of `SecureVaultPro_Universal_Cloud_Key_v3` — the literal every
pre-keyring writer actually sealed with. The first four candidates burned the 8 s budget, so the
real key was never reached. This release restores the original reader's exact key order and
budgets, and makes the failure message name the parameter that actually differs.

### Fixed — the old backups' own key is tried first again
- `LegacyKeyPlan.ordered` is now the one source of truth for key order, lifted verbatim from the
  original `decryptBackup`: passphrase → stored vault key → **universal v3** → account address
  (exact, then lowercase) → v2 → v1 → v0. This is the order every build since the format landed
  used, and it is unit-tested.
- `CloudSyncManager.storedVaultSecret` returns the vault key only when `keyring.hasDeviceKey()`;
  a restore no longer generates a key that was never used to seal anything.
- Per-secret iteration profiles follow the original reader: the universal keys sweep
  600,000 → 210,000 → 120,000, the account address and vault key use 600,000, and a file that
  states its own count narrows every candidate to that count.
- Budgets raised to cover the real sweep on a phone CPU: 25 s per file, five files and 60 s for
  the whole walk — enough for the universal key and every profile, still bounded. Stop still
  ends the sweep within one derivation through `operationEpoch`.

### Fixed — the error names the real mismatch
- `EnvelopeDiagnostics` inspects the file before blaming the key: salt width (32 B), IV width
  (12 B), ciphertext length and payload shape. A structural difference reports
  "Salt mismatch: …", "IV mismatch: …" or "Ciphertext mismatch: …" and says the format, not the
  account key, is what differs.
- A structurally intact envelope reports the measured parameters ("Envelope v3, salt 32 B,
  IV 12 B, tag 16 B, shape iv.payload, 600,000 rounds"), then states whether the sweep was cut
  off by its budget or all derivations were rejected — and in the latter case points at restoring
  with the passphrase or recovery phrase if the vault was created with one.
- The message is no longer truncated at 220/240 characters in onboarding or the backup screen.

### Added — tests
- `LegacyKeyPlanTest` (8) pins the historical order, the no-generated-key rule, the per-era
  iteration profiles, deduplication, stated-count narrowing and the empty-email case.
- `LegacyRestoreBudgetTest` gains a proof that the retired universal v3 key opens the
  pre-keyring `A-universal-v3-600k` vector in one derivation in under 2 s.
- `EnvelopeDiagnosticsTest` (5) proves a shipped vector is reported intact and that a 16-byte
  salt, 16-byte IV and short ciphertext each produce their own named mismatch.
- `UniversalKeyGuardTest` now reads `LegacyKeyPlan.kt` for the read-path assertions and still
  forbids every universal literal in any write path.

## [3.4.9] — 2026-10-31 (versionCode 354)

The owner's Google restore froze at 45% on "Decrypting vault…". The legacy opener swept every
retired key × every iteration profile for every file in Drive, with no deadline, and a single
600,000-round PBKDF2 derivation costs ~1.35 s on a phone-class CPU — so the progress bar never
moved and Stop never took effect. The sweep is bounded now, the account's own key is tried
first, and stopping interrupts the CPU work instead of waiting for it.

### Fixed — restore no longer hangs at 45%
- `LegacyEnvelopeOpener.openWithin` replaces the unbounded `open` sweep: it takes a wall-clock
  budget and a `shouldStop` predicate, checks both before every PBKDF2 call, and reports
  `candidatesTried` / `budgetExhausted` in an `OpenAttempt`. `open` stays as the unbudgeted
  wrapper, so existing callers and tests are unchanged.
- A backup that states its round count derives with that count only. Files that do not state one
  still get the shipped profile list (600,000 → 210,000 → 120,000); no cipher, KDF, salt, IV,
  tag or envelope field changed.
- `buildSecrets` now tries the signed-in account address first (exact, then lowercase), followed
  by the vault key and the retired universal keys. A backup sealed to the owner's own Gmail
  opens on the first derivation; one sealed to something else fails rather than scans.
- `restoreFromCloud` caps a single file at 8 s; `restoreAnyCloudBackup` walks at most 3 candidate
  files under a 24 s wall budget. Exhausting a budget throws a named error ("the key that sealed
  this backup is not on this device…") instead of spinning.
- The 45% detail text now says which key is actually being tried: "Account address, then your
  vault key".

### Fixed — "Stop waiting and continue" stops the decryption
- `CloudSyncManager.operationEpoch` is an `AtomicLong` captured when a flow starts and compared
  inside the derivation loop. `abandonOperation` increments it, so Stop ends the sweep within one
  derivation and the onboarding screen returns with everything downloaded so far intact. The 30 s
  auto-stop uses the same path.

### Added — tests
- `LegacyRestoreBudgetTest` opens the foreign-written `E-email-600k` vector with the account
  address in one candidate in under 2 s (measured ~1.35 s), proves twelve wrong keys halt at
  their deadline rather than running on, and proves a stop signal ends the sweep after a single
  derivation.

## [3.4.8] — 2026-10-31 (versionCode 353)

3.4.5 put FLAG_SECURE on the whole window, so the welcome and sign-in screens could not be
photographed — exactly where the owner needed to report a failure. That is undone, and the
sign-in path that could repeat the same fault is fixed.

### Fixed — screenshots are blocked in the vault, not at the door
- `MainActivity` no longer raises `FLAG_SECURE` on creation. `VaultAppScaffold` raises it only
  for `AppStage.UNLOCKED`, so welcome, onboarding, setup, the lock screen and the rescue report
  stay photographable; leaving the vault clears it immediately.
- The rescue branch clears any flag the vault left behind, because that report is what the owner
  photographs. The preference and the "Allow screenshots" switch are unchanged: blocked by
  default inside the vault, opt-in to lift it.

### Reverted — the legacy decryption is exactly as it shipped
- The 3.4.5 addition of 100k/50k/10k PBKDF2 fallback counts is removed. `ITERATION_PROFILES` is
  again the shipped list only: 600,000 → 210,000 → 120,000, plus a file's own stated count.
- No cipher, KDF, salt, IV, tag or envelope field was touched in 3.4.5 or here: `CryptoManager`,
  `KeyDerivation` and the `vault_backup.enc` writer are byte-identical to the 3.4.1 tree. Old
  backups keep opening with the same code path they always used.

### Fixed — Google sign-in no longer rejects its own success
- `completeSignIn` demanded both the app-data and the file scope. A consent screen that granted
  app-data (all a restore reads) was reported as "permission was not granted", and every retry
  replayed the same refusal. It now requires app-data only; the file scope is still requested,
  and only the pre-wipe archive needs it.
- After a consent/token fault the next tap always opens Google's picker. The cached account can
  keep claiming a grant it no longer holds, which is what made the loop feel endless; a
  `forcePicker` flag now overrides it until a sign-in completes.
- No seed or passphrase stands anywhere in the pre-login flow; seed recovery remains a Settings
  tool after unlock.

## [3.4.5] — 2026-10-31 (versionCode 350)

The owner reported screenshots as a security gap, and that a phrase still stood in front of an
old backup (this time the XML copy of the vault, which the restore walk never tried).

### Added — screenshots blocked by default
- `FLAG_SECURE` is on app-wide from first launch: screenshots and recent-app previews are hidden,
  applied in `MainActivity.onCreate` and re-applied by `VaultAppScaffold` from stored state.
- Settings → Security now carries **Allow screenshots**, off by default, backed by the same
  `screenshot_blocked` flag. Turning it on lifts the flag immediately; it never affects lock state.

### Fixed — no phrase anywhere before login
- The pre-login screen offers exactly three tiles and nothing to type: the seed tab, the
  recovery-seed section, the Drive passphrase dialog, the import passphrase field and the
  failure-card opt-in are gone. `OnboardingScreen` asks the view model for a restore; the file
  tile opens the system picker directly.
- `SetupViewModel` lost `restorePassphrase`, `setRestorePassphrase` and the seed path;
  `restoreFromDrive(email)` is the only Drive entry point and always calls
  `restoreAnyCloudBackup(email)`. `importLocalBackup` now passes `passphrase = null`.

### Fixed — the XML backup is a first-class restore source
- `listCloudBackups()` no longer filters `vault_backup.xml.enc`, and the restore walk orders
  primary first, then newest by modified time, so a JSON slot this device cannot read no longer
  blocks the XML copy of the same vault.
- New `BackupXmlParser` reads the mirror written beside every JSON backup; `BackupParser` detects
  a `<`-leading body and routes it through the parser. The DOM is hardened (no DOCTYPE, no
  external entities, no XInclude).
- Plain-text JSON *and* XML are both accepted from Drive and from a local import.

### Fixed — wider legacy key sweep
- `LegacyEnvelopeOpener.ITERATION_PROFILES` keeps the shipped order (600,000 → 210,000 →
  120,000) and adds cheap fallbacks (100,000, 50,000, 10,000) for builds whose exact count was
  never recorded. GCM still authenticates before anything is accepted.
- Failure and progress copy never mentions a passphrase; the undecryptable diagnostic still
  states envelope version, salt size, payload shape, round count and `secrets x profiles` tried.

### Testing
- 569 unit tests, 0 failures. New `ScreenshotPolicyTest` pins the default-blocked flag, the
  inverted toggle and both FLAG_SECURE application points; `BackupXmlParserTest` round-trips a
  full vault through the XML serializer and rejects DOCTYPE/foreign documents;
  `LegacyRestoreReachTest` pins the XML slot as reachable and the failure copy as phrase-free;
  `WelcomeGateTest` and `GoogleRestoreFlowTest` assert no phrase/seed entry point exists at all;
  `LegacyEnvelopeOpenerTest` pins the fallback profile list.

## [3.4.4] — 2026-10-31 (versionCode 349)

The owner signed in with Google on 3.4.3 and was asked for a passphrase before the restore,
then the attempt failed with a connection error; tapping Google again asked again. The account
was never the problem — the screen was gating it.

### Fixed — Google is the whole authentication
- **One tap, no phrase.** The Google tile calls `SetupViewModel.onGoogleTileTapped()`: with a
  usable Drive grant it restores immediately; without one it opens Google's picker. Neither path
  reads a passphrase and neither can show a seed screen. The passphrase sheet is now reachable
  only from the failure card ("This vault uses a passphrase"), for vaults the owner sealed with
  their own secret — it never stands in front of an ordinary restore.
- **No seed requirement pre-login.** Seed generation and recovery remain in Settings → Security
  tools (and as the optional tab in Import backup); nothing in the Google or file restore is
  conditional on having one.

### Fixed — the connection loop
- A cached account whose Drive grant was lost (reinstall, revoked access) used to be handed to
  the Drive client anyway; the resulting 401 was reported as a connection failure and rebuilt
  identically on every retry. `GoogleDriveService.usableAccount()` now answers only with an
  account that actually holds the Drive grant, and `DriveFaults` recognises a consent/token
  fault by its message. When one occurs the credential is signed out, the owner is told to tap
  Continue with Google again, and the next tap reaches the picker instead of repeating the call.
- The restore walk stops at the first consent fault instead of failing once per backup, and the
  welcome screen's listing cut-off moved from 30 s to 60 s so a slow handshake is no longer
  misreported as "Drive is not answering. Check the connection."
- Every Drive call translates `UserRecoverableAuthIOException` and 401/403 into the same
  actionable sentence; unrelated failures keep their status without masquerading as network
  problems.

### Testing
- 557 unit tests, 0 failures. `GoogleRestoreFlowTest` reads the shipped sources and pins: the
  Google tap has no passphrase/seed branch, both one-tap entry points pass `passphrase = null`,
  the Drive path never reads the file-import passphrase, an auth fault calls `signOutOfDrive()`,
  and the list cut-off outlasts the 45 s Drive call. `DriveFaultsTest` pins the fault classifier
  against both consent faults and real transfer failures; `WelcomeGateTest` asserts the phrase
  sheet has exactly one opt-in opening and the Google tile carries none.
- Verification caveat: no emulator or adb here, so the on-device proof is the owner signing in
  with Google on this APK and watching a restore run with no prompt.

## [3.4.3] — 2026-10-31 (versionCode 348)

The owner installed 3.4.2, signed in with Google, and was told his backup was corrupted and
could not be restored. The file was not corrupted. The app had stopped looking with the key the
file was sealed with, and the writer would have replaced that file on the next sync.

### Root cause — three regressions, no format change
- **The app only knew the new key.** 3.4.x seals backups with a per-account vault key held in
  the Android Keystore. A reinstall has no Keystore copy, so the key that sealed the owner's
  existing Drive backup was simply not among the candidates: the restore tried the new key,
  failed authentication, and reported "corrupted". The envelope format itself — PBKDF2-HMAC-
  SHA256, per-file salt, AES-256-GCM, `base64(iv).base64(ciphertext+tag)` — has never changed,
  and has not been changed by this release.
- **Drive restore never saw the passphrase.** The owner's passphrase was wired only into the
  file-import path. The cloud keyring (which can hand a reinstall its vault key, wrapped under
  that same passphrase) was therefore unreachable from the welcome screen, and the typed
  passphrase was never offered as a decryption candidate.
- **The writer overwrote the slot in place.** `vault_backup.enc` was updated by file id after a
  restore failure installed the demo vault, so the next silent sync could replace the owner's
  only real backup with demo records.

### Fixed — legacy decryption fallback
- `LegacyEnvelopeOpener` sweeps every secret an old build could have sealed with — the typed
  passphrase, this device's vault key, all four retired universal cloud keys, and the account
  address — against every derivation profile any release shipped (600,000 / 210,000 / 120,000
  PBKDF2 rounds), across all three payload shapes (`iv.body`, `iv.ct.tag`, `iv.tag.ct`). GCM
  authenticates first, so a wrong key returns nothing rather than garbage; the first secret that
  authenticates is reported by class so the restore can say how the vault came back.
- `BackupEnvelopeFormat` reads every field name past builds used (`salt`/`kdfSalt`, `data`/
  `ciphertext`/`payload`/`blob`, `iterations`/`iter`/`kdfIterations`, `v`/`version`), unwraps a
  base64-wrapped file, and reports a non-envelope as `recognised = false` instead of guessing.
- Restore now tries **every** backup in Drive — slot first, then dated snapshots and preserved
  copies, newest first — instead of only the slot. A failure names the version, salt length,
  body shape and how many secrets/profiles were tried, and is no longer the word "corrupted".

### Fixed — old backups are never overwritten
- All slot writes go through `BackupSlotGuard`. If the bytes already in the slot do not open
  with this device's key, they are uploaded under `vault_backup_legacy_<yyyyMMdd_HHmmss>.enc`
  **before** the slot is replaced, and that copy is never deleted by any code path (no automatic
  rotation, no retention window). If the slot cannot be downloaded at all, nothing is written.
  A slot this device has already proven it owns is replaced without a download.
- A failed restore no longer installs the demo vault while other copies are still being tried.
  That installation was what made an empty device look populated and let the next sync push demo
  records over the owner's backup.

### Changed — the welcome screen
- Tapping "Continue with Google" now asks for the vault passphrase (optional). Empty still
  works for a device that holds its own key; a reinstall uses it to fetch the vault key from the
  account keyring and to open files sealed with it.

### Testing
- 547 unit tests, 0 failures (43 new).
- `LegacyEnvelopeOpenerTest` decrypts nine envelopes produced by an **independent**
  implementation (`tools/legacy_vectors/generate.py`, Python `cryptography`): universal key at
  600k/210k/120k, passphrase, account address, separate-tag and swapped-tag shapes, a
  base64-wrapped file, and every retired universal key release. The assertion is on the exact
  plaintext JSON, so a regression here means an owner's old backup has stopped opening.
- `BackupEnvelopeFormatTest` holds the reader to every field alias, the wrapped form, and the
  plaintext/foreign cases that must be rejected rather than guessed at.
- `BackupSlotGuardTest` drives the writer against an in-memory Drive: a foreign slot is copied
  out byte-identically before replacement, an unreadable slot writes nothing, a proven slot
  costs no download, and the policy table is exercised state by state.
- Verification caveat: no emulator or adb in this environment. Backward compatibility is proven
  by the independent-implementation vectors above and by the full suite; the on-device proof is
  the owner restoring his existing Drive backup with this APK.

## [3.4.2] — 2026-10-30 (versionCode 347)

Found on the owner's first run of 3.4.1: the pre-login gate had drifted to four tiles, and
starting a Google restore could grey the whole screen out with no way back.

### Fixed — the welcome gate
- **Back to exactly three ways in.** The BIP-39 work had added a fourth tile, "Recover with
  your seed phrase", and the headline had been re-written to match it ("Your vault, four ways
  in"). The tile is gone; seed recovery is now a tab inside the Import backup sheet, so it is
  still one tap away and still works with no Google account and no network. The headline says
  three again, and the ViewModel's own guarantee that the three options never freeze is true
  once more.
- **A restore can no longer pin the screen.** The watchdog that offers Cancel and then
  auto-stops was keyed on the progress stream, so every Drive percent/detail update restarted
  both timers. A restore that kept reporting but never finished — the case on the owner's
  handset — therefore never revealed Cancel and never reached the auto-stop, leaving all three
  tiles disabled behind an overlay with no exit. The clock now runs on wall time from the start
  of the attempt: Cancel always arrives after 6 s, progress only buys patience on the 30 s
  auto-stop, and the tiles are disabled only by this screen's own in-flight attempt.

### Testing
- 510 unit tests, 0 failures. New `WelcomeGateTest` pins the rule by reading the shipped
  screen: exactly three tiles, headline matching, seed recovery reachable as a tab and not as
  a tile, one uniform enable gate, a single wall-clock watchdog that cannot restart on
  progress, and timer constants in a sane order.
- Verification caveat: no emulator or adb here, so the hang is fixed by construction and by
  these source-level pins; the on-device proof is the owner's next run.

## [3.4.1] — 2026-10-30 (versionCode 346)

The owner's test of 3.4.0 found two blockers: the new layouts clipped and overlapped text,
and the steganography module was invisible on the dashboard. Both are fixed and pinned by tests.

### Fixed — Home board geometry
- Tiles no longer demand a height. The board gives each row its share of the pane with a
  weight, so a tile that asked for more than it was handed was **clipped by its own corner**
  instead of growing the row — the cut-off labels in the Gallery and Mosaic screenshots.
  Each cell now measures the height it received and negotiates what that height can pay for,
  in the order the eye forgives: count band → padding → glyph/label gap → glyph → label
  (9sp legibility floor). `fitCell` / `CellFit` in `presentation/theme/Layout.kt`, applied to
  all five cell kinds in `presentation/screens/home/HomeBoard.kt`.
- The grid can no longer leave a hole or an orphan square. `boardGrid` greedy-packs, lets a
  lone middle cell borrow the trailing cell of the row above (this was Bento stacking two
  closing bars), widens the narrowest cells to fill the pane **exactly**, then re-kinds every
  cell for the width it ended up with — so a two-column cell draws a feature, never a
  stretched plate. Gallery's 1+2 and Mosaic's 2+1 rows now close on a full-width bar.
- Dead code removed while in there: `VaultModuleBoard`, `boardRowRemainder`,
  `indicatorTravelFraction` and the imports they orphaned.

### Changed — colour rule
- **No dim plates.** Measured cause: 52 of the 210 catalog accents did not match the plate
  colour the vector actually painted, and 72 accents sat under 0.45 HSL saturation — the
  accents passed contrast while the shipped art stayed pale. All 210 plates were re-cut at a
  chroma floor of 0.62 with white ink ≥ 4.6:1 (`tools/brand-icons/vibrancy-pass.mjs`) and the
  catalog accents re-pointed at the drawn plate.
- Draw layer raised to match: tile beds deepened (plate 0.72, bar 0.88, feature wash 0.20/0.26
  of the *vibrant* accent) and in-tile hints, counts and chevrons now use the module's own
  accent at AA-large instead of the page-wide faint grey token.
- New shared invariant: `VaultContrast.CHROMA_FLOOR` (0.55) with `saturation()` / `vibrant()`.

### Added — Steganography on the dashboard
- `VaultModule.STEGO` ("Secret Image") is a first-class board entry: the home board is now
  **13 modules** on every theme, with 15 new plate icons (`ic_b_<theme>_stego`). Tapping the
  tile opens the hide/reveal screen with Print and Share; it also leads the evening priority
  of the Adaptive board. The engine was reachable only from Settings before, which is why the
  owner could not find it.

### Testing
- 504 unit tests, 0 failures. New: zero holes in every row at **every** module count 1..20 plus
  ascending board order; 15 distinct board signatures retained at 13 modules; the catalog accent
  must equal the plate fill actually drawn in the vector (all 210 pairs); chroma floor and
  white-ink ≥ 4.5:1 per plate; `HomeBoardFitTest` proves no cell exceeds the row height it is
  handed, including the shipped palettes and rhythms at board boxes of 320/380/440/520 dp.
- Verification caveat: no emulator or adb on the build machine, so the board is proven by the
  pure layout/fit tests and the signed build — not by pixels.

## [3.4.0] — 2026-10-30 (versionCode 345)

Fifteen named themes, each on its own `VaultLayout`, plus the theming grammar that replaced
"the same screen in a different colour": column counts, span/kind partitions, board rhythm and
dock silhouette per layout (`theme/Layout.kt`), the shared Phosphor icon board, and the sync
micro-animations on the theme-switch crossfade.

Shipped two blockers that 3.4.1 fixes: the weighted board rows clipped the tiles that demanded
their own minimum height, and the steganography module had no dashboard entry point.

## Earlier releases (reconstructed from the published artifacts)

- **3.3.4 / 3.3.3 / 3.3.2 / 3.3.1 / 3.3.0** — Firebase realtime push sync between the APK and
  the Blogger web companion (one backend, two frontends), the per-user keyring, the on-device
  latency log, and the pre-release trust audit recorded in `AUDIT-2026-10-30.md`.
- **3.2.1** — five-theme lineup with the shared icon board, Play Store prep (`.aab` + upload key
  split) and the privacy policy page.
- **3.1.x** — the brand-themed lineup and per-theme icon ramps, CamScanner-style live document
  scanner, WhatsApp chat archive viewer, in-app Blogger theme export.
- **3.0.x** — the zero-knowledge vault core (encryption format unchanged since, so old backups
  stay restorable), Drive backup and wipe-with-archive, biometric unlock, expiry reminders,
  crypto wallet, autofill, and the crash-report/rescue path for no-adb diagnosis.

## Standing rules these releases obey

- No automatic deletion of user data — no 30-day trash, no time-based purge; data stays until
  the user deletes it.
- The encryption/decryption format is frozen; every old backup must remain restorable.
- Unlock is biometric only (face/fingerprint) — never the device credential.
- TLS is never relaxed; pairing codes are presence-only; the universal cloud key never ships in
  the Blogger theme.
- Release builds ship unshrunk (R8 off on purpose — a shrunk build died in a Compose static
  initialiser on the owner's OPPO).
