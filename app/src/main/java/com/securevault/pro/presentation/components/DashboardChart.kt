package com.securevault.pro.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.theme.PieForm
import com.securevault.pro.presentation.theme.PieLegend
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType

data class PieSlice(
    val label: String,
    val value: Int,
    val color: Color
)

// ---------------------------------------------------------------------------------------------
// Pie geometry.
//
// Every number the chart draws with comes out of the pure functions below, and every one of them
// is unit-tested (`DialogAndPieGuardTest`). They are unit-agnostic: call them with pixels inside a
// `DrawScope`, or with dp outside one, and the arithmetic is the same — which is how the caption
// box and the ring it must sit inside can be guaranteed to agree.
//
// The tokens this honours, all read from the palette and none of them ignored:
//   pieForm         — wedge / ring / stacked ring / 3D wedge / segmented rail / bars
//   pieGapDegrees   — empty degrees between two arcs (never paid for a single slice)
//   pieRoundCaps    — stroke caps, with the angular room a round cap claims trimmed off the arc
//   pieSeparator    — hairline of card surface between wedge boundaries
//   pieInnerRatio   — how thin the ring is, and how big the hole the caption lives in is
//   pieGradient     — radial wash per slice
//   pieTrackAlpha   — the unfilled ring behind every band
//   pieLegend       — the key shape drawn beside each row
//   pieSelectedLift — the selected slice steps outward, inside the canvas, always
//
// The three non-circular forms read the same tokens along an axis instead of around a dial: the
// gap becomes the air between two segments or two rows, the caps become the shape of a segment's
// ends, the lift becomes the raised segment or the thicker row, the separator becomes the hairline
// between them and the track stays the unfilled rail behind the ink. None of them leaves a hole,
// so none of them gets a readout painted in the middle of its own ink (see `pieHoleRadius`).
// ---------------------------------------------------------------------------------------------

/** Degrees the chart starts at, so the first slice begins at 12 o'clock. */
internal const val PIE_START_DEGREES = -90f

/** How much of the radius a lifted slice may step outward. */
internal const val SELECTED_LIFT_OF_RADIUS = 0.075f

/** The hole never closes below this share of the radius, or the caption has nowhere to sit. */
internal const val MIN_HOLE_OF_RADIUS = 0.34f

/** Radial air between two stacked bands; the ColorOS form's own rhythm. */
internal const val STACKED_BAND_GAP_DP = 2f

/**
 * A side that fits inside a circle of radius [holeRadius] without touching it: the inscribed
 * square. Captions sized any larger overlap the ring at the top and bottom of the hole, where the
 * circle narrows — the reported "legend overlaps the ring" defect.
 */
internal const val HOLE_INSCRIPTION = 0.70f

/**
 * The side a legend diamond may use inside a box of `size`, so that rotating it 45° still fits:
 * a rotated square's bounding box is `side * sqrt(2)`, and `0.70 * 1.414 = 0.99 < 1`. Any larger
 * and a rounded ancestor cuts the diamond's points off — the clipped-key half of the selection
 * defect. Guarded by `DialogAndPieGuardTest`.
 */
internal const val DIAMOND_SIDE_OF_BOX = 0.70f

/**
 * The solid pie's readout medallion, as a share of the dial radius. `pieInnerRatio` is documented
 * as ring-only ("only read by the ring forms"), so the wedge brands keep their own proportion
 * instead of borrowing a token that would make a 0.90 ratio erase the whole chart.
 */
internal const val WEDGE_MEDALLION_OF_RADIUS = 0.46f

/** One slice's slot on the dial: where its degrees start and how many it owns. */
internal data class PieSpan(val startDegrees: Float, val shareDegrees: Float)

/**
 * The angular slot of every entry in [values], in order, from [startDegrees].
 * Zero-value entries keep their slot (so indices stay addressable) with zero degrees.
 * The shares always sum to exactly 360 for any input with a positive total.
 */
internal fun pieSpans(values: List<Int>, startDegrees: Float = PIE_START_DEGREES): List<PieSpan> {
    val total = values.sumOf { if (it > 0) it else 0 }
    if (total <= 0) return values.map { PieSpan(startDegrees, 0f) }
    val out = ArrayList<PieSpan>(values.size)
    var cursor = startDegrees
    for (raw in values) {
        val value = if (raw > 0) raw else 0
        val share = value.toFloat() / total.toFloat() * 360f
        out.add(PieSpan(cursor, share))
        cursor += share
    }
    return out
}

/** The gap between two arcs. A single slice owns the whole dial, so it pays no gap. */
internal fun pieGapFor(gapDegrees: Float, liveCount: Int): Float =
    if (liveCount <= 1) 0f else gapDegrees.coerceIn(0f, 30f)

/**
 * Which slice a tap addresses. [atanDegrees] is the raw `atan2(dy, dx)` in degrees (0 at 3 o'clock);
 * it is folded into the dial frame the ink is drawn from and matched against the very same spans,
 * so a tap can never select a segment the eye does not see. Returns -1 for the dial positions that
 * belong to a zero-value entry.
 */
internal fun pieSliceAtTap(atanDegrees: Float, values: List<Int>): Int {
    val spans = pieSpans(values)
    if (spans.isEmpty()) return -1
    var angle = atanDegrees - PIE_START_DEGREES
    while (angle < 0f) angle += 360f
    while (angle >= 360f) angle -= 360f
    var cursor = 0f
    for (index in spans.indices) {
        val share = spans[index].shareDegrees
        if (share > 0f && angle >= cursor && angle < cursor + share) return index
        cursor += share
    }
    return -1
}

/**
 * How far one round cap reaches past the end of its arc, in degrees: a cap is a half-disc of
 * radius stroke/2 centred on the arc end, so it subtends `atan((stroke/2)/radius)`.
 * [allowanceDegrees] is what the slice can afford to give up — the caller passes a fraction of the
 * slice's own slot, which is what stops a fat cap on a thin inner band from eating the neighbour's
 * gap (the ColorOS end-cap artefact).
 */
internal fun capOverhangDegrees(stroke: Float, radius: Float, allowanceDegrees: Float): Float {
    if (radius <= 0f || stroke <= 0f || allowanceDegrees <= 0f) return 0f
    val raw = Math.toDegrees(atan(((stroke / 2f) / radius).toDouble())).toFloat()
    return raw.coerceIn(0f, max(0f, allowanceDegrees))
}

/**
 * The degrees actually inked for one slice: its slot, minus the gap it pays, minus the room its own
 * two caps claim, then scaled by the entry [progress]. Never negative, so a slice that cannot pay
 * its gap simply draws no ink instead of drawing backwards into the next slice.
 */
internal fun pieInkDegrees(
    shareDegrees: Float,
    gapDegrees: Float,
    capOverhangDegrees: Float,
    progress: Float = 1f
): Float {
    val net = shareDegrees - gapDegrees - 2f * capOverhangDegrees
    if (net <= 0f) return 0f
    return net * progress.coerceIn(0f, 1f)
}

/** The ring form's geometry, in whatever unit [radius] is given in. */
internal data class RingGeometry(
    val centreRadius: Float,
    val thickness: Float,
    val lift: Float,
    /** Clear radius at the middle of the dial: the hole a caption may live in. */
    val holeRadius: Float,
    /** The outermost pixel radius ANY slice reaches, lifted slice included. */
    val outerRadius: Float
)

/**
 * A selected ring slice steps outward *and* keeps the whole arc on the canvas, so the base ring is
 * drawn one lift short of the edge. The reservation used to be the unbounded `thickness * 0.34`,
 * which on Piano Glass (innerRatio 0.70) collapsed the ring to a 15.dp stub in the middle of a
 * 126.dp box — and the caption then painted straight over it.
 */
internal fun ringGeometry(
    radius: Float,
    strokeWidth: Float,
    innerRatio: Float,
    liftEnabled: Boolean,
    safety: Float = 0f
): RingGeometry {
    if (radius <= 0f) return RingGeometry(0f, 0f, 0f, 0f, 0f)
    val span = (radius - safety).coerceAtLeast(1f)
    val ratio = innerRatio.coerceIn(0f, 0.9f)
    val minHole = span * MIN_HOLE_OF_RADIUS
    val liftIdeal = if (liftEnabled) span * SELECTED_LIFT_OF_RADIUS else 0f
    var thickness = span * (1f - ratio)
    if (strokeWidth > 0f) thickness = min(thickness, strokeWidth)
    val maxThickness = max(1f, span - minHole - liftIdeal)
    thickness = max(1f, min(thickness, maxThickness))
    val lift = if (liftEnabled) min(liftIdeal, max(0f, span - thickness - minHole)) else 0f
    val centre = span - lift - thickness / 2f
    val hole = max(0.5f, centre - thickness / 2f)
    return RingGeometry(centre, thickness, lift, hole, centre + lift + thickness / 2f)
}

