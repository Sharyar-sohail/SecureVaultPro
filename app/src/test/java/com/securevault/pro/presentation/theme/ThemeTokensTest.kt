package com.securevault.pro.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.components.iconPlateColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise of the token split, pinned: for every shipped theme, every role that carries
 * text must clear WCAG AA on the surface it is drawn on, and every pre-split name must still
 * resolve. Runs on the JVM because colour is plain maths.
 */
class ThemeTokensTest {

    private inline fun <T> withPalette(palette: VaultPalette, block: () -> T): T {
        VaultPaletteController.apply(palette)
        return block()
    }

    @Test
    fun `contrast maths matches the published WCAG pairs`() {
        assertEquals(21.0, VaultContrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, VaultContrast.ratio(Color.White, Color.White), 0.001)
        // #767676 is the canonical 4.54:1 grey; #777777 is the first that fails.
        assertTrue(VaultContrast.ratio(Color(0xFF767676), Color.White) >= 4.5)
        assertTrue(VaultContrast.ratio(Color(0xFF777777), Color.White) < 4.5)
    }

    @Test
    fun `ensure only darkens as far as the ratio needs`() {
        val paleGreen = Color(0xFF30D158)
        val fixed = VaultContrast.ensure(paleGreen, Color.White)
        assertTrue(VaultContrast.ratio(fixed, Color.White) >= VaultContrast.AA_TEXT)
        // Still recognisably green: the hue must not collapse to grey or black.
        assertTrue(fixed.green > fixed.red && fixed.green > fixed.blue)
        assertEquals(Color(0xFF0B0C10), VaultContrast.ensure(Color(0xFF0B0C10), Color.White))
    }

