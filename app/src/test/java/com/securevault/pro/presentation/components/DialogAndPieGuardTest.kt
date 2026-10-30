package com.securevault.pro.presentation.components

import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.theme.PieForm
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The guard tests for the shared component layer's fitting maths.
 *
 * Everything here is pure Kotlin over the palette tokens, so it runs on the JVM with no device, no
 * emulator and no Compose runtime. Each test pins one of the reported layout defects at the exact
 * arithmetic the drawing code uses: the pie's angles, the pie's radius budget (so a lifted slice
 * and a caption both stay inside the box the chart claims), how a dialog splits the screen it is
 * given, and the icon plate's silhouette and tint.
 */
class DialogAndPieGuardTest {

    /** The dp-side room the chart keeps from the canvas edge; pins the component's own number. */
    private val edgeSafety = 0.5f

    private inline fun <T> withPalette(palette: VaultPalette, block: () -> T): T {
        VaultPaletteController.apply(palette)
        return block()
    }

    // -----------------------------------------------------------------------------------------
    // Defect 3 — the pie's angles.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `slice shares always fill the dial exactly`() {
        for (count in 1..12) {
            val values = List(count) { 10 }
            val spans = pieSpans(values)
            assertEquals(count, spans.size)
            assertEquals(
                "shares of $count equal slices",
                360f,
                spans.sumOf { it.shareDegrees.toDouble() }.toFloat(),
                0.001f
            )
            // Contiguous, in order, starting at 12 o'clock.
            assertEquals(PIE_START_DEGREES, spans.first().startDegrees, 0.001f)
            for (index in 1 until count) {
                val previous = spans[index - 1]
                assertEquals(
                    "slice $index starts where ${index - 1} ends",
                    previous.startDegrees + previous.shareDegrees,
                    spans[index].startDegrees,
                    0.001f
                )
            }
        }
    }

    @Test
    fun `zero value entries keep their index but ink nothing`() {
        val spans = pieSpans(listOf(0, 5, 0, 5))
        assertEquals(4, spans.size)
        assertEquals(0f, spans[0].shareDegrees, 0.001f)
        assertEquals(180f, spans[1].shareDegrees, 0.001f)
        assertEquals(0f, spans[2].shareDegrees, 0.001f)
        assertEquals(-90f + 180f, spans[3].startDegrees, 0.001f)
        assertEquals(360f, spans.sumOf { it.shareDegrees.toDouble() }.toFloat(), 0.001f)
        // A tap on a dead entry addresses no slice rather than the wrong one.
        assertEquals(-1, pieSliceAtTap(-90f, listOf(0, 0, 0)))
    }

    @Test
    fun `visible ink sums to the dial minus exactly the gaps it pays`() {
        for (gap in listOf(0f, 1f, 2f, 3.5f, 5f, 9f, 30f)) {
            for (count in 1..12) {
                val spans = pieSpans(List(count) { 10 })
                val paid = pieGapFor(gap, count)
                var inked = 0f
                for (span in spans) inked += pieInkDegrees(span.shareDegrees, paid, 0f, 1f)
                assertEquals(
                    "$count slices at $gap gap degrees",
                    360f - count * paid,
                    inked,
                    0.001f
                )
            }
        }
    }

    @Test
    fun `a single slice owns the whole dial and pays no gap`() {
        val single = pieSpans(listOf(7)).single()
        assertEquals(360f, single.shareDegrees, 0.001f)
        assertEquals(0f, pieGapFor(9f, 1), 0.001f)
        assertEquals(360f, pieInkDegrees(single.shareDegrees, pieGapFor(9f, 1), 0f), 0.001f)
        // Two or more slices do pay it, and the token is clamped to a sane size.
        assertEquals(9f, pieGapFor(9f, 2), 0.001f)
        assertEquals(30f, pieGapFor(120f, 3), 0.001f)
    }

    @Test
    fun `a slice too thin for its gap draws no ink instead of drawing backwards`() {
        val tiny = pieInkDegrees(shareDegrees = 2f, gapDegrees = 9f, capOverhangDegrees = 1f)
        assertEquals(0f, tiny, 0f)
        assertTrue(pieInkDegrees(10f, 4f, 100f) >= 0f)
        for (progress in listOf(0f, 0.001f, 0.5f, 1f, 1.4f)) {
            val inked = pieInkDegrees(45f, 5f, 2f, progress)
            assertTrue("progress $progress inks $inked", inked >= 0f && inked <= 45f)
        }
    }

    @Test
    fun `a round cap only ever claims the room its slice can afford`() {
        // Half a stroke at the arc's radius, as an angle, is what the cap reaches past the end.
        assertEquals(
            5.7f,
            capOverhangDegrees(stroke = 20f, radius = 100f, allowanceDegrees = 90f),
            0.2f
        )
        // Clamped to the allowance, so a fat cap can never eat a neighbour's gap.
        assertEquals(3f, capOverhangDegrees(20f, 100f, 3f), 0.001f)
        assertEquals(0f, capOverhangDegrees(20f, 100f, 0f), 0f)
        assertEquals(0f, capOverhangDegrees(0f, 100f, 90f), 0f)
        assertEquals(0f, capOverhangDegrees(20f, 0f, 90f), 0f)
        // The overhang is always paid twice per arc, and never takes more than a third of the slot.
        for (count in 2..12) {
            val share = 360f / count
            val overhang = capOverhangDegrees(18f, 55f, share / 3f)
            assertTrue(overhang <= share / 3f + 0.001f)
            assertTrue(pieInkDegrees(share, 5f, overhang) >= 0f)
        }
    }

    @Test
    fun `a tap addresses the slice the dial actually shows`() {
        val values = listOf(1, 1, 2)
        val spans = pieSpans(values)
        // A tap at the middle of each slice's own arc lands on that slice, for every slice count.
        for (index in spans.indices) {
            val middle = spans[index].startDegrees + spans[index].shareDegrees / 2f
            assertEquals(index, pieSliceAtTap(middle, values))
        }
        // 12 o'clock is the top of the first slice; raw atan2 there is -90 degrees.
        assertEquals(0, pieSliceAtTap(-90f, values))
        // The dial wraps: a tap a hair left of 12 o'clock is still the last slice.
        assertEquals(values.size - 1, pieSliceAtTap(-91f, values))
        for (count in 1..12) {
            val dial = List(count) { 10 }
            val slots = pieSpans(dial)
            slots.forEachIndexed { index, span ->
                val middle = span.startDegrees + span.shareDegrees / 2f
                assertEquals("$count slices, index $index", index, pieSliceAtTap(middle, dial))
            }
            // Anything outside the dial addresses nothing.
            assertEquals(-1, pieSliceAtTap(0f, List(count) { 0 }))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Defect 3 + 2 — the pie's radius budget: lift and caption both stay inside the box.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a lifted slice stays inside the dial the chart claims, on every theme`() {
        for (palette in VaultPalettes.all) {
            for (diameter in listOf(96f, 110f, 126f, 150f)) {
                for (count in 1..12) {
                    val radius = diameter / 2f
                    val liftOn = palette.pieSelectedLift
                    when (palette.pieForm) {
                        PieForm.RING -> {
                            val g = ringGeometry(
                                radius = radius,
                                strokeWidth = 17f,
                                innerRatio = palette.pieInnerRatio,
                                liftEnabled = liftOn,
                                safety = edgeSafety
                            )
                            assertTrue(
                                "${palette.id}: outer ${g.outerRadius} over ${radius}",
                                g.outerRadius <= radius + 0.001f
                            )
                            assertTrue("${palette.id}: thickness", g.thickness > 0f)
                            assertTrue("${palette.id}: lift", g.lift >= 0f)
                            if (!liftOn) assertEquals(0f, g.lift, 0f)
                        }
                        PieForm.STACKED_RING -> {
                            val bands = stackedBands(
                                radius = radius,
                                innerRatio = palette.pieInnerRatio,
                                bandCount = count,
                                bandGap = STACKED_BAND_GAP_DP,
                                safety = edgeSafety,
                                swollenPosition = 0
                            )
                            assertEquals(count, bands.size)
                            bands.forEachIndexed { position, band ->
                                val outer = band.centreRadius + band.stroke / 2f
                                assertTrue(
                                    "${palette.id} x$count band $position reaches $outer of $radius",
                                    outer <= radius + 0.001f
                                )
                                assertTrue("${palette.id}: band stroke", band.stroke > 0f)
                                // A band never crosses its own slot, so it cannot touch a neighbour.
                                assertTrue(
                                    "${palette.id}: band $position crosses its slot",
                                    band.stroke <= band.slot + 0.001f
                                )
                            }
                            // Ordered outward-in and non-overlapping.
                            for (i in 1 until bands.size) {
                                val innerEdge = bands[i - 1].centreRadius - bands[i - 1].stroke / 2f
                                val outerEdge = bands[i].centreRadius + bands[i].stroke / 2f
                                assertTrue(
                                    "${palette.id} x$count: bands ${i - 1}/$i overlap",
                                    outerEdge <= innerEdge + 0.001f
                                )
                            }
                        }
                        PieForm.WEDGE -> {
                            val lift = if (liftOn) radius * SELECTED_LIFT_OF_RADIUS else 0f
                            assertTrue(
                                "${palette.id}: a slid wedge leaves the dial",
                                radius - edgeSafety - lift >= 1f
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `only the selected band swells, and only inside its own slot`() {
        for (palette in VaultPalettes.all) {
            val radius = 63f
            for (count in 1..12) {
                val calm = stackedBands(radius, palette.pieInnerRatio, count, safety = edgeSafety)
                val lifted = stackedBands(
                    radius, palette.pieInnerRatio, count,
                    safety = edgeSafety, swollenPosition = 0
                )
                calm.zip(lifted).forEachIndexed { position, (before, after) ->
                    if (position == 0) {
                        assertTrue("${palette.id} x$count: band 0 did not swell", after.stroke >= before.stroke)
                    } else {
                        assertEquals("${palette.id} x$count: band $position moved", before, after)
                    }
                    assertTrue(after.stroke <= after.slot + 0.001f)
                }
            }
        }
    }

    @Test
    fun `the ring never collapses below the hole its caption needs`() {
        for (palette in VaultPalettes.all) {
            for (diameter in listOf(96f, 110f, 126f, 150f)) {
                for (count in 1..12) {
                    val radius = diameter / 2f
                    val hole = pieHoleRadius(
                        radius = radius,
                        form = palette.pieForm,
                        innerRatio = palette.pieInnerRatio,
                        strokeWidth = if (palette.pieForm == PieForm.RING) 17f else 0f,
                        bandCount = count,
                        liftEnabled = palette.pieSelectedLift,
                        safety = edgeSafety
                    )
                    val budget = radius - edgeSafety
                    assertTrue("${palette.id}: hole $hole of $budget", hole > 0f && hole <= budget)
                    if (palette.pieForm == PieForm.RING) {
                        // The ring form keeps a readable hole whatever the ratio, or the caption
                        // would be painting over the band — the reported overlap.
                        assertTrue(
                            "${palette.id}: ring hole $hole below the floor",
                            hole >= budget * MIN_HOLE_OF_RADIUS - 0.001f
                        )
                    }
                    // The caption is capped to the square inscribed in that hole, so by
                    // construction its corners cannot touch the ring it sits inside.
                    val caption = captionWidth(hole)
                    assertEquals(hole * 2f * HOLE_INSCRIPTION, caption, 0.001f)
                    val half = caption / 2f
                    assertTrue(
                        "${palette.id}: caption corner ${"%.2f".format(sqrt(half * half + half * half))} " +
                            "outside hole ${"%.2f".format(hole)}",
                        sqrt(half * half + half * half) <= hole + 0.001f
                    )
                }
            }
        }
    }

    @Test
    fun `the key moves under the dial when the pane is too narrow to sit beside it`() {
        val chart = 126.dp
        // A 360.dp phone with gutters and card padding still has room beside the dial.
        assertTrue(chartLegendSitsBeside(320.dp, chart))
        // A pane this narrow still has just enough room for the key's minimum width.
        assertTrue(chartLegendSitsBeside(274.dp, chart))
        // Anything narrower stacks: the column would be squeezed under the ring.
        assertFalse(chartLegendSitsBeside(260.dp, chart))
        assertFalse(chartLegendSitsBeside(0.dp, chart))
        // The split point is exactly the chart plus the air plus the key's own minimum room.
        val split = chart + CHART_LEGEND_GAP_DP.dp + LEGEND_MIN_ROOM_DP.dp
        assertTrue(chartLegendSitsBeside(split, chart))
        assertFalse(chartLegendSitsBeside(split - 1.dp, chart))
    }

    // -----------------------------------------------------------------------------------------
    // Defect 1 — how much of the screen a dialog may claim.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a dialog's budget always fits the screen it was measured on`() {
        for (screenHeight in listOf(320f, 360f, 420f, 500f, 640f, 800f, 1080f, 1200f)) {
            val budget = dialogHeightBudgetDp(screenHeight)
            assertTrue("${screenHeight}dp: body ${budget.bodyMaxDp}", budget.bodyMaxDp > 0f)
            assertTrue("${screenHeight}dp: title ${budget.titleMaxDp}", budget.titleMaxDp > 0f)
            assertTrue(
                "${screenHeight}dp: total ${budget.totalMaxDp}",
                budget.totalMaxDp <= screenHeight * DIALOG_SCREEN_BUDGET + 0.001f
            )
            assertEquals(
                "${screenHeight}dp: chrome",
                budget.bodyMaxDp + budget.titleMaxDp + DIALOG_CHROME_DP,
                budget.totalMaxDp,
                0.001f
            )
            assertTrue("${screenHeight}dp: body ceiling", budget.bodyMaxDp <= DIALOG_BODY_CEILING_DP)
            assertTrue("${screenHeight}dp: title ceiling", budget.titleMaxDp <= DIALOG_TITLE_CEILING_DP)
            // A body taller than the screen is reachable by scrolling, never clipped: so it must
            // be the dominant share of what the dialog may claim.
            assertTrue("${screenHeight}dp: share", budget.bodyMaxDp >= budget.titleMaxDp)
        }
    }

    @Test
    fun `a dialog never claims more width than the screen leaves it`() {
        for (screenWidth in listOf(320f, 360f, 411f, 500f, 700f, 1200f)) {
            val cap = dialogWidthCapDp(screenWidth)
            assertTrue("$screenWidth: cap $cap", cap <= screenWidth - 24f + 0.001f)
            assertTrue("$screenWidth: cap $cap", cap >= 200f)
            assertTrue("$screenWidth: cap $cap", cap <= 420f)
        }
        assertEquals(420f, dialogWidthCapDp(1200f), 0.001f)
        assertEquals(336f, dialogWidthCapDp(360f), 0.001f)
    }

    // -----------------------------------------------------------------------------------------
    // Defect 5 — one silhouette, one tint, for the tile and the generated board.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the kotlin plate draws the board's own squircle at every tile size`() {
        for (palette in VaultPalettes.all) {
            val fraction = iconPlateCornerFraction(palette)
            assertEquals(
                "${palette.id}: plate corner fraction",
                BOARD_PLATE_CORNER_FRACTION,
                fraction,
                0.01f
            )
            var previousCorner = -1f
            for (tile in listOf(24f, 30f, 34f, 38f, 40f, 48f, 64f)) {
                val corner = iconPlateCornerDp(palette, tile)
                assertEquals(
                    "${palette.id}: ${tile}dp tile corner tracks the tile",
                    tile * BOARD_PLATE_CORNER_FRACTION,
                    corner,
                    0.6f
                )
                assertTrue("${palette.id}: corner past half the tile", corner <= tile / 2f)
                // The corner grows with the tile instead of staying a fixed dp, which is the whole
                // defect: a fixed 9.dp is a circle at 24.dp and a square at 64.dp.
                assertTrue(
                    "${palette.id}: ${tile}dp corner $corner does not track the previous $previousCorner",
                    previousCorner < 0f || corner > previousCorner
                )
                previousCorner = corner
            }
        }
        // The token and the vector agree: 9.dp at the 38.dp tile is the board's 60/256 corner.
        assertEquals(0.234f, BOARD_PLATE_CORNER_FRACTION, 0.001f)
        assertEquals(9.dp, VaultPalettes.all.first().iconTileCorner)
        assertEquals(
            "token agrees with the board at the calibrated tile",
            BOARD_PLATE_CORNER_FRACTION * ICON_TILE_CALIBRATION_DP,
            iconPlateCornerDp(VaultPalettes.all.first(), ICON_TILE_CALIBRATION_DP),
            0.15f
        )
    }

    @Test
    fun `no palette's icon plate breaks the tint limit`() {
        for (palette in VaultPalettes.all) withPalette(palette) {
            val card = VaultColor.surface
            for (accent in VaultColor.accentRamp) {
                val plate = iconPlateColor(palette, accent, accent.copy(alpha = 0.14f))
                val lift = VaultContrast.ratio(plate, card)
                assertTrue(
                    "${palette.id}: plate %.2f:1 from the card - it must stay a tint".format(lift),
                    lift <= 1.6
                )
                val ink = VaultContrast.ensure(accent, plate)
                assertTrue(
                    "${palette.id}: glyph %.2f:1 on the plate".format(VaultContrast.ratio(ink, plate)),
                    VaultContrast.ratio(ink, plate) >= VaultContrast.AA_TEXT
                )
            }
        }
    }

    @Test
    fun `a legend key fits the box it is drawn in`() {
        // The diamond is rotated 45 degrees inside its own box. Any rounded ancestor (a selected
        // row's bed, a card corner) clips whatever leaves the box, which is the clipped-key half of
        // the selection defect: a rotated square's bounding box is `side * sqrt(2)`, so the side
        // has to stay under `box / sqrt(2)`.
        val bounding = DIAMOND_SIDE_OF_BOX * sqrt(2f)
        assertTrue("diamond reaches $bounding of its box", bounding <= 1f)
        // The key sizes the component asks for stay inside their own measured boxes.
        val box = 9.dp
        assertTrue(box * DIAMOND_SIDE_OF_BOX < box)
        assertTrue(box / 3.2f >= 1.dp)
        assertFalse((box * DIAMOND_SIDE_OF_BOX) * sqrt(2f) > box)
        // And the hole's inscribed square is the caption's whole budget: no corner escapes.
        val cornerReach = (captionWidth(40f) / 2f) * sqrt(2f)
        assertTrue("caption corner $cornerReach of hole 40", cornerReach <= 40f)
    }
}
