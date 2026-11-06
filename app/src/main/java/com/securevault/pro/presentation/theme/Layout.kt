package com.securevault.pro.presentation.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/* =====================================================================================
 * LAYOUT GRAMMAR
 *
 * `VaultLayout` used to be read by exactly one chip label, which is why every theme looked
 * like the same screen in a different colour. The functions here are the replacement: pure,
 * unit-tested composition instructions that say *how the screen is built* — how many columns,
 * which cells are wide, how far apart the sections breathe, how the dock is cut, where the
 * navigation indicator travels.
 *
 * Nothing here invents a colour. The only palette a structure reads is the one already on screen,
 * and only the two motion layers below touch it at all — the bloom and the sheen both burn off the
 * brand accent that palette already ships. Per-theme geometry is what moves here, not hue: the
 * board's icon geometry stays identical across themes (see ThemeTokensTest), while the *structure*
 * around it is what differs.
 * =====================================================================================
 */

/** The shape of one board cell, and therefore the content arrangement it draws. */
enum class TileKind {
    /** Icon above, label below — the default plate. */
    PLATE,
    /** Double-width, icon beside its text, holds the most-used module. */
    FEATURE,
    /** Full-width closing bar of a bento board. */
    BAR,
    /** Full-width intent rail: flat leading edge, label and hint inline. */
    ROW,
    /** A cell that carries no card of its own — the quiet grid under an agentic list. */
    MICRO
}

/** One cell of the board: which module it holds, how many weight units it owns, how it draws. */
data class BoardCell(val index: Int, val span: Int, val kind: TileKind) {
    init {
        require(span >= 1) { "a cell must own at least one column" }
    }
}

/** The widest board the packer will build, so a corrupt override can never ask for more. */
private const val MAX_BOARD_COLUMNS = 6

/** How many weight units one row of a board owns. Every row always fills them exactly. */
fun boardColumns(layout: VaultLayout): Int = when (layout) {
    VaultLayout.CLASSIC, VaultLayout.BENTO, VaultLayout.AGENTIC,
    VaultLayout.STRIP, VaultLayout.TRIAD -> 4
    VaultLayout.ADAPTIVE, VaultLayout.LEDGER, VaultLayout.DUO, VaultLayout.SPLIT -> 2
    VaultLayout.GENERATIVE, VaultLayout.MOSAIC, VaultLayout.COLUMN, VaultLayout.GALLERY -> 3
    VaultLayout.COMPACT -> 5
    VaultLayout.DENSE -> 6
}

/**
 * The span of each cell, in board order. Bento leads with one double-width feature and closes
 * with a full-width bar; agentic puts its first four modules on rails of their own; adaptive
 * promotes two, and classic and clay keep every cell identical.
 */
internal fun planSpans(layout: VaultLayout, count: Int, columns: Int): List<Int> = when (layout) {
    VaultLayout.CLASSIC, VaultLayout.GENERATIVE, VaultLayout.COMPACT,
    VaultLayout.DENSE, VaultLayout.LEDGER, VaultLayout.DUO,
    VaultLayout.SPLIT -> List(count) { 1 }
    VaultLayout.BENTO -> when {
        count <= 1 -> listOf(columns)
        else -> List(count) { index ->
            when (index) {
                0 -> 2
                count - 1 -> if (count == 2) 1 else columns
                else -> 1
            }
        }
    }
    VaultLayout.AGENTIC -> List(count) { index -> if (index < 4) columns else 1 }
    VaultLayout.ADAPTIVE -> List(count) { index -> if (index < 2) columns else 1 }
    // Mosaic: a double-width cell beside a single, alternating all the way down the board.
    VaultLayout.MOSAIC -> List(count) { index -> if (index % 2 == 0) 2 else 1 }
    // Column: one full-width opener, then equal plates.
    VaultLayout.COLUMN -> List(count) { index -> if (index == 0) columns else 1 }
    // Gallery: a single beside a double, mirrored against Mosaic.
    VaultLayout.GALLERY -> List(count) { index -> if (index % 2 == 0) 1 else 2 }
    // Strip: a full-width rail, then double-width feature pairs.
    VaultLayout.STRIP -> List(count) { index -> if (index == 0) columns else 2 }
    // Triad: two double-width cells to a row, six rows for twelve modules.
    VaultLayout.TRIAD -> List(count) { 2 }
}

private fun kindFor(layout: VaultLayout, index: Int, span: Int, count: Int, columns: Int): TileKind =
    when (layout) {
        // A plate layout that hands one cell the whole pane closes on a bar, not on a giant square.
        VaultLayout.CLASSIC, VaultLayout.GENERATIVE, VaultLayout.DENSE ->
            if (span >= columns) TileKind.BAR else TileKind.PLATE
        VaultLayout.COMPACT -> if (span >= columns) TileKind.BAR else TileKind.MICRO
        VaultLayout.LEDGER -> TileKind.ROW
        VaultLayout.DUO -> TileKind.FEATURE
        VaultLayout.SPLIT -> if (index % 2 == 0) TileKind.FEATURE else TileKind.PLATE
        VaultLayout.BENTO -> when {
            span >= columns -> TileKind.BAR
            index == 0 && span > 1 -> TileKind.FEATURE
            else -> TileKind.PLATE
        }
        VaultLayout.AGENTIC -> if (span >= columns) TileKind.ROW else TileKind.MICRO
        VaultLayout.ADAPTIVE -> if (span >= columns) TileKind.FEATURE else TileKind.PLATE
        VaultLayout.COLUMN -> if (span >= columns) TileKind.ROW else TileKind.PLATE
        VaultLayout.STRIP -> if (span >= columns) TileKind.ROW else TileKind.FEATURE
        VaultLayout.MOSAIC, VaultLayout.GALLERY, VaultLayout.TRIAD -> when {
            span >= columns -> TileKind.BAR
            span > 1 -> TileKind.FEATURE
            else -> TileKind.PLATE
        }
    }

/**
 * The whole board as rows of cells, and the three guarantees the raw greedy packer could not make:
 *
 *  1. no row ever crosses the pane (spans total at most `columns`),
 *  2. no row leaves a hole in it either — the leftover weight is given to the narrowest cells of
 *     that row, one column at a time, so the board cannot end with a lone square beside a gap,
 *  3. a lone cell that is *not* the board's closer borrows the trailing cell of the row above, so
 *     a bento board does not stack two closing bars.
 *
 * The closing cell of a board is therefore allowed to take the whole pane, and `kindFor` reads it
 * as a bar. Cells are re-kinded after widening, which is what stops a two-column feature drawing
 * with a one-column plate's content.
 *
 * [columnsOverride] is the owner's per-theme column tuning. When it is non-null it replaces the
 * layout's own [boardColumns] *and* the span planner is fed the same number, so every guarantee
 * above still holds — only the pane's division changes. Null (the default) is the untouched path.
 */
