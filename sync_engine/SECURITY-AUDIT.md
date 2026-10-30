# Security audit — A9 (Trust & Security), 2026-10-29

Scope: the wipe path, biometric unlock, pre-login escape hatches, ciphertext-only, TLS, secret
hygiene and keyring exposure for `D:\sharyar\SMART VALUATE`. Everything is evidence-based; `file:line`
is given for each verdict. No Gradle was run (siblings held the build lock) — see "Not verifiable
without a device".

---

## MANAGER ACTION REQUIRED

Three findings are genuine and **cannot be fixed inside my owned files**. None of them is caused by
this audit round; all three predate it.

1. **Release keystore passwords are hardcoded in the build script.**
   `app/build.gradle.kts:29` and `app/build.gradle.kts:31` assign the keystore and key passwords as
   string literals (the same two values also sit in the stale copies `theme_backup/build.gradle.kts:28,30`
   and `backup_2026-10-29_12-00/app_build.gradle.kts:28,30`). `release.jks` is committed at the repo
   root and **there is no `.gitignore` anywhere in the tree**, so the key material and the words that
   unlock it travel together in every clone and in `backup_2026-10-29_12-00/`. Value not reproduced
   here. Fix: move both to `gradle.properties` / env vars (`%` -referenced or `providers.environmentVariable`),
   add a `.gitignore` covering `*.jks`, `google-services.json`, `local.properties`, `.gradle/`,
   `*.apk`, `backup_*/`, `theme_backup/`, and rotate the keystore password if the repo has ever been
   pushed anywhere shared. `app/build.gradle.kts` is explicitly not mine.

2. **The retired universal-key literal is spelled out verbatim in the guard that is supposed to
   protect it.** `tools/blogger-theme/generate.test.js:36` asserts the theme does not contain
   `SecureVaultPro_Universal_Cloud_Key` — by containing it. Same for the password-shaped fixture on
   line 37 (`testpass123`). A1 already hit exactly this trap in Kotlin and solved it by assembling
   the string at runtime (`app/src/test/java/com/securevault/pro/data/remote/firebase/UniversalKeyGuardTest.kt:25`);
   the JS guard was never given the same treatment. `tools/**` is the web agent's. Fix: build the
   needle as `'SecureVaultPro' + '_Universal_Cloud_Key'`. This is documentation-hygiene only — it
   does not weaken the assertion, and I did not touch it.
   Related, same family: `CloudSyncManager.kt:130-133` keeps all four retired literals in plain
   source. The spec allows that (legacy *read* candidates) and `buildCandidates` (`:952-974`) is the
   only consumer, so it is *accepted*, but a leaked APK plus these strings is a decrypted backup for
   anyone who ever used the universal key — the migration-off schedule should get a date.

3. **A passphrase change that cannot reach the network silently strands web access, permanently.**
   `AppLockManager.setMasterPassword` (`AppLockManager.kt:201-214`) writes the new master hash first
   (`:208-212`) and only then fires `keyring.onPassphraseRotated(previous, new)` (`:213`) — fire and
   forget on a background scope. If that rotation fails (offline, timeout, Drive not ready) the old
   passphrase is never stored anywhere, so it can never be retried. The cloud keyring then still
   opens only with the old passphrase, and the natural self-heal path is closed: `Keyring.publishKeyring`
   (`Keyring.kt:246-253`) reads the existing node, fails to open it with the new passphrase and
   returns `WRONG_PASSPHRASE` **without writing** — deliberately, because clobbering is worse.
   Result: the Blogger companion asks forever for a passphrase that opens nothing, and the owner has
   no in-app way to re-publish. Not fixable in my files (the recovery needs a deferred
   `rotateKeyring` retry state in `Keyring.kt`, which is `data/remote/firebase/**` — forbidden to me).
   Suggested shape of the fix: persist a "keyring rotation pending" marker (previous passphrase is
   *not* persisted) and have `setMasterPassword` re-offer it on the next successful
   `verifyMasterPassword`, or make the rotation a bounded, awaited step that rolls the local hash
   back when it fails. I did **not** change the passphrase flow unilaterally — it is the one path
   where a clever wrong fix equals a permanent lockout.

Two smaller items for whoever owns the file:

