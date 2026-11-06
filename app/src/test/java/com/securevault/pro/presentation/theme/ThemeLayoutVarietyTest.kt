package com.securevault.pro.presentation.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rejection in one sentence: "the fifteen themes are the same geometry in different
 * colours". This test answers it in numbers, over the shipped board count of thirteen modules.
 *
 * A theme's board is only genuinely different if the *composition* differs, so the signature is
 * columns + the span/kind of every cell, row by row + the rhythm that decides how the rows and
 * plates breathe. Colours are deliberately absent: two themes may share a hue but not a shape.
 *
 * Nothing here is a screenshot test — it is the pure grammar (`theme/Layout.kt`) the screen
 * consumes, which is the thing that used to collapse. `HomeScreen.HomeModuleGrid` now passes
 * `cell.kind` through to `BoardTile`, so a signature that differs here differs on the device.
 */
class ThemeLayoutVarietyTest {

    private val boardCount = 13
    private val layouts: List<VaultLayout> = VaultLayout.values().toList()

    private data class Cell(val span: Int, val kind: TileKind)

    /**
     * Everything the eye reads as board geometry: the grid's width, the shape of every row
     * (weight and content arrangement), and the spacing/height rhythm around it.
     */
    private data class Signature(
        val columns: Int,
        val rows: List<List<Cell>>,
        val rowGap: Float,
        val cellGap: Float,
        val tilePadH: Float,
        val plateMinHeight: Float,
        val wideMinHeight: Float,
        val sectionGap: Float
    ) {
        /** "4c 2F+1P+1P | 1P+1P+1P+1P | 4B" — the failure message's evidence. */
        fun shape(): String = buildString {
            append(columns).append("c ")
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) append(" | ")
                row.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) append("+")
                    append(cell.span).append(cell.kind.name.first())
                }
            }
        }
    }

    private fun signatureOf(layout: VaultLayout): Signature {
        val rhythm = BoardRhythm.of(layout)
        return Signature(
            columns = boardColumns(layout),
            rows = boardGrid(layout, boardCount).map { row -> row.map { Cell(it.span, it.kind) } },
            rowGap = rhythm.rowGap.value,
            cellGap = rhythm.cellGap.value,
            tilePadH = rhythm.tilePadH.value,
            plateMinHeight = rhythm.plateMinHeight.value,
            wideMinHeight = rhythm.wideMinHeight.value,
            sectionGap = rhythm.sectionGap.value
        )
    }

    // ---------------------------------------------------------------------------------
    // (a) fifteen boards, fifteen signatures
    // ---------------------------------------------------------------------------------
    @Test
    fun `no two of the fifteen layouts share a board signature`() {
        val signatures = layouts.associateWith { signatureOf(it) }

        for (i in layouts.indices) {
            for (j in i + 1 until layouts.size) {
                val first = layouts[i]
                val second = layouts[j]
                val a = signatures.getValue(first)
                val b = signatures.getValue(second)
                assertNotEquals(
                    "layouts $first and $second render the same board " +
                        "(${a.shape()} / rhythm ${a.rhythm()} vs ${b.shape()} / rhythm ${b.rhythm()})",
                    a,
                    b
                )
            }
        }

        assertEquals(
            "all fifteen layouts must resolve to fifteen distinct boards, got " +
                signatures.values.map { it.shape() }.distinct().size,
            15,
            signatures.values.distinct().size
        )
        // A board that is one repeated cell everywhere is 'the same geometry' by definition;
        // BENTO/AGENTIC/ADAPTIVE/COLUMN/STRIP/MOSAIC/GALLERY must mix at least two kinds.
        val mixed = layouts.filter { layout ->
            signatureOf(layout).rows.flatten().map { it.kind }.distinct().size >= 2
        }
        assertTrue("layouts mixing two or more cell kinds: $mixed", mixed.size >= 6)
    }

    private fun Signature.rhythm(): String =
        "rows=${rowGap}dp cells=${cellGap}dp padH=${tilePadH}dp plate=${plateMinHeight}dp wide=${wideMinHeight}dp"

    // ---------------------------------------------------------------------------------
    // (b) every row occupies its pane exactly — no overflow and, critically, no hole
    // ---------------------------------------------------------------------------------
    @Test
    fun `every row fills its pane with no overflow and no hole`() {
        for (layout in layouts) {
            val columns = boardColumns(layout)
            val rows = boardGrid(layout, boardCount)
            assertTrue("$layout produced no rows", rows.isNotEmpty())

            rows.forEachIndexed { index, row ->
                val occupied = row.sumOf { it.span }
                assertTrue("$layout must produce rows, row $index is empty", row.isNotEmpty())
                // The owner's screenshots: a lone square beside dead space, or a cell running off
                // the pane. Both come from a row that does not total its column count.
                assertEquals(
                    "$layout row $index does not fill the pane: $occupied of $columns, " +
                        row.joinToString("+") { "${it.span}${it.kind.name.first()}" },
                    columns,
                    occupied
                )
                assertTrue(
                    "$layout row $index carries a cell with no weight",
                    row.all { it.span >= 1 }
                )
            }

            assertEquals(
                "$layout must place all $boardCount cells",
                boardCount,
                rows.sumOf { it.size }
            )
            // Board order must survive the packer: the modules are laid out in the order the
            // home screen resolved them, so a re-order here would silently re-shuffle the board.
            val placed = rows.flatten().map { it.index }
            assertEquals((0 until boardCount).toList(), placed)
        }
    }

    // ---------------------------------------------------------------------------------
    // (b2) the guarantee holds at every count, not just the shipped thirteen
    // ---------------------------------------------------------------------------------
    @Test
    fun `no count from one to twenty leaves a hole in any board`() {
        for (layout in layouts) {
            val columns = boardColumns(layout)
            for (count in 1..20) {
                val rows = boardGrid(layout, count)
                assertEquals("$count cells placed", count, rows.sumOf { it.size })
                for ((index, row) in rows.withIndex()) {
                    assertEquals(
                        "$layout with $count cells leaves a hole in row $index: " +
                            row.joinToString("+") { "${it.span}${it.kind.name.first()}" },
                        columns,
                        row.sumOf { it.span }
                    )
                    assertTrue(
                        "$layout with $count cells draws a BAR that is not full width",
                        row.none { it.kind == TileKind.BAR && it.span != columns }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // (c) promoted cells and full-width cells actually ship
    // ---------------------------------------------------------------------------------
    @Test
    fun `promoted cells and full-width cells actually ship`() {
        val promoted = layouts.filter { layout ->
            boardGrid(layout, boardCount).any { row -> row.any { it.span >= 2 } }
        }
        val fullWidth = layouts.filter { layout ->
            val columns = boardColumns(layout)
            boardGrid(layout, boardCount).any { row -> row.any { it.span == columns } }
        }

        assertTrue(
            "no layout yields a row with a >= 2-span cell; every theme is a uniform grid",
            promoted.isNotEmpty()
        )
        assertTrue(
            "no layout yields a full-width cell; no board can close on a bar",
            fullWidth.isNotEmpty()
        )
        assertTrue(
            "expected at least five layouts with a promoted cell, got $promoted",
            promoted.size >= 5
        )
        assertTrue(
            "expected at least five layouts with a full-width cell, got $fullWidth",
            fullWidth.size >= 5
        )

        // Every cell kind must be reachable, or a kind is dead code the owner cannot see.
        val kinds = layouts.flatMap { layout ->
            boardGrid(layout, boardCount).flatten().map { it.kind }
        }.toSet()
        assertEquals(
            "every TileKind must appear on the shipped boards, missing " +
                TileKind.values().filterNot { it in kinds },
            TileKind.values().toSet(),
            kinds
        )

        // The closing bar is only a bar if it spans the pane; a BAR at one column of four
        // would be a plate wearing a bar's silhouette.
        for (layout in layouts) {
            val columns = boardColumns(layout)
            for (row in boardGrid(layout, boardCount)) {
                for (cell in row) {
                    if (cell.kind == TileKind.BAR) {
                        assertEquals(
                            "$layout draws a BAR that is not full width: ${cell.span} of $columns",
                            columns,
                            cell.span
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // (d) the dock silhouette moves with the layout, not just with the palette
    // ---------------------------------------------------------------------------------
    @Test
    fun `dock silhouettes stay distinct across the fifteen layouts`() {
        // A shared dock style isolates the layout's own contribution: if two layouts still
        // agree here, their docks are identical in the app too.
        val shared = DockStyle.SOFT_FLOATING
        val specs = layouts.associateWith {
            dockSpec(it, shared, navFloating = true, navCorner = 24.dp)
        }
        for (i in layouts.indices) {
            for (j in i + 1 until layouts.size) {
                val first = layouts[i]
                val second = layouts[j]
                assertNotEquals(
                    "layouts $first and $second share a dock silhouette " +
                        "(${specs.getValue(first)} vs ${specs.getValue(second)})",
                    specs.getValue(first),
                    specs.getValue(second)
                )
            }
        }

        // The worst case for the grammar: FLAT_PLATE fuses the bar to the bottom edge and
        // `navCorner = 0` refuses every item plate, so the float, the gaps and the plate count
        // are all gone. What is left is still the theme's own cut — corner, seat, fill, the way
        // the destinations share the width and where the marker sits — so the concession this
        // test used to make (one merge, CLASSIC/GALLERY, both 0.16 over 3 dp) no longer holds:
        // GALLERY seats its marker in the item, CLASSIC drops it under the label. Zero merges.
        val flat = layouts.associateWith {
            dockSpec(it, DockStyle.FLAT_PLATE, navFloating = false, navCorner = 0.dp)
        }
        val merged = mutableListOf<String>()
        for (i in layouts.indices) {
            for (j in i + 1 until layouts.size) {
                if (flat.getValue(layouts[i]) == flat.getValue(layouts[j])) {
                    merged += "${layouts[i]}/${layouts[j]}"
                }
            }
        }
        assertTrue(
            "under FLAT_PLATE no dock pair may merge, merged: $merged",
            merged.isEmpty()
        )
        assertEquals(
            "FLAT_PLATE must still leave fifteen different docks, only " +
                "${flat.values.distinct().size} survive",
            15,
            flat.values.distinct().size
        )
    }

    // ---------------------------------------------------------------------------------
    // (e) the shipped lineup, not just the grammar: each palette's own dock is unique
    // ---------------------------------------------------------------------------------
    @Test
    fun `the fifteen shipped palettes keep fifteen distinct docks`() {
        val palettes = VaultPalettes.all
        assertEquals("the lineup must ship fifteen palettes", 15, palettes.size)
        assertEquals(
            "every palette must own a different layout",
            15,
            palettes.map { it.layout }.distinct().size
        )

        val specs = palettes.associate { palette ->
            palette.id to dockSpec(palette.layout, palette.dock, palette.navFloating, palette.navCorner)
        }
        for (i in palettes.indices) {
            for (j in i + 1 until palettes.size) {
                val first = palettes[i]
                val second = palettes[j]
                assertNotEquals(
                    "palettes ${first.id} (${first.layout}) and ${second.id} (${second.layout}) " +
                        "ship the same dock (${specs.getValue(first.id)} vs ${specs.getValue(second.id)})",
                    specs.getValue(first.id),
                    specs.getValue(second.id)
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // (f) the spec is not advice: the bar the app draws is the plan resolved from it
    // ---------------------------------------------------------------------------------
    @Test
    fun `the fifteen shipped palettes draw fifteen different bars`() {
        // The defect this closes: `dockSpec` was computed and thrown away, so the rendered dock
        // read only DockStyle + NavIndicator and fifteen themes collapsed into four clusters.
        // `dockPlan` is now the single source the bar draws from, so the signature below is the
        // silhouette on the device — not a second, unused description of one.
        val plans = VaultPalettes.all.associate { palette ->
            palette.id to dockPlan(palette, TAB_COUNT)
        }
        for (palette in VaultPalettes.all) {
            for (other in VaultPalettes.all) {
                if (palette.id >= other.id) continue
                assertNotEquals(
                    "palettes ${palette.id} and ${other.id} render the same bar " +
                        "(${plans.getValue(palette.id).dockSignature()} vs " +
                        "${plans.getValue(other.id).dockSignature()})",
                    plans.getValue(palette.id).dockSignature(),
                    plans.getValue(other.id).dockSignature()
                )
            }
        }
        assertEquals(
            "fifteen themes must produce fifteen rendered docks",
            15,
            plans.values.map { it.dockSignature() }.distinct().size
        )

        // The five descriptors are what carries the difference. A field every theme sets the same
        // way is a field the bar might as well not read, so the lineup has to reach every answer
        // each of them offers — fifteen themes, seven silhouettes, seven seats, five fills, six
        // arrangements, five marker anchors.
        val descriptors: List<Triple<String, List<Any?>, List<Any?>>> = listOf(
            Triple("bar silhouette", plans.values.map { it.corner }, DockCorner.values().toList()),
            Triple("item seat", plans.values.map { it.seat }, DockSeat.values().toList()),
            Triple("bar fill", plans.values.map { it.fill }, DockFill.values().toList()),
            Triple("width arrangement", plans.values.map { it.arrangement }, DockArrangement.values().toList()),
            Triple("marker anchor", plans.values.map { it.marker }, MarkerAnchor.values().toList())
        )
        descriptors.forEach { (name, used, every) ->
            assertEquals(
                "$name is not a per-theme choice: ${used.distinct()} covers only " +
                    "${used.distinct().size} of ${every.size} answers",
                every.size,
                used.distinct().size
            )
        }
    }

    @Test
    fun `the rendered dock honours the palette's own dock tokens`() {
        for (palette in VaultPalettes.all) {
            val plan = dockPlan(palette, TAB_COUNT)
            // navHeight and navFrostAlpha are the palette's; the bar may raise the height to the
            // touch floor but may never trade the frost away, and the marker may not stretch past
            // the bar it sits in.
            assertTrue(
                "${palette.id} shrank its bar below navHeight=${palette.navHeight}",
                plan.barHeight >= palette.navHeight
            )
            assertEquals("${palette.id} lost its own frost alpha", palette.navFrostAlpha, plan.frostTop, 0f)
            assertTrue("${palette.id} froze past opaque", plan.frostBottom <= 1f)
            assertEquals("${palette.id} lost its own glyph size", palette.navIconSize, plan.iconSize)
            assertEquals("${palette.id} lost its own label size", palette.navLabelSize, plan.labelSize)
            assertEquals("${palette.id} lost its own indicator", palette.navIndicator, plan.indicator)
            assertTrue("${palette.id} has ${plan.itemWeights.size} shares for $TAB_COUNT tabs",
                plan.itemWeights.size == TAB_COUNT)
            assertTrue("${palette.id} gives a tab no width", plan.itemWeights.all { it > 0f })
            assertTrue("${palette.id} floats on a bar fused to the edge",
                plan.floats == (palette.dock == DockStyle.PILL_FROSTED ||
                    palette.dock == DockStyle.COMFORT_PANEL ||
                    (palette.dock == DockStyle.SOFT_FLOATING && palette.navFloating)))
        }
    }

    @Test
    fun `no dock item loses its label or its touch target`() {
        for (palette in VaultPalettes.all) {
            val plan = dockPlan(palette, TAB_COUNT)
            // Height: the plate inset is what gives way, never the 44 dp target and never the
            // label the user reads.
            assertTrue(
                "${palette.id} cuts an item to ${plan.itemHeight} — under the 44 dp touch floor",
                plan.itemHeight >= 44.dp
            )
            assertTrue(
                "${palette.id} needs ${plan.contentHeight} inside ${plan.itemHeight}: the caption " +
                    "would ellipsis or clip",
                plan.contentHeight <= plan.itemHeight
            )
            assertEquals("${palette.id} hid its captions", palette.navLabels, plan.showLabels)
            // Width: every share must stay past the floor on the narrowest phone supported, which
            // is what the weight floor exists for.
            val paneWidth = 360.dp
            val barWidth = paneWidth - plan.sideInset * 2
            val rowWidth = barWidth - plan.innerInset * 2
            val gapTotal = plan.itemGap * (TAB_COUNT - 1)
            val usable = (rowWidth - gapTotal).value
            assertTrue("${palette.id} leaves no width for its items ($usable dp)", usable > 0f)
            val shareSum = plan.itemWeights.sum()
            val narrowest = usable * plan.itemWeights.min() / shareSum
            assertTrue(
                "${palette.id}'s narrowest tab is ${narrowest}dp wide — under the 44 dp touch floor",
                narrowest >= 44f
            )
        }
    }

    /**
     * What the renderer actually draws, as a string: silhouette, seat, fill, arrangement, marker,
     * insets, and every share of the bar. Two bars that agree here are the same bar on the phone,
     * which is the only claim that matters for "fifteen themes, fifteen docks".
     */
    private fun DockPlan.dockSignature(): String = buildString {
        append(corner).append('/')
        append(seat).append('/')
        append(fill).append('/')
        append(arrangement).append('/')
        append(marker).append('(').append(indicator).append(if (markerInFlow) "flow" else "edge").append(") ")
        append("insets=").append(sideInset.value).append('/').append(bottomInset.value)
        append('/').append(innerInset.value).append('/').append(plateInset.value)
        append(" gap=").append(itemGap.value)
        append(" h=").append(barHeight.value).append('/').append(itemHeight.value)
        append(" marker=").append(markerWidthFraction).append('x').append(markerThickness.value)
        append(" shares=").append(itemWeights.joinToString(","))
        append(" frost=").append(frostTop).append('/').append(frostBottom)
        append(" edge=").append(edgeRule)
    }

    private companion object {
        /** The live bar's destination count: Home, Vault, Auth, Crypto, Cards. */
        const val TAB_COUNT = 5
    }
}