/** One band of the ColorOS stacked ring. */
internal data class StackedBand(val centreRadius: Float, val stroke: Float, val slot: Float)

/**
 * Each category owns its own concentric slot between the hole and the canvas edge. A band's stroke
 * may never cross its slot, so a swollen band can neither leave the canvas nor touch a neighbour —
 * and the innermost band always leaves the hole intact for the caption.
 *
 * [swollenPosition] is the band the selection lifted, counted from the outer edge in, or -1 for
 * none: only that band swells, and only inside its own slot. [bandGap] is the radial air between
 * two bands, in the SAME unit as [radius] (dp outside a canvas, px inside one).
 */
internal fun stackedBands(
    radius: Float,
    innerRatio: Float,
    bandCount: Int,
    bandGap: Float = STACKED_BAND_GAP_DP,
    safety: Float = 0f,
    swollenPosition: Int = -1
): List<StackedBand> {
    if (radius <= 0f || bandCount <= 0) return emptyList()
    val span = (radius - safety).coerceAtLeast(1f)
    val inner = span * innerRatio.coerceIn(0.1f, 0.9f)
    val stackHeight = max(1f, span - inner)
    val slot = stackHeight / bandCount.toFloat()
    val gap = bandGap.coerceIn(0f, slot * 0.6f)
    // A swollen band may take its own slot minus half a gap on each side: that is the most it can
    // claim without meeting the band above or below it.
    val baseStroke = max(0.8f, slot - gap).coerceAtMost(slot)
    val swollenStroke = max(baseStroke, slot - gap * 0.5f).coerceAtMost(slot)
    return (0 until bandCount).map { position ->
        StackedBand(
            centreRadius = span - slot * (position + 0.5f),
            stroke = if (position == swollenPosition) swollenStroke else baseStroke,
            slot = slot
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The three non-circular forms.
//
// WEDGE_3D is still a dial, so it keeps the dial's own span and angle maths untouched and only
// changes the projection: x passes through, y is divided by the squash. That is an affine map, so
// un-projecting a tap before reading its angle is exact rather than approximate, and the existing
// pieSliceAtTap answers it without a copy of its logic.
//
// SEGMENTED and BARS have no centre, no angle and no hole. Their proportional budget is the same
// share table pieSpans builds, laid along one axis, so a gap is a width instead of a number of
// degrees and a tap is answered by the slot it fell in. Both are pure and unit-agnostic like
// everything else here, which is how the ink and the tap test are guaranteed to agree - and how
// DialogAndPieGuardTest can pin them on the JVM.
// ---------------------------------------------------------------------------------------------

/** The 3D pie's top face: its vertical radius as a share of its horizontal one. */
internal const val PIE_3D_SQUASH = 0.62f

/** The 3D pie's side wall, as a share of the top face's horizontal radius. */
internal const val PIE_3D_DEPTH_OF_RX = 0.18f

/** The 3D readout's bed: the top face scaled about its own centre by this share. */
internal const val PIE_3D_BED_OF_FACE = 0.62f

/** The side wall is the slice's own channels multiplied down: never a new constant, never a new hue. */
internal const val PIE_3D_WALL_SHADE = 0.58f

/** Horizontal air a linear form keeps from the canvas edge, as a share of the half-side. */
internal const val LINEAR_EDGE_INSET_OF_RADIUS = 0.08f

/** How much of the canvas side a rail may claim, however thick the caller asked for it. */
internal const val RAIL_MAX_THICKNESS_OF_SIDE = 0.42f

/** A rail's own thickness when the caller brings no stroke width to cap it with. */
internal const val RAIL_THICKNESS_OF_RADIUS = 0.30f

/** How far a raised segment may grow past the rail, as a share of the rail's thickness. */
internal const val SEGMENT_LIFT_OF_THICKNESS = 0.45f

/** How far a selected bar row may grow past its own thickness, as a share of that thickness. */
internal const val BAR_LIFT_OF_THICKNESS = 0.40f

/** The least vertical air a bar row keeps from its neighbour, in dp, whatever the theme asks. */
internal const val BAR_ROW_GAP_DP = 3f

/** How much of the reveal one bar row starts behind the row above it. */
internal const val BAR_ROW_STAGGER = 0.12f

/** How far along the rail a row's leading label is assumed to reach, as a share of the rail. */
internal const val BAR_LABEL_COVER_OF_RAIL = 0.25f

/** How far along the rail a row's trailing count is assumed to sit under, as a share of the rail. */
internal const val BAR_VALUE_COVER_OF_RAIL = 0.75f

/**
 * The linear forms ink straight through the middle of their box, so they report no clear radius at
 * all and this is the floor their caption maths lands on. VaultPieChart never paints a centred
 * readout for them: the total gets its own line above the canvas instead.
 */
internal const val LINEAR_CLEAR_RADIUS_FLOOR = 1f

/** The 3D pie's frame: the top face's ellipse, where it stands, and the wall beneath it. */
internal data class Pie3dGeometry(
    val centreX: Float,
    /** The top face's centre: half a wall above the middle of the box, so the object is centred. */
    val topCentreY: Float,
    val rx: Float,
    val ry: Float,
    val depth: Float,
    /** The bed the readout is painted on: a similar ellipse, concentric with the top face. */
    val bedRx: Float,
    val bedRy: Float
)

/**
 * The 3D frame inside a square canvas of [side]. The face keeps the flat pie's own budget
 * (`radius - safety - lift`), so a slid wedge still has nowhere to go but inside the canvas. Its
 * centre is the middle of the box, which is where the dial's own readout already stands, and the
 * wall hangs `depth` below it: the whole object reaches `ry + depth` (0.80 of the radius) down and
 * only `ry` up, so it is inside the box on both sides without the caption needing to move.
 */
internal fun pie3dGeometry(side: Float, liftEnabled: Boolean, safety: Float = 0f): Pie3dGeometry {
    val half = (side / 2f).coerceAtLeast(1f)
    val lift = if (liftEnabled) half * SELECTED_LIFT_OF_RADIUS else 0f
    val rx = (half - safety - lift).coerceAtLeast(1f)
    val ry = rx * PIE_3D_SQUASH
    val depth = rx * PIE_3D_DEPTH_OF_RX
    return Pie3dGeometry(
        centreX = side / 2f,
        topCentreY = half,
        rx = rx,
        ry = ry,
        depth = depth,
        bedRx = rx * PIE_3D_BED_OF_FACE,
        bedRy = ry * PIE_3D_BED_OF_FACE
    )
}

/**
 * Which wedge a tap on the 3D pie addresses. Dividing the offset by the squash un-does the
 * projection exactly, so the answer comes from the very same [pieSliceAtTap] the flat dial uses: a
 * tap on a zero-value category still resolves to -1, and a tap outside the squashed dial is outside
 * the un-projected circle, so it is refused on the radius test before any angle is read.
 */
internal fun pie3dSliceAtTap(tap: Offset, geometry: Pie3dGeometry, values: List<Int>): Int {
    val dx = tap.x - geometry.centreX
    val dy = (tap.y - geometry.topCentreY) / PIE_3D_SQUASH
    if (sqrt(dx * dx + dy * dy) > geometry.rx) return -1
    return pieSliceAtTap(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat(), values)
}

/** The run every linear form shares: where the rail sits, how wide it runs, how thick it stands. */
internal data class RailGeometry(
    val left: Float,
    val width: Float,
    val centreY: Float,
    val thickness: Float
)

/**
 * The rail inside a square canvas of [side]. The inset keeps the first and last segment off the
 * clipped edge; the thickness is the caller's stroke width capped at a share of the side, which is
 * the linear reading of the margin discipline `ringGeometry` applies to a dial.
 */
internal fun railGeometry(side: Float, requestedThickness: Float, safety: Float = 0f): RailGeometry {
    val half = (side / 2f).coerceAtLeast(1f)
    val span = (half - safety).coerceAtLeast(1f)
    val inset = span * LINEAR_EDGE_INSET_OF_RADIUS
    val asked = if (requestedThickness > 0f) requestedThickness else span * RAIL_THICKNESS_OF_RADIUS
    val thickness = asked.coerceAtMost(side * RAIL_MAX_THICKNESS_OF_SIDE).coerceAtLeast(1f)
    return RailGeometry(
        left = inset,
        width = (side - 2f * inset).coerceAtLeast(1f),
        centreY = side / 2f,
        thickness = thickness
    )
}

/** The linear reading of [pieGapFor]: the same token, spent along an axis instead of around a dial. */
internal fun linearGapFor(gapDegrees: Float, railWidth: Float, liveCount: Int): Float =
    if (liveCount <= 1) 0f else (gapDegrees.coerceIn(0f, 30f) / 360f) * railWidth

/** One category's place on a linear chart: where its slot starts and how much of it is inked. */
internal data class LinearSlot(val start: Float, val slotWidth: Float, val inkWidth: Float)

/**
 * The linear sibling of [pieSpans]. Each category owns a share of [span] proportional to its value
 * and pays [gap] of its OWN slot as ink, so the slots tile the rail exactly (one slot starts where
 * the previous one ends) and a lone segment pays nothing, which is the rule [pieGapFor] already
 * enforces on the dial. A zero-value entry keeps a zero-width slot, so it stays addressable by
 * index while never being inked and never being tappable.
 */
internal fun linearSlots(values: List<Int>, start: Float, span: Float, gap: Float): List<LinearSlot> {
    val total = values.sumOf { if (it > 0) it else 0 }
    if (span <= 0f || total <= 0) return values.map { LinearSlot(start, 0f, 0f) }
    val live = values.filter { it > 0 }
    // The smallest segment has to survive its own gap, or a thin category would vanish entirely.
    val smallest = live.minOrNull()!!.toFloat() / total.toFloat() * span
    val paid = gap.coerceIn(0f, smallest * 0.5f)
    var cursor = start
    return values.map { raw ->
        val value = if (raw > 0) raw else 0
        val slotWidth = value.toFloat() / total.toFloat() * span
        val slot = LinearSlot(cursor, slotWidth, (slotWidth - paid).coerceAtLeast(0f))
        cursor += slotWidth
        slot
    }
}

/**
 * The segmented rail's segments, in the canvas's own unit: the geometry the ink is drawn with and
 * the geometry a tap is tested against come out of this one function, so the two cannot drift.
 * A brand that rules a separator instead of leaving room gets its segments edge to edge, exactly
 * the way [drawWedges] does it, and the hairline is what makes the gap read as a gap.
 */
internal fun segmentedSlots(
    side: Float,
    strokeWidthPx: Float,
    gapDegrees: Float,
    separatorPx: Float,
    values: List<Int>,
    safety: Float = 0f
): List<LinearSlot> {
    val rail = railGeometry(side, strokeWidthPx, safety)
    val gap = if (separatorPx > 0f) 0f
    else linearGapFor(gapDegrees, rail.width, values.count { it > 0 })
    return linearSlots(values, rail.left, rail.width, gap)
}

/** Which slot a tap along one axis addresses. A dead category's zero-width slot is never answered. */
internal fun sliceAtLinearTap(tap: Float, slots: List<LinearSlot>): Int {
    for (index in slots.indices) {
        val slot = slots[index]
        if (slot.slotWidth > 0f && tap >= slot.start && tap < slot.start + slot.slotWidth) return index
    }
    return -1
}

/**
 * Which row a tap addresses when the chart draws only its live categories, in order. [reverse]
 * flips the reading axis, because a bar chart stacks its first category at the BOTTOM while a tap
 * walks the y axis from the top; the ink and this function walk the same list in the same order,
 * which is what keeps the two in step.
 *
 * The bands are weighed equally, not by value: a bar chart encodes its share in the LENGTH of a
 * row's fill, and every row gets the same height, which is what [barRows] strikes the ink on. So
 * the slot table here is the one [linearSlots] builds for a list of ones - the same helper, the
 * same guarantee that the bands tile the axis exactly.
 */
internal fun rowsByLinearTap(
    tap: Float,
    values: List<Int>,
    start: Float,
    span: Float,
    reverse: Boolean = false
): Int {
    val live = values.indices.filter { values[it] > 0 }
    if (live.isEmpty() || span <= 0f) return -1
    val ordered = if (reverse) live.reversed() else live
    val bands = linearSlots(ordered.map { 1 }, start, span, 0f)
    val position = sliceAtLinearTap(tap, bands)
    return if (position < 0) -1 else ordered[position]
}

/**
 * A raised segment is thicker than its rail, and grows by the same fraction of itself that a
 * lifted arc steps outward of a dial - capped at what the box can hold, so "raised" never comes to
 * mean "clipped".
 */
internal fun raisedSegmentThickness(
    thickness: Float,
    liftEnabled: Boolean,
    halfSide: Float,
    safety: Float = 0f
): Float {
    if (!liftEnabled) return thickness
    val room = ((halfSide - safety) * 2f).coerceAtLeast(1f)
    return (thickness * (1f + SEGMENT_LIFT_OF_THICKNESS)).coerceIn(thickness, room)
}

/** One row of the bar chart: the centre its band is struck on, its thickness and the band itself. */
internal data class BarRow(val centreY: Float, val thickness: Float, val slotHeight: Float)

/**
 * Rows stacked in a square canvas of [side]. The slot is the side divided by the row count, and
 * the Column that paints the row text weights the very same box by the very same count, so the two
 * land on identical centres. A row's ink may never cross its own slot - exactly the discipline a
 * stacked band keeps - so rows cannot touch each other however hard a theme asks for a fat bar.
 */
internal fun barRows(
    side: Float,
    rowCount: Int,
    gapPx: Float,
    requestedThickness: Float,
    swollenPosition: Int = -1
): List<BarRow> {
    if (side <= 0f || rowCount <= 0) return emptyList()
    val slot = side / rowCount.toFloat()
    val gap = gapPx.coerceIn(0f, slot * 0.5f)
    val limit = (slot - gap).coerceAtLeast(0.5f)
    val base = (if (requestedThickness > 0f) min(requestedThickness, limit) else limit)
        .coerceIn(0.5f, slot)
    return (0 until rowCount).map { position ->
        val thickness = if (position == swollenPosition) {
            (base * (1f + BAR_LIFT_OF_THICKNESS)).coerceIn(base, slot)
        } else {
            base
        }
        BarRow(centreY = slot * (position + 0.5f), thickness = thickness, slotHeight = slot)
    }
}

/**
 * The vertical air between two bar rows. [gapDegrees] is this theme's own token for "empty room
 * between two arcs", and a bar chart's two arcs are two rows, so it is read linearly across the
 * box's height and then clamped by the room a single row really has. One row pays nothing, and a
 * single row is therefore as tall as its own slot allows.
 */
internal fun barRowGapPx(sidePx: Float, rowCount: Int, gapDegrees: Float, gapFloorPx: Float): Float {
    if (rowCount <= 1) return 0f
    val slot = sidePx / rowCount.toFloat()
    val asked = (gapDegrees.coerceIn(0f, 30f) / 360f) * sidePx
    return min(slot * 0.5f, max(gapFloorPx, asked))
}

/**
 * The reveal, staggered by row: row N starts [BAR_ROW_STAGGER] behind the row above it, and every
 * row still lands exactly on 1 when the animation does, so no bar can be left mid-sweep.
 */
internal fun barRowProgress(progress: Float, position: Int, rowCount: Int): Float {
    val span = 1f + BAR_ROW_STAGGER * (rowCount - 1).coerceAtLeast(0).toFloat()
    return ((progress * span) - position * BAR_ROW_STAGGER).coerceIn(0f, 1f)
}

/** Clear radius left for a caption, for the form actually being drawn; [radius] and [strokeWidth]
 * must be in the same unit, which is the unit the returned hole is expressed in. */
internal fun pieHoleRadius(
    radius: Float,
    form: PieForm,
    innerRatio: Float,
    strokeWidth: Float,
    bandCount: Int,
    liftEnabled: Boolean,
    safety: Float = 0f
): Float = when (form) {
    PieForm.RING -> ringGeometry(radius, strokeWidth, innerRatio, liftEnabled, safety).holeRadius
    PieForm.STACKED_RING -> {
        val bands = stackedBands(radius, innerRatio, bandCount, STACKED_BAND_GAP_DP, safety)
        val innermost = bands.lastOrNull()
        if (innermost == null) radius * innerRatio.coerceIn(0f, 0.9f)
        else max(1f, innermost.centreRadius - innermost.stroke / 2f)
    }
    // A solid pie has no hole; its readout sits on its own medallion, whose size is the wedge
    // brands' proportion rather than the ring token.
    PieForm.WEDGE -> max(1f, radius * WEDGE_MEDALLION_OF_RADIUS)
    // The 3D pie's readout lies on the bed drawn across its own top face. That bed is a similar
    // ellipse at PIE_3D_BED_OF_FACE of the face, so the smaller of its two semi-axes — the squashed
    // vertical one — is the largest circle that fits inside it, and a caption inscribed in that
    // circle cannot reach the wall below the face or the rim above it.
    PieForm.WEDGE_3D -> max(1f, pie3dGeometry(radius * 2f, liftEnabled, safety).bedRy)
    // A rail and a stack of bars ink straight through the middle of their box, so there is no
    // clear radius to report and VaultPieChart draws their readout as its own line ABOVE the
    // canvas instead of in the ink. The floor is all the caption maths needs to stay ordinary.
    PieForm.SEGMENTED, PieForm.BARS -> LINEAR_CLEAR_RADIUS_FLOOR
}

/** The widest a caption may be to sit inside a circular hole of [holeRadius] without clipping. */
internal fun captionWidth(holeRadius: Float): Float = holeRadius * 2f * HOLE_INSCRIPTION

/** Narrowest room a category column needs before its labels stop being readable on one line. */
internal const val LEGEND_MIN_ROOM_DP = 132f

/** Air kept between the dial and the column beside it. */
internal const val CHART_LEGEND_GAP_DP = 12f

/**
 * Whether the chart's category column can sit beside the dial in a pane this wide, or has to move
 * underneath it. Beside-layout is what lets the column and the ring collide on a narrow phone or in
 * landscape split view; below the dial they cannot touch at all, because the ring's box and the
 * rows' box no longer overlap. Pure dp maths, so it is unit-testable without a device.
 */
internal fun chartLegendSitsBeside(
    cardWidth: Dp,
    chartWidth: Dp,
    legendRoom: Dp = LEGEND_MIN_ROOM_DP.dp,
    gap: Dp = CHART_LEGEND_GAP_DP.dp
): Boolean = (cardWidth - chartWidth - gap) >= legendRoom

/** The key drawn beside a chart row; every brand uses a different one. */
@Composable
fun PieLegendMarker(
    color: Color,
    modifier: Modifier = Modifier,
    legend: PieLegend = VaultPaletteController.current.pieLegend,
    size: Dp = 9.dp
) {
    when (legend) {
        PieLegend.DOT -> Box(modifier.size(size).background(color, CircleShape))
        PieLegend.SQUARE -> Box(modifier.size(size).background(color, VaultShapes.tag))
        // The diamond is rotated inside its own box, at the side length whose rotated bounding
        // box still equals `size`. Rotating a 0.82 square overflowed its bounds by ~16% and was
        // clipped by any rounded ancestor, so half of the key disappeared.
        PieLegend.DIAMOND -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size * DIAMOND_SIDE_OF_BOX)
                    .graphicsLayer { rotationZ = 45f }
                    .background(color, VaultShapes.tag)
            )
        }
        PieLegend.BAR -> Box(
            modifier
                .size(width = size * 1.35f, height = (size / 2.6f).coerceAtLeast(2.dp))
                .background(color, VaultRadius.pill())
        )
        PieLegend.RING -> Box(
            modifier
                .size(size)
                .border((size / 3.2f).coerceAtLeast(1.dp), color, CircleShape)
        )
    }
}

