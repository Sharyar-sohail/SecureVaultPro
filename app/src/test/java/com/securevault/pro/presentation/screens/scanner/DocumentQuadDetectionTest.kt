package com.securevault.pro.presentation.screens.scanner

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The live viewfinder and the still-image path share [quadFromGray], so the detector is
 * exercised here as pure maths: a luminance buffer in, a corner list out, no Android.
 */
class DocumentQuadDetectionTest {

    private fun buffer(width: Int, height: Int, fill: (x: Int, y: Int) -> Int): IntArray {
        val gray = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                gray[index] = fill(x, y)
                index += 1
            }
        }
        return gray
    }

    @Test
    fun `finds an axis-aligned sheet`() {
        val width = 240
        val height = 320
        val gray = buffer(width, height) { x, y ->
            if (x in 40..200 && y in 70..250) 225 else 38
        }
        val quad = quadFromGray(gray, width, height)
        assertTrue("a bright rectangle must be found", quad != null)
        val found = quad!!
        assertTrue("top-left corner should be near (40, 70)", distance(found[0], found[1], 40f, 70f) < 14f)
        assertTrue("top-right corner should be near (200, 70)", distance(found[2], found[3], 200f, 70f) < 14f)
        assertTrue("bottom-right corner should be near (200, 250)", distance(found[4], found[5], 200f, 250f) < 14f)
        assertTrue("bottom-left corner should be near (40, 250)", distance(found[6], found[7], 40f, 250f) < 14f)
    }

    @Test
    fun `finds a tilted sheet`() {
        val width = 240
        val height = 320
        // A parallelogram: the sheet seen at an angle, which is what the crop relies on.
        val gray = buffer(width, height) { x, y ->
            val lean = (y - 160) * 0.35f
            if (y in 60..260 && x in (50 + lean).toInt()..(190 + lean).toInt()) 220 else 30
        }
        val quad = quadFromGray(gray, width, height)
        assertTrue("a tilted rectangle must still be found", quad != null)
        val found = quad!!
        val area = abs(found[0] * found[3] - found[2] * found[1] +
            found[2] * found[5] - found[4] * found[3] +
            found[4] * found[7] - found[6] * found[5] +
            found[6] * found[1] - found[0] * found[7]) / 2f
        assertTrue("tilted sheet should cover most of its true area", area > 10_000f)
    }

    @Test
    fun `refuses a flat frame so the photo is never cropped blindly`() {
        val width = 200
        val height = 200
        val gray = buffer(width, height) { _, _ -> 120 }
        assertTrue(quadFromGray(gray, width, height) == null)
    }

    @Test
    fun `refuses a sheet that fills the whole frame`() {
        val width = 200
        val height = 260
        val gray = buffer(width, height) { x, y ->
            if (x in 2..197 && y in 2..257) 230 else 20
        }
        assertTrue(
            "a page that covers the frame gives no reliable outline",
            quadFromGray(gray, width, height) == null
        )
    }

    private fun distance(firstX: Float, firstY: Float, secondX: Float, secondY: Float): Float =
        sqrt((firstX - secondX) * (firstX - secondX) + (firstY - secondY) * (firstY - secondY))
}
