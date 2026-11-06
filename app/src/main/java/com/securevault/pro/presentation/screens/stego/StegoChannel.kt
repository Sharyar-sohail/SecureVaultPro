package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.LumaPlane
import kotlin.math.roundToInt

/**
 * The redraw a messaging app performs on a photo, written so a JVM test can run it.
 *
 * Sending a picture through a chat app is not a file transfer. The app decodes the image, rescales it
 * so its longest side fits a budget (about 1280 for a phone send), softens it, and re-encodes it as a
 * JPEG at a middling quality. What survives is not what was written.
 *
 * The re-encode is the part the mark has to be designed against, and its rule is one sentence: a JPEG
 * encoder keeps the *average* of every 8x8 block nearly intact and throws away most of the
 * *variation inside* that block. So this file models exactly that — the block mean is rounded to a
 * coarse step, and each pixel's departure from its block mean is multiplied down — rather than
 * pretending to a full discrete cosine transform. The model is honest about what it is: an estimate
 * of the damage, in the same shape as the real damage, which the encoder uses to *choose* a grid.
 * The Android side runs the real encoder over the same plane before it promises anything.
 */
object StegoChannel {

    /** The longest side a phone send typically rescales a photo to. */
    const val SEND_LONGEST = 1280

    /**
     * The same send on a messenger that trims tighter than the grid this app writes on.
     *
     * [SEND_LONGEST] happens to be the canonical working size, so a photo resized to it lands back on
     * the writer's own cells and the resize is nearly free. That makes it the *easier* case, and a
     * robustness test that only measured it would prove nothing about geometry. This is the budget a
     * second-rate compressor uses, and the one the seal's self-test is held to: the mark has to come
     * back off a plane the reader has to put on the canonical grid before it can be decoded.
     */
    const val TIGHT_SEND_LONGEST = 960

    /**
     * Share of the variation inside an 8x8 block that a middling-quality re-encode keeps.
     *
     * Somewhere under half. The number is deliberately pessimistic: quality 70 on a phone leaves the
     * lowest-frequency AC coefficients and discards the rest, so a mark carried by block-internal
     * detail loses most of its amplitude here.
     */
    const val DETAIL_KEPT = 0.42f

    /**
     * Grey levels the block mean is rounded to. The mean is the encoder's cheapest coefficient, so
     * this is small — which is precisely why a mark written as a whole-block average costs almost
     * nothing to carry.
     */
    const val MEAN_STEP = 1.25f

    /** A chat app's send: rescale, soften, requantise. */
    fun redraw(
        plane: LumaPlane,
        longestSide: Int = TIGHT_SEND_LONGEST,
        detailKept: Float = DETAIL_KEPT,
        meanStep: Float = MEAN_STEP
    ): LumaPlane {
        val scaled = resized(plane, longestSide)
        return requantised(blurred(scaled), detailKept, meanStep)
    }

    /** Aspect-preserving box resample so the longest side is `longestSide`, never upscaled. */
    fun resized(plane: LumaPlane, longestSide: Int): LumaPlane {
        val longest = StegoPlanes.longestSide(plane)
        if (longestSide <= 0 || longest <= longestSide) return plane
        val scale = longestSide.toFloat() / longest
        val width = (plane.width * scale).roundToInt().coerceAtLeast(1)
        val height = (plane.height * scale).roundToInt().coerceAtLeast(1)
        return StegoPlanes.resampled(plane, width, height)
    }

    /**
     * A gentle 3x3 softening, applied twice.
     *
     * One pass of a 3x3 box is barely a blur; two are what a phone pipeline's denoise looks like at
     * this scale — enough to flatten a mark that leaned on neighbouring cells disagreeing, and not
     * enough to move a whole-block average, which is the difference the coarse grid is trying to
     * exploit.
     */
    fun blurred(plane: LumaPlane): LumaPlane {
        val once = box(plane)
        return box(once)
    }

    private fun box(plane: LumaPlane): LumaPlane {
        val width = plane.width
        val height = plane.height
        val out = IntArray(plane.samples.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var total = 0L
                var count = 0
                for (dy in -1..1) {
                    val sy = y + dy
                    if (sy < 0 || sy >= height) continue
                    for (dx in -1..1) {
                        val sx = x + dx
                        if (sx < 0 || sx >= width) continue
                        total += plane.samples[sy * width + sx]
                        count += 1
                    }
                }
                out[y * width + x] = if (count == 0) 128 else ((total + count / 2) / count).toInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(width, height, out)
    }

    /**
     * What a block requantiser does to a picture: hold each 8x8 block's average to a coarse step,
     * attenuate everything inside it, and write the two back.
     *
     * The block average is taken before either operation so the two never fight over the same pixels.
     * `meanStep` also acts as the dither the encoder's rounding introduces, which is the noise a
     * receiver's soft vote has to average away.
     */
    fun requantised(
        plane: LumaPlane,
        detailKept: Float = DETAIL_KEPT,
        meanStep: Float = MEAN_STEP,
        block: Int = 8
    ): LumaPlane {
        val width = plane.width
        val height = plane.height
        val out = IntArray(plane.samples.size)
        var y = 0
        while (y < height) {
            val blockHigh = minOf(block, height - y)
            var x = 0
            while (x < width) {
                val blockWide = minOf(block, width - x)
                var total = 0L
                for (dy in 0 until blockHigh) {
                    val row = (y + dy) * width
                    for (dx in 0 until blockWide) total += plane.samples[row + x + dx]
                }
                val cells = blockWide * blockHigh
                val mean = total.toDouble() / cells
                // The block's average is the encoder's cheapest coefficient: it is rounded to a coarse
                // step but hardly moved, so a mark carried by that average costs the photo almost
                // nothing and arrives intact.
                val quantised = (mean / meanStep).roundToInt() * meanStep
                for (dy in 0 until blockHigh) {
                    val row = (y + dy) * width
                    for (dx in 0 until blockWide) {
                        val index = row + x + dx
                        val value = plane.samples[index]
                        val detail = value - mean
                        out[index] = (quantised + detail * detailKept)
                            .roundToInt()
                            .coerceIn(0, 255)
                    }
                }
                x += blockWide
            }
            y += blockHigh
        }
        return LumaPlane(width, height, out)
    }

    /** The brightness-only version of the same damage: a whole-photo gamma push, no detail touched. */
    fun brightened(plane: LumaPlane, delta: Int): LumaPlane =
        plane.map { it + delta }
}
