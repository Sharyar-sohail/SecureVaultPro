# Changelog — Secure Wallet Pro

All notable changes to this app, its Blogger web companion and the build tooling.
Version numbers are the shipped `versionName` / `versionCode` in `app/build.gradle.kts`;
every release keeps the same signing key, so a new APK installs over an old one and the
vault survives the upgrade.

Release artifacts for each version: `SecureWalletPro-<version>.apk`,
`Blogger-Theme-<version>.xml` (byte-identical to `app/src/main/assets/blogger_theme.xml`,
with a blank `var SEED`), and `app-release.aab` for Play.

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