fun boardGrid(
    layout: VaultLayout,
    count: Int,
    columnsOverride: Int? = null
): List<List<BoardCell>> {
    if (count <= 0) return emptyList()
    val columns = (columnsOverride ?: boardColumns(layout)).coerceIn(1, MAX_BOARD_COLUMNS)
    val spans = planSpans(layout, count, columns).map { it.coerceIn(1, columns) }.toMutableList()

    val rows = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    var used = 0
    spans.forEachIndexed { index, span ->
        if (used + span > columns && current.isNotEmpty()) {
            rows.add(current)
            current = mutableListOf()
            used = 0
        }
        current.add(index)
        used += span
    }
    if (current.isNotEmpty()) rows.add(current)

    rows.forEachIndexed { position, row ->
        if (position == 0 || position == rows.lastIndex) return@forEachIndexed
        if (row.size != 1 || spans[row[0]] >= columns) return@forEachIndexed
        val above = rows[position - 1]
        if (above.size < 2) return@forEachIndexed
        val borrow = above.last()
        if (spans[borrow] + spans[row[0]] > columns) return@forEachIndexed
        above.removeAt(above.size - 1)
        row.add(0, borrow)
    }

    for (row in rows) {
        var hole = columns - row.sumOf { spans[it] }
        while (hole > 0) {
            val narrowest = row.filter { spans[it] < columns }.minByOrNull { spans[it] }
            if (narrowest == null) break
            spans[narrowest] += 1
            hole -= 1
        }
    }

    return rows.map { row ->
        row.map { index ->
            val width = spans[index]
            BoardCell(index, width, kindFor(layout, index, width, count, columns))
        }
    }
}

/**
 * How much content a cell of the *measured* height can carry.
 *
 * The board hands each row its share of the pane with a weight, so a tile that asks for more than
 * it was given does not grow the row — it clips, and that is the defect the owner saw. This is the
 * negotiation, in the order the eye forgives: the reserved count band goes first, then the plate's
 * own padding, then the air between glyph and label, then the glyph, and the label size is the
 * last thing to move — never below the legibility floor. A label only takes a second line when the
 * height genuinely affords it, so nothing is ellipsised that could have been shown whole.
 */
data class CellFit(
    val padV: Dp,
    val iconSize: Dp,
    val labelSize: TextUnit,
    val labelLines: Int,
    val countBand: Boolean,
    val hint: Boolean,
    /** The air between the glyph and the label in a stacked cell. */
    val gap: Dp = 5.dp
) {
    /** The stacked kinds' own content height, so the fit can be proved rather than eyeballed. */
    fun stackedHeight(): Dp = stackedHeight(labelLines)

    fun stackedHeight(lines: Int): Dp =
        iconSize + gap + labelSize.value.lineHeight() * lines +
            (if (countBand) max(labelSize.value - 1f, 8f).lineHeight() + 3.dp else 0.dp)
}

private const val LABEL_FLOOR_SP = 9f
private const val GLYPH_FLOOR_DP = 16f

/** The absolute least a glyph may be cut to before the plate simply has to overflow. */
private const val GLYPH_HARD_FLOOR_DP = 10f

/** The fit for a cell of [height], given the palette's own glyph and label sizes. */
fun fitCell(kind: TileKind, height: Dp, icon: Dp, labelSize: TextUnit, padV: Dp): CellFit {
    val requested = labelSize.value
    val oneLine = requested.lineHeight()
    if (kind == TileKind.FEATURE || kind == TileKind.ROW || kind == TileKind.BAR) {
        // Inline kinds draw one line beside the glyph: the seat gives way, the text never does.
        var pad = padV.coerceAtMost(8.dp)
        var guard = 0
        while (oneLine > (height - pad * 2).coerceAtLeast(0.dp) && pad > 0.dp && guard++ < 40) pad -= 1.dp
        val room = (height - pad * 2).coerceAtLeast(0.dp)
        // The seat gives way to the room it was given: a floor the row cannot pay for would clip
        // the glyph it is supposed to protect.
        val seat = minOf(icon + 14.dp, room)
            .let { if (room >= GLYPH_FLOOR_DP.dp) it.coerceAtLeast(GLYPH_FLOOR_DP.dp) else it }
        return CellFit(pad, seat, labelSize, 1, countBand = false, hint = oneLine * 2 + 6.dp <= room)
    }
    var pad = padV.coerceAtMost(8.dp)
    var gap = 5.dp
    var glyph = icon
    var size = requested
    var band = true
    var guard = 0
    fun room() = (height - pad * 2).coerceAtLeast(0.dp)
    fun needs(lines: Int): Dp =
        glyph + gap + size.lineHeight() * lines +
            (if (band) max(size - 1f, 8f).lineHeight() + 3.dp else 0.dp)
    if (needs(1) > room()) band = false
    while (needs(1) > room() && pad > 0.dp && guard++ < 40) pad -= 1.dp
    guard = 0
    while (needs(1) > room() && gap > 2.dp && guard++ < 10) gap -= 1.dp
    guard = 0
    // The glyph is the thing a user forgives; a clipped label is the thing they report as broken.
    while (needs(1) > room() && glyph > GLYPH_FLOOR_DP.dp && guard++ < 80) glyph -= 2.dp
    guard = 0
    while (needs(1) > room() && size > LABEL_FLOOR_SP && guard++ < 40) size -= 0.5f
    guard = 0
    while (needs(1) > room() && glyph > GLYPH_HARD_FLOOR_DP.dp && guard++ < 40) glyph -= 1.dp
    val twoLines = needs(2) <= room()
    return CellFit(pad, glyph, size.sp, if (twoLines) 2 else 1, band, hint = false, gap = gap)
}

private fun Float.lineHeight(): Dp = (this * 1.3f).dp

/** The spacing rhythm of a board: how the rows breathe and how tall a cell stands at rest. */
data class BoardRhythm(
    val columns: Int,
    val rowGap: Dp,
    val cellGap: Dp,
    /** Horizontal padding inside a cell; vertical padding stays the shared board token. */
    val tilePadH: Dp,
    val plateMinHeight: Dp,
    val wideMinHeight: Dp,
    /** Air between the home screen's sections — the section rhythm proper. */
    val sectionGap: Dp
) {
    companion object {
        fun of(layout: VaultLayout): BoardRhythm = when (layout) {
            VaultLayout.CLASSIC -> BoardRhythm(4, 8.dp, 8.dp, 6.dp, 96.dp, 74.dp, 12.dp)
            VaultLayout.BENTO -> BoardRhythm(4, 10.dp, 10.dp, 10.dp, 92.dp, 86.dp, 16.dp)
            VaultLayout.AGENTIC -> BoardRhythm(4, 6.dp, 6.dp, 12.dp, 84.dp, 62.dp, 10.dp)
            VaultLayout.ADAPTIVE -> BoardRhythm(2, 8.dp, 8.dp, 10.dp, 104.dp, 98.dp, 14.dp)
            VaultLayout.GENERATIVE -> BoardRhythm(3, 12.dp, 12.dp, 8.dp, 112.dp, 80.dp, 18.dp)
            VaultLayout.MOSAIC -> BoardRhythm(3, 9.dp, 7.dp, 8.dp, 100.dp, 78.dp, 13.dp)
            VaultLayout.LEDGER -> BoardRhythm(2, 4.dp, 4.dp, 4.dp, 58.dp, 50.dp, 8.dp)
            VaultLayout.DUO -> BoardRhythm(2, 14.dp, 12.dp, 14.dp, 128.dp, 104.dp, 20.dp)
            VaultLayout.COMPACT -> BoardRhythm(5, 6.dp, 6.dp, 2.dp, 64.dp, 56.dp, 10.dp)
            VaultLayout.DENSE -> BoardRhythm(6, 4.dp, 4.dp, 2.dp, 52.dp, 46.dp, 8.dp)
            VaultLayout.COLUMN -> BoardRhythm(3, 10.dp, 8.dp, 8.dp, 88.dp, 70.dp, 14.dp)
            VaultLayout.SPLIT -> BoardRhythm(2, 10.dp, 10.dp, 10.dp, 88.dp, 76.dp, 16.dp)
            VaultLayout.GALLERY -> BoardRhythm(3, 8.dp, 14.dp, 6.dp, 94.dp, 82.dp, 12.dp)
            VaultLayout.STRIP -> BoardRhythm(4, 7.dp, 9.dp, 8.dp, 74.dp, 58.dp, 11.dp)
            VaultLayout.TRIAD -> BoardRhythm(4, 12.dp, 12.dp, 12.dp, 100.dp, 90.dp, 18.dp)
        }

        /**
         * The same rhythm, breathing at the owner's own [tuning] spacing.
         *
         * Only the four air values move — row gap, cell gap, inner horizontal padding and the
         * section gap — each multiplied by `spacingScale` and rounded to a whole dp so a stored
         * multiplier cannot produce sub-pixel seams between two rows. The column count is *not*
         * read from the tuning: the board itself is re-cut by [boardGrid]'s `columnsOverride`,
         * and the two must never disagree. A default tuning returns the untouched rhythm, so
         * every existing call site keeps its exact numbers.
         */
        fun of(layout: VaultLayout, tuning: LayoutTuning): BoardRhythm {
            val base = of(layout)
            if (tuning.isDefault) return base
            val scale = tuning.spacingScale.coerceIn(0.6f, 1.6f)
            fun Dp.breathe(): Dp = (value * scale).roundToInt().coerceAtLeast(0).dp
            return base.copy(
                rowGap = base.rowGap.breathe(),
                cellGap = base.cellGap.breathe(),
                tilePadH = base.tilePadH.breathe(),
                sectionGap = base.sectionGap.breathe()
            )
        }
    }
}

