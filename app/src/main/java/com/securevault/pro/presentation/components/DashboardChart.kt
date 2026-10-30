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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
//   pieForm         — wedge / ring / stacked ring
//   pieGapDegrees   — empty degrees between two arcs (never paid for a single slice)
//   pieRoundCaps    — stroke caps, with the angular room a round cap claims trimmed off the arc
//   pieSeparator    — hairline of card surface between wedge boundaries
//   pieInnerRatio   — how thin the ring is, and how big the hole the caption lives in is
//   pieGradient     — radial wash per slice
//   pieTrackAlpha   — the unfilled ring behind every band
//   pieLegend       — the key shape drawn beside each row
//   pieSelectedLift — the selected slice steps outward, inside the canvas, always
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

    Column(
        modifier = modifier.width(diameter),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(diameter)
                    .pointerInput(slices, total, selectedIndex) {
                        detectTapGestures { tap ->
                            if (total <= 0) return@detectTapGestures
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val dx = tap.x - centre.x
                            val dy = tap.y - centre.y
                            if (sqrt(dx * dx + dy * dy) > min(size.width, size.height) / 2f) {
                                return@detectTapGestures
                            }
                            val index = pieSliceAtTap(
                                Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat(),
                                slices.map { it.value }
                            )
                            if (index < 0) return@detectTapGestures
                            onSliceSelected(if (selectedIndex == index) -1 else index)
                        }
                    }
            ) {
                if (total <= 0) {
                    drawEmptyTrack(palette, strokeWidth)
                    return@Canvas
                }
                when (palette.pieForm) {
                    PieForm.WEDGE -> drawWedges(slices, total, selectedIndex, palette, progress.value)
                    PieForm.RING -> drawRing(slices, total, selectedIndex, palette, progress.value, strokeWidth)
                    PieForm.STACKED_RING -> drawStackedRings(
                        slices, total, selectedIndex, palette, progress.value
                    )
                }
            }

            // A solid pie has no hole to write in, so the wedge brands get their own medallion
            // instead of text sitting on coloured ink. It is the same proportion the caption was
            // measured against, so the readout can never spill off its bed.
            if (palette.pieForm == PieForm.WEDGE) {
                Box(
                    modifier = Modifier
                        .size(radiusDp * 2f * WEDGE_MEDALLION_OF_RADIUS)
                        .clip(CircleShape)
                        .background(palette.abyssSurface)
                        .border(1.dp, palette.glassBorder, CircleShape)
                )
            }

            // The in-hole readout is capped to the largest square that fits the hole, so no slice
            // count, label length or total can push it onto the ring.
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

/** The radial gap left between two stacked bands' track rings, in dp. */
private const val STACKED_TRACK_INSET_DP = 1f

/**
 * A slice is flat ink for the solid-pie brands and a short radial wash for the
 * glass ones, so the chart never reads as the same object in a different colour.
 */
private fun DrawScope.sliceBrush(
    color: Color,
    palette: VaultPalette,
    radius: Float,
    dim: Boolean = false
): Brush {
    val faded = if (dim) color.copy(alpha = 0.26f) else color
    if (dim || !palette.pieGradient) return Brush.sweepGradient(listOf(faded, faded))
    // Lit from the middle outward, the glass brands' read on a chart.
    return Brush.radialGradient(
        colors = listOf(lerp(faded, VaultColor.sheen, 0.34f), faded),
        center = Offset(radius, radius),
        radius = radius
    )
}
