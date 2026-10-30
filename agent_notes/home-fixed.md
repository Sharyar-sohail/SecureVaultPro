# Home layout — zero-scroll dashboard fix (HomeScreen.kt)

Scope respected: only `app/src/main/java/com/securevault/pro/presentation/screens/home/HomeScreen.kt`
was rewritten. `HomeBoard.kt` and `HomeViewModel.kt` are **unchanged**; no shared kit, theme,
navigation or DI file was touched. No Gradle was run (manager owns the build).

## What was the defect

The dashboard root was a `LazyColumn` (`HomeScreen.kt:169`) with a `navHeight + 48.dp` bottom
content pad, so the whole home screen scrolled and ran off the screen on a short phone
(AUDIT-2026-10-30.md §7 confirms). The `verticalScroll` at old line 1226 was inside
`QuickAddSheet` — a sheet body, which is a sanctioned scroll — and stayed.

## What the rebuild does

One fixed, **non-scrolling** `Column` inside `NeonBackground { BoxWithConstraints { … } }`:

- The board is a strict **4-column × 3-row grid of 12 plates**, built from the same pure
  `boardGrid(VaultLayout.CLASSIC, 12)` / `boardRowRemainder` geometry in `theme/Layout.kt`
  (`BrandIconCatalogTest` still pins `board.size == 12`). Rows split the leftover pane height
  evenly with weights — the grid structurally owns every pixel the upper blocks don't, so the
  grid always gets its rows. Tiles are the existing package-internal `BoardTile(kind = PLATE)`
  with a screen-local `StrictBoardRhythm` (plate min 88.dp, gaps 6.dp), so every silhouette,
  press spring, badge band and AA-guarded ink from HomeBoard survives; all 15 palettes restyle
  through `tileShape`/palette tokens unchanged. `HomeBoardGuardTest` is referenced in comments
  but **does not exist in `app/src/test`** — see "Findings" below.
- Height tiers are decided by measurement (`BoxWithConstraints.maxHeight`), and blocks are
  **dropped or compacted**, never shrunk below ~11 sp:
  | pane height | what appears |
  | --- | --- |
  | < 428 dp | hero + search + 4×3 board only (composition hidden; grid still un-clipped) |
  | ≥ 540 dp | + compact composition strip: 58 dp dial, 2+2+1 count chips (11 sp `labelSmall`), expiry chip, running total, sync micro-animation |
  | ≥ 660 dp | full Vault composition card (126 dp dial + tappable legend + LIVE chip), "All modules" header with animated grammar chip |
  | ≥ 780 dp | + security stamp row and sync timestamp label |
  | ≥ 980 dp | + travel-mode card + expiry reminders card (top 3) |
  | ≥ 1120 dp | + favorites preview (3 rows) — tablet |
- **Sync micro-animation** (arrow-to-cloud / pulsing cloud): `SyncOrb` — new composable in
  HomeScreen driven by the existing `HomeUiState.syncRunning/syncPending/syncLastAt/syncError`.
  Running: `arrowUpward` rises (`graphicsLayer` translation + fade) into `Glyph.cloud`;
  pending: pulsing cloud (infinite `graphicsLayer` scale/alpha); done: `cloudDone` in
  `VaultColor.credit`; error: `cloudOff` in `VaultColor.warning`. It did **not** previously
  exist on Home despite being an established requirement; it is now built from real VM state.
- Search stays a sanctioned data-list scroll: when `query.isNotBlank()` the dashboard swaps to
  a weighted results pane (`LazyColumn` + `animateItem`), the board/composition are simply not
  drawn, and the dashboard chrome behind it never scrolls.

## Smallest viewport I believe the layout survives

**~428 dp of pane** for "no scroll, no clip, grid intact" (hero ≈44 + search ≈56 + board 3×88
+ gaps/pads ≈72 = ~404, plus tolerance). The named target — a 360×640 dp phone — leaves
≈544 dp of pane after a ~24 dp status bar and the ~72 dp dock reserved by the app-level
Scaffold in `VaultApp`, so at 360×640 the tier ladder lands on "hero + search + compact
composition strip + strict 4×3 board with ~100 dp rows", nothing dropped. At the 900 dp
tablet width (and ≥1120 dp pane) everything, including favorites, is on screen with room to
spare; the full card's legend sits beside the dial via the unchanged `chartLegendSitsBeside`
split.

## Blocks removed as duplicates / clutter

1. **Five per-layout hero variants** (`HomeHero`'s CLASSIC/BENTO/AGENTIC/ADAPTIVE/GENERATIVE
   compositions: the 84 dp clay halo, the bento control-rail cell, the adaptive hour-rule bar,
   the agentic intent rail). Replaced by one compact token-styled `HomeHeaderRow`. The agentic
   intent rail (Passwords/2FA/Cards/Scan/Notes pills) duplicated the board tiles underneath it
   one-to-one — the clearest duplicate on the screen.
