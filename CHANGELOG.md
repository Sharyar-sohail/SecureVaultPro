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

## [3.8.0] — 2026-11-06 (versionCode 384)

One theme: the two faces of the vault now wear the same clothes. The web page used to ship a single
hand-written dark skin that knew nothing about the 15 palettes on the phone, and the phone never told
the page which one it was wearing. Both halves are fixed, and neither fix reaches the encryption
envelope, the record nodes, the revision counter or the reconcile loop — the frozen systems are
byte-for-byte what 3.7.0 shipped.

### The web face list is derived from the app, not copied into it

`tools/blogger-theme/palette-bridge.js` parses `VaultPalettes` out of
`app/src/main/java/com/securevault/pro/presentation/theme/Palette.kt` and emits, per theme, the CSS
custom-property block the page renders from (`--bg`, `--panel`, `--accent`, corner radii, border
weight, label case, accent-fill gradients and the layout column count). `generate.js` injects three
things it could not inject before: `var SVP_THEMES`, `var SVP_DEFAULT_FACE` and the `:root` fallback,
which is generated from the app's default face rather than a colour picked in the template. A theme
that exists on one side and not the other is now a build failure, not a silent divergence — the
generator suite asserts the id lists are deep-equal, in order. Ten hardcoded hexes remain in the
template and all ten live inside the generated `:root`.

### The phone publishes its face; the page follows

`FaceMirror` (`data/remote/firebase/FaceMirror.kt`) writes the theme id to `svp1/users/$uid/meta/face`
— a plain string on a node the sync engine already treats as its own bookkeeping. It is deliberately
not part of the engine: 8-second timeout, no retry, and a failure is invisible, because a cosmetic
write must never surface as a vault error or touch a record. `AppViewModel` observes the single funnel
every theme path ends in (`appLockManager.themeId`) — the Settings switcher, the light/dark toggle and
the retired-id repair all pass through it — and publishes only while the session is `REAL`, so a decoy
or locked screen has no mirror to dress.

On the page, `watchPhoneFace()` subscribes to `meta/face` once live mode starts and repaints; a
`<select>` in the header lets the owner pick a face locally (remembered in `svp.face`) without ever
writing back to the phone. `bumpMeta()` and `nextRevision()` both read-then-write inside a
transaction, so neither clobbers the other's children — that was verified against the shipped sources
before this node was used.

### The disk was the actual blocker

`D:` was 100% full (370 MB free): 74 loose release APKs sat in the project root, plus a 1.4 GB
pre-sweep backup. `tools/move-root-apks.sh` copied every one to
`C:\Users\Sharyar\SmartVaultate-APK-Archive`, md5-verified each copy and only then removed the
source — nothing was deleted outright. 370 MB became 5.7 GB.

Two folders now exist outside the project: `D:\sharyar\backup_broken_v4_2026-11-06` (47 MB, all 723
source files, md5-matched samples, zero-byte count 0) as the untouched reference copy, and
`D:\sharyar\SecureWalletPro_v5_Rebuild` — the same engine with the build caches, stale artifacts and
checkpoints excluded (30 MB), which compiles and packages clean. The rebuild is a verified-clean tree
of the shipped code, not a second fork: the app source, crypto and sync logic are the files git
tracks here, and the deliverables below are built from them.

### Measured, not assumed, about the XML

The premise "the Blogger XML is completely non-functional" was tested before being acted on. The
deployed page renders, throws no console errors, carries the Firebase 10.12.2 ESM client with the real
project config, ships the Google button and the pairing QR, and its generator contract tests pass. The
real gaps were the two fixed above, plus one that no XML rewrite can close: **web Google sign-in still
needs `securevaultpro.blogspot.com` added to Firebase Auth → Settings → Authorized domains**, an
owner-side console entry. Until that is done the page's sign-in refuses by design and names the entry.

### Not proven

No emulator or adb here, so the on-device behaviour of the new publisher, the theme switch and the
secure keyboard is unverified on hardware — the evidence is the JVM suite, the generator suite and a
signed release build. And on "vibrant light colours, no dark": 9 of the 15 shipped palettes are still
`isLight = false` by design (the 3 Neon skins, dark, noir, royal and four others are intentionally
dark accent studies). Converting them is a lineup decision, not a bug, so it is left for the owner to
call rather than changed under a rebuild label.

## [3.7.0] — 2026-11-06 (versionCode 383)

One professional-audit wave: each functionality — email, crypto balances and sends, the document
scanner, files, notes, the vault list, cards, contacts, the secure board, biometrics, passkeys, TOTP,
the password analyst, steganography, theming, splash and the crash reporter — was re-read against
current practice and rebuilt where it fell short. 40 source files, ~7,000 added lines. The two
frozen systems are untouched (see *What the wave did not reach*).

### Email: the protocol answers the servers actually give

`ImapEngine` used to act as though every IMAP server were Dovecot. It now reads the capability list
and changes its behaviour per server:

- **A delete only ever removes what the owner selected.** `UID EXPUNGE` with a set argument (RFC 4315)
  is used when the server advertises `UIDPLUS`; the bare `UID EXPUNGE` the client sent before is not a
  valid command, so every server refused it and the screen said "deleted" over messages that were
  still on the server. Without the capability the client stops at flagging and says so, because the
  only alternative — plain `EXPUNGE` — would remove every message already marked deleted in that
  folder, including ones another app left there.
