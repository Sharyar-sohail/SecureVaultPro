package com.securevault.pro.presentation.components

import androidx.compose.ui.geometry.Offset
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
import kotlin.math.cos
import kotlin.math.sin
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
                        PieForm.WEDGE_3D -> {
                            val frame = pie3dGeometry(
                                side = diameter,
                                liftEnabled = liftOn,
                                safety = edgeSafety
                            )
                            // The face keeps the flat pie's own radius budget, so it cannot reach
                            // past the box the dial is measured against.
                            assertTrue(
                                "${palette.id}: 3d face ${frame.rx} over $radius",
                                frame.rx <= radius + 0.001f
                            )
                            // The squash is what buys the wall its room: the whole object - face on
                            // top of depth - still stands inside the square.
                            assertTrue(
                                "${palette.id}: 3d face leaves the top edge",
                                frame.topCentreY - frame.ry >= -0.001f
                            )
                            assertTrue(
                                "${palette.id}: 3d wall leaves the bottom edge ${frame.topCentreY + frame.ry + frame.depth}",
                                frame.topCentreY + frame.ry + frame.depth <= diameter + 0.001f
                            )
                            // A lifted wedge slides along its own bisector, and the slide is paid
                            // for out of the face's radius, never out of the canvas.
                            val slide = if (liftOn) frame.rx * SELECTED_LIFT_OF_RADIUS else 0f
                            assertTrue(
                                "${palette.id}: slid wedge leaves the box",
                                frame.rx + slide <= radius + 0.001f
                            )
                            // The readout's bed is a similar ellipse strictly inside the face, so
                            // the caption can never sit on the shaded wall under it.
                            assertTrue("${palette.id}: 3d bed overruns the face", frame.bedRx < frame.rx)
                            assertTrue("${palette.id}: 3d bed reaches the wall", frame.bedRy < frame.ry)
                        }
                        PieForm.SEGMENTED -> {
                            val rail = railGeometry(
                                side = diameter,
                                requestedThickness = 17f,
                                safety = edgeSafety
                            )
                            assertTrue(
                                "${palette.id}: rail ${rail.left} + ${rail.width} of $diameter",
                                rail.left >= 0f && rail.left + rail.width <= diameter + 0.001f
                            )
                            assertTrue(
                                "${palette.id}: rail thickness ${rail.thickness}",
                                rail.thickness >= 1f &&
                                    rail.thickness <= diameter * RAIL_MAX_THICKNESS_OF_SIDE + 0.001f
                            )
                            val slots = segmentedSlots(
                                side = diameter,
                                strokeWidthPx = 17f,
                                gapDegrees = palette.pieGapDegrees,
                                separatorPx = palette.pieSeparator.value,
                                values = List(count) { 10 },
                                safety = edgeSafety
                            )
                            assertEquals(count, slots.size)
                            slots.forEachIndexed { index, slot ->
                                assertTrue(
                                    "${palette.id} x$count: segment $index leaves the rail",
                                    slot.start >= rail.left - 0.001f &&
                                        slot.start + slot.slotWidth <= rail.left + rail.width + 0.001f
                                )
                                assertTrue(
                                    "${palette.id} x$count: segment $index inks past its slot",
                                    slot.inkWidth >= 0f && slot.inkWidth <= slot.slotWidth + 0.001f
                                )
                            }
                            // The theme's lift raises the selected segment, and the box holds it.
                            val raised = raisedSegmentThickness(
                                thickness = rail.thickness,
                                liftEnabled = liftOn,
                                halfSide = radius,
                                safety = edgeSafety
                            )
                            assertTrue(
                                "${palette.id}: raised segment $raised leaves the box",
                                raised >= rail.thickness && raised / 2f <= radius + 0.001f
                            )
                        }
                        PieForm.BARS -> {
                            val rows = barRows(
                                side = diameter,
                                rowCount = count,
                                gapPx = barRowGapPx(diameter, count, palette.pieGapDegrees, BAR_ROW_GAP_DP),
                                requestedThickness = railGeometry(diameter, 17f, edgeSafety).thickness,
                                swollenPosition = 0
                            )
                            assertEquals(count, rows.size)
                            rows.forEachIndexed { position, row ->
                                assertTrue(
                                    "${palette.id} x$count: row $position leaves the box",
                                    row.centreY - row.thickness / 2f >= -0.001f &&
                                        row.centreY + row.thickness / 2f <= diameter + 0.001f
                                )
                                // A row never crosses its own slot, so no bar can touch a neighbour.
                                assertTrue(
                                    "${palette.id} x$count: row $position crosses its slot",
                                    row.thickness <= row.slotHeight + 0.001f && row.thickness > 0f
                                )
                            }
                            for (position in 1 until rows.size) {
                                val below = rows[position - 1]
                                val above = rows[position]
                                assertTrue(
                                    "${palette.id} x$count: rows ${position - 1}/$position overlap",
                                    below.centreY + below.thickness / 2f <=
                                        above.centreY - above.thickness / 2f + 0.001f
                                )
                            }
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
    // The three non-circular forms: projection, tiling and the taps each one answers.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the 3d pie keeps its proportions, its wall and its bed inside the box`() {
        for (side in listOf(96f, 110f, 126f, 150f)) {
            for (liftEnabled in listOf(false, true)) {
                val frame = pie3dGeometry(side, liftEnabled, edgeSafety)
                val radius = side / 2f
                // The documented projection, checked rather than assumed.
                assertEquals(frame.rx * PIE_3D_SQUASH, frame.ry, 0.001f)
                assertEquals(frame.rx * PIE_3D_DEPTH_OF_RX, frame.depth, 0.001f)
                assertEquals(frame.rx * PIE_3D_BED_OF_FACE, frame.bedRx, 0.001f)
                assertEquals(frame.ry * PIE_3D_BED_OF_FACE, frame.bedRy, 0.001f)
                // Nothing reaches past the square: the face up top, the wall at the bottom.
                assertTrue("$side: face radius ${frame.rx} of $radius", frame.rx <= radius)
                assertTrue("$side: top edge", frame.topCentreY - frame.ry >= 0f)
                assertTrue("$side: bottom edge", frame.topCentreY + frame.ry + frame.depth <= side + 0.001f)
                // The lift is reserved out of the face's own radius, so it has nowhere to spill.
                val slide = if (liftEnabled) frame.rx * SELECTED_LIFT_OF_RADIUS else 0f
                assertTrue("$side: slid face ${frame.rx + slide} of $radius", frame.rx + slide <= radius + 0.001f)
                if (!liftEnabled) assertEquals(radius - edgeSafety, frame.rx, 0.001f)
                // The bed the readout stands on is strictly smaller than the face it is cut into.
                assertTrue("$side: bed overruns the face", frame.bedRx < frame.rx && frame.bedRy < frame.ry)
                // ...and the caption is measured against the bed's smaller semi-axis, so its
                // inscribed square cannot reach the shaded wall or the rim.
                val hole = pieHoleRadius(
                    radius = radius,
                    form = PieForm.WEDGE_3D,
                    innerRatio = 0.62f,
                    strokeWidth = 17f,
                    bandCount = 4,
                    liftEnabled = liftEnabled,
                    safety = edgeSafety
                )
                assertEquals(frame.bedRy, hole, 0.001f)
                assertTrue("$side: caption past the bed", (captionWidth(hole) / 2f) * sqrt(2f) <= hole + 0.001f)
            }
        }
    }

    @Test
    fun `a tap on the 3d pie is un-projected before the dial answers it`() {
        val values = listOf(3, 1, 0, 2)
        val frame = pie3dGeometry(side = 126f, liftEnabled = true, safety = edgeSafety)
        val spans = pieSpans(values)
        for (index in spans.indices) {
            val span = spans[index]
            if (span.shareDegrees <= 0f) continue
            val mid = Math.toRadians((span.startDegrees + span.shareDegrees / 2f).toDouble())
            // Halfway out the face's own radius, at the projected position the eye actually sees.
            val tap = Offset(
                frame.centreX + (cos(mid) * frame.rx * 0.5f).toFloat(),
                frame.topCentreY + (sin(mid) * frame.ry * 0.5f).toFloat()
            )
            assertEquals("slice $index", index, pie3dSliceAtTap(tap, frame, values))
            // And the same point on the flat dial resolves to the same slice: the squash did not
            // move the geometry, only the picture of it.
            val flat = Offset(
                frame.centreX + (cos(mid) * frame.rx * 0.5f).toFloat(),
                frame.topCentreY + (sin(mid) * frame.rx * 0.5f).toFloat()
            )
            assertEquals(index, pieSliceAtTap(angleOf(flat, frame.centreX, frame.topCentreY), values))
        }
        // A dead category owns no degrees, so no point anywhere on the face resolves to it: walking
        // the whole dial answers either a live slice or nothing at all.
        for (step in 0 until 360) {
            val radians = Math.toRadians(step.toDouble())
            val tap = Offset(
                frame.centreX + (cos(radians) * frame.rx * 0.6f).toFloat(),
                frame.topCentreY + (sin(radians) * frame.ry * 0.6f).toFloat()
            )
            val answer = pie3dSliceAtTap(tap, frame, values)
            assertTrue("angle $step answered dead slice $answer", answer < 0 || values[answer] > 0)
        }
        // Outside the squashed dial is outside the un-projected circle: refused, not guessed at.
        assertEquals(-1, pie3dSliceAtTap(Offset(frame.centreX, 0f), frame, values))
        assertEquals(-1, pie3dSliceAtTap(Offset(0f, frame.topCentreY), frame, values))
        assertEquals(-1, pie3dSliceAtTap(Offset(126f, 126f), frame, values))
        // An empty vault answers nothing whatever the tap.
        assertEquals(-1, pie3dSliceAtTap(Offset(frame.centreX, frame.topCentreY), frame, listOf(0, 0, 0)))
    }

    @Test
    fun `the segmented rail tiles its track and every segment answers its own tap`() {
        val values = listOf(4, 1, 0, 5)
        val rail = railGeometry(side = 126f, requestedThickness = 17f, safety = edgeSafety)
        for (side in listOf(96f, 126f, 150f)) {
            for (gapDegrees in listOf(0f, 1.5f, 3.5f, 30f, 120f)) {
                val slots = segmentedSlots(side, 17f, gapDegrees, 0f, values, edgeSafety)
                assertEquals(values.size, slots.size)
                // The slots tile the rail: first starts at the inset, contiguous, last ends flush.
                assertEquals(railGeometry(side, 17f, edgeSafety).left, slots.first().start, 0.001f)
                for (index in 1 until slots.size) {
                    val previous = slots[index - 1]
                    assertEquals(previous.start + previous.slotWidth, slots[index].start, 0.001f)
                }
                val run = railGeometry(side, 17f, edgeSafety)
                val last = slots.last()
                assertEquals(run.left + run.width, last.start + last.slotWidth, 0.001f)
                // No ink outside the rail, no ink past its own slot, no negative ink.
                slots.forEach { slot ->
                    assertTrue(slot.inkWidth >= 0f && slot.inkWidth <= slot.slotWidth + 0.001f)
                    assertTrue(slot.start + slot.inkWidth <= run.left + run.width + 0.001f)
                }
            }
        }
        // A tap is x across those very slots, and a dead category's zero-width slot is not tappable.
        val slots = segmentedSlots(126f, 17f, 2f, 0f, values, edgeSafety)
        slots.forEachIndexed { index, slot ->
            if (slot.slotWidth <= 0f) {
                // A dead category owns no width, so the x where it would have started belongs to
                // whichever live segment is inked there - never to the dead one.
                assertTrue(sliceAtLinearTap(slot.start, slots) != index)
            } else {
                assertEquals(index, sliceAtLinearTap(slot.start + slot.slotWidth / 2f, slots))
            }
        }
        assertEquals(-1, sliceAtLinearTap(-1f, slots))
        assertEquals(-1, sliceAtLinearTap(1e6f, slots))
        // One category owns the rail and pays no gap, exactly as one wedge owns the whole dial.
        val loneRail = railGeometry(126f, 17f, edgeSafety)
        val single = segmentedSlots(126f, 17f, 9f, 0f, listOf(3), edgeSafety).single()
        assertEquals(loneRail.width, single.slotWidth, 0.001f)
        assertEquals(loneRail.width, single.inkWidth, 0.001f)
        assertEquals(0f, linearGapFor(9f, loneRail.width, 1), 0f)
        assertEquals(9f / 360f * loneRail.width, linearGapFor(9f, loneRail.width, 3), 0.001f)
        // A brand that rules separators spends the gap token on the hairline instead: edge to edge.
        val ruled = segmentedSlots(126f, 17f, 9f, 1f, values, edgeSafety)
        assertEquals(loneRail.width, ruled.sumOf { it.slotWidth.toDouble() }.toFloat(), 0.001f)
        ruled.forEach { slot -> assertEquals(slot.slotWidth, slot.inkWidth, 0.001f) }
        // A thin category never vanishes: what a slot pays as gap is capped by its own width.
        val tight = segmentedSlots(126f, 17f, 30f, 0f, listOf(100, 1), edgeSafety)
        assertTrue("thin segment inked ${tight[1].inkWidth}", tight[1].inkWidth > 0f)
        // Nothing sealed yet: every slot is zero-width, so nothing is inked and nothing is tappable.
        val empty = segmentedSlots(126f, 17f, 2f, 0f, listOf(0, 0, 0), edgeSafety)
        empty.forEach { slot -> assertEquals(0f, slot.slotWidth, 0f) }
        assertEquals(-1, sliceAtLinearTap(60f, empty))
        // A raised segment grows, a calm one is the rail, and neither leaves the box.
        assertEquals(17f, raisedSegmentThickness(17f, false, 63f, edgeSafety), 0.001f)
        val raised = raisedSegmentThickness(17f, true, 63f, edgeSafety)
        assertTrue("raised $raised", raised > 17f && raised / 2f <= 63f)
        // However fat the ask, a rail never claims more than its share of the canvas.
        val fat = railGeometry(126f, 900f, edgeSafety)
        assertTrue("fat rail ${fat.thickness}", fat.thickness <= 126f * RAIL_MAX_THICKNESS_OF_SIDE + 0.001f)
        assertTrue("no ask, a default thickness", railGeometry(126f, 0f, edgeSafety).thickness > 0f)
    }

    @Test
    fun `every bar row fits its slot, fills its share and answers the tap on it`() {
        val values = listOf(6, 0, 3, 1)
        val live = values.indices.filter { values[it] > 0 }
        val total = values.sumOf { if (it > 0) it else 0 }
        for (side in listOf(96f, 126f, 150f)) {
            for (count in 1..12) {
                val gap = barRowGapPx(side, count, 3.5f, BAR_ROW_GAP_DP)
                val rows = barRows(side, count, gap, 17f, swollenPosition = 0)
                assertEquals(count, rows.size)
                assertEquals(side / count.toFloat(), rows.first().slotHeight, 0.001f)
                rows.forEachIndexed { position, row ->
                    assertTrue("row $position leaves the box", row.centreY - row.thickness / 2f >= -0.001f)
                    assertTrue("row $position leaves the box", row.centreY + row.thickness / 2f <= side + 0.001f)
                    assertTrue("row $position crosses its slot", row.thickness <= row.slotHeight + 0.001f)
                    assertEquals(side / count.toFloat() * (position + 0.5f), row.centreY, 0.001f)
                }
                for (position in 1 until rows.size) {
                    val below = rows[position - 1]
                    val above = rows[position]
                    assertTrue(
                        "rows ${position - 1}/$position touch",
                        below.centreY + below.thickness / 2f <= above.centreY - above.thickness / 2f + 0.001f
                    )
                }
                if (count == 1) assertEquals(0f, gap, 0f)
            }
        }
        // The token is honoured: more gap degrees is more air, and the floor keeps rows apart.
        assertTrue(barRowGapPx(126f, 4, 30f, BAR_ROW_GAP_DP) > barRowGapPx(126f, 4, 1f, BAR_ROW_GAP_DP))
        assertEquals(BAR_ROW_GAP_DP, barRowGapPx(126f, 4, 0f, BAR_ROW_GAP_DP), 0.001f)
        assertTrue(barRowGapPx(126f, 4, 360f, BAR_ROW_GAP_DP) <= 126f / 4f * 0.5f + 0.001f)
        // Only the selected row swells, and the rest do not move.
        val calm = barRows(126f, 5, barRowGapPx(126f, 5, 3.5f, BAR_ROW_GAP_DP), 17f)
        val lifted = barRows(126f, 5, barRowGapPx(126f, 5, 3.5f, BAR_ROW_GAP_DP), 17f, swollenPosition = 2)
        calm.zip(lifted).forEachIndexed { position, (before, after) ->
            if (position == 2) assertTrue("row 2 did not swell", after.thickness >= before.thickness)
            else assertEquals("row $position moved", before, after)
            assertTrue(after.thickness <= after.slotHeight + 0.001f)
        }
        // The fill is the category's share of the rail, so it can never overrun the track.
        val rail = railGeometry(126f, 17f, edgeSafety)
        live.forEach { index ->
            val share = values[index].toFloat() / total.toFloat()
            val fill = rail.width * share
            assertTrue("row $index fills $fill of ${rail.width}", fill > 0f && fill <= rail.width + 0.001f)
            assertEquals(
                "row $index share",
                pieSpans(values)[index].shareDegrees / 360f,
                share,
                0.001f
            )
        }
        assertEquals(rail.width.toDouble(), live.sumOf { values[it].toDouble() / total.toDouble() } * rail.width.toDouble(), 0.01)
        // A tap walks the rows in the order they are drawn: first category at the BOTTOM, so the
        // y axis reads the live list reversed, exactly like `drawBars`.
        val slot = 126f / live.size.toFloat()
        live.reversed().forEachIndexed { position, index ->
            assertEquals(
                "position $position is index $index",
                index,
                rowsByLinearTap(slot * (position + 0.5f), values, 0f, 126f, reverse = true)
            )
        }
        live.forEachIndexed { position, index ->
            assertEquals(index, rowsByLinearTap(slot * (position + 0.5f), values, 0f, 126f))
        }
        // The bands are equal-height, because a bar chart encodes a category's share in the LENGTH
        // of its fill and never in the height of its row: any point inside a band answers it.
        live.reversed().forEachIndexed { position, index ->
            assertEquals(index, rowsByLinearTap(slot * position + slot * 0.1f, values, 0f, 126f, reverse = true))
            assertEquals(index, rowsByLinearTap(slot * (position + 1f) - slot * 0.1f, values, 0f, 126f, reverse = true))
        }
        // Past the last row there is no row, and an empty chart answers nothing.
        assertEquals(-1, rowsByLinearTap(126f, values, 0f, 126f, reverse = true))
        assertEquals(-1, rowsByLinearTap(10f, listOf(0, 0, 0), 0f, 126f))
        assertEquals(-1, rowsByLinearTap(10f, values, 0f, 0f))
        assertTrue(barRows(126f, 0, 3f, 17f).isEmpty())
        // The reveal is staggered, but every row lands on full when the animation does.
        for (rowCount in 1..8) {
            for (position in 0 until rowCount) {
                assertEquals("row $position of $rowCount", 1f, barRowProgress(1f, position, rowCount), 0.001f)
                assertTrue(barRowProgress(0f, position, rowCount) >= 0f)
                val half = barRowProgress(0.5f, position, rowCount)
                assertTrue("row $position at half is $half", half >= 0f && half <= 1f)
                if (position > 0) {
                    assertTrue(
                        barRowProgress(0.5f, position, rowCount) <= barRowProgress(0.5f, position - 1, rowCount) + 0.001f
                    )
                }
            }
        }
    }

    @Test
    fun `a rail and a stack of bars report no circular bed, the dial forms still do`() {
        val radius = 63f
        val budget = radius - edgeSafety
        for (form in PieForm.entries) {
            for (palette in VaultPalettes.all) {
                val hole = pieHoleRadius(
                    radius = radius,
                    form = form,
                    innerRatio = palette.pieInnerRatio,
                    strokeWidth = 17f,
                    bandCount = 4,
                    liftEnabled = palette.pieSelectedLift,
                    safety = edgeSafety
                )
                // The hole maths stays ordinary whatever the form - never zero, never past the dial.
                assertTrue("$form on ${palette.id}: hole $hole", hole > 0f && hole <= budget)
                val caption = captionWidth(hole)
                assertEquals(hole * 2f * HOLE_INSCRIPTION, caption, 0.001f)
                assertTrue(
                    "$form on ${palette.id}: caption corner outside hole",
                    (caption / 2f) * sqrt(2f) <= hole + 0.001f
                )
                when (form) {
                    // These two ink straight through the middle of their box, so there is no bed to
                    // report and `VaultPieChart` draws their readout above the canvas instead.
                    PieForm.SEGMENTED, PieForm.BARS -> assertEquals(LINEAR_CLEAR_RADIUS_FLOOR, hole, 0.001f)
                    PieForm.WEDGE_3D -> assertEquals(pie3dGeometry(radius * 2f, palette.pieSelectedLift, edgeSafety).bedRy, hole, 0.001f)
                    PieForm.WEDGE -> assertEquals(radius * WEDGE_MEDALLION_OF_RADIUS, hole, 0.001f)
                    PieForm.RING -> assertTrue(hole >= budget * MIN_HOLE_OF_RADIUS - 0.001f)
                    PieForm.STACKED_RING -> assertTrue(hole > LINEAR_CLEAR_RADIUS_FLOOR)
                }
            }
        }
    }

    /** The raw atan2 angle the chart's own gesture maths would read at [tap]. */
    private fun angleOf(tap: Offset, centreX: Float, centreY: Float): Float =
        Math.toDegrees(
            kotlin.math.atan2((tap.y - centreY).toDouble(), (tap.x - centreX).toDouble())
        ).toFloat()

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
