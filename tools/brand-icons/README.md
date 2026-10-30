# Brand icon pack — fifteen construction grammars

Generated Android VectorDrawables for the fifteen shipped themes. Glyph art is
[Phosphor Icons](https://phosphoricons.com) v2.1.1 (MIT), vendored under `vendor/`; the
*grammar* — silhouette, depth layers, glyph placement, and for the two raster families the
cell rebuild itself — is authored in `icon-grammars.mjs`. Run `node build.mjs` to
regenerate; run `node --test` to re-prove the guard contract without writing assets.

Every theme draws its own family. The lineup guard test compares the per-module geometry
fingerprint emitted into `BrandIconCatalog.geometry()`, so two themes that differ only in
colour fail the build.

| Theme id | Name | Grammar | Construction | Worst guarded white-ink ratio |
|---|---|---|---|---|
| `ios` | iOS Glass | `GLASS_SILVERWARE` | frosted pane: superellipse plate, two-stop specular strip, inset rim ring | 4.60:1 |
| `oneui` | Samsung One UI | `COMFORT_SHOULDER` | asymmetric shoulder square, wide gutter, base band, no gloss, no rim | 4.60:1 |
| `arctic` | Neumorphic Soft | `NEUMORPH_EXTRUDE` | stadium plate extruded by light+dark crescents behind it, recessed inner seat | 5.25:1 |
| `voltra` | Cyberpunk Neon | `NEON_CHAMFER` | opposite-corner chamfer, hollow glow ring behind, bright edge ticks | 4.61:1 |
| `terra` | Claymorphism | `CLAY_PUFF` | puffed squircle, crown bloom, foot shade crescent, thick soft rim | 4.60:1 |
| `slate` | Retro 8-Bit | `PIXEL_SPRITE` | notched hard square, glyph rebuilt on a 16-cell grid, two-tone limited palette | 4.61:1 |
| `royal` | Liquid Metal | `CHROME_LENS` | lens silhouette, five crossed chrome bands, diagonal specular blade | 4.60:1 |
| `light` | Minimal Line | `MONOLINE_RING` | no plate: single-weight hairline ring plus inner rule, thin-weight outline glyph in ink | 4.60:1 |
| `sunset` | Gradient Mesh | `MESH_BLOB` | asymmetric blob filled by a three-stop mesh, two translucent hue lobes | 9.79:1 |
| `sakura` | Origami | `ORIGAMI_FOLD` | folded-sheet pentagon in three hard facets with two crease strokes | 4.60:1 |
| `carbon` | Bento Grid | `BENTO_SUBBOX` | rounded box partitioned internally, glyph parked in the largest sub-box | 4.61:1 |
| `noir` | Dark Academia | `ENGRAVED_PLATE` | sharp plate, double hairline frame, diagonal hatch, letterpress glyph | 10.38:1 |
| `mint` | Vibrant Duotone | `DUOTONE_SPLIT` | plate split on the diagonal, glyph rebuilt as two-tone blocks plus overlap band | 4.61:1 |
| `aqua` | Aurora | `WAVE_FIELD` | vertical gradient field plate with three crossed wave bands | 4.60:1 |
| `dark` | Emulate OS Deep Dark | `OLED_NOTCH` | sharp plate with one corner notched, bright rule under the foot, tight inset | 4.62:1 |

Contrast: the guard does not check the bare plate. Each grammar declares the overlays that can
land under the glyph, the guard partitions the glyph's guard box by those overlay rects and
composites every stack that really covers a cell, and the ramp is darkened by the smallest
scale that clears WCAG AA (≥4.5:1, generated to ≥4.6:1 for 8-bit
rounding) at the worst cell. A layer marked `decor` that overlaps the guard box, or a
`frame` ring that does not sit clear of it, fails the build — so a mislabelled layer cannot
dodge the check.

Attribution:
- Phosphor Icons — MIT, Copyright (c) 2018 Windo Avalar (`LICENSE.phosphor.txt`).