2. **FAB quick-add** — its 72 dp corner reservation pushed the board off short screens and the
   overlapping placement clipped the bottom-right tile. Quick add is now the `Glyph.add` button
   in the header control cluster; the sheet is unchanged (its close button was mislabeled
   `Glyph.add`; it is now `Glyph.close`).
3. **Inner `Scaffold` + `navHeight + 48` bottom pad** — the dock is already reserved by the
   app-level `Scaffold` in `VaultApp.kt:246-275`; the dashboard was reserving it a second time.
4. **Favorites list in the default tier** — a copy of vault rows; kept only as a 3-row preview
   at the tablet tier (was an up-to-4-row list that always pushed the board away).
5. **Travel-mode full card in the default tier** — one sentence; now a `TRAVEL` chip beside the
   greeting, the card only at the rich tier.
6. **Expiry reminders card in the default tier** — duplicates vault content; compacted to a
   `n DUE` chip (strip/header) and the real card only at the rich tier; single-line pointer
   above the board in the mid tier. Exactly one representation of the expiry fact at every tier.
7. **Duplicated sealed-count text** — the hero "N items sealed", the card subtitle and the dial
   caption all said the same number; now the count appears once per tier (header line only from
   the 660 dp tier, where the caption lives; the compact strip shows the total only in the chip
   line).
8. **Decorative plates with no information** — the adaptive hour-progress rule (says nothing the
   ordering chip doesn't), the clay halo, and the second redundant `qrCodeScanner` glyph at the
   tail of the sheet's scan row.

## Kept, per the standing requirements

4×3 twelve-module grid with count badges · Vault composition pie chart + tappable legend +
counts · sync micro-animation (newly real, driven by `HomeUiState`) · grammar chip naming the
active layout · theme-switch crossfade on the board (`AnimatedContent` +
`themeSwitchEnter/Exit`) · reveal entrances · AA guards (`VaultContrast.ensure` on selected-row
ink, shield plate, chip ink) · quick-add sheet · lock/theme/settings escape hatches always
visible. All colours/typography/shapes go through `VaultColor`/`VaultType`/palette tokens;
zero hex/`Color(0xFF…)` literals, zero Material icons (only allowed `Glyph.*`).

## Need from shared files

**Nothing required.** One optional nicety for the kit owner, not needed to compile:
a public `SyncOrb` could eventually replace the one-off in Home if the sync screens want the
same micro-animation. `VaultModuleBoard` in `HomeBoard.kt` is now unused by Home (its
variable-span boards can't honor a fixed 4×3 + zero-scroll contract at 640 dp); it still
compiles and its pure geometry is still exercised via `boardGrid`. I deliberately left the
file untouched rather than delete it — manager's call whether to retire it.

## Findings in other people's code (reported, not fixed)

- `HomeBoard.kt:78` and the old HomeScreen comments pin `HomeBoardGuardTest`; **no such test
  exists** anywhere under `app/src/test` (only `BrandIconCatalogTest` pins the 12-module board).
  The claimed 4×3 guard was therefore documentation, not a test.
- The audit's "two further `verticalScroll` usages" on Home: one was the sheet (legitimate),
  the other existed only through the root `LazyColumn` now removed.
- `VaultApp` already pads the NavHost with the bottom-bar height; any screen that also pads
  `palette.navHeight` double-counts the dock (Home did; other screens should be checked by
  their owners).

## Verification status (per rules: no Gradle run by me)

Every API called was read in the live tree first: `BoardTile`/`BoardItem` (internal, same
package, `HomeBoard.kt`), `BoardRhythm`/`boardGrid`/`boardRowRemainder`/`TileKind`/`VaultLayout`
(`theme/Layout.kt`), `GlassCard`/`GlassIconButton`/`TagChip`/`SectionHeader`/`VaultSearchField`/
`selectedBed`/`iconPlateShape`/`revealEntry`/`greetingForHour`/`MonogramBadge`/`strengthLabel`/
`securityColor`/`formatShortDate` (`components/Common.kt`), `VaultPieChart`/`PieSlice`/
`PieLegendMarker`/`chartLegendSitsBeside` (`components/DashboardChart.kt`),
`BrandIconCatalog`/`VaultModule`/`BrandModuleIcon` (`components/BrandIconCatalog.kt`),
`VaultColor` roles (`theme/Role.kt`), `VaultType` (`theme/Type.kt`), palette fields
(`theme/Palette.kt`), `HomeUiState` (`HomeViewModel.kt`), and the dock reservation
(`navigation/VaultApp.kt:246-275`). Ready for the manager's
`:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` cycle.