/**
 * How the dock is cut per layout, on top of the [DockStyle] the palette already picks. The
 * silhouette — float, side gaps, the shape each item is seated in — is a composition choice,
 * so a bento dock and a clay dock differ even where two brands share a [DockStyle].
 *
 * The spec used to be read by nobody: [VaultApp]'s bar took only `dock` and `navIndicator`, so
 * fifteen themes collapsed into four clusters. It is now the source of the rendered bar — see
 * [dockPlan], which resolves a spec plus the palette's own dock tokens into the exact instructions
 * the bar draws with.
 */

/** How the bar is cut at its corners, resolved against the palette's `navCorner` by [dockBarShape]. */
enum class DockCorner {
    /** A full capsule: both ends of every corner. */
    CAPSULE,
    /** The palette's own radius on all four corners. */
    SQUIRCLE,
    /** Rounded at the top, only a hint of radius at the bottom. */
    TOP_PILL,
    /** One wide arch across the top edge, small feet below. */
    ARCH,
    /** Chamfered: the leading corners carry the radius, the trailing ones a third of it. */
    CHAMFER,
    /** A diagonal shield — hollow top-left, full top-right, and the mirror below. */
    SHIELD,
    /** Nearly square, fused to the edge it rests on. */
    SLAB
}

/** The seat an item is cut into when the spec hands every item a plate of its own. */
enum class DockSeat {
    CIRCLE,
    CAPSULE,
    PILL,
    TOP_PILL,
    SQUIRCLE,
    SLAB,
    SQUARE
}

/** How the bar is painted. Every stop comes out of the palette's own surface tokens. */
enum class DockFill {
    /** One flat sheet of the surface token. */
    FLAT,
    /** Elevated at the items, sinking into the surface below them. */
    GRADIENT,
    /** The glass token over the surface — the wallpaper reads through. */
    GLASS,
    /** A hard two-tone break: an elevated bed with a surface footer. */
    TWO_TONE,
    /** A gradient with a lit rule along the top edge. */
    SHEEN_TOP
}

/** How the destinations share the bar's width. [dockWeights] keeps every share above the floor. */
enum class DockArrangement {
    EQUAL,
    LEAD_WIDE,
    TAIL_WIDE,
    CENTER_WIDE,
    COUNTER,
    EDGE_PIN
}

/** Where the selected marker is drawn inside its item. The palette's [NavIndicator] says what shape. */
enum class MarkerAnchor {
    /** A wash under the whole item. */
    SEAT,
    /** A spot behind the glyph only. */
    ICON,
    /** Under the label — in the flow when the bar can pay for it, over the plate when it cannot. */
    BELOW,
    /** Along the bar's inner top edge, above the item. */
    ABOVE,
    /** A vertical tab on the item's leading edge. */
    BESIDE
}

data class DockSpec(
    val sideInset: Dp,
    val bottomInset: Dp,
    val itemGap: Dp,
    /** Each item sits on its own plate rather than sharing one bar. */
    val itemPlates: Boolean,
    /** The rail the selected indicator slides along, in fractions of the bar's width. */
    val indicatorWidthFraction: Float,
    val indicatorThickness: Dp,
    /** The bar's own silhouette. */
    val corner: DockCorner,
    /** The silhouette of an item's seat when [itemPlates] is set. */
    val seat: DockSeat,
    val fill: DockFill,
    val arrangement: DockArrangement,
    val marker: MarkerAnchor,
    /** How far an item's plate is pulled off the bar's top and bottom edges. */
    val plateInset: Dp,
    /** How far the row of items is pulled off the bar's side edges. */
    val innerInset: Dp
)

