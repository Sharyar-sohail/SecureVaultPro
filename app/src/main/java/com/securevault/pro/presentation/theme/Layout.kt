package com.securevault.pro.presentation.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
 * Nothing here reads a colour. Nothing here may be overridable per palette either: the board's
 * icon geometry stays identical across themes (see ThemeTokensTest), while the *structure*
 * around it is what moves.
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
 */
data class DockSpec(
    val sideInset: Dp,
    val bottomInset: Dp,
    val itemGap: Dp,
    /** Each item sits on its own plate rather than sharing one bar. */
    val itemPlates: Boolean,
    /** The rail the selected indicator slides along, in fractions of the bar's width. */
    val indicatorWidthFraction: Float,
    val indicatorThickness: Dp
)

fun dockSpec(layout: VaultLayout, dock: DockStyle, navFloating: Boolean, navCorner: Dp): DockSpec {
    val floating = dock == DockStyle.PILL_FROSTED || dock == DockStyle.COMFORT_PANEL ||
        (dock == DockStyle.SOFT_FLOATING && navFloating)
    val side = if (floating) (if (dock == DockStyle.PILL_FROSTED) 18.dp else 12.dp) else 0.dp
    val bottom = if (floating) 10.dp else 0.dp
    return when (layout) {
        VaultLayout.CLASSIC -> DockSpec(side, bottom, 0.dp, false, 0.16f, 3.dp)
        // Bento: the bar opens up and every item owns its own block.
        VaultLayout.BENTO -> DockSpec(side + 4.dp, bottom + 4.dp, 4.dp, true, 0.13f, 4.dp)
        // Agentic: a flat rail with no float, indicators as a travelling underline.
        VaultLayout.AGENTIC -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.20f, 2.dp)
        // Adaptive: a chamfered plate pulled clear of both edges.
        VaultLayout.ADAPTIVE -> DockSpec(10.dp, 8.dp, 2.dp, true, 0.11f, 5.dp)
        // Clay: the thickest float and the roundest seats.
        VaultLayout.GENERATIVE -> DockSpec(side + 8.dp, bottom + 8.dp, 6.dp, true, 0.18f, 6.dp)
        VaultLayout.MOSAIC -> DockSpec(side + 2.dp, bottom + 2.dp, 3.dp, false, 0.14f, 4.dp)
        VaultLayout.LEDGER -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.22f, 2.dp)
        VaultLayout.DUO -> DockSpec(side + 6.dp, bottom + 6.dp, 8.dp, true, 0.17f, 6.dp)
        VaultLayout.COMPACT -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.12f, 3.dp)
        VaultLayout.DENSE -> DockSpec(0.dp, 0.dp, 0.dp, false, 0.10f, 2.dp)
        VaultLayout.COLUMN -> DockSpec(side + 3.dp, bottom + 3.dp, 4.dp, true, 0.15f, 5.dp)
        VaultLayout.SPLIT -> DockSpec(6.dp, 4.dp, 2.dp, true, 0.19f, 4.dp)
        VaultLayout.GALLERY -> DockSpec(side + 5.dp, bottom + 5.dp, 5.dp, true, 0.16f, 3.dp)
        VaultLayout.STRIP -> DockSpec(0.dp, 2.dp, 1.dp, false, 0.13f, 6.dp)
        VaultLayout.TRIAD -> DockSpec(side + 7.dp, bottom + 7.dp, 7.dp, true, 0.12f, 5.dp)
    }.let {
        if (dock == DockStyle.FLAT_PLATE) it.copy(sideInset = 0.dp, bottomInset = 0.dp, itemPlates = false, itemGap = 0.dp)
        else it
    }.let { if (navCorner <= 0.dp) it.copy(itemPlates = false) else it }
}

/**
 * The entrance each theme design asks for, on the shared duration ladder. Staggering comes from
 * [VaultMotion.stagger], so a cascade collapses to one frame the moment the device asks for no
 * animation instead of firing eight delayed tweens.
 */
@Composable
fun revealForDesign(
    design: ThemeDesign,
    index: Int,
    enterContent: @Composable ((EnterTransition) -> EnterTransition)? = null
): EnterTransition {
    val delay = VaultMotion.stagger(index)
    val base: EnterTransition = when (design) {
        ThemeDesign.NEUTRAL -> VaultMotion.fadeEnter(VaultMotion.SHORT, delay)
        ThemeDesign.GENERATIVE -> VaultMotion.fadeEnter(VaultMotion.LONG, delay) +
            VaultMotion.slideEnterVertically(VaultMotion.LONG, delay) { it / 10 }
        ThemeDesign.AGENTIC -> VaultMotion.fadeEnter(VaultMotion.STANDARD, delay) +
            VaultMotion.slideEnterHorizontally(VaultMotion.EMPHATIC, delay) { -it / 6 }
        ThemeDesign.LIQUID_GLASS -> VaultMotion.fadeEnter(VaultMotion.LONG, delay)
        ThemeDesign.BENTO -> VaultMotion.fadeEnter(VaultMotion.STANDARD, delay) +
            VaultMotion.scaleEnter(0.94f, VaultMotion.EMPHATIC)
        ThemeDesign.NEO_SKEUOMORPHIC -> VaultMotion.fadeEnter(VaultMotion.SHORT, delay)
        ThemeDesign.NEO_BRUTALIST -> VaultMotion.fadeEnter(VaultMotion.INSTANT, delay) +
            VaultMotion.slideEnterVertically(VaultMotion.QUICK, delay) { it / 8 }
        ThemeDesign.CLAYMORPHISM -> VaultMotion.fadeEnter(VaultMotion.STANDARD, delay) +
            androidx.compose.animation.scaleIn(
                VaultMotion.springSpec<Float>(VaultMotion.SpringBouncy),
                initialScale = 0.8f
            )
        ThemeDesign.CYBERPUNK -> VaultMotion.fadeEnter(VaultMotion.EMPHATIC, delay) +
            VaultMotion.slideEnterHorizontally(VaultMotion.LINGERING, delay) { it / 5 }
    }
    return enterContent?.invoke(base) ?: base
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