/**
 * The Vault Composition chart, cut the way the active brand cuts it: flat wedges,
 * a hairline ring, or one band per category. Selection is hoisted so the category
 * column beside it stays in sync with the tapped segment.
 *
 * [caption] is drawn *under* the ring rather than inside it, so the total is legible at any slice
 * count and can never overlap the band — the in-hole readout is for the selected slice only.
 */
@Composable
fun VaultPieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    selectedIndex: Int = -1,
    onSliceSelected: (Int) -> Unit = {},
    diameter: Dp = 132.dp,
    strokeWidth: Dp = 20.dp,
    caption: String? = null
) {
    val palette = VaultPaletteController.current
    val total = slices.sumOf { if (it.value > 0) it.value else 0 }
    val progress = remember { Animatable(0.001f) }
    val dataKey = slices.joinToString("|") { "${it.label}:${it.value}" }

    LaunchedEffect(dataKey, palette.id) {
        progress.snapTo(0.001f)
        progress.animateTo(1f, animationSpec = tween(850, easing = FastOutSlowInEasing))
    }

    // The same maths the canvas uses, run once in dp, so the caption box is sized against the hole
    // the slices are actually drawn around — not against a fraction guessed at the call site.
    val radiusDp = diameter / 2f
    val liveCount = slices.count { it.value > 0 }
    val holeRadiusDp = if (total <= 0) {
        // With nothing sealed the chart draws one placeholder ring in every form, so the readout is
        // measured against that ring's hole rather than a stack that is not there.
        ringGeometry(
            radius = radiusDp.value,
            strokeWidth = strokeWidth.value,
            innerRatio = palette.pieInnerRatio,
            liftEnabled = false,
            safety = EDGE_SAFETY_DP
        ).holeRadius.dp
    } else {
        pieHoleRadius(
            radius = radiusDp.value,
            form = palette.pieForm,
            innerRatio = palette.pieInnerRatio,
            strokeWidth = if (palette.pieForm == PieForm.RING) strokeWidth.value else 0f,
            bandCount = liveCount,
            liftEnabled = palette.pieSelectedLift,
            safety = EDGE_SAFETY_DP
        ).dp
    }
    val captionWidthDp = captionWidth(holeRadiusDp.value).dp.coerceAtLeast(28.dp)
    // Type is sized from the box it has to live in, so a small ring never gets a caption that
    // spills over the band it is standing in. Held as plain sp numbers so the line-height maths
    // stays ordinary Float arithmetic.
    val heroSizeSp = (captionWidthDp.value * 0.30f).coerceIn(11f, 22f)
    val microSizeSp = (captionWidthDp.value * 0.13f).coerceIn(9f, 12f)

    // Only the dial forms leave a bed in their own middle. A rail and a stack of bars ink straight
    // through the centre of the box, so for those two the readout below is never composed and the
    // total is drawn as its own line above the canvas instead.
    val form = palette.pieForm
    val readoutSitsInInk = form == PieForm.WEDGE || form == PieForm.RING ||
        form == PieForm.STACKED_RING || form == PieForm.WEDGE_3D
    val focused = slices.getOrNull(selectedIndex)?.takeIf { it.value > 0 }
    // The 3D readout's bed, measured in dp by the same function the canvas measures in px, so the
    // ellipse the text stands on and the one the ink is cut into are one and the same shape.
    val frame3d = pie3dGeometry(radiusDp.value * 2f, palette.pieSelectedLift, EDGE_SAFETY_DP)
    // The linear forms' rail, likewise: the overlay's text is inset by exactly the room the canvas
    // keeps for the ends of the track, so the rows and the bars describe one grid.
    val linearRail = railGeometry(radiusDp.value * 2f, strokeWidth.value, EDGE_SAFETY_DP)
    val liveIndices = slices.indices.filter { slices[it].value > 0 }
    val rowLabelSp = if (liveIndices.isEmpty()) microSizeSp
    else (radiusDp.value / liveIndices.size.toFloat() * 0.42f).coerceIn(8f, 13f)

    Column(
        modifier = modifier.width(diameter),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The linear forms have no hole, so their readout is a line of its own above the canvas:
        // nothing about the chart's geometry can overlap it, and it carries the same selection the
        // in-hole readout would have shown.
        if (!readoutSitsInInk && total > 0) {
            Text(
                text = if (focused != null) {
                    val share = (focused.value.toFloat() / total.toFloat() * 100f).roundToInt()
                    "${focused.label.uppercase()}  ${focused.value} . $share%"
                } else {
                    "TOTAL $total"
                },
                style = VaultType.micro(),
                fontSize = microSizeSp.sp,
                lineHeight = (microSizeSp * 1.25f).sp,
                // The selected row's own brand colour, guarded against the card it is written on
                // rather than shipped raw — the same rule the in-hole label follows.
                color = focused?.let { VaultContrast.ensure(it.color, palette.abyssSurface) }
                    ?: VaultColor.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(diameter)
            )
            Spacer(Modifier.height(4.dp))
        }

        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(diameter)
                    .pointerInput(slices, total, selectedIndex, form) {
                        detectTapGestures { tap ->
                            if (total <= 0) return@detectTapGestures
                            val values = slices.map { it.value }
                            val side = min(size.width, size.height).toFloat()
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val index = when (form) {
                                // The dial forms answer a tap by angle, exactly as they always
                                // have: the same centre, the same radius guard, the same spans.
                                PieForm.WEDGE, PieForm.RING, PieForm.STACKED_RING -> {
                                    val dx = tap.x - centre.x
                                    val dy = tap.y - centre.y
                                    if (sqrt(dx * dx + dy * dy) > side / 2f) {
                                        -1
                                    } else {
                                        pieSliceAtTap(
                                            Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat(),
                                            values
                                        )
                                    }
                                }
                                // The 3D pie is that same dial under an affine squash, so the tap is
                                // un-projected first and the angle maths above answers it unchanged.
                                PieForm.WEDGE_3D -> pie3dSliceAtTap(
                                    tap,
                                    pie3dGeometry(side, palette.pieSelectedLift, EDGE_SAFETY_PX),
                                    values
                                )
                                // A rail is hit by x across its segments and a bar chart by which
                                // row's y was tapped. Neither of them has an angle to read.
                                PieForm.SEGMENTED -> sliceAtLinearTap(
                                    tap.x,
                                    segmentedSlots(
                                        side = side,
                                        strokeWidthPx = strokeWidth.toPx(),
                                        gapDegrees = palette.pieGapDegrees,
                                        separatorPx = palette.pieSeparator.toPx(),
                                        values = values,
                                        safety = EDGE_SAFETY_PX
                                    )
                                )
                                PieForm.BARS -> rowsByLinearTap(
                                    tap = tap.y,
                                    values = values,
                                    start = 0f,
                                    span = side,
                                    reverse = true
                                )
                            }
                            if (index < 0) return@detectTapGestures
                            onSliceSelected(if (selectedIndex == index) -1 else index)
                        }
                    }
            ) {
                if (total <= 0) {
                    // The two linear forms have no ring to leave empty, so they show their rail.
                    if (form == PieForm.SEGMENTED || form == PieForm.BARS) {
                        drawEmptyRail(palette, strokeWidth)
                    } else {
                        drawEmptyTrack(palette, strokeWidth)
                    }
                    return@Canvas
                }
                when (form) {
                    PieForm.WEDGE -> drawWedges(slices, total, selectedIndex, palette, progress.value)
                    PieForm.RING -> drawRing(slices, total, selectedIndex, palette, progress.value, strokeWidth)
                    PieForm.STACKED_RING -> drawStackedRings(
                        slices, total, selectedIndex, palette, progress.value
                    )
                    PieForm.WEDGE_3D -> drawWedges3d(slices, selectedIndex, palette, progress.value)
                    PieForm.SEGMENTED -> drawSegmentedRail(
                        slices, selectedIndex, palette, progress.value, strokeWidth
                    )
                    PieForm.BARS -> drawBars(
                        slices, total, selectedIndex, palette, progress.value, strokeWidth
                    )
                }
            }

            // The bar chart's rows are the one part of the chart that carries its own words: the
            // count and the share belong on the row they measure. This Column weighs the very same
            // square the Canvas filled, divided by the very same live count, so its rows land on
            // the centres barRows struck the ink on - and it takes no pointer input, so a tap on
            // the text still reaches the canvas underneath it.
            if (form == PieForm.BARS && total > 0 && liveIndices.isNotEmpty()) {
                val cardBed = palette.abyssSurface
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = linearRail.left.dp)) {
                    liveIndices.reversed().forEachIndexed { position, index ->
                        val slice = slices[index]
                        val active = selectedIndex == index
                        val dimmed = selectedIndex >= 0 && !active
                        val share = slice.value.toFloat() / total.toFloat()
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (palette.uppercaseLabels) slice.label.uppercase() else slice.label,
                                style = VaultType.micro(),
                                fontSize = rowLabelSp.sp,
                                lineHeight = (rowLabelSp * 1.2f).sp,
                                color = VaultContrast.ensure(
                                    // Ink measured against what is really under it: the fill only
                                    // reaches the head of the row once a category owns a quarter of
                                    // the vault, and a dimmed fill is 26% ink over the card, which
                                    // is the card. Never a raw accent, never a new colour.
                                    if (active) slice.color else VaultColor.textStrong,
                                    if (share > BAR_LABEL_COVER_OF_RAIL && !dimmed) slice.color else cardBed
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 3.dp)
                            )
                            Text(
                                text = slice.value.toString() + " / " +
                                    (share * 100f).roundToInt().toString() + "%",
                                style = VaultType.micro(),
                                fontSize = rowLabelSp.sp,
                                lineHeight = (rowLabelSp * 1.2f).sp,
                                color = VaultContrast.ensure(
                                    VaultColor.textStrong,
                                    if (share > BAR_VALUE_COVER_OF_RAIL && !dimmed) slice.color else cardBed
                                ),
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = 3.dp)
                            )
                        }
                    }
                }
            }

            // A solid pie has no hole to write in, so the wedge brands get their own medallion
            // instead of text sitting on coloured ink. It is the same proportion the caption was
            // measured against, so the readout can never spill off its bed.
            if (form == PieForm.WEDGE) {
                Box(
                    modifier = Modifier
                        .size(radiusDp * 2f * WEDGE_MEDALLION_OF_RADIUS)
                        .clip(CircleShape)
                        .background(palette.abyssSurface)
                        .border(1.dp, palette.glassBorder, CircleShape)
                )
            }

            // The 3D pie keeps a readout, but it needs a bed of its own shape: the top face scaled
            // about its centre. Same promise as the medallion above, in an ellipse instead of a
            // circle, and it is measured by the same pieHoleRadius the caption below is capped to,
            // so the text can never reach the shaded wall hanging under the face.
            if (form == PieForm.WEDGE_3D) {
                Box(
                    modifier = Modifier
                        .size(
                            width = frame3d.bedRx.dp * 2f,
                            height = frame3d.bedRy.dp * 2f
                        )
                        .clip(CircleShape)
                        .background(palette.abyssSurface)
                        .border(1.dp, palette.glassBorder, CircleShape)
                )
            }

            // The in-hole readout is capped to the largest square that fits the hole, so no slice
            // count, label length or total can push it onto the ring.
            //
            // The two linear forms are gated out here, not deleted: their ink is the whole middle
            // of the box, so their readout is the line above the canvas instead.
            if (readoutSitsInInk) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(captionWidthDp)
                ) {
                    val selected = slices.getOrNull(selectedIndex)
                    // Ink drawn over the card bed: the selected slice's brand colour goes
                    // through the AA guard instead of shipping raw (it read under 4.5:1).
                    val bed = palette.abyssSurface
                    Text(
                        text = selected?.label?.uppercase() ?: "TOTAL",
                        style = VaultType.micro().copy(
                            fontSize = microSizeSp.sp,
                            lineHeight = (microSizeSp * 1.25f).sp,
                            letterSpacing = if (palette.monoTags) 1.6.sp else 1.1.sp,
                            fontWeight = if (palette.uppercaseLabels) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = selected?.let { VaultContrast.ensure(it.color, bed) } ?: VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = (selected?.value ?: total).toString(),
                        style = VaultType.hero().copy(
                            fontSize = heroSizeSp.sp,
                            lineHeight = (heroSizeSp * 1.15f).sp,
                            fontWeight = if (palette.hardOutline) FontWeight.Black else FontWeight.SemiBold
                        ),
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (selected != null) {
                            "${((selected.value.toFloat() / total.coerceAtLeast(1)) * 100).roundToInt()}%"
                        } else if (total == 0) {
                            "not sealed yet"
                        } else {
                            "sealed"
                        },
                        style = VaultType.micro(),
                        fontSize = microSizeSp.sp,
                        lineHeight = (microSizeSp * 1.25f).sp,
                        // Muted caption ink (the palette textMuted role, AA-guarded) — never a
                        // raw accent and never a hardcoded colour.
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // The total gets its own line *outside* the ring. Nothing about the chart's geometry can
        // overlap it, which is the guarantee the in-hole readout alone could never make.
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                style = VaultType.micro(),
                color = VaultColor.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(diameter)
            )
        }
    }
}