fun dockSpec(layout: VaultLayout, dock: DockStyle, navFloating: Boolean, navCorner: Dp): DockSpec {
    val floating = dock == DockStyle.PILL_FROSTED || dock == DockStyle.COMFORT_PANEL ||
        (dock == DockStyle.SOFT_FLOATING && navFloating)
    val side = if (floating) (if (dock == DockStyle.PILL_FROSTED) 18.dp else 12.dp) else 0.dp
    val bottom = if (floating) 10.dp else 0.dp
    // fmt: off — one row per layout, and the row *is* the theme's dock signature.
    val cut = when (layout) {
        // CLASSIC (lime) — the reference bar: its own radius, equal items, a dot under the label.
        VaultLayout.CLASSIC -> DockSpec(side, bottom, 0.dp, false, 0.16f, 3.dp,
            DockCorner.SQUIRCLE, DockSeat.SQUIRCLE, DockFill.FLAT, DockArrangement.EQUAL, MarkerAnchor.BELOW, 0.dp, 6.dp)
        // BENTO (sakura) — the bar opens up, every item owns a round block, the dot becomes a spot.
        VaultLayout.BENTO -> DockSpec(side + 4.dp, bottom + 4.dp, 4.dp, true, 0.13f, 4.dp,
            DockCorner.CAPSULE, DockSeat.CIRCLE, DockFill.SHEEN_TOP, DockArrangement.CENTER_WIDE, MarkerAnchor.ICON, 4.dp, 8.dp)
        // AGENTIC (neon_cyan) — a flat rail, home-forward, the indicator a tab on the leading edge.
        VaultLayout.AGENTIC -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.20f, 2.dp,
            DockCorner.SLAB, DockSeat.SLAB, DockFill.TWO_TONE, DockArrangement.LEAD_WIDE, MarkerAnchor.BESIDE, 0.dp, 10.dp)
        // ADAPTIVE (oneui) — a chamfered plate pulled clear of both edges, roomy at the ends.
        VaultLayout.ADAPTIVE -> DockSpec(10.dp, 8.dp, 2.dp, true, 0.11f, 5.dp,
            DockCorner.TOP_PILL, DockSeat.TOP_PILL, DockFill.GRADIENT, DockArrangement.EDGE_PIN, MarkerAnchor.BELOW, 4.dp, 10.dp)
        // CLAY (neon_plasma) — the thickest float, the roundest seats, an arch across the top.
        VaultLayout.GENERATIVE -> DockSpec(side + 8.dp, bottom + 8.dp, 6.dp, true, 0.18f, 6.dp,
            DockCorner.ARCH, DockSeat.CAPSULE, DockFill.GLASS, DockArrangement.TAIL_WIDE, MarkerAnchor.BELOW, 5.dp, 8.dp)
        // MOSAIC (solstice) — a flat plate in alternating widths, the poster's hard rule underneath.
        VaultLayout.MOSAIC -> DockSpec(side + 2.dp, bottom + 2.dp, 3.dp, false, 0.14f, 4.dp,
            DockCorner.SLAB, DockSeat.SQUARE, DockFill.FLAT, DockArrangement.COUNTER, MarkerAnchor.BELOW, 0.dp, 5.dp)
        // LEDGER (dark) — flush, wide at home, the whole item lit as the marker.
        VaultLayout.LEDGER -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.22f, 2.dp,
            DockCorner.SLAB, DockSeat.SQUARE, DockFill.TWO_TONE, DockArrangement.LEAD_WIDE, MarkerAnchor.SEAT, 0.dp, 4.dp)
        // DUO (mint) — the widest plates, a lit seat under the chosen destination.
        VaultLayout.DUO -> DockSpec(side + 6.dp, bottom + 6.dp, 8.dp, true, 0.17f, 6.dp,
            DockCorner.CAPSULE, DockSeat.PILL, DockFill.GRADIENT, DockArrangement.EDGE_PIN, MarkerAnchor.SEAT, 5.dp, 8.dp)
        // COMPACT (aqua) — five tight seats in a frosted capsule, alternating widths.
        VaultLayout.COMPACT -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.12f, 3.dp,
            DockCorner.CAPSULE, DockSeat.CIRCLE, DockFill.GLASS, DockArrangement.COUNTER, MarkerAnchor.ICON, 0.dp, 4.dp)
        // DENSE (noir) — flush and editorial: a rule along the bar's inner edge, nothing floated.
        VaultLayout.DENSE -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.10f, 2.dp,
            DockCorner.SLAB, DockSeat.SQUARE, DockFill.FLAT, DockArrangement.CENTER_WIDE, MarkerAnchor.ABOVE, 0.dp, 4.dp)
        // COLUMN (neon_magenta) — plates lifted off the base line, the last item carrying the rail.
        VaultLayout.COLUMN -> DockSpec(side + 3.dp, bottom + 3.dp, 4.dp, true, 0.15f, 5.dp,
            DockCorner.SQUIRCLE, DockSeat.TOP_PILL, DockFill.TWO_TONE, DockArrangement.TAIL_WIDE, MarkerAnchor.BELOW, 4.dp, 7.dp)
        // SPLIT (voltra) — a shield cut, a lit top rule, the items all one width.
        VaultLayout.SPLIT -> DockSpec(6.dp, 4.dp, 2.dp, true, 0.19f, 4.dp,
            DockCorner.SHIELD, DockSeat.SLAB, DockFill.SHEEN_TOP, DockArrangement.EQUAL, MarkerAnchor.ABOVE, 3.dp, 6.dp)
        // GALLERY (sunset) — an arched frost capsule, lit seats, home given the widest share.
        VaultLayout.GALLERY -> DockSpec(side + 5.dp, bottom + 5.dp, 5.dp, true, 0.16f, 3.dp,
            DockCorner.ARCH, DockSeat.PILL, DockFill.GLASS, DockArrangement.LEAD_WIDE, MarkerAnchor.SEAT, 5.dp, 9.dp)
        // STRIP (royal) — a chamfered rail, centred weight, the marker sunk into the item.
        VaultLayout.STRIP -> DockSpec(0.dp, 2.dp, 1.dp, false, 0.13f, 6.dp,
            DockCorner.CHAMFER, DockSeat.CAPSULE, DockFill.GRADIENT, DockArrangement.CENTER_WIDE, MarkerAnchor.SEAT, 0.dp, 5.dp)
        // TRIAD (arctic) — the deepest float, ends pinned wide, a spot behind the glyph.
        VaultLayout.TRIAD -> DockSpec(side + 7.dp, bottom + 7.dp, 7.dp, true, 0.12f, 5.dp,
            DockCorner.TOP_PILL, DockSeat.SQUIRCLE, DockFill.SHEEN_TOP, DockArrangement.EDGE_PIN, MarkerAnchor.ICON, 4.dp, 8.dp)
    }
    // fmt: on
    return cut.let {
        if (dock == DockStyle.FLAT_PLATE) it.copy(sideInset = 0.dp, bottomInset = 0.dp, itemPlates = false, itemGap = 0.dp)
        else it
    }.let { if (navCorner <= 0.dp) it.copy(itemPlates = false) else it }
}

/**
 * The plan the bar actually draws from: a [DockSpec] resolved against one palette and its
 * destination count. Every number the renderer needs is here, and nothing is invented downstream —
 * which is what makes "fifteen themes, fifteen docks" a claim a unit test can check.
 */
data class DockPlan(
    val corner: DockCorner,
    val seat: DockSeat,
    val fill: DockFill,
    val arrangement: DockArrangement,
    val marker: MarkerAnchor,
    val indicator: NavIndicator,
    val sideInset: Dp,
    val bottomInset: Dp,
    /** The palette's own `navHeight`, never less than the touch floor. */
    val barHeight: Dp,
    val itemGap: Dp,
    val innerInset: Dp,
    val itemPlates: Boolean,
    val plateInset: Dp,
    /** The share of the bar each destination owns, in tab order. */
    val itemWeights: List<Float>,
    /** The marker's length as a fraction of its *item's* width. */
    val markerWidthFraction: Float,
    val markerThickness: Dp,
    val markerDotSize: Dp,
    /** How much wider than the glyph the icon spot is cut; 0 when the marker is not a spot. */
    val iconSpotPad: Dp,
    /** True when the marker takes its own line inside the item, false when it rides the plate edge. */
    val markerInFlow: Boolean,
    /** The item's own height — the touch target, and the budget the content has to fit inside. */
    val itemHeight: Dp,
    /** What the item's content needs: glyph, gap, one label line, and the marker when it is in the flow. */
    val contentHeight: Dp,
    val iconSize: Dp,
    val labelSize: TextUnit,
    val showLabels: Boolean,
    val floats: Boolean,
    /** The hairline across the top of an edge-to-edge minimal bar. */
    val edgeRule: Boolean,
    val frostTop: Float,
    val frostBottom: Float
)

/** The least a dock item may be cut to, in height or in width. */
internal val DOCK_MIN_TOUCH: Dp = 44.dp

/** The dock glyph is drawn at `navIconSize * DOCK_ICON_SCALE` — the renderer must match this. */
internal const val DOCK_ICON_SCALE: Float = 1.34f

/** The air between the glyph and the label inside a dock item. */
internal val DOCK_LABEL_GAP: Dp = 3.dp

/** The air a marker in the flow reserves before its own thickness. */
internal val DOCK_MARKER_GAP: Dp = 4.dp

/** The shortest share a destination may be given, as a fraction of an equal share. */
private const val DOCK_MIN_WEIGHT = 0.78f