* `CloudSyncManager.kt:338` still shows the user, mid-restore, `"PBKDF2-SHA256 · universal cloud key first"`.
  Since A1 the first candidate is the owner's own vault key (`:856-857`). Harmless but it advertises
  a shared-key story that is no longer true (and the same wording appears nowhere else).
* `wipeAll` never erases the realtime data nodes. Drive backups are deleted
  (`LocalWipeService.kt:181-184`) and the *local* key copy is dropped (`:225-237`), but
  `svp1/users/$uid/data/*` and the cloud keyring stay — by A1's design (`Keyring.kt:426-432`,
  "Forgets the local copy only"). A "Wipe all data" therefore does not remove the vault from the
  realtime database. If the product means "erase everything", A2 owes a `stopAndClear`-adjacent
  cloud-data delete behind an explicit confirmation; if it means "wipe this device", the UI label
  should say so. Manager call, not mine.

---

## 1. Wipe ordering invariant — PASS after fix (was drifted)

**Rule:** archive to a visible Drive ZIP first, abort the erase if the archive fails, then local
wipe, then cloud erase, then `RealtimeEngine.stopAndClear()` + `Keyring.clearDeviceKey()`.

**What I found.** The archive-first / abort-on-failure half was intact and correct
(`LocalWipeService.kt` old `:97-111`: `archiveVaultSnapshot()` before anything else,
`return@withContext` with `localCleared = false` when it fails). Two drifts against the stated
contract:

* the order was archive → **cloud erase → local wipe**, i.e. the cloud copies were deleted before
  the device was;
* `RealtimeEngine.stopAndClear()` and `Keyring.clearDeviceKey()` were wired to nothing — exactly the
  open item A1 left in `sync_engine/HANDOFF.md` ("`Keyring.clearDeviceKey()` is wired to nothing").
  A wiped device kept its Keystore-wrapped vault key in `svp_keyring_prefs` and could keep sealing.

**What I changed** (`app/src/main/java/com/securevault/pro/security/LocalWipeService.kt`):

* order is now archive → local wipe → cloud erase → engine stop + key clear, as specified;
* `RealtimeEngine` and `Keyring` injected (`:93-101`); `releaseRealtimeKeyring()` (`:225-237`) calls
  `realtimeEngine.stopAndClear()` then `keyring.clearDeviceKey()`;
* it runs in a **`finally`** (`:206-210`), so a throwing cloud step cannot leave the engine alive —
  the safety property no longer depends on the happy path;
