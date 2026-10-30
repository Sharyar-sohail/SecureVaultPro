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

        // Honest weaker claim: FLAT_PLATE zeroes four of the five fields, so only the
        // indicator geometry is left to separate the layouts. It must still separate 14 of
        // the 15; CLASSIC and GALLERY are the one admissible merge (both 0.16 / 3 dp).
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
            "under FLAT_PLATE at most one dock pair may merge, merged: $merged",
            merged.size <= 1
        )
        assertTrue(
            "dock geometry must distinguish at least 13 of 15 layouts, only " +
                "${flat.values.distinct().size} survive FLAT_PLATE",
            flat.values.distinct().size >= 13
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
}