fun dockPlan(palette: VaultPalette, tabCount: Int): DockPlan {
    val spec = dockSpec(palette.layout, palette.dock, palette.navFloating, palette.navCorner)
    val count = tabCount.coerceAtLeast(1)
    val barHeight = palette.navHeight.coerceAtLeast(DOCK_MIN_TOUCH)
    val thickness = spec.indicatorThickness.coerceAtLeast(2.dp)
    val dotSize = (thickness * 2f).coerceIn(4.dp, 9.dp)
    val spot = if (spec.marker == MarkerAnchor.ICON) 6.dp + thickness / 2f else 0.dp
    val reserve = DOCK_MARKER_GAP + if (palette.navIndicator == NavIndicator.DOT) dotSize else thickness
    val glyph = palette.navIconSize * DOCK_ICON_SCALE
    val labelLine = palette.navLabelSize.value.lineHeight()
    val plain = dockStackHeight(glyph, labelLine, spot * 2f, 0.dp, palette.navLabels)
    val stacked = dockStackHeight(glyph, labelLine, spot * 2f, reserve, palette.navLabels)
    // Nothing trades the touch floor, and nothing trades the label either: when the bar is too short
    // for the plate inset it asked for, the *inset* gives way. A dock whose label is ellipsised is a
    // worse defect than a dock whose plates sit a hair closer to its edges.
    val insetCeiling = if (spec.itemPlates) {
        ((barHeight - DOCK_MIN_TOUCH) / 2).coerceAtLeast(0.dp)
    } else {
        0.dp
    }
    val triedHeight = barHeight - insetCeiling * 2
    val need = if (spec.marker == MarkerAnchor.BELOW && stacked <= triedHeight) stacked else plain
    val plateInset = if (spec.itemPlates) {
        spec.plateInset.coerceAtMost(insetCeiling).coerceAtMost(((barHeight - need) / 2).coerceAtLeast(0.dp))
    } else {
        0.dp
    }
    val itemHeight = barHeight - plateInset * 2
    val inFlow = spec.marker == MarkerAnchor.BELOW && stacked <= itemHeight
    val floats = palette.dock == DockStyle.PILL_FROSTED || palette.dock == DockStyle.COMFORT_PANEL ||
        (palette.dock == DockStyle.SOFT_FLOATING && palette.navFloating)
    return DockPlan(
        corner = spec.corner,
        seat = spec.seat,
        fill = spec.fill,
        arrangement = spec.arrangement,
        marker = spec.marker,
        indicator = palette.navIndicator,
        sideInset = spec.sideInset,
        bottomInset = spec.bottomInset,
        barHeight = barHeight,
        itemGap = spec.itemGap,
        innerInset = spec.innerInset,
        itemPlates = spec.itemPlates,
        plateInset = plateInset,
        itemWeights = dockWeights(spec.arrangement, count),
        markerWidthFraction = (spec.indicatorWidthFraction * count).coerceIn(0.26f, 0.9f),
        markerThickness = thickness,
        markerDotSize = dotSize,
        iconSpotPad = spot,
        markerInFlow = inFlow,
        itemHeight = itemHeight,
        contentHeight = if (inFlow) stacked else plain,
        iconSize = palette.navIconSize,
        labelSize = palette.navLabelSize,
        showLabels = palette.navLabels,
        floats = floats,
        edgeRule = palette.dock == DockStyle.MINIMAL_BAR,
        frostTop = palette.navFrostAlpha,
        frostBottom = (palette.navFrostAlpha + 0.03f).coerceAtMost(1f)
    )
}

/** What one dock item's stack costs in height: glyph, its spot, gap, one label line, marker reserve.
 * The renderer's own spacer sizes are the constants this adds, so the budget and the drawing cannot
 * drift apart. */
private fun dockStackHeight(glyph: Dp, labelLine: Dp, spot: Dp, reserve: Dp, showLabel: Boolean): Dp =
    glyph + spot + (if (showLabel) DOCK_LABEL_GAP + labelLine else 0.dp) + reserve

/**
 * The share of the bar each destination owns. No share drops below [DOCK_MIN_WEIGHT] of an equal
 * split, so even the tightest arrangement keeps every item past the 44 dp touch floor on the
 * narrowest phone the app supports.
 */
fun dockWeights(arrangement: DockArrangement, count: Int): List<Float> {
    require(count > 0) { "a dock with no destinations draws nothing" }
    val last = count - 1
    val middle = count / 2
    val raw = when (arrangement) {
        DockArrangement.EQUAL -> List(count) { 1f }
        DockArrangement.LEAD_WIDE -> List(count) { index -> if (index == 0) 1.28f else 1f }
        DockArrangement.TAIL_WIDE -> List(count) { index -> if (index == last) 1.26f else 1f }
        DockArrangement.CENTER_WIDE -> List(count) { index -> if (index == middle) 1.24f else 0.9f }
        DockArrangement.COUNTER -> List(count) { index -> if (index % 2 == 0) 1.16f else 0.92f }
        DockArrangement.EDGE_PIN -> List(count) { index -> if (index == 0 || index == last) 1.22f else 0.88f }
    }
    return raw.map { it.coerceAtLeast(DOCK_MIN_WEIGHT) }
}

/** The bar's silhouette, always scaled from the palette's own `navCorner`. */
fun dockBarShape(corner: DockCorner, navCorner: Dp): Shape {
    val n = navCorner.coerceAtLeast(0.dp)
    return when (corner) {
        DockCorner.CAPSULE -> RoundedCornerShape(percent = 50)
        DockCorner.SQUIRCLE -> RoundedCornerShape(n)
        DockCorner.TOP_PILL -> RoundedCornerShape(topStart = n, topEnd = n, bottomStart = n / 3, bottomEnd = n / 3)
        DockCorner.ARCH -> RoundedCornerShape(topStart = n + 8.dp, topEnd = n + 8.dp, bottomStart = n / 2, bottomEnd = n / 2)
        DockCorner.CHAMFER -> RoundedCornerShape(topStart = n, topEnd = n / 2, bottomStart = n, bottomEnd = n / 4)
        DockCorner.SHIELD -> RoundedCornerShape(topStart = n / 4, topEnd = n, bottomStart = n, bottomEnd = n / 4)
        DockCorner.SLAB -> RoundedCornerShape(n / 5)
    }
}

/** The seat an item is cut into. Same rule as the bar: the radius is the palette's, never invented. */
fun dockSeatShape(seat: DockSeat, navCorner: Dp): Shape {
    val n = navCorner.coerceAtLeast(0.dp)
    return when (seat) {
        DockSeat.CIRCLE -> CircleShape
        DockSeat.CAPSULE -> RoundedCornerShape(percent = 50)
        DockSeat.PILL -> RoundedCornerShape(n)
        DockSeat.TOP_PILL -> RoundedCornerShape(topStart = n, topEnd = n, bottomStart = n / 3, bottomEnd = n / 3)
        DockSeat.SQUIRCLE -> RoundedCornerShape(n / 2)
        DockSeat.SLAB -> RoundedCornerShape(topStart = n / 2, topEnd = n / 2, bottomStart = 0.dp, bottomEnd = 0.dp)
        DockSeat.SQUARE -> RoundedCornerShape(n / 4)
    }
}

/* =====================================================================================
 * MOTION GRAMMAR
 *
 * This used to hang off `ThemeDesign`, so fifteen themes shared seven movements and two of the
 * branches could never be reached at all. Motion is now a *theme* token: [motionFor] reads
 * `palette.id` — the same deterministic key the rest of the theme system uses — and composes one
 * signature out of the behaviours below. Two themes that share a design family keep sharing their
 * components; nothing here lets them share their movement.
 *
 * Every duration comes off [ThemeMotion] and is bounded by it, so "no theme may feel laggy" is a
 * checked property rather than a review comment, and the whole cascade still collapses to one
 * frame under the device's reduced-motion setting because every delay runs through
 * [VaultMotion.stagger].
 * =====================================================================================
 */

/** Which way an entering plate travels. */
enum class EntryAxis {
    /** Up from below its resting line. */
    RISE,
    /** Down from above it. */
    FALL,
    /** In from the leading edge. */
    FROM_LEFT,
    /** In from the trailing edge. */
    FROM_RIGHT,
    /** No travel at all — the plate simply arrives. */
    NIL
}

