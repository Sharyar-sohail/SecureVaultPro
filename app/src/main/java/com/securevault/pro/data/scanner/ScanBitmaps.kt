package com.securevault.pro.data.scanner

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * The only place the pure core touches [Bitmap].
 *
 * Kept this thin on purpose: a bitmap is a native allocation whose pixel order
 * (`0xAARRGGBB`, non-premultiplied, row-major, stride = width) is exactly [ArgbPlane]'s, so the
 * conversion is one `getPixels` / one `setPixels` and no channel shuffling. Anything cleverer
 * belongs in the core, where a test can reach it.
 */
object ScanBitmaps {

    fun argbOf(bitmap: Bitmap): ArgbPlane {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ArgbPlane(width, height, pixels)
    }

    /** Copies a plane into a fresh RGB_565-free ARGB_8888 bitmap; the caller owns the result. */
    fun toBitmap(plane: ArgbPlane): Bitmap {
        val bitmap = Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(plane.pixels, 0, plane.width, 0, 0, plane.width, plane.height)
        return bitmap
    }

    /**
     * Runs the page pipeline on a decoded bitmap.
     *
     * Returns the finished bitmap plus the pure-Kotlin [ScanOutcome] so the caller can show the
     * detection notice. When the pipeline could not improve on the input the *same* bitmap is
     * returned (identity), which callers must not recycle twice — the existing scanner code recycles
     * conditionally on `!==` and that contract is preserved here.
     */
    fun process(
        source: Bitmap,
        look: ScanLook,
        autoCrop: Boolean = true,
        overrideQuad: FloatArray? = null,
        maxDimension: Int = 2400
    ): Pair<Bitmap, ScanOutcome> {
        val plane = argbOf(source)
        val outcome = DocumentPipeline.process(plane, look, autoCrop, overrideQuad, maxDimension)
        if (outcome.plane === plane) return source to outcome
        return toBitmap(outcome.plane) to outcome
    }

    /** Rectifies a bitmap onto a quad the user approved, without re-running detection. */
    fun rectify(source: Bitmap, quad: FloatArray, look: ScanLook, maxDimension: Int = 2400): Bitmap? {
        val outcome = DocumentPipeline.rectifyOnly(argbOf(source), quad, look, maxDimension) ?: return null
        return toBitmap(outcome.plane)
    }

    /** Downscale to a longest side, for a review thumbnail; returns the input when already small. */
    fun fitWithin(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    }

    /**
     * The cap on the pixels a page buffer may hold, as a function of the device's heap.
     *
     * 2400 px is comfortable on a 6 GB-RAM handset and marginal on a 2 GB one; the scanner takes
     * the smaller of the requested cap and what the runtime says is left, so a 40-page session on a
     * cheap phone degrades to 1600 px pages rather than to a killed process.
     */
    fun safeDimension(requested: Int, availableHeapBytes: Long): Int {
        val budget = availableHeapBytes / 6L  // one page, one preview, one temp copy
        val pixels = max(1L, budget / 4L)
        val side = max(640, min(requested.toLong(), sqrtInt(pixels)))
        return side.toInt()
    }

    private fun sqrtInt(value: Long): Long {
        if (value <= 0) return 0
        var root = Math.sqrt(value.toDouble()).toLong()
        while ((root + 1) * (root + 1) <= value) root += 1
        while (root * root > value) root -= 1
        return root
    }
}