private fun DrawScope.drawEmptyTrack(palette: VaultPalette, strokeWidth: Dp) {
    val radius = min(size.width, size.height) / 2f
    val geometry = ringGeometry(
        radius = radius,
        strokeWidth = strokeWidth.toPx(),
        innerRatio = palette.pieInnerRatio,
        liftEnabled = false,
        safety = EDGE_SAFETY_PX
    )
    drawCircle(
        color = palette.pieTrack,
        radius = geometry.centreRadius,
        center = Offset(radius, radius),
        style = Stroke(width = geometry.thickness, cap = StrokeCap.Butt)
    )
}

private fun cap(palette: VaultPalette): StrokeCap =
    if (palette.pieRoundCaps) StrokeCap.Round else StrokeCap.Butt

/**
 * Pixel room kept between the widest slice and the canvas edge. Compose clips a canvas to its own
 * bounds, so an arc that exactly reaches the radius loses its outer antialiased row of pixels —
 * the lifted slice read as "cut off" even when the arithmetic said it fit.
 */
private const val EDGE_SAFETY_PX = 1f

/**
 * The same room in dp, for the pass that sizes the caption outside a `DrawScope`. It is a hair
 * tighter than the pixel guard, so the caption box is always sized against a hole at least as
 * small as the one actually drawn.
 */