/** The order a set of plates arrives in, given the index of one plate and the size of the set. */
enum class RevealOrder {
    CASCADE,
    REVERSE,
    CENTER_OUT,
    EDGES_IN,
    PAIRED,
    ALTERNATE,
    UNISON
}

/** The easing curve a theme moves on. */
enum class MotionCurve {
    /** Fast out, slow in — the calm default. */
    GLIDE,
    /** Slow out of the gate, long settle. */
    SETTLING,
    /** The house emphasis curve: almost nothing, then everything, then a brake. */
    EMPHATIC,
    /** Overshoots its resting line and comes back. */
    SPRINGY,
    /** Hard in, hard out, no coast. */
    SWIFT,
    /** Symmetrical arc. */
    ARC
}

/** How a plate's own size is revealed as it enters. */
enum class TileReveal {
    /** Grows into its resting size. */
    EXPAND,
    /** Settles down into it from slightly oversized. */
    CONTRACT,
    /** Size is not part of this theme's entrance. */
    NIL
}

/** How a tile answers a press. */
enum class PressResponse {
    /** The house dip: shrink a little, lose the lift. */
    DIP,
    /** Barely moves — the plate simply dims its own elevation. */
    FLATTEN,
    /** Stays its size and sinks into the board. */
    SINK,
    /** A deep springy squash that springs back past rest. */
    POP,
    /** A shallow, held compressions with no lift at all. */
    HOLD
}

data class ThemeMotion(
    val axis: EntryAxis,
    val order: RevealOrder,
    val curve: MotionCurve,
    /** One plate's own entrance, in ms. Bounded so no theme can feel laggy. */
    val duration: Int,
    /** The delay added per cascade slot; [duration] plus [stagger] x the slot cap stays under a second. */
    val stagger: Int,
    /** Travel is `size / travel`, so a bigger number is a shorter, quieter slide. */
    val travel: Int,
    val reveal: TileReveal,
    val scaleFrom: Float,
    /** A brand-lit bloom that burns off as the plate settles. */
    val glow: Boolean,
    val glowRadius: Dp,
    /** A sheen that crosses the plate once, on entry. */
    val sweep: Boolean,
    val press: PressResponse
) {
    init {
        require(duration in MIN_DURATION_MS..MAX_DURATION_MS) {
            "a theme entrance must sit in the 120-420 ms band, was $duration"
        }
        require(stagger in 0..MAX_STAGGER_STEP) {
            "the per-slot step cannot exceed $MAX_STAGGER_STEP ms (was $stagger)"
        }
        // The last plate of a full cascade lands inside the budget, so no theme can assemble a
        // board so slowly that the user watches content arrive rather than reading it.
        require(duration + VaultMotion.MAX_STAGGER * stagger <= MAX_CASCADE_MS) {
            "$duration ms with a $stagger ms step puts the last plate at " +
                "${duration + VaultMotion.MAX_STAGGER * stagger} ms — past the ${MAX_CASCADE_MS} ms " +
                "the whole cascade is allowed to take"
        }
        require(travel >= 4) { "travel of /$travel would fling the plate off the pane" }
        require(scaleFrom in 0.85f..1.15f) { "a plate may only enter within 15% of its resting size" }
        require(!glow || glowRadius > 0.dp) { "a glowing entrance needs a radius to bloom at" }
    }

    companion object {
        const val MIN_DURATION_MS = 120
        const val MAX_DURATION_MS = 420

        /** The absolute cap on one cascade step, whatever the total budget allows. */
        const val MAX_STAGGER_STEP = 80

        /** When the last plate of a full cascade must be settled: under three quarters of a second. */
        const val MAX_CASCADE_MS = 700
    }
}

/**
 * The motion signature of one theme, keyed by `palette.id`. Each of the fifteen is composed from
 * the behaviours above so no two agree on every field, and the fields are chosen to read as the
 * brand rather than as a random draw: the editorial themes arrive quietly and together, the arcade
 * themes swing in from the side with a lit edge, the glass and clay themes settle and overshoot.
 */
fun motionFor(paletteId: String): ThemeMotion = when (paletteId) {
    // Solar Pop — a poster goes up in strips: hard in from the left, no coast, no bloom.
    "solstice" -> ThemeMotion(EntryAxis.FROM_LEFT, RevealOrder.CASCADE, MotionCurve.SWIFT, 150, 30, 8, TileReveal.EXPAND, 0.96f, glow = false, glowRadius = 0.dp, sweep = true, press = PressResponse.FLATTEN)
    // Comfort Panel — One UI takes its time and rises, one row after another.
    "oneui" -> ThemeMotion(EntryAxis.RISE, RevealOrder.CASCADE, MotionCurve.GLIDE, 300, 50, 12, TileReveal.EXPAND, 0.97f, glow = false, glowRadius = 0.dp, sweep = false, press = PressResponse.DIP)
    // Aqua Tint — frosted panes surface from the middle outward and settle into place.
    "aqua" -> ThemeMotion(EntryAxis.RISE, RevealOrder.CENTER_OUT, MotionCurve.SETTLING, 340, 45, 14, TileReveal.CONTRACT, 1.03f, glow = true, glowRadius = 12.dp, sweep = true, press = PressResponse.POP)
    // Neon Lime — daylight arcade: the board closes in from both edges, lit as it lands.
    "lime" -> ThemeMotion(EntryAxis.FROM_LEFT, RevealOrder.EDGES_IN, MotionCurve.EMPHATIC, 200, 60, 6, TileReveal.EXPAND, 0.93f, glow = true, glowRadius = 8.dp, sweep = false, press = PressResponse.DIP)
    // Midnight Pop — a ledger flips in from the trailing edge, newest line first.
    "dark" -> ThemeMotion(EntryAxis.FROM_RIGHT, RevealOrder.REVERSE, MotionCurve.SWIFT, 160, 40, 10, TileReveal.NIL, 1f, glow = false, glowRadius = 0.dp, sweep = true, press = PressResponse.SINK)
    // Obsidian Noir — editorial: nothing travels, everything arrives on one frame.
    "noir" -> ThemeMotion(EntryAxis.NIL, RevealOrder.UNISON, MotionCurve.ARC, 130, 0, 16, TileReveal.CONTRACT, 1.02f, glow = false, glowRadius = 0.dp, sweep = false, press = PressResponse.HOLD)
    // Mint Ledger — airy plates lift in pairs on the calm curve.
    "mint" -> ThemeMotion(EntryAxis.RISE, RevealOrder.PAIRED, MotionCurve.GLIDE, 260, 55, 11, TileReveal.EXPAND, 0.95f, glow = false, glowRadius = 0.dp, sweep = false, press = PressResponse.POP)
    // Sunset Dusk — the light comes down over the board, centre of the frame first.
    "sunset" -> ThemeMotion(EntryAxis.FALL, RevealOrder.CENTER_OUT, MotionCurve.SETTLING, 320, 45, 13, TileReveal.CONTRACT, 1.04f, glow = false, glowRadius = 0.dp, sweep = true, press = PressResponse.DIP)
    // Arctic Frost — ice forms edge to edge in two beats, overshooting as it sets.
    "arctic" -> ThemeMotion(EntryAxis.RISE, RevealOrder.ALTERNATE, MotionCurve.EMPHATIC, 280, 52, 9, TileReveal.EXPAND, 0.92f, glow = true, glowRadius = 10.dp, sweep = false, press = PressResponse.FLATTEN)
    // Volt Aura — the board swings in from the leading edge and springs past its line.
    "voltra" -> ThemeMotion(EntryAxis.FROM_LEFT, RevealOrder.CASCADE, MotionCurve.SPRINGY, 220, 35, 5, TileReveal.EXPAND, 0.90f, glow = true, glowRadius = 14.dp, sweep = true, press = PressResponse.POP)
    // Neon Cyan — a terminal paints its rails top-down, hard and fast, no settle.
    "neon_cyan" -> ThemeMotion(EntryAxis.FALL, RevealOrder.REVERSE, MotionCurve.SWIFT, 170, 45, 7, TileReveal.NIL, 1f, glow = true, glowRadius = 9.dp, sweep = false, press = PressResponse.SINK)
    // Royal Blue — a vault door lifts, slowly and in order, with a long burn of gold.
    "royal" -> ThemeMotion(EntryAxis.RISE, RevealOrder.CASCADE, MotionCurve.ARC, 360, 35, 15, TileReveal.CONTRACT, 1.05f, glow = true, glowRadius = 16.dp, sweep = false, press = PressResponse.DIP)
    // Signal Red — the board closes on the opening rail from both edges and lights up.
    "neon_magenta" -> ThemeMotion(EntryAxis.FROM_RIGHT, RevealOrder.EDGES_IN, MotionCurve.SPRINGY, 240, 40, 8, TileReveal.EXPAND, 0.94f, glow = true, glowRadius = 11.dp, sweep = true, press = PressResponse.FLATTEN)
    // Glacier Blue — clay plates drop in pairs and squash back up to rest.
    "sakura" -> ThemeMotion(EntryAxis.FALL, RevealOrder.PAIRED, MotionCurve.SPRINGY, 300, 48, 12, TileReveal.CONTRACT, 1.06f, glow = false, glowRadius = 0.dp, sweep = false, press = PressResponse.POP)
    // Neon Plasma — counterlight comes across the board, alternating rows, never settling.
    "neon_plasma" -> ThemeMotion(EntryAxis.FROM_RIGHT, RevealOrder.ALTERNATE, MotionCurve.EMPHATIC, 190, 30, 6, TileReveal.EXPAND, 0.91f, glow = true, glowRadius = 13.dp, sweep = true, press = PressResponse.SINK)
    // Anything the resolver hands back is one of the fifteen; this is the default theme's own motion.
    else -> motionFor(VaultPalettes.DEFAULT_ID)
}

