package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.LumaPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour the owner keeps, and the luminance the reader measures.
 *
 * A carrier leaves the app as an ordinary colour JPEG, which is only true if [StegoImages.tinted]
 * paints the marked grey back onto the photo without moving its hue and without spending more than
 * the last rounding step. These tests are the whole promise of the colour path: nothing here touches
 * `android.graphics`, so the JVM can prove it on a synthetic grid.
 */
class StegoColourTest {

    private fun argb(red: Int, green: Int, blue: Int) =
        (-0x1000000) or (red shl 16) or (green shl 8) or blue

    /** A grid of real photograph colours: sky, skin, grass, shadow, and the two saturated ends. */
    private val colors = intArrayOf(
        argb(120, 180, 230),
        argb(210, 160, 120),
        argb(60, 130, 70),
        argb(25, 30, 45),
        argb(255, 255, 255),
        argb(0, 0, 0),
        argb(255, 200, 40),
        argb(90, 20, 150)
    )

    private val plane = LumaPlane(4, 2, intArrayOf(118, 165, 200, 30, 250, 6, 190, 96))

    @Test
    fun `every tinted pixel reads back as the marked grey level`() {
        val tinted = StegoImages.tinted(plane, colors)
        assertEquals(plane.samples.size, tinted.size)
        for (index in plane.samples.indices) {
            assertEquals(
                "pixel $index",
                plane.samples[index],
                StegoImages.lumaOf(tinted[index])
            )
        }
    }

    @Test
    fun `a tint keeps hue where the photo has headroom`() {
        val tinted = StegoImages.tinted(plane, colors)
        // Hue is the ratio between the channels' differences from their own mean. A whole-pixel
        // brightness shift leaves it alone; a per-channel shift would not.
        for (index in 0 until 4) {
            val before = channels(colors[index])
            val after = channels(tinted[index])
            val meanBefore = (before[0] + before[1] + before[2] + 2) / 3
            val meanAfter = (after[0] + after[1] + after[2] + 2) / 3
            for (channel in 0 until 3) {
                val spread = (after[channel] - meanAfter) - (before[channel] - meanBefore)
                assertTrue(
                    "channel $channel of pixel $index moved $spread out of shape",
                    kotlin.math.abs(spread) <= 1
                )
            }
        }
    }

    @Test
    fun `an untouched plane leaves a colour pixel almost where it was`() {
        val source = LumaPlane(1, colors.size, IntArray(colors.size) { StegoImages.lumaOf(colors[it]) })
        val tinted = StegoImages.tinted(source, colors)
        for (index in colors.indices) {
            for (channel in 0 until 3) {
                val moved = kotlin.math.abs(((channels(tinted[index])[channel]) - channels(colors[index])[channel]))
                assertTrue("pixel $index channel $channel moved by $moved", moved <= 3)
            }
        }
    }

    @Test
    fun `a shorter colour source still produces a full grid`() {
        val tinted = StegoImages.tinted(plane, colors.copyOfRange(0, 3))
        assertEquals(plane.samples.size, tinted.size)
        for (index in 3 until plane.samples.size) {
            assertEquals(plane.samples[index], StegoImages.lumaOf(tinted[index]))
        }
    }

    @Test
    fun `the alpha of the photo survives the tint`() {
        val transparent = argb(200, 100, 50) and 0xFFFFFF // fully transparent, colour still set
        val one = LumaPlane(1, 1, intArrayOf(140))
        val tinted = StegoImages.tinted(one, intArrayOf(transparent))
        assertEquals(0, (tinted[0] ushr 24))
        assertEquals(140, StegoImages.lumaOf(tinted[0]))
    }

    private fun channels(color: Int) =
        intArrayOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
}