private const val EDGE_SAFETY_DP = 0.5f

/** Flat MIUI / Fluent pie: solid wedges split by a hairline of card surface. */
private fun DrawScope.drawWedges(
    slices: List<PieSlice>,
    total: Int,
    selected: Int,
    palette: VaultPalette,
    progress: Float
) {
    val radius = min(size.width, size.height) / 2f
    val centre = Offset(radius, radius)
    val separatorPx = palette.pieSeparator.toPx()
    // The separator token is the wedge brand's own divider: when it is set, wedges meet edge to
    // edge and a hairline of the card surface is ruled between them. When it is 0 the pie is cut
    // with an angular gap instead.
    val gap = if (separatorPx > 0f) 0f
    else pieGapFor(palette.pieGapDegrees, slices.count { it.value > 0 })
    val spans = pieSpans(slices.map { it.value })
    // A lifted wedge slides along its bisector, so the wedges are drawn one lift short of the edge
    // and the slide has somewhere to go.
    val lift = if (palette.pieSelectedLift) radius * SELECTED_LIFT_OF_RADIUS else 0f
    val drawRadius = (radius - EDGE_SAFETY_PX - lift).coerceAtLeast(1f)
    val separatorInk = palette.abyssSurface
    slices.forEachIndexed { index, slice ->
        if (slice.value <= 0) return@forEachIndexed
        val span = spans[index]
        val visible = pieInkDegrees(span.shareDegrees, gap, 0f, progress)
        if (visible <= 0f) return@forEachIndexed
        val mid = Math.toRadians((span.startDegrees + span.shareDegrees / 2f).toDouble())
        val slide = if (selected == index && palette.pieSelectedLift) lift else 0f
        val wedgeCentre = Offset(
            centre.x + (cos(mid) * slide).toFloat(),
            centre.y + (sin(mid) * slide).toFloat()
        )
        drawArc(
            brush = sliceBrush(slice.color, palette, radius, dim = selected != -1 && selected != index),
            startAngle = span.startDegrees + gap / 2f,
            sweepAngle = visible,
            useCenter = true,
            topLeft = Offset(wedgeCentre.x - drawRadius, wedgeCentre.y - drawRadius),
            size = Size(drawRadius * 2f, drawRadius * 2f)
        )
        if (separatorPx > 0f) {
            // Both of this wedge's own boundaries, ruled from the centre the wedge was drawn from:
            // when a wedge slides outward its hairlines travel with it instead of being left
            // behind as a diagonal streak across the neighbours.
            ruleSeparator(wedgeCentre, span.startDegrees, drawRadius, separatorPx, separatorInk)
            ruleSeparator(
                wedgeCentre,
                span.startDegrees + span.shareDegrees,
                drawRadius,
                separatorPx,
                separatorInk
            )
        }
    }
}