/** The cascade slot a plate occupies, given the theme's order and the size of the set. */
fun motionSlot(order: RevealOrder, index: Int, count: Int): Int {
    val last = (count - 1).coerceAtLeast(0)
    val position = index.coerceIn(0, last)
    return when (order) {
        RevealOrder.CASCADE -> position
        RevealOrder.REVERSE -> last - position
        RevealOrder.CENTER_OUT -> kotlin.math.abs(position - last / 2)
        RevealOrder.EDGES_IN -> minOf(position, last - position)
        RevealOrder.PAIRED -> position / 2
        RevealOrder.ALTERNATE -> position % 2
        RevealOrder.UNISON -> 0
    }
}

/** The curve as a Compose easing, so a non-composable call site can still honour the theme. */
fun easingFor(curve: MotionCurve): Easing = when (curve) {
    MotionCurve.GLIDE -> FastOutSlowInEasing
    MotionCurve.SETTLING -> LinearOutSlowInEasing
    MotionCurve.EMPHATIC -> VaultMotion.Emphasis
    MotionCurve.SPRINGY -> CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    // Hard in, hard out, no coast at either end. This is *not* [FastOutSlowInEasing] again: the
    // house glide already owns that shape, and SWIFT has to be a different movement to be a name.
    MotionCurve.SWIFT -> CubicBezierEasing(0.85f, 0f, 0.15f, 1f)
    MotionCurve.ARC -> CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
}

/** How far a tile shrinks while it is held. */
fun pressedScaleFor(press: PressResponse): Float = when (press) {
    PressResponse.DIP -> 0.962f
    PressResponse.FLATTEN -> 0.99f
    PressResponse.SINK -> 1f
    PressResponse.POP -> 0.94f
    PressResponse.HOLD -> 0.978f
}

/** The share of a tile's resting lift it keeps while held. */
fun pressedLiftFor(press: PressResponse): Float = when (press) {
    PressResponse.DIP -> 0f
    PressResponse.FLATTEN -> 0.45f
    PressResponse.SINK -> 0f
    PressResponse.POP -> 0.7f
    PressResponse.HOLD -> 0f
}

/** True when the press answers on a spring rather than a tween. */
fun pressSprings(press: PressResponse): Boolean =
    press == PressResponse.DIP || press == PressResponse.POP

/** How far a tile travels down into the board while it is held, in dp. Only SINK moves at all. */
fun pressedSinkFor(press: PressResponse): Float = if (press == PressResponse.SINK) 3f else 0f

/**
 * The entrance one theme uses for a board row, composed from its [ThemeMotion]. The fade always
 * starts on frame one — a plate that arrives dark for a beat is content hidden from the user — and
 * only the travel and the size are delayed by the cascade slot.
 */
@Composable
fun revealForTheme(
    paletteId: String,
    index: Int,
    count: Int = 6
): EnterTransition {
    val motion = motionFor(paletteId)
    val easing = easingFor(motion.curve)
    val slot = motionSlot(motion.order, index, count)
    val delay = VaultMotion.stagger(slot, motion.stagger)
    val parts = mutableListOf(
        fadeIn(VaultMotion.tweenSpec<Float>(motion.duration, easing, delay))
    )
    when (motion.axis) {
        EntryAxis.NIL -> Unit
        EntryAxis.RISE -> parts += slideInVertically(
            VaultMotion.tweenSpec<IntOffset>(motion.duration, easing, delay)
        ) { it / motion.travel }
        EntryAxis.FALL -> parts += slideInVertically(
            VaultMotion.tweenSpec<IntOffset>(motion.duration, easing, delay)
        ) { -(it / motion.travel) }
        EntryAxis.FROM_LEFT -> parts += slideInHorizontally(
            VaultMotion.tweenSpec<IntOffset>(motion.duration, easing, delay)
        ) { -(it / motion.travel) }
        EntryAxis.FROM_RIGHT -> parts += slideInHorizontally(
            VaultMotion.tweenSpec<IntOffset>(motion.duration, easing, delay)
        ) { it / motion.travel }
    }
    when (motion.reveal) {
        TileReveal.NIL -> Unit
        else -> parts += scaleIn(
            VaultMotion.tweenSpec<Float>(motion.duration, easing, delay),
            initialScale = motion.scaleFrom
        )
    }
    return parts.reduce { left, right -> left + right }
}

/**
 * A route change on the theme's own movement. Non-composable because `NavHost` builds its
 * transitions outside composition: the caller reads the device's animation scale once and hands it
 * in, so reduced motion still collapses the whole thing to one frame.
 *
 * [reverse] mirrors the travel, which is what a pop is: the screen comes back from the side it left
 * on rather than repeating the forward entrance.
 */
fun themeRouteEnter(motion: ThemeMotion, durationScale: Float, reverse: Boolean = false): EnterTransition {
    val ms = scaledMs(motion.duration, durationScale)
    val easing = easingFor(motion.curve)
    val axis = if (reverse) popMotion(motion).axis else motion.axis
    val fade = fadeIn(tween(ms, easing = easing))
    return when (axis) {
        EntryAxis.NIL -> fade
        EntryAxis.RISE -> fade + slideInVertically(tween(ms, easing = easing)) { it / 8 }
        EntryAxis.FALL -> fade + slideInVertically(tween(ms, easing = easing)) { -it / 8 }
        EntryAxis.FROM_LEFT -> fade + slideInHorizontally(tween(ms, easing = easing)) { -it / 8 }
        EntryAxis.FROM_RIGHT -> fade + slideInHorizontally(tween(ms, easing = easing)) { it / 8 }
    } + when (motion.reveal) {
        TileReveal.NIL -> EnterTransition.None
        else -> scaleIn(tween(ms, easing = easing), motion.scaleFrom)
    }
}

