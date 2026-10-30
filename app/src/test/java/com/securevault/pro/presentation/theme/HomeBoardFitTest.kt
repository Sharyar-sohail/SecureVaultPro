package com.securevault.pro.presentation.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's second complaint about the new boards: text cut off and cells overlapping.
 *
 * The cause is structural, not cosmetic. The home board hands every row its share of the pane
 * with a weight, so a tile that *demands* a height does not grow the row — it is clipped by the
 * card's own corner. `fitCell` is the replacement: the cell reads the height it was given and
 * negotiates what that height can pay for, in a fixed order (count band, padding, glyph, then
 * the label, and the label never below the legibility floor).
 *
 * These tests prove the negotiation, purely, for the sizes the fifteen shipped palettes actually
 * use. If a tile still clips, it is because this function promised a height it could not draw.
 */
class HomeBoardFitTest {

    /** The inline label the rails, bars and features draw at (`titleSmall`). */
    private val inlineLabel: TextUnit = 14.sp

    /** Heights a row can actually be handed on a phone, from a cramped landscape pane up. */
    private val heights = (44..180 step 4).map { it.dp }

    private val glyphs = listOf(24.dp, 30.dp, 34.dp, 40.dp)
    private val labelSizes = listOf(10.sp, 12.sp, 14.sp)
    private val pads = listOf(6.dp, 11.dp, 16.dp)

    private fun budget(fit: CellFit): Dp =
        fit.padV * 2 + fit.stackedHeight()

    @Test
    fun `a stacked cell never asks for more height than it was given`() {
        for (glyph in glyphs) {
            for (label in labelSizes) {
                for (pad in pads) {
                    for (height in heights) {
                        for (kind in listOf(TileKind.PLATE, TileKind.MICRO)) {
                            val fit = fitCell(kind, height, glyph, label, pad)
                            assertTrue(
                                "$kind at $height with ${label.value}sp/$glyph: content " +
                                    "${budget(fit)} does not fit (band=${fit.countBand})",
                                budget(fit) <= height
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an inline cell never asks for more height than it was given`() {
        for (glyph in glyphs) {
            for (pad in pads) {
                for (height in heights) {
                    for (kind in listOf(TileKind.ROW, TileKind.BAR, TileKind.FEATURE)) {
                        val fit = fitCell(kind, height, glyph, inlineLabel, pad)
                        val room = height - fit.padV * 2
                        val line = (fit.labelSize.value * 1.3f).dp
                        assertTrue(
                            "$kind at $height: seat ${fit.iconSize} taller than the ${room} it has",
                            fit.iconSize <= room
                        )
                        assertTrue(
                            "$kind at $height: one ${fit.labelSize} line needs $line, has $room",
                            line <= room
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `nothing is shrunk below the legibility floor`() {
        for (kind in TileKind.values()) {
            for (label in labelSizes) {
                // A cell handed almost nothing must still keep its text at the readable floor.
                // The glyph is what gives way — a small icon forgives, a clipped label does not.
                val fit = fitCell(kind, 26.dp, 40.dp, label, 16.dp)
                assertTrue("$kind label shrank to ${fit.labelSize}", fit.labelSize.value >= 9f)
                assertTrue("$kind glyph shrank to ${fit.iconSize}", fit.iconSize.value >= 10f)
                val roomy = fitCell(kind, 60.dp, 40.dp, label, 16.dp)
                assertTrue("$kind glyph at 60 dp is ${roomy.iconSize}", roomy.iconSize >= 16.dp)
            }
        }
    }

    @Test
    fun `a tall cell keeps the palette's own sizes`() {
        for (kind in listOf(TileKind.PLATE, TileKind.MICRO)) {
            val fit = fitCell(kind, 180.dp, 34.dp, 12.sp, 11.dp)
            assertEquals(12f, fit.labelSize.value, 0.001f)
            assertEquals(34.dp, fit.iconSize)
            assertTrue("$kind dropped its count band on a 180 dp row", fit.countBand)
        }
        val rail = fitCell(TileKind.ROW, 180.dp, 34.dp, inlineLabel, 11.dp)
        assertEquals(inlineLabel.value, rail.labelSize.value, 0.001f)
    }

    @Test
    fun `a label only takes a second line when the row can pay for it`() {
        for (height in heights) {
            val fit = fitCell(TileKind.PLATE, height, 34.dp, 12.sp, 11.dp)
            val secondLine = fit.stackedHeight(2) + fit.padV * 2
            assertEquals(
                "lines=${fit.labelLines} band=${fit.countBand} pad=${fit.padV} " +
                    "icon=${fit.iconSize} gap=${fit.gap} label=${fit.labelSize} " +
                    "second=$secondLine of $height",
                secondLine <= height,
                fit.labelLines == 2
            )
        }
    }

    @Test
    fun `the fifteen palettes fit inside the board they are actually handed`() {
        // The real case: the shipped rhythm, the shipped palette sizes, the shipped thirteen
        // modules, and a board box from a small phone's pane up to a tablet's.
        for (palette in VaultPalettes.all) {
            val layout = palette.layout
            val rhythm = BoardRhythm.of(layout)
            val rows = boardGrid(layout, 13)
            for (box in listOf(320, 380, 440, 520)) {
                val rowHeight = (box.dp - rhythm.rowGap * (rows.size - 1)) / rows.size
                for (cell in rows.flatten()) {
                    val fit = fitCell(
                        cell.kind,
                        rowHeight,
                        palette.moduleIconSize,
                        palette.moduleLabelSize,
                        palette.moduleTilePadV
                    )
                    val line = (fit.labelSize.value * 1.3f).dp
                    val used = when (cell.kind) {
                        TileKind.PLATE, TileKind.MICRO -> fit.padV * 2 + fit.stackedHeight()
                        else -> fit.padV * 2 + maxOf(fit.iconSize, line * if (fit.hint) 2 else 1)
                    }
                    assertTrue(
                        "${palette.id}/${layout} ${cell.kind} in a $box dp box: $used of $rowHeight",
                        used <= rowHeight
                    )
                }
            }
        }
    }
}