    @Test
    fun `every text role clears AA on the surface it draws on`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            val surface = VaultColor.surface
            val cardRoles = mapOf(
                "textStrong" to VaultColor.textStrong,
                "textBody" to VaultColor.textBody,
                "textFaint" to VaultColor.textFaint,
                "accentInk" to VaultColor.accentInk,
                "secondaryInk" to VaultColor.secondaryInk,
                "decorativeInk" to VaultColor.decorativeInk,
                "creditInk" to VaultColor.creditInk,
                "warningInk" to VaultColor.warningInk,
                "debitInk" to VaultColor.debitInk
            )
            cardRoles.forEach { (name, ink) ->
                assertTrue(
                    "${palette.id}: $name ${hex(ink)} on ${hex(surface)} is " +
                        "%.2f:1".format(VaultContrast.ratio(ink, surface)),
                    VaultContrast.ratio(ink, surface) >= VaultContrast.AA_TEXT
                )
            }
            assertTrue(
                "${palette.id}: textStrong on background",
                VaultContrast.ratio(VaultColor.textStrong, VaultColor.background) >= VaultContrast.AA_TEXT
            )
            assertTrue(
                "${palette.id}: faint text on the elevated tile",
                VaultContrast.ratio(VaultColor.textFaint, VaultColor.elevated) >= VaultContrast.AA_TEXT
            )
        }
    }

    @Test
    fun `ink on a solid accent fill clears AA`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            listOf(
                VaultColor.onAccent to VaultColor.accent,
                VaultColor.onSecondary to VaultColor.secondary,
                VaultColor.onWarning to VaultColor.warning
            ).forEach { (ink, fill) ->
                assertTrue(
                    "${palette.id}: ${hex(ink)} on ${hex(fill)}",
                    VaultContrast.ratio(ink, fill) >= VaultContrast.AA_TEXT
                )
            }
        }
    }

    @Test
    fun `a hairline is at least visible on its card`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            // Decorative rules need AA-large, not body-text contrast.
            assertTrue(
                "${palette.id}: hairline ${hex(VaultColor.hairlineStrong)}",
                VaultContrast.ratio(VaultColor.hairlineStrong, VaultColor.surface) >= VaultContrast.AA_LARGE
            )
        }
    }

    @Test
    fun `the M3 scheme carries the same guarantees`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            val scheme = VaultColor.colorScheme(palette)
            val card = palette.abyssSurface
            assertTrue("${palette.id}: onPrimary", VaultContrast.ratio(scheme.onPrimary, scheme.primary) >= VaultContrast.AA_TEXT)
            assertTrue(
                "${palette.id}: onSurfaceVariant",
                VaultContrast.ratio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= VaultContrast.AA_TEXT
            )
            assertTrue(
                "${palette.id}: onBackground",
                VaultContrast.ratio(scheme.onBackground, scheme.background) >= VaultContrast.AA_TEXT
            )
            assertEquals("${palette.id}: surface", card, scheme.surface)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `pre-split names still resolve to their semantic roles`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            assertEquals(VaultColor.textStrong, TextPrimary)
            assertEquals(VaultColor.textBody, TextSecondary)
            assertEquals(VaultColor.textFaint, TextMuted)
            assertEquals(VaultColor.background, AbyssDark)
            assertEquals(VaultColor.surface, AbyssSurface)
            assertEquals(VaultColor.elevated, AbyssElevated)
            assertEquals(VaultColor.accent, NeonCyan)
            assertEquals(VaultColor.secondary, NeonIndigo)
            assertEquals(VaultColor.warning, NeonAmber)
            assertEquals(VaultColor.credit, NeonMint)
            assertEquals(VaultColor.debit, NeonCoral)
            assertEquals(VaultColor.decorative, NeonLavender)
            assertEquals(VaultColor.hairline, GlassBorder)
            assertEquals(VaultColor.dangerFill, DangerDim)
            assertEquals(VaultColor.onAccent, OnAccent)
            assertEquals(6, AccentPalette.size)
            assertEquals(palette.moduleLabelSize, ModuleLabelSize)
        }
    }

    @Test
    fun `roles move when the theme moves`() {
        val solstice = VaultPalettes.find("solstice")
        val aqua = VaultPalettes.find("aqua")
        val solsticeAccent = withPalette(solstice) { VaultColor.accent }
        val aquaAccent = withPalette(aqua) { VaultColor.accent }
        assertNotEquals(solsticeAccent, aquaAccent)
    }

    @Test
    fun `radius ramp follows the palette instead of a magic number`() {
        val tight = VaultPalettes.find("lime")
        val airy = VaultPalettes.find("solstice")
        withPalette(tight) {
            assertTrue(VaultRadius.xs < VaultRadius.sm)
            assertTrue(VaultRadius.sm < VaultRadius.md)
            assertTrue(VaultRadius.md < VaultRadius.lg)
            assertEquals(tight.chipCorner, VaultRadius.sm)
            assertEquals(tight.cardCorner, VaultRadius.xl)
        }
        withPalette(airy) {
            assertTrue(VaultRadius.sm < VaultRadius.md)
            assertTrue(VaultRadius.xl > tight.cardCorner)
        }
    }

    @Test
    fun `spacing rhythm reads the palette gutters`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            assertEquals(palette.screenGutter, VaultSpace.gutter)
            assertEquals(palette.gridGutter, VaultSpace.gap)
            assertEquals(palette.navHeight, VaultSpace.dockClear)
        }
    }

    /**
     * Fifteen themes, fifteen compositions.
     *
     * The earlier promise was the opposite — one board for every theme, colours only — and the
     * owner rejected it on sight: a theme you can only tell apart by its accent is not a theme.
     * What must stay uniform is the glyph itself (size, plate radius, label size), because one
     * icon family across the app is what keeps the modules recognisable. What must differ is the
     * board: columns, spans, cell kinds, spacing rhythm, dock cut and corner grammar.
     */
    @Test
    fun `every theme composes its own board`() {
        val layouts = VaultPalettes.all.map { it.layout }
        assertEquals("fifteen themes, fifteen layouts", VaultPalettes.all.size, layouts.distinct().size)

        val signatures = layouts.map { layout ->
            val rows = boardGrid(layout, 12)
            listOf(
                boardColumns(layout),
                rows.joinToString("/") { row ->
                    row.joinToString("+") { it.span.toString() + it.kind.name.first() }
                },
                BoardRhythm.of(layout).plateMinHeight.value,
                dockSpec(layout, DockStyle.MINIMAL_BAR, navFloating = false, navCorner = 12.dp)
                    .itemGap.value
            ).joinToString("|")
        }
        assertEquals("two layouts resolve to the same board", signatures.size, signatures.distinct().size)

        // Twelve modules must tile into whole rows, or the board draws a ragged last line.
        for (layout in layouts) {
            val columns = boardColumns(layout)
            val rows = boardGrid(layout, 12)
            assertTrue("$layout: empty board", rows.isNotEmpty())
            for (row in rows) {
                assertTrue("$layout: a span overflowed its row", row.sumOf { it.span } <= columns)
            }
            assertEquals(
                "$layout: rhythm columns disagree with the board",
                columns,
                BoardRhythm.of(layout).columns
            )
        }

        // The glyph tokens themselves stay put: one icon family, legible on every palette.
        val first = VaultPalettes.all.first()
        for (palette in VaultPalettes.all) {
            assertEquals("${palette.id}: icon size", first.moduleIconSize, palette.moduleIconSize)
            assertEquals("${palette.id}: icon plate radius", first.iconTileCorner, palette.iconTileCorner)
            assertEquals("${palette.id}: label size", first.moduleLabelSize, palette.moduleLabelSize)
        }
    }

    @Test
    fun `retired theme ids still land on a shipped palette`() {
        mapOf(
            "obsidian" to "dark", "cyberpunk" to "dark", "emui" to "dark",
            "coloros" to "aqua", "glass" to "aqua", "aurora" to "aqua",
            "apple" to "solstice", "funtouch" to "solstice", "hyperos" to "solstice",
            "samsung" to "oneui", "lumen_white" to "lime", "" to "aqua"
        ).forEach { (legacy, expected) ->
            assertEquals(expected, VaultPalettes.find(legacy).id)
        }
    }

    /**
     * The icon tile's own promise, measured the way the component measures it.
     *
     * `ThemedIconTile` paints a translucent accent plate and used to draw the raw brand accent as
     * the glyph: on a white card that is a 2.7:1 pair, which reads as a smudge rather than a symbol.
     * The tile now composites the plate it really paints and passes the ink through [VaultContrast.ensure],
     * so every glyph on every shipped theme clears AA — and the plate stays a tint, never a solid
     * block, which is what the "dark squares behind the icons" report was about.
     */
    @Test
    fun `an icon glyph clears AA on the plate it paints and the plate stays a tint`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            val card = VaultColor.surface
            for (accent in VaultColor.accentRamp) {
                val background = accent.copy(alpha = 0.14f)
                val plate = iconPlateColor(palette, accent, background)
                val tint = when (palette.design) {
                    ThemeDesign.NEO_BRUTALIST -> palette.abyssDark
                    ThemeDesign.AGENTIC -> palette.textSecondary
                    else -> accent
                }
                val ink = VaultContrast.ensure(tint, plate)
                val ratio = VaultContrast.ratio(ink, plate)
                assertTrue(
                    "${palette.id}: glyph ${hex(ink)} on plate ${hex(plate)} is %.2f:1".format(ratio),
                    ratio >= VaultContrast.AA_TEXT
                )
                val plateLift = VaultContrast.ratio(plate, card)
                assertTrue(
                    "${palette.id}: the plate is %.2f:1 from the card - it must stay a tint".format(plateLift),
                    plateLift <= 1.6
                )
            }
        }
    }

    private fun hex(color: Color): String {
        fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt().toString(16).uppercase().padStart(2, '0')
        return "#${byte(color.red)}${byte(color.green)}${byte(color.blue)}"
    }
}