fun themeRouteExit(motion: ThemeMotion, durationScale: Float, reverse: Boolean = false): ExitTransition {
    val ms = scaledMs(motion.duration * 3 / 4, durationScale)
    val easing = easingFor(motion.curve)
    val axis = if (reverse) popMotion(motion).axis else motion.axis
    val fade = fadeOut(tween(ms, easing = easing))
    return when (axis) {
        EntryAxis.NIL -> fade
        EntryAxis.RISE -> fade + slideOutVertically(tween(ms, easing = easing)) { -it / 14 }
        EntryAxis.FALL -> fade + slideOutVertically(tween(ms, easing = easing)) { it / 14 }
        EntryAxis.FROM_LEFT -> fade + slideOutHorizontally(tween(ms, easing = easing)) { it / 14 }
        EntryAxis.FROM_RIGHT -> fade + slideOutHorizontally(tween(ms, easing = easing)) { -it / 14 }
    }
}

private fun scaledMs(durationMillis: Int, scale: Float): Int =
    if (scale <= 0f) 0 else (durationMillis * scale).toInt().coerceAtLeast(1)

/**
 * The same movement, mirrored: what a route does when the user pops back rather than pushing
 * forward. A screen that lifted in from below leaves downward on the way back, so a pop always
 * reads as the reverse of the push rather than as a second, unrelated entrance.
 */
fun popMotion(motion: ThemeMotion): ThemeMotion = motion.copy(
    axis = when (motion.axis) {
        EntryAxis.RISE -> EntryAxis.FALL
        EntryAxis.FALL -> EntryAxis.RISE
        EntryAxis.FROM_LEFT -> EntryAxis.FROM_RIGHT
        EntryAxis.FROM_RIGHT -> EntryAxis.FROM_LEFT
        EntryAxis.NIL -> EntryAxis.NIL
    }
)

/**
 * The theme's entry bloom: a brand-lit shadow that burns off while the plate settles. Themes whose
 * motion says nothing about glow get no layer at all, so this cannot slow a calm theme down.
 */
@Composable
fun Modifier.revealGlow(palette: VaultPalette, shape: Shape): Modifier {
    val motion = motionFor(palette.id)
    if (!motion.glow || VaultMotion.reduced()) return this
    val bloom = remember(palette.id) { Animatable(1f) }
    LaunchedEffect(bloom) {
        bloom.animateTo(
            0f,
            animationSpec = tween(
                durationMillis = (motion.duration * 2).coerceAtMost(ThemeMotion.MAX_DURATION_MS * 2),
                easing = easingFor(motion.curve)
            )
        )
    }
    val lit = palette.cyan
    return this.shadow(
        elevation = motion.glowRadius * bloom.value,
        shape = shape,
        clip = false,
        ambientColor = lit.copy(alpha = 0.28f),
        spotColor = lit.copy(alpha = 0.42f)
    )
}

/**
 * The theme's entry sheen: one band of light crosses the plate as it arrives and is gone. Drawn
 * inside whatever clip the caller already set, so it can never spill past a card's corner.
 */
@Composable
fun Modifier.revealSweep(palette: VaultPalette): Modifier {
    val motion = motionFor(palette.id)
    if (!motion.sweep || VaultMotion.reduced()) return this
    val travel = remember(palette.id) { Animatable(0f) }
    LaunchedEffect(travel) {
        travel.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = (motion.duration + ThemeMotion.MAX_STAGGER_STEP).coerceAtMost(ThemeMotion.MAX_DURATION_MS),
                easing = easingFor(motion.curve)
            )
        )
    }
    val lit = VaultContrast.vibrant(palette.cyan).copy(alpha = 0.14f)
    return this.drawWithContent {
        drawContent()
        val progress = travel.value
        if (progress < 0.999f) {
            val band = size.width * 0.45f
            val left = -band + (size.width + band * 2f) * progress
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, lit, Color.Transparent),
                    start = Offset(left, 0f),
                    end = Offset(left + band, size.height)
                )
            )
        }
    }
}

/** The theme-switch crossfade: how one palette's composition hands over to the next one's. */
@Composable
fun themeSwitchEnter(): EnterTransition =
    VaultMotion.fadeEnter(VaultMotion.STANDARD) + VaultMotion.scaleEnter(0.985f, VaultMotion.LONG)

@Composable
fun themeSwitchExit(): ExitTransition =
    VaultMotion.fadeExit(VaultMotion.QUICK) + VaultMotion.scaleExit(1.015f, VaultMotion.QUICK)

/**
 * The card a board cell is drawn on. This is the second half of the layout grammar: [TileKind]
 * says how much room a cell owns and where its content sits, and this says what its silhouette
 * actually is — so a bento board and a clay board stop being the same rectangle in two colours.
 *
 * Structure only. The icon geometry inside the plate stays identical across every theme (that is
 * pinned by ThemeTokensTest); the corners, the pill and the cut are what move here.
 */
fun tileShape(palette: VaultPalette, layout: VaultLayout, kind: TileKind): Shape {
    val hard = palette.design == ThemeDesign.NEO_BRUTALIST
    val base = when (layout) {
        VaultLayout.CLASSIC -> 22.dp
        VaultLayout.BENTO -> 28.dp
        VaultLayout.AGENTIC -> 14.dp
        VaultLayout.ADAPTIVE -> 26.dp
        VaultLayout.GENERATIVE -> 36.dp
        VaultLayout.MOSAIC -> 20.dp
        VaultLayout.LEDGER -> 10.dp
        VaultLayout.DUO -> 30.dp
        VaultLayout.COMPACT -> 12.dp
        VaultLayout.DENSE -> 6.dp
        VaultLayout.COLUMN -> 24.dp
        VaultLayout.SPLIT -> 18.dp
        VaultLayout.GALLERY -> 32.dp
        VaultLayout.STRIP -> 16.dp
        VaultLayout.TRIAD -> 34.dp
    }
    if (hard) return RoundedCornerShape(4.dp)
    return when (kind) {
        TileKind.ROW -> RoundedCornerShape(topStart = base, topEnd = base / 3, bottomStart = base, bottomEnd = base / 3)
        TileKind.BAR -> RoundedCornerShape(bottomStart = base, bottomEnd = base, topStart = 8.dp, topEnd = 8.dp)
        TileKind.FEATURE -> RoundedCornerShape((base + 6.dp))
        TileKind.MICRO -> when (layout) {
            VaultLayout.AGENTIC -> CircleShape
            VaultLayout.COMPACT -> RoundedCornerShape(10.dp)
            VaultLayout.DENSE -> RoundedCornerShape(6.dp)
            else -> RoundedCornerShape(16.dp)
        }
        TileKind.PLATE -> when (palette.design) {
            ThemeDesign.CLAYMORPHISM -> RoundedCornerShape(topStart = base, topEnd = base / 2, bottomStart = base / 2, bottomEnd = base)
            ThemeDesign.LIQUID_GLASS -> RoundedCornerShape(percent = 50)
            ThemeDesign.CYBERPUNK -> RoundedCornerShape(topStart = base, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = base)
            else -> RoundedCornerShape(base)
        }
    }
}
