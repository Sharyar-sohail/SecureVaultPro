# Theme expansion handoff — ten new themes

Date: 2026-10 line. Owner of this change: UI-themes agent. Do not re-run
`node tools/brand-icons/build.mjs` from a branch that still has the old THEMES
table — the catalog and the 195 drawables are generated together.

## What changed

| File | Change |
|---|---|
| `app/src/main/java/com/securevault/pro/presentation/theme/Palette.kt` | Ten new `VaultPalette` entries appended to `VaultPalettes.all` (now 15). No enum values added, no existing palette touched, `DEFAULT_ID` still `aqua`, no aliases deleted. A comment notes that the legacy alias `"slate" -> "dark"` is dead while the shipped `slate` theme exists (`find()` matches `all` first). |
| `tools/brand-icons/build.mjs` | Added the ten `THEMES` ramp entries; **removed `slate` from `RETIRED_THEMES`** (it would have deleted the freshly generated `ic_b_slate_*` boards — this was a live trap); wording updated to "fifteen". |
| `app/src/main/res/drawable/ic_b_*` | Regenerated: 195 drawables (15 themes x 13 glyphs). Old five byte-identical (same inputs, deterministic build; pinned test values re-verified). |
| `app/src/main/java/com/securevault/pro/presentation/components/BrandIconCatalog.kt` | Regenerated from the new THEMES table. |
| `app/src/test/java/com/securevault/pro/presentation/components/BrandIconCatalogTest.kt` | `themeIds` now the 15 ids; `slate` dropped from `retiredIds` (it ships again); four new pins sampled from the corrected catalog. |
| `app/src/main/java/com/securevault/pro/presentation/screens/settings/SettingsScreen.kt` | Only the switcher subtitle ("Five themes…" → "Fifteen themes…"). `VaultPalettes.all.chunked(2)` inside the scrolling Column already renders 15 cards (8 rows, odd card padded) — no layout change needed. |
| `app/src/main/java/com/securevault/pro/VaultApplication.kt` | Startup preload bounded (see below). |
| `app/src/test/java/com/securevault/pro/presentation/theme/NewThemesGuardTest.kt` | New lineup guard. |

## The ten themes (signature tokens)

| id | design / backdrop / layout | dock · header · indicator · frost | corners (card/btn/chip) | surface FX | pie grammar | isLight |
|---|---|---|---|---|---|---|
| noir | AGENTIC · CALM · AGENTIC | MINIMAL_BAR · TAB_UPPER · UNDERLINE · 0.98 | 16/12/6 | matte graphite, no gradient, no glow, no blur, caps labels | WEDGE + 1dp separator, SQUARE legend | false |
| mint | BENTO · PULSE_MESH · CLASSIC | SOFT_FLOATING · LARGE_COMFORT · PILL · 0.90 | 24/20/12 | soft teal-tinted cards, shadow 6, blur 18, sheen off | RING gap 6, gradient, DOT legend | true |
| sunset | GENERATIVE · AURORA_MESH · CLASSIC | PILL_FROSTED · INLINE_BOLD · PILL · 0.62 | 28/22/14 | umber glass, gradient cards + sheen, mesh 1.0 | WEDGE gradient, DIAMOND legend, lift | false |
| arctic | LIQUID_GLASS · PULSE_MESH · CLASSIC | PILL_FROSTED · CENTERED · PILL · 0.55 | 32/26/18 | near-white ice, backdropBlur 48 (heaviest), sheen | RING, gradient, RING legend, lift | true |
| voltra | CYBERPUNK · NEON_MESH · ADAPTIVE | SOFT_FLOATING · TAB_UPPER · DOT · 0.90 | 6/4/2 | near-black, hardOutline true, glow true, mono tags | WEDGE + 2dp separator, SQUARE legend | false |
| terra | CLAYMORPHISM · SOFT_CLAY · GENERATIVE | COMFORT_PANEL · LARGE_COMFORT · PILL · 0.97 | 34/30/20 | clay cream, shadow 20, embossed, pressDepth 2 | STACKED_RING, DOT legend | true |
| royal | BENTO · AURORA_MESH · CLASSIC | PILL_FROSTED · CENTERED · PILL · 0.66 | 24/18/12 | violet velvet, gold hairline (`glassBorder 0x4DD9B878`), gradient cards | RING gradient, BAR legend, lift | false |
| slate | NEO_BRUTALIST · FLAT_GRID · CLASSIC | FLAT_PLATE · INLINE_BOLD · UNDERLINE · 1.00 | 6/6/4 | paper, 2dp hard outlines, zero shadow, pressDepth 3, caps + mono | RING gap 0, SQUARE legend (spec) | true |
| sakura | CLAYMORPHISM · PULSE_MESH · CLASSIC | SOFT_FLOATING · LARGE_COMFORT · DOT · 0.88 | 30/26/22 | pink clay, shadow 16, embossed, blur 12 | STACKED_RING gradient, DOT legend | true |
| carbon | BENTO · TEXTURED · BENTO | COMFORT_PANEL · TAB_UPPER · PILL · 0.98 | 14/10/6 | graphite, flat 1.5dp-edged tiles, mono tags, bold labels | WEDGE gap 1, BAR legend | false |