- **Flags are subtracted, never rewritten.** Un-starring now sends `UID STORE n -FLAGS (\Flagged)`
  instead of regenerating the whole flag list, so a mark another client put on the message cannot be
  dropped by guessing.
- **The tagged answer is parsed.** `SELECT`/`EXAMINE` now reads `UIDVALIDITY` and `UIDNEXT` off the
  tagged `OK`, so a folder the server renumbered is noticed instead of silently addressed by the old
  numbers ([`ImapEngine.kt`](app/src/main/java/com/securevault/pro/data/email/ImapEngine.kt),
  [`ImapResponseParser.kt`](app/src/main/java/com/securevault/pro/data/email/ImapResponseParser.kt)).

### Crypto: the wallet stops showing only the gas coin

The balance scan walks every chain the owner has, asks Blockscout for the full token list an address
holds (which catches contracts no public list publishes), and reads anything it cannot find from a
contract named in Settings first. The send path now reserves gas from what the nodes actually report
about the inputs being spent instead of a flat number, with the old flat reserve kept as the
no-answer fallback ([`CryptoBalanceService.kt:68`](app/src/main/java/com/securevault/pro/data/wallet/CryptoBalanceService.kt#L68),
[`CryptoTransferService.kt`](app/src/main/java/com/securevault/pro/data/wallet/CryptoTransferService.kt)).

### Unlocking can no longer strand the owner outside the vault

`BiometricManager.canAuthenticate` is a binder call into a service OEMs leave in odd states, and it
used to run unguarded from view-model init — a `DeadObjectException` there killed the lock screen
before it painted. It is wrapped now, and a refused probe reads as "no biometrics" so the PIN and
master-password form still appear. Every `BiometricPrompt` error code that means "the platform
refused" — lockout, timeout, no hardware, sensor unavailable, security update required — opens the
manual form in the same breath and names itself; ColourOS reports several of them with an empty
`errString`, so a message that only said "try again later" used to be a dead end
([`BiometricUtils.kt`](app/src/main/java/com/securevault/pro/presentation/components/BiometricUtils.kt)).

Passkey refusals were rewritten for a person instead of a log line: no "transport", no "relying
party" — each sentence says what happened, whether anything was saved, and what to do next
([`PasskeyAvailability.kt:267`](app/src/main/java/com/securevault/pro/security/passkey/PasskeyAvailability.kt#L267)).

### Authenticator codes

Base32 setup keys are accepted the way Aegis and Google Authenticator accept them: case-folded, with
every kind of space (including non-breaking and zero-width), hyphens, underscores and `=` padding
removed, and the two look-alike digits corrected (`0`→`O`, `1`→`I`). Those digits are not in the
RFC 4648 alphabet, so they can only be typing mistakes — a key that previously decoded to a string of
dashes now decodes correctly, and any secret that already validated decodes to exactly the same
bytes. SHA-256 and SHA-512 issuers are honoured. The code is computed once per time step rather than
once per second, and the 1 Hz ticker is cold: it stops a few seconds after the screen leaves
composition instead of regenerating every account's code behind another screen forever
([`TotpGenerator.kt`](app/src/main/java/com/securevault/pro/security/TotpGenerator.kt),
[`AuthViewModel.kt`](app/src/main/java/com/securevault/pro/presentation/screens/auth/AuthViewModel.kt)).

### Reports that name the place, not the state

A navigation-host stage can only say "unlocked" or "locked", so every death during ordinary use was
blamed on the same place. The wave added the missing half — `CrashLogger.noteScreen`, a per-screen
name that outranks the stage token, `placeWords` to put the stage into owner words ("inside the
vault", "the unlock screen, face or fingerprint prompt", "the app was still opening"), the dead run's
closing steps inside a trace-less report, and `summaryOf` so the recovery banner shows one sentence
rather than a build line — and then left `noteScreen` with no caller at all, which meant the report
still said "unlocked". The navigation host now publishes it from the current route, so every one of
the 28 screens names itself without any screen being touched individually
([`CrashLogger.kt:388`](app/src/main/java/com/securevault/pro/diagnostics/CrashLogger.kt#L388),
[`Routes.kt`](app/src/main/java/com/securevault/pro/presentation/navigation/Routes.kt),
[`VaultApp.kt:306`](app/src/main/java/com/securevault/pro/presentation/navigation/VaultApp.kt#L306)).

### Everyday surfaces

- **Files:** streaming seal with a real fraction (the sealed container is polled, so 0.99 means
  "still moving" and only a written index row means 100%), and the same sealing pass a plain save
  uses — `CryptoManager` is untouched.
- **Notes:** the markdown renderer was rebuilt (emphasis only where a marker actually opens a run,
  nested lists by depth, task checkboxes), and a pasted WhatsApp transcript is parsed off the UI
  thread behind a 250 ms debounce, so typing a long note does not re-tokenise on every keystroke.
- **Vault list / health:** hygiene scoring is scoped to credentials — an identity document or a
  vehicle record is no longer graded as a weak password — and the analyst reads l33t substitutions
  and date tails before it guesses.
- **Viewer:** a file it can decrypt but not draw (TIFF, RAW, archive) says which type it is instead
  of "unsupported".
- **Document camera:** live outline detection with a smoother, a deadband so the overlay stops
  shimmering, and guidance that names the real failure ("too dark", "fill the frame with one page").
- **Secure board:** the keystroke pulse is Android's own `KEYBOARD_TAP` through `performHapticFeedback`
  — the virtual-key tick the platform defines — and fires after the character is committed; the board
  trims its own height on short viewports so the bottom row stays reachable in landscape.

### What the wave did not reach

Phase 2 held, and it is proven by the files themselves rather than by intent. Every source modified
after the 3.6.9 artifact was published is listed, and none of them is in the encryption or sync
path: `RealtimeEngine.kt`, `AutoSyncManager.kt` and `VaultRepository.kt` were last written 2026-11-03,
`security/backup/*` on 2026-11-02, `security/CryptoManager` and the wallet key vault on 2026-11-05
before the wave opened. `CloudSyncManager.kt` carries only the 3.6.9 instrumentation. The v3.1.9
envelope format, its key plan, the legacy openers and the realtime wire contract are byte-for-byte
what 3.6.9 shipped.

### Verification

Full suite: **753 tests, 0 failures** on the release variant (`:app:testReleaseUnitTest`).

The wave's own work was repaired, not reverted: 12 files it left uncompilable — an invented
`HapticFeedbackType.VirtualKeyboard`, a `remember { @Composable … }` slot pattern Compose rejects, a
`BiometricPrompt.ERROR_BUSY` that does not exist in `androidx.biometric` 1.1.0, five crypto constants
and one `WhatsAppTranscript` helper called but never written, a nested `/**` inside an open KDoc
(Kotlin block comments nest, so the comment ran to end of file), and a camera sheet rewritten against
a live-detection API nobody had written. Copies of all twelve, exactly as the wave left them, are in
`wave_backup/`.

Two contract tests were updated to describe the shipped behaviour honestly rather than the old shape:
`AutoSyncTriggerCoverageTest` now accepts a file write that delegates to a function that signals
(`saveStream` → `saveStreamWithProgress`, which still emits the mutation), and
`PasskeyAvailabilityTest` checks that the transport refusal *names Android 14 and says nothing was
lost* instead of pinning one legacy sentence verbatim. The Phase-3 guarantee itself is unchanged and
still asserted end to end: every repository write path emits a mutation, `AutoSyncManager` debounces
500 ms into `syncNowSilently`, the realtime fast path keeps its own copy of the same signal, and no
data-entry screen offers a manual "Save & Sync".

One thing the wave added and never connected was found and finished here. `CrashLogger.noteScreen`
had zero call sites, so the new screen-name feature was dead code and reports would still have said
"unlocked": it is now published by the navigation host from the current route, with
`UncleanRunReportTest`'s new case guarding both halves — that the call exists, and that every route
constant in `Routes` has an owner-reading name in `screenNameFor`. That test caught a real gap on its
first run (`SCANNER` shared an arm with `SCAN_HUB` and would have reported a raw route); the two are
now named separately, because one is the QR code scanner and the other the scan hub.

`git diff --stat` over the wave's files: 40 files, +6,992 / −3,626.

On-device behaviour still cannot be proven from this machine: there is no emulator or debug bridge
here, so this release is verified by the suite, by a signed release build, and by reading the code.
The Blogger theme asset is unchanged, so `Blogger-Theme-3.7.0.xml` is byte-identical to
`Blogger-Theme-3.6.9.xml` and to the copy embedded in the APK.

## [3.6.9] — 2026-11-05 (versionCode 382)

### The report that came back saying nothing

The owner photographed the on-screen fault after a normal session and it read: *"The previous run
ended without closing cleanly after 'probe/the vault database' after 4904842 ms. No crash report was
left either."* Three separate defects made that sentence useless, and none of them was the app
crashing in the vault database.

- **A background step impersonated the screen.** The startup probes called `CrashLogger.noteStage`
  from an IO coroutine minutes after the UI had settled, so the record of *where the owner was*
  ended up holding the name of the last background probe to finish — always `probe/the vault
  database`, because that probe is last. Every trace-less death was therefore blamed on Room.
  `VaultApplication` now calls the new `CrashLogger.noteProbe`, which writes only to the trail.
  `noteStage` is left to the screens ([`CrashLogger.kt:259`](app/src/main/java/com/securevault/pro/diagnostics/CrashLogger.kt#L259),
  [`VaultApplication.kt:92`](app/src/main/java/com/securevault/pro/VaultApplication.kt#L92)).
- **A no-trace death wrote no artifact.** The trail was quoted only into a *stack* report, so a
  native or OEM kill — precisely the case the report claims to describe — published nothing to
  `Downloads`. The unclean-exit report now appends the dead run's own last 14 trail lines plus the
  absolute path of the full trail, and publishes itself through the same MediaStore route the crash
  handler uses ([`CrashLogger.kt:194`](app/src/main/java/com/securevault/pro/diagnostics/CrashLogger.kt#L194)).
- **Milliseconds are not a duration.** `4904842 ms` was quoted raw. `RunLength.spoken` turns a run
  length into `41 s`, `12 min 7 s` or `1 h 21 min`, because a death four seconds after launch and a
  death after ninety minutes of use are different faults with different remedies
  ([`RunLength.kt`](app/src/main/java/com/securevault/pro/diagnostics/RunLength.kt)).

### Work that can outlive the process now says so

The trail is the only witness when nothing catches the death, so the heavy operations that hold
memory, a socket or the display pipeline each leave a begin/end line: the multi-chain portfolio
scan (asset count and silent-chain count on the way out), a WhatsApp transcript import (bounded to
40 characters of the file name), every cloud operation (its `CloudOperation` name, then finished or
failed with the first 80 characters of the reason), and the document camera, which additionally
records `camera/document preview` as its *stage* — a CameraX/Skia death has no Java frame at all,
and the preview is the one surface where that is likely ([`CryptoBalanceService.kt:68`](app/src/main/java/com/securevault/pro/data/wallet/CryptoBalanceService.kt#L68),
[`WhatsAppImporter.kt:196`](app/src/main/java/com/securevault/pro/data/imports/WhatsAppImporter.kt#L196),
[`CloudSyncManager.kt:1296`](app/src/main/java/com/securevault/pro/data/remote/google/CloudSyncManager.kt#L1296),
[`DocumentCameraSheet.kt:219`](app/src/main/java/com/securevault/pro/presentation/screens/scanner/DocumentCameraSheet.kt#L219)).

### Verification

`UncleanRunReportTest` (5 tests) pins the attribution rules so a future refactor cannot quietly put
a probe back into the screen record: it asserts `VaultApplication` contains no `noteStage`, that
both probe helpers use `noteProbe`, that the report quotes `previousRunSteps()` and publishes to
Downloads, that the trail tail is read *before* this launch writes its own first line, and that each
watched operation names itself. Full suite: **736 tests, 0 failures** on the release variant
(`:app:testReleaseUnitTest`). No vault, crypto, keyring, sync or Google sign-in code changed, and the
watchdog thresholds, safe-render rules and recovery-mode arithmetic are untouched.

The Blogger theme asset is unchanged this release, so `Blogger-Theme-3.6.9.xml` is byte-identical to
`Blogger-Theme-3.6.8.xml` and to the copy embedded in the APK.

On-device behaviour still cannot be proven from this machine: there is no emulator or debug bridge
here, so the attribution fix is verified by the suite and by reading the code, and the real proof is
the next report the owner photographs.

## [3.6.8] — 2026-11-05 (versionCode 381)

Four asks in one release: the Keyboard settings that were missing, the crypto wallet's keys inside the
Google Drive backup, every major EVM chain instead of three, and a seed-phrase field that keeps up with
typing.

### Added — Settings → Keyboard
`presentation/screens/settings/SettingsScreen.kt` gained a Keyboard section, backed by three new
`AppLockManager` keys and surfaced through `LocalSecureKeyboardTyping`, `LocalSecureBoardHeight` and
`LocalSecureKeyboardHaptics` from the app root:

- **Use the app's own keyboard** — off hands every field back to the installed keyboard; on keeps typing
  inside the app. One switch for the whole app, not one per screen.
- **Keyboard size** — Normal / Large / Extra large. Only the keycaps scale; the gaps do not, and the
  grid is derived from the pad's own scaled caps, so letters, numbers and the PIN pad still resolve to
  one identical board height and the window never jumps between pages.
- **Vibrate on key press** — the pulse fires *after* the character commits, so turning it off can never
  swallow a key.

### Added — the wallet's keys travel in the backup, sealed
A restored phone used to hand back the wallet's addresses but never its keys, so the owner had to type
the seed phrase again. Now every wallet row carries a `keyBackup` in the backup document (JSON for
Drive, `<keyBackup>` in the XML), and restore opens it first:

- `data/wallet/WalletKeyBackup.kt` (new) — the document codec. A private key and a seed phrase are told
  apart the way a row always told them apart, a key is canonicalised to `0x` + 64 lower-case hexes, and
  `seal` refuses to write a blob that still shows the secret. Hand-written flat JSON, so no new
  dependency enters the backup path.
- `data/wallet/WalletKeyBackupKeySource.kt` (new) — seals through the **existing** device vault key and
  `CryptoManager.encryptWithDerivedKey` / `decryptWithDerivedKey`. PBKDF2 at 600,000 iterations and
  AES-256-GCM are untouched; no parameter changed and no second key system was invented. A device with
  no vault key simply carries no blob rather than guessing one.
- `WalletRepository.buildBackup()` seals either the key-store key or the row's own phrase;
  `restore()` derives the addresses from what it opened and **refuses a wallet whose address does not
  match** the row it arrived on. `keyBackup` is document-only: it is absent from Room's schema and from
  `RealtimeFieldSchema`, so realtime sync and the web clone still carry no key material.
- `LocalWipeService` now wipes `WalletKeyStore` too, so an erase leaves no sealed key behind.

### Added — five more chains, and the registry is the only place chains live
Arbitrum One (42161), Base (8453), Optimism (10), Avalanche C-Chain (43114) and Fantom (250) joined
Ethereum, BNB Chain and Polygon; Bitcoin and Tron keep their own address families. Eight EVM chains,
ten chains in all. Everything that used to spell chains out per screen now asks `ChainRegistry`:

- `crypto/ChainRegistry.kt` — five new descriptors, and `ALL` is the lineup the portfolio shows.
- `crypto/WalletDeriver.kt` — both derivations switched from a per-chain `when` to the address
  *family*, so a chain added later cannot silently derive a wrong address.
- `data/wallet/CryptoEndpoints.kt` — the dead `evmRpc` and `explorerTx` tables are gone (the registry
  is the only source of hosts); CoinGecko platform slugs and token-list sources added for the five.
- `data/wallet/CryptoBalanceService.kt` — price ids for AVAX / FTM / ARB / OP / WETH, each checked
  against the live endpoint.
- `data/wallet/TokenCatalog.kt` — per-chain published lists plus majors read from those lists, not from
  memory (Arbitrum USDC `0xaf88d0…5831`, Base WETH `0x4200…0006`, OP `0x4200…0042`, WAVAX `0xB31f…66c7`).
- `CryptoWalletScreen.kt` — the short name, fee symbol, address placeholder and chain accent all come
  from the registry; a chain's colour is its slot in the theme's own ramp, so nothing new can drift out
  of the theme. `Manage networks`, `Manage tokens`, the send sheet and the scanner pick the new chains
  up with no further edit.

Every host shipped was resolved first. `fantom-rpc.publicnode.com`, `1rpc.io/avax`, `rpc.ftm.tools` and
`api.avax.blockscout.com` did not answer, so Fantom ships the two nodes that do and no chain got a
history API it could not prove.

### Fixed — typing a recovery phrase is no longer behind a list scan
One keystroke of a 24-word phrase used to cost up to 24 × 2048 word comparisons, and the character only
appeared once that finished on a background thread:

- `presentation/screens/secureinput/RecoveryWordList.kt` — the list now also builds a word → position
  index once.
- `RecoverySeedLogic.validatePhrase` / `Bip39.validate` — take that lookup; the list overload stays for
  callers that have only a list. Identical answers, proven by test.
- `RecoverySeedViewModel` — `setCustomPhrase` / `setTypedPhrase` put the text on screen immediately and
  run the check behind it; a check that finishes late is discarded instead of overwriting a newer one.
  Accept and Submit act on the exact text on screen, never on an older draft.
- `RecoverySeedSection.kt` — the fixed 300 dp board allowance is gone: the host already lays the board
  out as a sibling that takes its own height, so the form now respects the Keyboard size setting
  instead of being pushed away from the keys.

### Not taken
WalletConnect / Web3j SDKs. A dApp-connection browser needs a WalletConnect Cloud project id — an
external account the owner has not issued — and Web3j would be a second signing stack next to one that
works. The requirement the brief actually measures (scan one address across every major chain and show
the whole portfolio) is met by the registry-driven scanner above, keyless.

### Verified
731 JVM tests, 0 failures (`:app:testReleaseUnitTest`), including two new files:
`RecoverySeedTypingTest` (index and list answer identically, a keystroke costs at most two lookups per
word) and `ChainRegistryCoversEveryChainTest` (every enum chain has one descriptor, chain ids unique,
https-only hosts, one derivation for all EVM chains, registry helpers resolve for every stored id).
Two `WalletKeyBackupTest` assertions written against the wrong rule were corrected — a spaced value
reads as a phrase, and a base64 blob is compared by what it opens to, not by how it is escaped.
Signed release built unshrunk, `apksigner verify` good on the same `CN=SecureVault` certificate as every
prior release, `aapt2` reports `versionCode='381' versionName='3.6.8'`, and the `blogger_theme.xml`
inside the APK is byte-identical to `release/Blogger-Theme-3.6.8.xml` (md5 `3b5f5ff8…`, blank `SEED`).
No emulator or debug bridge on this machine: the board size, the restored wallet and the ten-chain
portfolio still need a look on the phone.

## [3.6.7] — 2026-11-04 (versionCode 380)

Reported after comparing the app's Crypto Wallet against Trust Wallet: it showed only the native gas
token, the board lagged and dropped letters of a recovery phrase, and Settings had no way to say which
chains to read or which token to add.

### Added — a keyless multi-chain sub-token scanner
An imported wallet now reports every asset it holds on BNB Chain, Ethereum and Polygon, not just BNB /
ETH / POL. No indexer account, no paid key, no new dependency — public token lists, public nodes and
CoinGecko's contract endpoint:

- `data/wallet/WalletHttp.kt` (new): one HTTPS entry point for GET, POST and JSON-RPC **batches**
  (64 `eth_call`s in one round trip measured 211 ms). A host that refuses a batch size is retried at
  40 → 20 → 10 → 3 → 1 rather than losing the chain. `describeFailure` turns every transport error into
  the sentence the owner sees. TLS is exactly as strict as before.
- `data/wallet/TokenCatalog.kt` (new): the curated token lists for chains 1 / 56 / 137
  (Uniswap list + PancakeSwap-extended) cached in `filesDir/svp_token_catalog.json`, re-fetched every
  three days, with a built-in seed list so a first run offline still searches.
- `data/wallet/EvmTokenProbe.kt` (new): `fundedTokens` reads balances for candidate contracts and keeps
  the non-zero ones; `describe` asks a contract for its own symbol/name/decimals; `verifiedTokens`
  answers "which contracts in this saved portfolio did the chain itself confirm".
- `data/wallet/TokenScanPlanner.kt` (new, pure Kotlin so it is JVM-testable): the probe window is the
  owner's own hand-added contracts first, then what this wallet held last time, then the curated tail,
  capped at 260 — so a token that was always funded can never age out mid-scan, and chain 56's contracts
  never enter chain 1's window. Explorer rows only fill what the node did not answer.
- `data/wallet/CryptoBalanceService.kt` re-sweeps: native + BEP-20/ERC-20 rows per enabled chain, priced
  by contract through `simple/token_price/{ethereum|binance-smart-chain|polygon-pos}` (a ticker is not a
  price identity), and reports which chains were actually scanned.
- `data/wallet/WalletRepository.kt`: a saved balance row now carries `contract` and `decimals`. This is
  a JSON field inside an existing column — **no Room column changed, so no migration and no upgrade risk**
  to an existing vault. `merge()` still leaves a chain it did not scan exactly as it was.
- `data/wallet/CryptoTransferService.kt`: `knownTokens(network, wallet)` is the built-in spec list *plus*
  the contracts this wallet was verified to hold, so a discovered token is spendable. The lookalike gate
  is unchanged in strength: a symbol whose contract is not on that chain's exact list is still refused.
- `domain/model/CryptoModels.kt`: `TransferAsset.key` is now `networkId:contract`, so two contracts on one
  chain that both print "GOLD" cannot collapse into one picker entry.
- Tron stays address/TRC-20-balance only: TronGrid exposes no keyless contract metadata (`triggerconstantcontract`
  is 405), so its rows are named only when the owner added the contract or a built-in spec knows it.
- `app/src/test/java/com/securevault/pro/data/wallet/TokenScanPlannerTest.kt`: 8 JVM tests — owner
  contracts read first, a previously-held token never ages out, same contract spelled twice read once,
  no cross-chain leakage, the budget cuts the curated tail and never the owner's list, explorer rows only
  add what the node missed, only funded contracts become sendable knowledge, RPC hosts come from the registry.

### Fixed — the secure board is instant and cannot lose a keystroke
- `security/keyboard/SecureBoardEntry.kt` (new): the board-typed text/caret/layout state, lifted out of
  the password field so ordinary fields (seed word, wallet name, amount, search) run on the same engine —
  and the fix for dropped letters. A board-typed field is a loop (key writes → emit → caller's state
  echoes back one recomposition later). `lastEmitted` makes that echo ignored, so a slower echo can no
  longer write an older string over the character just typed, which is exactly how a fast phrase ended up
  a word short.
- `security/keyboard/SecureKeyboardPanel.kt`: keycap press feedback is **draw-only** (`mutableStateOf`
  read by the key's draw pass), so lighting the key costs no recomposition and no animation system — the
  frame that paints the press is the frame that shows the character. Rows are weighted into a grid that
  is the same height on every layer, so letters ↔ numbers ↔ pad does not move the window.
- `security/keyboard/SecurePasswordField.kt`: the dock publish is synchronous, identity-checked and keyed
  on the **layout**, never on the text — typing does not write dock state at all; `clearIfOwner` runs on dispose.
- `security/keyboard/SecureKeyboardEngine.kt`: new `NUMERIC` layer — a phone-dialer 3×3 pad (`pinRows`) for
  PINs and amounts, no letters page; number-page symbol rows re-cut to what a phone keyboard puts there,
  with their shifted counterparts, so a password's punctuation is two taps away.
- `security/keyboard/SecureKeyboardEngineTest.kt`: JVM tests pin the QWERTY rows and the bottom row.

### Added — Settings → Crypto scanning
- `data/wallet/WalletPreferences.kt` (new DataStore `svp_wallet_scan`): which chains are switched off, and
  the owner's hand-added tokens as JSON. `addCustomToken` accepts EVM contracts only (the shape is
  normalised through `EvmCrypto`, duplicates refused) because that is the only thing the scanner can read.
- `presentation/screens/settings/CryptoScanSheets.kt` (new): `CryptoScanViewModel` plus
  **Manage networks** (one row per chain with its native symbol and token standard, and a switch; the last
  chain on cannot be switched off) and **Manage tokens** (chain chips, contract field, *Read the chain and
  add it* — the name, symbol and decimals come from the contract itself, never from what someone typed —
  and a removable list of your tokens). Its own view model, so `SettingsViewModel` was not touched.
- `presentation/screens/settings/SettingsScreen.kt`: the new section with live counts
  (`3/5` chains, `n` tokens).
- `presentation/screens/crypto/CryptoWalletViewModel.kt` + `CryptoWalletScreen.kt`: the scan's own
  plain-language reason (e.g. every chain is switched off in Settings) now shows as a notice instead of an
  unexplained empty portfolio. A chain that simply failed keeps its own retry line.

### Changed — the web face reads the same portfolio
`tools/blogger-theme/secure_wallet_blogger_theme.xml`: `renderWalletItem` no longer caps at six rows — it
lists up to 24 funded rows per wallet, each carrying its chain name and the offline marker, matching what
the phone shows. Shipped `app/src/main/assets/blogger_theme.xml` and `release/Blogger-Theme-3.6.7.xml` are
byte-identical to each other and to the copy inside the APK
(md5 `3b5f5ff8649b1f3a4033818c447dae70`), `var SEED` still blank, 13/13 generator tests pass.

### Verification
`:app:testReleaseUnitTest` green (JVM suite incl. the two new files); `:app:assembleRelease` builds the
signed APK — `versionCode 380 / versionName 3.6.7`, `com.securevault.app`, signer SHA-256
`28fefa4663e44d109b61d8b7b48efd77aa56f8fd1d3fa4715bc0fd4a10328c1c` (same key as every release, so it
installs over 3.6.6 and the vault survives). R8 stays deliberately off. No emulator or adb here, so the
scanner against a real funded wallet and the board's feel on the OPPO are still owner-side checks.

## [3.6.6] — 2026-11-04 (versionCode 379)

Two defects reported together: *"why make so complicated — simple login with Google account"* and
*"why keyboard not opening on crypto vault"*.

### Fixed — the secure board opens inside a bottom sheet, so Crypto fields are not dead
`CryptoWalletScreen` puts every one of its text fields in a `ModalBottomSheet` (`AddWalletSheet` at
`presentation/screens/crypto/CryptoWalletScreen.kt:1206`, `SendSheet` at `:769`). A sheet composes in
its **own window**, while the app-level keyboard dock is mounted once in the activity window at
`presentation/navigation/VaultApp.kt:231`. The field published its layout to that dock, the shared
component then suppressed the inline board because a dock existed, and the strip painted behind the
sheet — so tapping a wallet name, seed phrase, private key or amount opened nothing at all.

- `SecureKeyboardDock` now records the view its strip draws in (`security/keyboard/SecurePasswordField.kt`).
- New `reachableSecureKeyboardDock()` returns that dock only when the calling composition shares its
  window; otherwise null, which is the existing instruction to draw the board under the field.
- `SecurePasswordField`, `rememberSecureBoard`, both `VaultTextField`/`VaultSearchField` gates and
  `RecoverySeedSection.boardIsOpen` read through it, so full-screen forms keep the docked board and
  every sheet or dialog gets a working inline one. No crypto, sync or crypto-key code touched.

### Changed — the web sign-in card is one button, not a console manual
- The waiting panel, *Check if it is ready*, *Show the Google button* and the load-time
  `getProjectConfig` probe are all **removed**, with their CSS. `idpRefusal`/`domainRefusal` became
  the two predicates `isIdpRefusal`/`isDomainRefusal`: the page still tells the two Google lists
  apart, it no longer recites either one's menu path at the reader.
- One way in at a time: Google's own button is drawn alone; the vault-window button appears only
  after that one is refused, and both disappear once `RT.popupFailed` proves neither route works
  from this address — the line then names Connect Drive and the backup file instead of a dead button.
- A Google-list refusal still empties the red paragraph and folds the server wording under *What the
  vault server actually said*. `Try again` clears the refusal memory and the popup flag, so a saved
  console entry takes effect without a reload.
- Generator test 13 rewritten: the panel ids and the probe must be **absent**, the classifiers must
  separate the two refusals, `showGoogleButton` must call `showPopupOffer(false)`, and both
  `liveFail` branches are pinned. 13/13.

701 unit tests and 13/13 generator tests pass. `Blogger-Theme-3.6.6.xml` (md5 `4bcc33bd…`) is
byte-identical to the theme bundled in `SecureWalletPro-3.6.6.apk`; same release key, `var SEED`
blank, no Kotlin crypto, sync or `google-services.json` change.

## [3.6.5] — 2026-11-04 (versionCode 378)

Theme-only, and the answer to "fix that error permanently, every time". The refusal text was now
short and correct, but the owner still saw a red error panel every single load, because Google has
not listed `securevaultpro.blogspot.com` yet. That state is not a fault on this page, so the page
stopped calling it one.

### Changed — a pending console step is never painted as an error
- New waiting panel (`svp-live-wait`, amber, high-contrast) titled *One step is left, and it is not
  on this page*, carrying the probe text plus **Check if it is ready** and **Show the Google button**.
  `showGoogleButton` fills it from the load-time domain probe, so the reader sees the pending step
  before pressing anything, and `liveFail` routes a recognised refusal into the same panel with
  `liveErr('')` — the red paragraph is reserved for failures Google cannot be talked out of.
- `checkReady()` re-reads Google's public `getProjectConfig` list on demand and flips the chip to
  `ready` with "This address is now on the list. Press Sign in with Google (vault window)." — no
  more pressing sign-in to find out whether the console entry landed.
- Once Google has refused the branded button, `hideGisButton(true)` takes it out of the way and
  leaves the single working route; *Show the Google button* restores it and forgets the memory.
- `showWait('')` clears with the error, on retry, and on a successful sign-in.
- Generator test extended: waiting branch must call `liveErr('')` before `showWait(warning)`, one id
  per new element, `checkReady` bound, ready-state flip, load-time probe drives the panel, GIS button
  hidden after its own refusal.
- Verified in a real browser for the first time: the shipped CSS + script were served locally and
  inspected — the panel renders between the two sign-in buttons, and pressing
  *Check if it is ready* moved the chip `waiting → ready` with the correct hint and no error text.
  (On `localhost` the address genuinely is authorized, so the ready path is the real one; the
  blogspot refusal path is proven by the live probe plus these assertions, not by a browser here.)

701 unit tests and 13/13 generator tests pass. `Blogger-Theme-3.6.5.xml` (md5 `2212bea4…`) is
byte-identical to the theme bundled in `SecureWalletPro-3.6.5.apk`; same release key, `var SEED`
blank, no Kotlin, crypto, sync or `google-services.json` change.

## [3.6.4] — 2026-11-04 (versionCode 377)

Theme-only. The owner's next screenshot showed the vault-window button rendering correctly but the
red panel running to ~15 lines — the page was explaining Google's console layout instead of telling
him which button to press. Also, that screenshot was an audience refusal, i.e. he had pressed the
branded Google button, which is the route that needs the second console entry.

### Changed — one instruction on screen, the manual folded away
- `idpRefusal` is now four short sentences: the branded button is shut until the vault project
  lists its client ID, press **Sign in with Google (vault window)** instead, the two values verbatim,
  and the warning not to register the button in the other project. The console walkthrough and the
  raw Firebase text moved into a collapsed *What the vault server actually said* block
  (`svp-live-raw`, filled by the new `liveErrRaw`, cleared with the error).
- `domainRefusal` trimmed to the same shape: what is missing, where to add it, which button after.
- The refusal kind is remembered (`svp.lastRefusal`, cleared on a successful sign-in), so a page
  that has already been refused by the branded button opens with a hint pointing at the vault-window
  route instead of steering the reader back to the failure.
- Wording corrected: the earlier text implied project `10151634026` was a foreign vault. Both faces
  use the same web API key, so the same Auth project mints the uid for the app and for the popup
  route — the mismatch is only that the web client ID is not on that project's Google-provider list.
- Generator test extended: lead under 620 characters, one raw block, folded only when translated,
  refusal remembered and forgotten.

701 unit tests and 13/13 generator tests pass. `Blogger-Theme-3.6.4.xml` (md5 `cfa18b40…`) is
byte-identical to the theme bundled in `SecureWalletPro-3.6.4.apk`; same release key, `var SEED`
blank, no Kotlin or crypto change.

## [3.6.3] — 2026-11-04 (versionCode 376)

Theme-only, driven by the owner's live screenshot: he pressed the new vault-window button and got
`auth/unauthorized-domain` — proof the route reaches the vault's own Auth project and is stopped
purely by the missing Authorized-domains entry. Two follow-on defects in my own 3.6.2 behaviour
were visible in that same screenshot.

### Fixed — the remedy survived its own error
- `liveFail` hid the vault-window button whenever the failure was not an audience refusal, so the
  domain error told the reader to press a button the error had just removed. It now keeps the
  offer for either refusal (`showPopupOffer(!!lead)`).
- `retryLive` cached the page-load domain probe, so an entry saved in the console stayed invisible
  until a full reload. `RT.domainProbe` is now cleared on retry, and `Try again` re-reads
  `getProjectConfig` — the pre-flight warning disappears by itself once the origin is listed.
- Generator test extended with both guards.

Live evidence taken from this machine while diagnosing: the public
`identitytoolkit/.../getProjectConfig?key=<theme key>` returned `authorizedDomains`
`[localhost, vault-35423.firebaseapp.com, vault-35423.web.app]` — blogspot absent, so the console
entry was still outstanding. 701 unit tests and 13/13 generator tests pass;
`Blogger-Theme-3.6.3.xml` (md5 `8c22021b…`) is byte-identical to the theme bundled in
`SecureWalletPro-3.6.3.apk`, same release key.

## [3.6.2] — 2026-11-04 (versionCode 375)

Web-only. The 3.6.1 page diagnosed the owner's sign-in refusal correctly but only offered the
working route *after* the failure, which left two console entries to make. This release makes the
Firebase-project route visible from the start, so one console entry — adding
`securevaultpro.blogspot.com` to project `vault-35423`'s Authorized domains — is enough to open
the vault.

### Changed — the alternate sign-in is now a first-class button
- `Sign in with Google (vault window)` ships in the unlock card markup next to the Google button
  and is shown as soon as the Google button is drawn (`showPopupOffer(true)` in `showGoogleButton`),
  not only after `liveFail`. It runs `popupSignIn()` → `signInWithPopup`, which uses the vault
  project's own OAuth client and is gated only by Authorized domains.
- The duplicate `svp-live-popup` button that used to sit inside `svp-live-actions` was removed;
  `Try again` / `Sign out` keep that row.
- `idpRefusal`, `domainRefusal` and the pre-flight `probeAuthorizedDomains` warning all name the
  vault-window button, so every refusal path points at something already on screen.
- 13th generator test now also asserts the markup: the row exists, each sign-in id appears exactly
  once, the button binds `popupSignIn`, and the offer is raised from `showGoogleButton`.

No Kotlin, crypto, sync or auth-config change: `google-services.json`, the SHA-1, the keyring
envelope and `var SEED` (blank) are untouched. Artifact `Blogger-Theme-3.6.2.xml` is byte-identical
to the asset bundled in `SecureWalletPro-3.6.2.apk` (md5 `14cf05bc…`), signed with the same release
key; 701 unit tests and 13/13 generator tests pass.

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
