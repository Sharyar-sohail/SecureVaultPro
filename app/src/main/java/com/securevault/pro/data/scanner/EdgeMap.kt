package com.securevault.pro.data.scanner

import kotlin.math.max
import kotlin.math.min

/**
 * Gradient and morphology stage.
 *
 * The Sobel operator is applied exactly once per probe buffer and its magnitude map is reused by
 * three consumers — the edge mask, the quad refinement line fit and the boundary-sharpness
 * confidence score — so the detector's "how sure am I" number is measured against the same
 * derivative the corners were found in, not against the raw grey levels.
 */
object EdgeMap {

    /** Largest value [sobel] can emit: a full 0→255 step on both axes, |gx|=|gy|=1020. */
    const val MAX_MAGNITUDE: Int = 1442

    /**
     * Sobel gradient magnitude, rescaled to 0..255 so a histogram of it fits [Thresholds.LEVELS].
     *
     * The rescale is linear (`magnitude * 255 / 1442`), so a threshold expressed in the rescaled
     * domain is a threshold expressed in the true gradient domain — the 24/255 floor in
     * [Thresholds.gradientThreshold] is ≈136 of raw gradient, i.e. a solid 50 % grey step.
     */
    fun sobel(source: LumaPlane): LumaPlane {
        val width = source.width
        val height = source.height
        val out = IntArray(source.size)
        for (y in 0 until height) {
            val up = max(0, y - 1)
            val down = min(height - 1, y + 1)
            val row = y * width
            for (x in 0 until width) {
                val left = max(0, x - 1)
                val right = min(width - 1, x + 1)
                val p00 = source[left, up]
                val p01 = source[x, up]
                val p02 = source[right, up]
                val p10 = source[left, y]
                val p12 = source[right, y]
                val p20 = source[left, down]
                val p21 = source[x, down]
                val p22 = source[right, down]
                val gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20)
                val gy = (p20 + 2 * p21 + p22) - (p00 + 2 * p01 + p02)
                val magnitude = isqrt(gx * gx + gy * gy)
                out[row + x] = magnitude * 255 / MAX_MAGNITUDE
            }
        }
        return LumaPlane(width, height, out)
    }

    /** Binary gradient mask: everything at or above [threshold] is an edge pixel. */
    fun edges(gradient: LumaPlane, threshold: Int): BooleanArray {
        val mask = BooleanArray(gradient.size)
        for (index in gradient.pixels.indices) mask[index] = gradient.pixels[index] >= threshold
        return mask
    }

    /** Square dilation by [radius] (Chebyshev distance); closes small breaks in a contour. */
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int = 1): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val from = max(0, y - radius)
            val to = min(height - 1, y + radius)
            val row = y * width
            for (x in 0 until width) {
                var hit = false
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                var sourceY = from
                while (!hit && sourceY <= to) {
                    val sourceRow = sourceY * width
                    var sourceX = left
                    while (sourceX <= right) {
                        if (mask[sourceRow + sourceX]) {
                            hit = true
                            break
                        }
                        sourceX += 1
                    }
                    sourceY += 1
                }
                out[row + x] = hit
            }
        }
        return out
    }

    /** Binary erosion by [radius]: shrinks a mask so a paper region loses its fuzzy border. */
    fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int = 1): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val from = max(0, y - radius)
            val to = min(height - 1, y + radius)
            val row = y * width
            for (x in 0 until width) {
                var all = true
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                var sourceY = from
                while (all && sourceY <= to) {
                    val sourceRow = sourceY * width
                    var sourceX = left
                    while (sourceX <= right) {
                        if (!mask[sourceRow + sourceX]) {
                            all = false
                            break
                        }
                        sourceX += 1
                    }
                    sourceY += 1
                }
                // Border pixels only see part of the window; treat the outside as "off" so a
                // region touching the frame edge does not survive the erosion.
                out[row + x] = all && x >= radius && y >= radius &&
                    x < width - radius && y < height - radius
            }
        }
        return out
    }

    /** Morphological closing (dilate then erode) — bridges a 1-2 px gap in an edge contour. */
    fun close(mask: BooleanArray, width: Int, height: Int, radius: Int = 1): BooleanArray =
        erode(dilate(mask, width, height, radius), width, height, radius)
}
