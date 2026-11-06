package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.StegoLimits
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel-buffer operations the codec needs that the stego core deliberately leaves out.
 *
 * `crypto/stego` speaks only one picture type — an `IntArray` of 0..255 luminance samples — so that
 * the maths runs identically on a JVM and on a handset. Everything that gets a photograph into that
 * shape (rotate, area-average, aspect) belongs next to it and stays free of `android.*` for the same
 * reason, so a test can hand it a synthetic plane.
 *
 * The bitmap half of this job is in `StegoImages.kt`, which is the only file in this package that
 * imports `android.graphics`.
 */
object StegoPlanes {

    /** Quarter turns clockwise: 0 keeps the frame, 1 turns right, 2 flips, 3 turns left. */
    fun rotated(plane: LumaPlane, quarterTurns: Int): LumaPlane {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return plane
        val width = plane.width
        val height = plane.height
        return when (turns) {
            1 -> {
                val out = IntArray(plane.samples.size)
                for (y in 0 until height) {
                    val row = y * width
                    val targetColumn = height - 1 - y
                    for (x in 0 until width) out[x * height + targetColumn] = plane.samples[row + x]
                }
                LumaPlane(height, width, out)
            }
            2 -> {
                val out = IntArray(plane.samples.size)
                for (y in 0 until height) {
                    val row = y * width
                    val targetRow = (height - 1 - y) * width
                    for (x in 0 until width) out[targetRow + (width - 1 - x)] = plane.samples[row + x]
                }
                LumaPlane(width, height, out)
            }
            else -> {
                // 270 clockwise == 90 counter-clockwise: (x, y) -> (y, width - 1 - x).
                val out = IntArray(plane.samples.size)
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = plane.samples[row + x]
                    }
                }
                LumaPlane(height, width, out)
            }
        }
    }

    /**
     * Area-average resample to `targetWidth x targetHeight`.
     *
     * Box averaging rather than nearest neighbour, because the mark lives in the 4x4 LL cell means:
     * a nearest-neighbour decimation *aliases* the photo's own grain into the band and can move a
     * cell mean by several grey levels, which is exactly the channel the chips occupy. Downsampling
     * is the step the encoder's own budget (`StegoLimits.MAX_WORKING_PIXELS`) relies on being honest.
     */
    fun resampled(plane: LumaPlane, targetWidth: Int, targetHeight: Int): LumaPlane {
        val w = max(1, targetWidth)
        val h = max(1, targetHeight)
        if (w == plane.width && h == plane.height) return plane
        val out = IntArray(w * h)
        val xRatio = plane.width.toDouble() / w
        val yRatio = plane.height.toDouble() / h
        for (y in 0 until h) {
            val fromY = (y * yRatio).toInt().coerceAtMost(plane.height - 1)
            val toY = max(fromY + 1, ((y + 1) * yRatio).toInt()).coerceAtMost(plane.height)
            for (x in 0 until w) {
                val fromX = (x * xRatio).toInt().coerceAtMost(plane.width - 1)
                val toX = max(fromX + 1, ((x + 1) * xRatio).toInt()).coerceAtMost(plane.width)
                var total = 0L
                var count = 0
                for (sourceY in fromY until toY) {
                    val row = sourceY * plane.width
                    for (sourceX in fromX until toX) {
                        total += plane.samples[row + sourceX]
                        count += 1
                    }
                }
                out[y * w + x] = if (count == 0) 128 else ((total + count / 2) / count).toInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(w, h, out)
    }

    /**
     * Area-average resample of a packed ARGB grid — the colour twin of [resampled], same box, same
     * rounding, one channel at a time.
     *
     * It lives here rather than beside `StegoImages.bitmapOfArgb` because it is `resampled` with three
     * accumulators, and this file has to stay free of `android.*` so a JVM test can hand it a grid.
     * A colour preview is built from a carrier's own colour array at preview size, which keeps the
     * full-size pixels the one copy of them the app holds.
     */
    fun resampledArgb(
        width: Int,
        height: Int,
        pixels: IntArray,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val w = max(1, targetWidth)
        val h = max(1, targetHeight)
        if (w == width && h == height) return pixels
        val fallback = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80
        val out = IntArray(w * h)
        val xRatio = width.toDouble() / w
        val yRatio = height.toDouble() / h
        for (y in 0 until h) {
            val fromY = (y * yRatio).toInt().coerceAtMost(height - 1)
            val toY = max(fromY + 1, ((y + 1) * yRatio).toInt()).coerceAtMost(height)
            for (x in 0 until w) {
                val fromX = (x * xRatio).toInt().coerceAtMost(width - 1)
                val toX = max(fromX + 1, ((x + 1) * xRatio).toInt()).coerceAtMost(width)
                var red = 0L
                var green = 0L
                var blue = 0L
                var count = 0
                for (sourceY in fromY until toY) {
                    val row = sourceY * width
                    for (sourceX in fromX until toX) {
                        val color = pixels.getOrElse(row + sourceX) { fallback }
                        red += (color shr 16) and 0xFF
                        green += (color shr 8) and 0xFF
                        blue += color and 0xFF
                        count += 1
                    }
                }
                if (count == 0) {
                    out[y * w + x] = fallback
                    continue
                }
                val half = count / 2
                out[y * w + x] = (0xFF shl 24) or
                    (((red + half) / count).toInt() shl 16) or
                    (((green + half) / count).toInt() shl 8) or
                    ((blue + half) / count).toInt()
            }
        }
        return out
    }

    /** Fitted into the encoder's pixel budget, aspect preserved, never upscaled. */
    fun fitted(plane: LumaPlane, maxPixels: Int = com.securevault.pro.crypto.stego.StegoLimits.MAX_WORKING_PIXELS): LumaPlane {
        val target = StegoGeometry.scaleToFit(plane.width, plane.height, maxPixels)
        if (target.first <= 0 || target.second <= 0) return plane
        if (target.first == plane.width && target.second == plane.height) return plane
        return resampled(plane, target.first, target.second)
    }

    /** Aspect ratio, guarded against a zero side. */
    fun aspect(plane: LumaPlane): Float =
        if (plane.height <= 0) 0f else plane.width.toFloat() / plane.height.toFloat()

    /**
     * The smallest copy worth putting back on the canonical grid.
     *
     * Half of [StegoLimits.CANONICAL_LONGEST]: a photo that came through a chat app is bigger than
     * this, and a live camera preview frame is not — so the reader's second geometry costs nothing on
     * the frames the viewfinder runs several times a second, and is still there for the file the
     * receiver actually picked from their gallery.
     */
    val RESCUE_MIN_LONGEST: Int = StegoLimits.CANONICAL_LONGEST / 2

    /**
     * The same photo, measured on the one grid this app seals: [StegoLimits.CANONICAL_LONGEST] along
     * its longer side, aspect preserved, box-filtered like every other resample here.
     *
     * Returns the plane untouched when it already is canonical, and when it is too small to be worth
     * stretching — an upscaled blur carries no more mark than the original did, so there is nothing
     * for the rescue to buy. The writer uses this before sealing; the reader uses it as a second
     * guess when a mark will not answer at the size it arrived.
     */
    fun canonicalised(plane: LumaPlane): LumaPlane {
        val longest = longestSide(plane)
        if (longest < StegoLimits.MIN_WORKING_LONGEST) return plane
        val target = StegoLimits.CANONICAL_LONGEST
        if (longest == target) return plane
        val scale = target.toFloat() / longest
        val width = max(1, (plane.width * scale).roundToInt())
        val height = max(1, (plane.height * scale).roundToInt())
        if (width == plane.width && height == plane.height) return plane
        return resampled(plane, width, height)
    }

    /** Longest side, the number every minimum-size rule in the subsystem is phrased against. */
    fun longestSide(plane: LumaPlane): Int = max(plane.width, plane.height)

    /** Mean grey level, used to reject an all-black frame before the maths starts. */
    fun meanOf(plane: LumaPlane): Int {
        if (plane.samples.isEmpty()) return 0
        var total = 0L
        for (value in plane.samples) total += value
        return (total / plane.samples.size).toInt()
    }

    /**
     * Standard deviation of the raw samples — a cheap "is there a picture here?" probe that needs no
     * band transform, so the viewfinder can call it on every frame.
     */
    fun contrastOf(plane: LumaPlane): Float {
        if (plane.samples.isEmpty()) return 0f
        val mean = meanOf(plane).toDouble()
        var energy = 0.0
        for (value in plane.samples) {
            val delta = value - mean
            energy += delta * delta
        }
        return kotlin.math.sqrt(energy / plane.samples.size).toFloat()
    }

    /**
     * Pushes the working plane into the 8-bit contract the band transform assumes. Cheap insurance
     * after a decode of a camera frame, whose Y plane can carry values outside 0..255 on some
     * sensors when the row stride runs short.
     */
    fun sanitised(plane: LumaPlane): LumaPlane {
        if (LumaPlane.isSane(plane.samples)) return plane
        val out = IntArray(plane.samples.size)
        for (index in plane.samples.indices) out[index] = plane.samples[index].coerceIn(0, 255)
        return LumaPlane(plane.width, plane.height, out)
    }

    /** Every orientation, upright first: the acquisition loop stops as soon as one answers. */
    val orientations: List<Int> = listOf(0, 1, 2, 3)

    /** Column-major flip used by the dispersal set when three carriers are shown as a strip. */
    fun sideBySide(planes: List<LumaPlane>, gap: Int = 4): LumaPlane {
        if (planes.isEmpty()) return LumaPlane.filled(1, 1)
        val height = planes.maxOf { it.height }
        val widths = planes.map { it.width }
        val totalWidth = widths.sum() + gap * (planes.size - 1)
        val out = IntArray(totalWidth * height) { 255 }
        var offset = 0
        planes.forEach { source ->
            for (y in 0 until height) {
                val sourceY = min(y, source.height - 1)
                for (x in 0 until source.width) {
                    out[y * totalWidth + offset + x] = source.samples[sourceY * source.width + x]
                }
            }
            offset += source.width + gap
        }
        return LumaPlane(totalWidth, height, out)
    }
}