/** One wedge boundary: a hairline of card surface from the pie centre out to the rim. */
private fun DrawScope.ruleSeparator(
    from: Offset,
    degrees: Float,
    length: Float,
    strokeWidth: Float,
    ink: Color
) {
    val radians = Math.toRadians(degrees.toDouble())
    drawLine(
        color = ink,
        start = from,
        end = Offset(
            from.x + (cos(radians) * length).toFloat(),
            from.y + (sin(radians) * length).toFloat()
        ),
        strokeWidth = strokeWidth
    )
}

/** iOS / One UI / Funtouch ring: one stroked track, arcs separated by gaps. */
private fun DrawScope.drawRing(
    slices: List<PieSlice>,
    total: Int,
    selected: Int,
    palette: VaultPalette,
    progress: Float,
    strokeWidth: Dp
) {
    val radius = min(size.width, size.height) / 2f
    val centre = Offset(radius, radius)
    val live = slices.count { it.value > 0 }
    val geometry = ringGeometry(
        radius = radius,
        strokeWidth = strokeWidth.toPx(),
        innerRatio = palette.pieInnerRatio,
        liftEnabled = palette.pieSelectedLift,
        safety = EDGE_SAFETY_PX
    )
    drawArc(
        color = palette.pieTrack,
        startAngle = PIE_START_DEGREES,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(radius - geometry.centreRadius, radius - geometry.centreRadius),
        size = Size(geometry.centreRadius * 2f, geometry.centreRadius * 2f),
        style = Stroke(width = geometry.thickness, cap = StrokeCap.Butt)
    )
    val gap = pieGapFor(palette.pieGapDegrees, live)
    val spans = pieSpans(slices.map { it.value })
    val singleSlice = live == 1
    slices.forEachIndexed { index, slice ->
        if (slice.value <= 0) return@forEachIndexed
        val span = spans[index]
        val lift = if (selected == index && palette.pieSelectedLift) geometry.lift else 0f
        val r = geometry.centreRadius + lift
        val w = geometry.thickness
        // A round cap reaches past the arc end. It may only claim a third of the slice's own slot,
        // so it can never cross into the neighbour's gap — but it always gets trimmed from the ink,
        // which is what makes the caps read as caps instead of as an overlap.
        val overhang = if (palette.pieRoundCaps && !singleSlice) {
            capOverhangDegrees(w, r, span.shareDegrees / 3f)
        } else {
            0f
        }
        val visible = pieInkDegrees(span.shareDegrees, gap, overhang, progress)
        if (singleSlice) {
            // One category is a closed ring: sweep the full dial with butt caps so there is no seam
            // for a round cap to bulge at — that bulge was the single-slice ColorOS artefact.
            drawArc(
                brush = sliceBrush(slice.color, palette, radius, dim = false),
                startAngle = PIE_START_DEGREES,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(radius - r, radius - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = w, cap = StrokeCap.Butt)
            )
        } else {
            if (visible <= 0f) return@forEachIndexed
            drawArc(
                brush = sliceBrush(slice.color, palette, radius, dim = selected != -1 && selected != index),
                startAngle = span.startDegrees + gap / 2f + overhang,
                sweepAngle = visible,
                useCenter = false,
                topLeft = Offset(radius - r, radius - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = w, cap = cap(palette))
            )
        }
    }
    if (palette.glow) {
        drawCircle(
            color = palette.glassHighlight,
            radius = radius - 1f,
            center = centre,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/** ColorOS / EMUI stack: each category keeps its own concentric radius. */
private fun DrawScope.drawStackedRings(
    slices: List<PieSlice>,
    total: Int,
    selected: Int,
    palette: VaultPalette,
    progress: Float
) {
    val radius = min(size.width, size.height) / 2f
    val liveIndices = slices.indices.filter { slices[it].value > 0 }
    if (liveIndices.isEmpty()) return
    val bandGapPx = STACKED_BAND_GAP_DP.dp.toPx()
    val trackInsetPx = STACKED_TRACK_INSET_DP.dp.toPx()
    // Only the selected band swells, and only when the theme asks for it: aqua ships
    // pieSelectedLift = false, and the old code grew the outermost band regardless.
    val swollen = if (palette.pieSelectedLift) liveIndices.indexOf(selected) else -1
    val bands = stackedBands(
        radius = radius,
        innerRatio = palette.pieInnerRatio,
        bandCount = liveIndices.size,
        bandGap = bandGapPx,
        safety = EDGE_SAFETY_PX,
        swollenPosition = swollen
    )
    val gap = pieGapFor(palette.pieGapDegrees, liveIndices.size)
    val spans = pieSpans(slices.map { it.value })
    val singleBand = liveIndices.size == 1
    liveIndices.forEachIndexed { position, index ->
        val slice = slices[index]
        val band = bands[position]
        val span = spans[index]
        val w = band.stroke
        val overhang = if (palette.pieRoundCaps && !singleBand) {
            // Trimmed to a third of this band's own slot, and to the band's own centre radius —
            // a fat cap on the tight inner band used to overshoot into the gap and read as a blob.
            capOverhangDegrees(w, band.centreRadius, span.shareDegrees / 3f)
        } else {
            0f
        }
        val visible = pieInkDegrees(span.shareDegrees, gap, overhang, progress)
        drawArc(
            color = palette.pieTrack,
            startAngle = PIE_START_DEGREES,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(radius - band.centreRadius, radius - band.centreRadius),
            size = Size(band.centreRadius * 2f, band.centreRadius * 2f),
            style = Stroke(width = (band.slot - trackInsetPx).coerceAtLeast(0.5f), cap = StrokeCap.Butt)
        )
        if (singleBand) {
            drawArc(
                brush = sliceBrush(slice.color, palette, radius, dim = false),
                startAngle = PIE_START_DEGREES,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(radius - band.centreRadius, radius - band.centreRadius),
                size = Size(band.centreRadius * 2f, band.centreRadius * 2f),
                style = Stroke(width = w, cap = StrokeCap.Butt)
            )
        } else if (visible > 0f) {
            drawArc(
                brush = sliceBrush(
                    slice.color,
                    palette,
                    radius,
                    dim = selected != -1 && selected != index
                ),
                startAngle = span.startDegrees + gap / 2f + overhang,
                sweepAngle = visible,
                useCenter = false,
                topLeft = Offset(radius - band.centreRadius, radius - band.centreRadius),
                size = Size(band.centreRadius * 2f, band.centreRadius * 2f),
                style = Stroke(width = w, cap = cap(palette))
            )
        }
    }
}


/**
 * Solstice and Signal Red: the flat dial tipped toward the viewer. The top face is the existing
 * wedge geometry under one affine squash, standing on an extruded side wall painted with the same
 * slice's colour multiplied down, and the readout's bed is a similar ellipse on the face's own
 * centre. Nothing about the spans, the gap or the lift is re-derived - this form is the flat pie
 * projected, which is what lets a tap keep using the flat pie's angle maths.
 */
private fun DrawScope.drawWedges3d(
    slices: List<PieSlice>,
    selected: Int,
    palette: VaultPalette,
    progress: Float
) {
    val side = min(size.width, size.height)
    val frame = pie3dGeometry(side, palette.pieSelectedLift, EDGE_SAFETY_PX)
    val separatorPx = palette.pieSeparator.toPx()
    val gap = if (separatorPx > 0f) 0f
    else pieGapFor(palette.pieGapDegrees, slices.count { it.value > 0 })
    val spans = pieSpans(slices.map { it.value })
    val separatorInk = palette.abyssSurface
    // The pedestal first: the ellipse the walls end on, in the theme's own unfilled-track ink, so
    // the gaps between wedges show depth rather than the card through them.
    drawArc(
        color = palette.pieTrack,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = true,
        topLeft = Offset(frame.centreX - frame.rx, frame.topCentreY + frame.depth - frame.ry),
        size = Size(frame.rx * 2f, frame.ry * 2f)
    )
    // Walls next, so the lit face always covers their top edge and the extrusion reads as depth.
    slices.forEachIndexed { index, slice ->
        if (slice.value <= 0) return@forEachIndexed
        val span = spans[index]
        val visible = pieInkDegrees(span.shareDegrees, gap, 0f, progress)
        if (visible <= 0f) return@forEachIndexed
        drawWedge3dWall(
            slice = slice,
            frame = frame,
            startDegrees = span.startDegrees,
            sweep = visible,
            centre = wedge3dCentre(frame, span, index, selected, palette),
            dim = selected != -1 && selected != index
        )
    }
    slices.forEachIndexed { index, slice ->
        if (slice.value <= 0) return@forEachIndexed
        val span = spans[index]
        val visible = pieInkDegrees(span.shareDegrees, gap, 0f, progress)
        if (visible <= 0f) return@forEachIndexed
        val centre = wedge3dCentre(frame, span, index, selected, palette)
        drawArc(
            brush = sliceBrush(slice.color, palette, side / 2f, dim = selected != -1 && selected != index),
            startAngle = span.startDegrees + gap / 2f,
            sweepAngle = visible,
            useCenter = true,
            topLeft = Offset(centre.x - frame.rx, centre.y - frame.ry),
            size = Size(frame.rx * 2f, frame.ry * 2f)
        )
        if (separatorPx > 0f) {
            ruleSeparator3d(centre, span.startDegrees, frame, separatorPx, separatorInk)
            ruleSeparator3d(centre, span.startDegrees + span.shareDegrees, frame, separatorPx, separatorInk)
        }
    }
}

/**
 * Where one slice of the 3D dial is drawn: the face's own centre, or slid along its bisector when
 * the theme lifts the selected slice. The slide is projected like everything else on this face -
 * the y share is squashed too - so a lifted slice travels along the ellipse instead of breaking
 * out of the box, and it never claims more than the `lift` the frame already reserved.
 */
private fun wedge3dCentre(
    frame: Pie3dGeometry,
    span: PieSpan,
    index: Int,
    selected: Int,
    palette: VaultPalette
): Offset {
    if (index != selected || !palette.pieSelectedLift) return Offset(frame.centreX, frame.topCentreY)
    val mid = Math.toRadians((span.startDegrees + span.shareDegrees / 2f).toDouble())
    val slide = frame.rx * SELECTED_LIFT_OF_RADIUS
    return Offset(
        frame.centreX + (cos(mid) * slide).toFloat(),
        frame.topCentreY + (sin(mid) * slide * PIE_3D_SQUASH).toFloat()
    )
}

/**
 * The side wall one wedge shows: the part of its own arc that faces the viewer (3 o'clock round
 * through 6 to 9 o'clock, the only half an extrusion can reveal), swept down by the depth and
 * closed. An arc that never reaches the front half has nothing to show, which is why the back of
 * the pie reads as a flat rim.
 */
private fun DrawScope.drawWedge3dWall(
    slice: PieSlice,
    frame: Pie3dGeometry,
    startDegrees: Float,
    sweep: Float,
    centre: Offset,
    dim: Boolean
) {
    val from = max(startDegrees, 0f)
    val to = min(startDegrees + sweep, 180f)
    if (to <= from) return
    // Shading is arithmetic on the slice's own channels: same hue, same saturation, less light.
    // No new constant, and so nothing for the hue scan to find.
    val shaded = Color(
        red = slice.color.red * PIE_3D_WALL_SHADE,
        green = slice.color.green * PIE_3D_WALL_SHADE,
        blue = slice.color.blue * PIE_3D_WALL_SHADE,
        alpha = slice.color.alpha
    )
    val face = Rect(centre.x - frame.rx, centre.y - frame.ry, centre.x + frame.rx, centre.y + frame.ry)
    val base = Rect(face.left, face.top + frame.depth, face.right, face.bottom + frame.depth)
    val top = ellipsePoint(face, from)
    val bottom = ellipsePoint(base, to)
    val wall = Path().apply {
        moveTo(top.x, top.y)
        arcTo(face, from, to - from, forceMoveTo = false)
        lineTo(bottom.x, bottom.y)
        arcTo(base, to, from - to, forceMoveTo = false)
        close()
    }
    drawPath(wall, color = if (dim) shaded.copy(alpha = shaded.alpha * 0.26f) else shaded)
}

/** A point on an ellipse's boundary, in the same degrees the dial is inked in. */
private fun ellipsePoint(oval: Rect, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(
        oval.center.x + (cos(radians) * oval.width / 2f).toFloat(),
        oval.center.y + (sin(radians) * oval.height / 2f).toFloat()
    )
}

/** One wedge boundary on the squashed face: a hairline of card surface from its centre to the rim. */
private fun DrawScope.ruleSeparator3d(
    from: Offset,
    degrees: Float,
    frame: Pie3dGeometry,
    strokeWidth: Float,
    ink: Color
) {
    val radians = Math.toRadians(degrees.toDouble())
    drawLine(
        color = ink,
        start = from,
        end = Offset(
            from.x + (cos(radians) * frame.rx).toFloat(),
            from.y + (sin(radians) * frame.ry).toFloat()
        ),
        strokeWidth = strokeWidth
    )
}

/**
 * Neon Lime and Volt Aura: one horizontal rail, its segments proportional to the records behind
 * them, hard gaps between them and a faint unfilled track the whole way. No dial at all, so no
 * hole, no angle and no centre readout - the tap maths reads x across the very same slots.
 */
private fun DrawScope.drawSegmentedRail(
    slices: List<PieSlice>,
    selected: Int,
    palette: VaultPalette,
    progress: Float,
    strokeWidth: Dp
) {
    val side = min(size.width, size.height)
    val separatorPx = palette.pieSeparator.toPx()
    val rail = railGeometry(side, strokeWidth.toPx(), EDGE_SAFETY_PX)
    val slots = segmentedSlots(
        side = side,
        strokeWidthPx = strokeWidth.toPx(),
        gapDegrees = palette.pieGapDegrees,
        separatorPx = separatorPx,
        values = slices.map { it.value },
        safety = EDGE_SAFETY_PX
    )
    if (slots.isEmpty()) return
    val corner = railCornerRadius(palette, rail.thickness)
    drawRoundRect(
        color = palette.pieTrack,
        topLeft = Offset(rail.left, rail.centreY - rail.thickness / 2f),
        size = Size(rail.width, rail.thickness),
        cornerRadius = corner
    )
    // One left-to-right reveal: the rail as a whole owns `progress` of its inked width and each
    // segment takes the room that is still left in order, so the bar grows instead of blinking.
    var room = slots.sumOf { it.inkWidth.toDouble() }.toFloat() * progress.coerceIn(0f, 1f)
    slices.forEachIndexed { index, slice ->
        val slot = slots.getOrNull(index) ?: return@forEachIndexed
        if (slot.slotWidth <= 0f) return@forEachIndexed
        val inked = min(slot.inkWidth, room)
        room -= inked
        if (inked <= 0f) return@forEachIndexed
        // The selected segment is raised: thicker than the rail, still inside the box.
        val thickness = raisedSegmentThickness(
            thickness = rail.thickness,
            liftEnabled = selected == index && palette.pieSelectedLift,
            halfSide = side / 2f,
            safety = EDGE_SAFETY_PX
        )
        drawRoundRect(
            brush = sliceBrush(
                slice.color,
                palette,
                rail.width,
                dim = selected != -1 && selected != index,
                linearFrom = Offset(slot.start, rail.centreY),
                linearTo = Offset(slot.start + slot.inkWidth, rail.centreY)
            ),
            topLeft = Offset(slot.start, rail.centreY - thickness / 2f),
            size = Size(inked, thickness),
            cornerRadius = railCornerRadius(palette, thickness)
        )
    }
    if (separatorPx > 0f) {
        // A brand that rules separators instead of leaving room: the segments meet edge to edge and
        // a hairline of the card between them is what makes the gap read as a gap, exactly as the
        // flat wedge form does it.
        val top = rail.centreY - rail.thickness / 2f
        val ink = palette.abyssSurface
        for (index in 1 until slots.size) {
            if (slots[index].slotWidth <= 0f || slots[index - 1].slotWidth <= 0f) continue
            drawLine(
                color = ink,
                start = Offset(slots[index].start, top),
                end = Offset(slots[index].start, top + rail.thickness),
                strokeWidth = separatorPx
            )
        }
    }
    if (palette.glow) {
        drawRoundRect(
            color = palette.glassHighlight,
            topLeft = Offset(rail.left, rail.centreY - rail.thickness / 2f),
            size = Size(rail.width, rail.thickness),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * Midnight Pop, Neon Cyan and Neon Plasma: a horizontal bar chart, one row per live category, each
 * row a full-width track with a fill that is that category's share of the total. The rows stack
 * inside the same square the dials used, first category at the bottom, and their words are painted
 * by `VaultPieChart` on the row they measure - this form has no hole to write in.
 */
private fun DrawScope.drawBars(
    slices: List<PieSlice>,
    total: Int,
    selected: Int,
    palette: VaultPalette,
    progress: Float,
    strokeWidth: Dp
) {
    val side = min(size.width, size.height)
    val live = slices.indices.filter { slices[it].value > 0 }
    if (live.isEmpty() || total <= 0) return
    val rail = railGeometry(side, strokeWidth.toPx(), EDGE_SAFETY_PX)
    val gapPx = barRowGapPx(side, live.size, palette.pieGapDegrees, BAR_ROW_GAP_DP.dp.toPx())
    // Only the selected row grows, and only when the theme asks for it, exactly like a stacked band.
    val swollen = if (palette.pieSelectedLift) live.indexOf(selected) else -1
    val rows = barRows(side, live.size, gapPx, rail.thickness, swollen)
    // Drawn bottom-up: the tap maths walks the same reversed list, so ink and hit area agree.
    live.reversed().forEachIndexed { position, index ->
        val slice = slices[index]
        val row = rows.getOrNull(position) ?: return@forEachIndexed
        val half = row.thickness / 2f
        val corner = railCornerRadius(palette, row.thickness)
        val dim = selected != -1 && selected != index
        drawRoundRect(
            color = palette.pieTrack,
            topLeft = Offset(rail.left, row.centreY - half),
            size = Size(rail.width, row.thickness),
            cornerRadius = corner
        )
        val share = slice.value.toFloat() / total.toFloat()
        val fill = rail.width * share * barRowProgress(progress, position, live.size)
        if (fill > 0f) {
            drawRoundRect(
                brush = sliceBrush(
                    slice.color,
                    palette,
                    rail.width,
                    dim = dim,
                    linearFrom = Offset(rail.left, row.centreY),
                    linearTo = Offset(rail.left + rail.width * share, row.centreY)
                ),
                topLeft = Offset(rail.left, row.centreY - half),
                size = Size(fill, row.thickness),
                cornerRadius = corner
            )
        }
    }
    val separatorPx = palette.pieSeparator.toPx()
    if (separatorPx > 0f) {
        // Between two rows, in the same card ink the wedge form rules its boundaries with.
        for (position in 1 until rows.size) {
            val boundary = rows[position].centreY - rows[position].slotHeight / 2f
            drawLine(
                color = palette.abyssSurface,
                start = Offset(rail.left, boundary),
                end = Offset(rail.left + rail.width, boundary),
                strokeWidth = separatorPx
            )
        }
    }
}

/**
 * Rounded or square segment ends: `pieRoundCaps` read across a bar instead of around a dial. The
 * radius is always the thickness's own half, so a fat rail still ends in a true cap.
 */
private fun railCornerRadius(palette: VaultPalette, thickness: Float): CornerRadius =
    if (palette.pieRoundCaps) CornerRadius(thickness / 2f, thickness / 2f) else CornerRadius(0f, 0f)

/**
 * Nothing sealed yet, in the linear grammar: the same rail, entirely unfilled. The dial forms keep
 * drawing their placeholder ring; this is only what a segmented bar or a bar chart shows while the
 * vault is empty, and it draws no segments at all, so there is nothing for a tap to answer.
 */
private fun DrawScope.drawEmptyRail(palette: VaultPalette, strokeWidth: Dp) {
    val side = min(size.width, size.height)
    val rail = railGeometry(side, strokeWidth.toPx(), EDGE_SAFETY_PX)
    drawRoundRect(
        color = palette.pieTrack,
        topLeft = Offset(rail.left, rail.centreY - rail.thickness / 2f),
        size = Size(rail.width, rail.thickness),
        cornerRadius = railCornerRadius(palette, rail.thickness)
    )
}

/** The radial gap left between two stacked bands' track rings, in dp. */
private const val STACKED_TRACK_INSET_DP = 1f

/**
 * A slice is flat ink for the solid-pie brands and a short radial wash for the
 * glass ones, so the chart never reads as the same object in a different colour.
 *
 * The two linear forms pass the ends of their own axis in [linearFrom] and [linearTo]: a dial's
 * radial gradient laid across a bar reads as a smudge, so the same two stops are strung along the
 * rail instead. With no axis passed - every dial call site - the maths below is byte for byte what
 * it always was.
 */
private fun DrawScope.sliceBrush(
    color: Color,
    palette: VaultPalette,
    radius: Float,
    dim: Boolean = false,
    linearFrom: Offset? = null,
    linearTo: Offset? = null
): Brush {
    val faded = if (dim) color.copy(alpha = 0.26f) else color
    if (linearFrom != null && linearTo != null) {
        if (dim || !palette.pieGradient) {
            return Brush.linearGradient(listOf(faded, faded), start = linearFrom, end = linearTo)
        }
        return Brush.linearGradient(
            colors = listOf(lerp(faded, VaultColor.sheen, 0.34f), faded),
            start = linearFrom,
            end = linearTo
        )
    }
    if (dim || !palette.pieGradient) return Brush.sweepGradient(listOf(faded, faded))
    // Lit from the middle outward, the glass brands' read on a chart.
    return Brush.radialGradient(
        colors = listOf(lerp(faded, VaultColor.sheen, 0.34f), faded),
        center = Offset(radius, radius),
        radius = radius
    )
}