Why they read as different products: eight of the nine `ThemeDesign` grammars are in
play across the 15 (only NEO_SKEUOMORPHIC stays unused); every `DockStyle` and every
`HeaderStyle` value ships; backdrops span all 8 except one; the dock silhouette,
frost alpha, height, indicator, corner scale, shadow/glow/sheen stack and chart
grammar each move together. None of the ten is a recolor of the original five — the
guard test fails if any two palettes share the `abyssDark + cyan + indigo` triple.

## Startup preload (195 drawables)

The old probe walked every theme x 13 glyphs on the main `onCreate` path. It only
resolved integer resource ids and `Color` values (no bitmap decode), but at 15 themes
it tripled the eager static-init work before the first frame. Now `onCreate` verifies
only the boot palette's board (the one the first frame can draw), and the remaining
14 boards are verified inside the existing `backgroundScope` launch via
`probeDeferred("the theme boards")`. A broken board still ends up in the crash-logger
recovery path instead of the UI process. Drawables still decode lazily per composition.

## Verification without a device

Gradle was not run. I replicated every numeric assertion of `ThemeTokensTest`
(WCAG luminance/ratio/composite maths, the `iconPlateColor` per-design plate rule,
the ≤1.6 tint cap, `inkOn`, `textStrong` native AA, `ensure` self-correction) in a
throwaway Node script and ran it against the five shipped palettes first — all five
pass exactly as in CI, which validates the replica — then against the ten new ones.
All ten pass, including the tightest margins (sunset GENERATIVE plate ≈1.5, royal
BENTO ≈1.3). The build.mjs AA plate guard ran green: neon/pale ramps (voltra lime,
sunset coral, noir gold, slate yellow) were auto-darkened to plate-solid tones while
the *palette* keeps the bright roles for surfaces and text — same split iOS already
ships (its `#FF9F0A` bakes as `#A66706` plates).

`NewThemesGuardTest` asserts: exactly 15 unique ids; every DockStyle and HeaderStyle
value used; ≥6 distinct dock looks and ≥5 distinct header looks once indicator/frost/
height/labels are counted (the enums have only 5 docks and 4 headers, so a literal
"6 distinct DockStyle" is impossible — the test comments say why); unique
`abyssDark+cyan+indigo` triples; all ten new ids resolve through `find()` (also
case/whitespace-insensitive); the light/dark split matches the brief; no dark theme at
#000; design/backdrop/pie spread floors.

## For the other agents / the owner

1. **Crypto agent**: `royal` (Royal Violet) is the lineup slot for the Bitget
   "Crypto Violet" panel. I did not touch `presentation/screens/crypto/`. If that
   screen special-cases theme ids, add `"royal"` there.
2. **HomeScreen/Common agents**: `ThemeDesign`/`VaultLayout` were not extended, but
   three new layouts are now live (`AGENTIC`, `ADAPTIVE`, `GENERATIVE`, `BENTO`
   values on palettes that previously never used them). Confirm the `when (palette.layout)`
   arms in `HomeScreen.kt` are all actually exercised — I could not edit that file.
3. Anyone re-running `build.mjs`: keep `slate` out of `RETIRED_THEMES` or you wipe
   its board.
4. Please run on the OPPO: heavy `backdropBlur` (arctic 48dp, sunset 26dp) and terra's
   20dp shadows are the plausible jank spots on ColorOS; voltra's glow + NEON_MESH the
   plausible battery spot. Nothing here exceeds what ios/aqua already request.

## Risks I could not close without a device

- Actual blur cost / renderScript fallback on ColorOS for arctic and sunset.
- The settings switcher is now 8 rows of cards; scroll length was never tested at 15.
- Dock label fit at `navLabelSize 9.sp` (noir/voltra) on narrow OPPO screens with
  `MINIMAL_BAR`/caps labels.
- Whether the generated olive-sunk voltra plates still feel "neon" next to the bright
  surface accents — colours are AA-safe by construction, taste needs eyes.