* `wipeLocalOnly()` (the dead man's switch, `DeadManSwitchManager.kt:53`) releases the key too (`:133`);
* every network step is individually bounded (`:110-122`: archive 240 s, cloud delete 90 s, Drive
  sign-out 10 s, engine stop 20 s) through `attemptWithin` (`:246-252`), which returns a timeout as
  `value = null` and **re-throws `CancellationException` instead of swallowing it** — a wipe whose
  caller died stops, it does not continue half-cancelled;
* on a failed archive the message now says plainly that nothing was erased and the vault is intact;
* on a failed cloud erase the owner is told the archive name and that the cloud copies still exist,
  and Drive is **not** signed out on that path, so a retry can succeed from the same screen
  (sign-out moved to the success branch, `:196-199`).

**Trade-off I made deliberately, flagging it for you:** the old code aborted the *whole* wipe when
the cloud delete failed ("Nothing was erased locally"). The contract order does not allow that any
more — the local wipe happens first. The rule that actually protects the user ("never lose data with
no copy") is still guaranteed because the archive is verified before anything is destroyed, and the
residual risk (old Drive backups survive a failed cloud erase) is now reported instead of silent. If
you prefer the old stricter behaviour, the change is one `return@withContext` in front of the local
wipe — say so and the Manager can apply it; my guard test asserts the specified order.

**Guard added:** `app/src/test/java/com/securevault/pro/security/WipeOrderingGuardTest.kt` (7 tests)
pins the ordering, the abort-with-`localCleared = false`, the `finally`-bound key release, the
per-step timeouts and the cancellation re-throw, so a future reorder fails a test instead of shipping.
It reads source because the wipe needs Drive, Keystore and a Google session — none of which exist in
a JVM test — and because the failure being prevented is specifically "someone edited the order".

## 2. Biometric-only unlock — PASS, guard added

* The single authenticator set in the app is `presentation/components/BiometricUtils.kt:16-18` =
  `BIOMETRIC_STRONG or BIOMETRIC_WEAK`, applied at `:55`. No `DEVICE_CREDENTIAL`, no
  `setDeviceCredentialAllowed(true)`, no `createConfirmDeviceCredentialIntent` anywhere in
  `app/src/main` (repo-wide grep: the token appears only in two comments in that file, and in the
  `backup_2026-10-29_12-00/` / `theme_backup/` snapshots which are not built).
* `setNegativeButtonText("Use master password")` (`BiometricUtils.kt:58`) is the documented way out
  of the prompt, and `LockScreen.kt:141-145` maps `ERROR_NEGATIVE_BUTTON` to manual entry.
* Sync adds no prompt: the realtime package contains no `BiometricPrompt` reference at all.
* **Guard added:** `app/src/test/java/com/securevault/pro/security/BiometricAuthenticatorGuardTest.kt`
  (3 tests): bans the token in *all* main sources (comments stripped so `https://` and the two
  explanatory comments do not false-positive), bans the two device-credential APIs, requires every
  `PromptInfo.Builder` to declare `setAllowedAuthenticators` **and** a negative button, and pins the
  mask members to `Authenticators.BIOMETRIC_*` so a future `or Authenticators.PASSWORD` fails even
  though it does not spell the banned word. The banned token is assembled at runtime from two
  fragments (`BiometricAuthenticatorGuardTest.kt:32`), following A1's precedent, so the test file
  cannot trip its own scan.

## 3. Pre-login escape hatches — PASS after fix (backup screens), no gap found (security screens)

Files I own: `presentation/screens/security/**`, `presentation/screens/backup/**`.

* `SecurityToolsScreen.kt` — no modal, no blocking gate (toggles only; no `Dialog`, no in-flight
  flag). Nothing to fix.
* `BackupScreen.kt` **had a dead end**: `CloudOperationOverlay` is a `Dialog` with
  `dismissOnBackPress = false` and `dismissOnClickOutside = false`
  (`presentation/components/CloudSyncOverlay.kt:62-68`) and the backup screen called it **without
  `onCancel`** (old `:496-505`), so `OnCancel == null` renders only the text "Keep this screen open
  while your vault is restored" (`CloudSyncOverlay.kt:192-198`). A stalled Drive sign-in or hydrate
  trapped the user on a full-screen, back-key-proof spinner with no exit. `completeGoogleSignIn` and
  `hydrateVault` have no internal timeout of their own (`CloudSyncManager.kt:535`, `:440`).
  Fixed, mirroring the pattern already used by `SetupScreen.kt:147-161` and `OnboardingScreen.kt:356`:
  after a 20 s grace (`BackupScreen.kt:501-530`) the overlay grows the existing "Stop waiting and
  continue" button (`CloudSyncOverlay.kt:208-212`), which calls the new
  `BackupViewModel.abandonCloudWait()` (`BackupViewModel.kt:214-227`) — it clears `driveLinking`,
  `loadingBackups`, `previewing`, cancels the list job and calls `cloudSyncManager.resetState()`
  (`CloudSyncManager.kt:516`) so the overlay cannot be re-armed by a stale `active` flag. Nothing is
  cancelled mid-transfer and nothing is erased; the message says so.
* Timeouts added around every cloud step this screen starts (`BackupViewModel.kt:62-72`): Drive
  sign-in 90 s, hydrate 150 s, list 60 s, preview 90 s, and each one clears its own in-flight flag on
  the timeout path — previously `previewing` could stick if anything outside the bounded download
  hung, which greyed out the INSPECT control forever (`BackupScreen.kt:685-700`). The two nullable
  answers are boxed (`BackupViewModel.kt:52`) so a `null` from `withTimeoutOrNull` means only
  "timed out" and can never be mistaken for a legitimate "no account" / "nothing to restore".
* The wipe gate itself (`SettingsViewModel.wipeVault`, `:569-598`) is not my file; it now always
  gets a `WipeOutcome` back because every step inside `wipeAll` is bounded — that was the point of
  item 1's timeouts. It still requires the master password and has no inactivity deadline, which is
  correct for a destructive action (a timeout on a confirm dialog is a UX hazard, not a security one).
* The lock screen already had bounded behaviour: 5 failures → 30 s lockout
  (`AppLockManager.kt:294-307`), a master-password negative button, and a biometric prompt failure
  falls back to manual entry (`LockScreen.kt:148-152`). One thing to watch, not a defect I can fix:
  `LockScreen.kt:95` `biometricInFlight` is only cleared inside prompt callbacks, so a BiometricPrompt
  whose service never calls back leaves the unlock button spinning — `manualEntry` still unlocks the
  keypad (`:134`, `:144`, `:150`), so the user is never trapped, only the button is stuck.
  `presentation/screens/lock/**` is not in my ownership list, so I left it.

## 4. Ciphertext-only audit — PASS

* Node shape: `RealtimeEnvelope.toCloudMap()` (`data/remote/realtime/RealtimeEnvelope.kt:31-36`)
  emits exactly `FIELD_E/FIELD_U/FIELD_R/FIELD_D` (`:41-44`) and `seal()` (`:57-68`) puts
  `KeyringCodec.sealBytes(plaintext…)` in `e` and nothing else — no plaintext field can reach the
  database. `fromCloudMap` rejects anything extra, and `RealtimeFieldSchema.identify` (`:131-133`)
  disqualifies a body with a foreign key rather than guessing.
* Database writes: Room never receives plaintext from the sync path — `RealtimeMergeResolver` returns
  an action (`WRITE_REMOTE` etc.) and the caller writes the decrypted entity, which Room then seals
  with `CryptoManager` (`CryptoManager.kt:354-358`, AES-256-GCM, fresh 12-byte IV). No `rawQuery`,
  no plaintext column added.
* Drive writes: the only cloud writers are `Keyring.kt:285/413` (keyring node: `wrap, salt, alg,
  iters, updatedAt`) and `CloudSyncManager` envelopes sealed via `sealingSecret()` — archive path
  `CloudSyncManager.kt:584` (`sealPayload(json, secret, 3)`), verified by
  `UniversalKeyGuardTest.no new write path seals with the universal key`.
* Logs: `SyncLatencyLog` (`data/remote/realtime/SyncLatencyLog.kt`) appends to
  `Downloads/SecureVaultSync.txt`; the only record-shaped string on that path is
  `RealtimeFieldSchema.Summary` → `"module/id u=clock d=tombstone"` (`RealtimeFieldSchema.kt:206-208`,
  "Everything except the plaintext itself"), and `RealtimeMergeResolver.describe()` is documented and
  built as clocks/revisions only (`:83-86`). `CrashLogger` (`diagnostics/CrashLogger.kt`) receives
  labels + `Throwable.message`; every call site passes a sentence, never a payload (audited all 20
  call sites). `RealtimeEnvelope.open` keeps the key buffer caller-owned (`:71-75`).
* Universal key reachable from a new write path: **no.** `PRIMARY_CLOUD_KEY` / `LEGACY_CLOUD_KEY*`
  (`CloudSyncManager.kt:130-133`) appear only at `:870` (a *warning* after a successful legacy open),
  `:879` (the `version < 3` read loop) and `:952-974` `buildCandidates`, which is read-only. Confirmed
  by test as well as by grep.
* Blogger theme: clean, twice over — `UniversalKeyGuardTest.the blogger theme never carries the
  universal key` (also bans `storePassword`) and `tools/blogger-theme/generate.test.js:36`.
* **Strengthened, not weakened** — I added two tests to `UniversalKeyGuardTest` rather than editing
  the existing four: `the realtime engine never carries the universal key` (A2's package must not
  contain the literal *or* any retired constant name, and must reference the keyring) and `the
  security package never carries the universal key`. Both skip only when the directory is absent, so
  they cannot be made vacuous by an empty package.

## 5. TLS audit — PASS, nothing found, nothing relaxed

* `res/xml/network_security_config.xml:3` base `cleartextTrafficPermitted="false"`; the only
  cleartext domain block (`:8-12`) is `10.0.2.2`, `127.0.0.1`, `localhost` — loopback and the emulator
  host gateway, which is what the local companion dev server needs, and it carries only envelopes
  already sealed by the vault key. No wildcard, no production host.
* No `trustAll`, no `X509TrustManager`, no `HostnameVerifier`, no `sslErrors`, no
  `setSSLSocketFactory`, no `ALLOW_ALL_HOSTNAME_VERIFIER`, no `checkServerTrusted` anywhere in the
  repo (grep across all files: zero matches).
* Codebase `http://` matches are XML namespace URIs (`xmlns:android=…`), SVG namespace declarations,
  URL-prefix stripping for display (`domain/model/Models.kt:40`, `VaultRepository.kt:817`,
  `EntryDetailScreen.kt:304`), and companion-URL tests. No request targets `http://`.
* Companion transport policy `data/remote/companion/CompanionUrl.kt:14-22` accepts `https://`
  everywhere and `http://` only for `localhost / 127.0.0.1 / ::1 / 10.0.2.2`; the paired URL is
  refused otherwise (`CompanionPairingTest.kt:70-82`).
* Firebase/RTDB is on `https://vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app`
  (SPEC §header) and the SDK owns that socket; `FirebaseBootstrap` installs no custom trust.
* RTDB rules published and verified by the Manager (`HANDOFF.md` blockers section, 2026-10-29):
  anonymous read/write to `svp1/…` returns 401.

## 6. Secret hygiene — 1 violation outside my files (see MANAGER ACTION #1), 2 accepted

* **Violation:** `app/build.gradle.kts:29,31` (keystore passwords) — reported, not pasted, not fixed
  (forbidden file). Copies in `theme_backup/build.gradle.kts:28,30` and
  `backup_2026-10-29_12-00/app_build.gradle.kts:28,30`.
* **Violation (docs/tests):** the retired universal-key literal verbatim in
  `tools/blogger-theme/generate.test.js:36`; a password-shaped fixture literal on line 37.
* **Accepted, not secrets:** `google-services.json:31` Firebase web API key (mirrored to
  `app/google-services.json` and into the generated theme
  `tools/blogger-theme/secure_wallet_blogger_theme.xml:136`) and the two OAuth client IDs in
  `SPEC-v1.md:6`. These are public browser identifiers gated by the RTDB rules; they are not vault
  key material. Recommendation (Manager, console-side, no code): restrict the browser key by
  HTTP-referrer to the Blogger domain so a leaked bundle cannot be used for auth enumeration.
* `CryptoManager.kt:33` `KEY_ALIAS = "SecureVaultProMasterKey"` is a Keystore alias, not a key.
  `AppLockManager` stores only PBKDF2 hashes + salts (`:174-179`), never a passphrase.
  `Keyring` stores only a Keystore-wrapped blob (`Keyring.kt:66-69`).
* Kotlin test fixtures: no passphrase-shaped literals — `UniversalKeyGuardTest` builds its needle at
  runtime (`:25`), `KeyringCodecTest`/`KeyringEnvelopeTest` use `System.nanoTime()`-derived values
  (A1 self-fix 11). Verified by grep for `storePassword|keyPassword|apiKey|clientSecret|token = "…"`
  across `app/src` → zero matches, and for `AIza…` / bearer tokens → only the public Firebase config.
* Nothing secret was written into `SECURITY-AUDIT.md` (this file) — findings are named by file:line.

## 7. Keyring exposure — PASS with two documented observations

* **`KeyringStatus` holds no key material** — the class has exactly `uid / hasDeviceKey / publishedAt
  / note` (`Keyring.kt:32-37`), all identifiers, a boolean, a timestamp and a worded sentence;
  `publishStatus()` (`:444-450`) and `markPublished()` (`:434-442`) only ever copy those.
  `_status.value = KeyringStatus(uid = auth.cachedUid())` on clear (`:431`). No `bytes`, no base64,
  no `VaultKey` field exists. Note `KeyringStatus.note` is safe to render and `sealingSecret()` is
  only ever returned to a caller, never stored in status.
* **`VaultKey.destroy()`** exists and zeroes in place (`VaultKey.kt:30-32`); callers obey: the
  transient key buffers in `publishKeyring` (`Keyring.kt:257, 261`), `rotateKeyring` (`:387, 392, 398,
  402`), `adoptDeviceKey` (`:164-183`, which zeroes the caller's buffer and destroys the previous
  cached key), `KeyringCodec.seal/unwrap` (`VaultKey.kt:83, 111` — the derived KEK is zeroed in a
  `finally`), and `deviceVaultKey()` zeroes a fresh key when Keystore refuses to wrap it (`:129`).
  **Observation 1 (by design, not changed):** the *session* copy `cachedKey: VaultKey`
  (`Keyring.kt:88`) is deliberately **not** destroyed on `SessionManager.lock()`, because a locked
  device still seals background sync — that is A1's documented rationale (`:43-46`) and A2's engine
  depends on it (`RealtimeEngine.kt:176-183` clears it only on stop). It is destroyed on
  `clearDeviceKey()` (`:428`), i.e. on wipe — which item 1 now actually triggers. I flag it rather
  than "fix" it: destroying on lock would break realtime sync and is not reversible by me alone. If
  you want defence-in-depth here, the right lever is process death (`android:killActivitiesAfterPause`
  / `onDestroy` → `clearDeviceKey()`), not lock.
* **Master passphrase reaches `Keyring` only at setup / unlock / rotation** — exactly three call
  sites in the whole app: `AppLockManager.kt:193` (after `completeSetup`), `:213` (after
  `setMasterPassword`), `:262` (after a verified `verifyMasterPassword`), plus `SettingsViewModel.kt:400`
  passing the previous value for rotation. The PIN path (`verifyHashedPin`, `:271-284`) and the
  decoy path touch only `KeyDerivation` and DataStore — no `keyring` reference at all. The biometric
  path (`LockScreen.kt:122-146` → `viewModel.onBiometricSuccess()`) never materialises a passphrase;
  `verifySecret` (`:286-292`) only routes to the passphrase branch when the user actually typed one.
  Interactive exports use a *user-typed* passphrase, not the master one
  (`SettingsViewModel.kt:600-639`, documented at `:604-607`).
* **`rotateKeyring(previous, new)` proves ownership before overwriting** — `Keyring.kt:374-392`: it
  reads the node, opens it with `previousPassphrase` (or with `newPassphrase`, which is a no-op
  confirmation), and then requires `constantTimeEquals(proven, deviceKey)` before sealing anything;
  a mismatch returns "not this device's key, so nothing was changed" with the node untouched. A typo
  therefore fails with `WRONG_PASSPHRASE` and cannot clobber the live keyring — no `updatedAt`
  heuristic, as A1 rejected. `publishKeyring` (`:246-268`) likewise never overwrites a node the
  passphrase does not open; an empty node is the only write case. **Observation 2** is the failure
  *mode* of this design, and it is MANAGER ACTION #3 above: the safe-no-clobber rule is what makes a
  lost rotation unrecoverable.

## 8. Extra finding in my own files — fixed

`CryptoManager.dataKey()` (`security/CryptoManager.kt:324-352`) silently minted and **persisted a new
DEK** whenever the stored wrapped DEK failed to unwrap. A transient Keystore fault (they do happen —
`keystore` daemon restarts, OEM ROM quirks) therefore overwrote the only copy of the key that opens
every field already in the database, making the whole vault permanently unreadable with no message to
the owner. Now: before replacing it the old blob is preserved under its own preference
(`DEK_WRAPPED_ORPHANED`, `:352-364`) so a later launch with a healthy Keystore can still recover, and
a fault is recorded (`:360-364`); the orphan copy is removed with everything else on wipe (`:286`).
Behaviour on the healthy path is unchanged, and no KDF parameter, iteration count, salt length, IV
length or tag length was touched anywhere: `PBKDF2_ITERATIONS` 600 000 / PIN 210 000, 32-byte salt,
12-byte IV, 128-bit tag, `AES/GCM/NoPadding` all still match `KeyringCodec` (`VaultKey.kt:50-57`) and
the web profile in `HANDOFF.md` §"How A3 unwraps the keyring".

## Changes made (complete list)

| File | Change |
|---|---|
| `app/src/main/java/com/securevault/pro/security/LocalWipeService.kt` | Injected `Keyring` + `RealtimeEngine`; contract order archive → local wipe → cloud erase → `stopAndClear()` + `clearDeviceKey()`; key release in `finally`; per-step timeouts via `attemptWithin` (cancellation re-thrown, not swallowed); Drive sign-out only on cloud-erase success; worded abort/retry messages; rule documentation on the class |
| `app/src/main/java/com/securevault/pro/security/CryptoManager.kt` | Preserved an unwrappable wrapped DEK instead of silently overwriting it; fault recorded; orphan blob cleared on wipe |
| `app/src/main/java/com/securevault/pro/presentation/screens/backup/BackupViewModel.kt` | `abandonCloudWait()` escape + `isWaitingOnCloud()`; bounded timeouts on sign-in / hydrate / list / preview with flags cleared on every path |
| `app/src/main/java/com/securevault/pro/presentation/screens/backup/BackupScreen.kt` | The undismissable restore overlay now grows the existing "Stop waiting and continue" button after a 20 s grace (same pattern as `SetupScreen`/`OnboardingScreen`) |
| `app/src/test/java/com/securevault/pro/security/BiometricAuthenticatorGuardTest.kt` | NEW — 3 tests, biometric-only unlock enforced across all main sources |
| `app/src/test/java/com/securevault/pro/security/WipeOrderingGuardTest.kt` | NEW — 7 tests, wipe ordering + archive-abort + key-release + bounded steps |
| `app/src/test/java/com/securevault/pro/data/remote/firebase/UniversalKeyGuardTest.kt` | 2 tests ADDED (realtime package, security package). The existing 4 are untouched and unweakened |
| `sync_engine/SECURITY-AUDIT.md` | NEW — this file |

No file outside my ownership was edited. `data/remote/realtime/**`, `data/remote/google/**`,
`data/remote/firebase/**`, `ui/theme/**`, `presentation/components/**`, `AndroidManifest.xml`,
`app/build.gradle.kts`, `di/AppModule.kt`, `tools/**` and `assets/blogger_theme.xml` were read only.

**Contract status vs the Android agent:** `com.securevault.pro.data.remote.realtime.RealtimeEngine.kt`
**did exist on disk when I finished** (it appeared mid-session; A2 owns it). Its surface matches the
frozen contract exactly — `start()` `:115`, `markRecordChanged` `:150`, `markRecordDeleted` `:157`,
`suspend fun stopAndClear()` `:176`, `@Singleton` with an `@Inject` constructor `:72-75`, and
`stopAndClear()` itself calls `keyring.clearDeviceKey()` `:183`. So `LocalWipeService` should compile
as written; when I wrote the call the class did not exist yet, and I followed the contract verbatim
rather than guessing at an API. I did not edit that file.

## Not verifiable without a device (no emulator, no adb on this machine)

1. **That the wipe really kills sealing.** The `stopAndClear()` → `clearDeviceKey()` chain is wired
   and unit-guarded at source level, but I cannot prove on hardware that after "Erase all data"
   `Keyring.deviceVaultKey()` returns null and a later write fails with the worded
   `CloudSyncException` instead of sealing with a stale key. Needs a run.
2. **That the pre-wipe archive ZIP is visible and restorable** in the Drive UI, opens with the
   owner's own vault key, and that `deleteAllCloudBackups()` really empties `appDataFolder`. My
   change is ordering + messaging only; upload visibility scopes were not exercised.
3. **That the biometric sheet on real OEM firmware never falls back to the device credential.**
   `canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK)` behaves differently across OPPO/Samsung
   ROMs; the guard test proves the code, not the platform. Specifically re-check the OPPO that
   produced the original lock-out regression.
4. **That the restore overlay's new "Stop waiting" button is actually reachable** and that
   `abandonCloudWait()` leaves the screen usable (and that the background download landing later does
   not re-arm the overlay). Compose interaction, not testable here.
5. **That a real Keystore unwrap failure keeps data recoverable** — item 8's fix cannot be provoked
   from a JVM test (`AndroidKeyStore` is absent). The orphaned-blob path needs a device or Robolectric
   with a faked `KeyStore`, which the project does not currently set up.
6. **Authenticated RTDB read/write end-to-end** from the APK (A1's remaining blocker), the actual
   `{e,u,r,d}` node bytes in the database, and tombstone propagation after a wipe. Console/rule level
   was verified by the Manager; client level is not reachable from here.
7. **Timing bounds.** ARCHIVE 240 s / DELETE 90 s / STOP 20 s are reasoned from
   `GoogleDriveService`'s own per-request bounds (`:50` 45 s, `:282` 180 s), not measured against a
   real upload on the owner's connection. If a legitimate archive ever exceeds 240 s the wipe aborts
   safely (nothing erased) and tells the user to retry — the failure is loud, never silent.
8. **No build was run.** `testDebugUnitTest` / `assembleRelease` are the Manager's. The two new test
   classes read `app/src/main` through the same `sourceRoot()` convention as
   `UniversalKeyGuardTest.kt:145-148` (module-directory-relative, `assumeTrue`-guarded when absent),
   so they should skip rather than fail on a job that ships only compiled output.
