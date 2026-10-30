package com.securevault.pro.data.scanner

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Thresholding primitives.
 *
 * Two of them matter for a document scanner and they fail in opposite ways:
 *
 *  - **Otsu** picks the split that maximises between-class variance. On a bimodal histogram
 *    (paper + desk, ink + paper) it is the right answer and it is deterministic, which is why
 *    the tests assert an exact band rather than "some number".
 *  - **Adaptive** thresholds each pixel against its own neighbourhood. A page lit from one side
 *    has no single global split at all — a global threshold eats the shadow as a black blob and
 *    loses the pen stroke inside it, so the ink path uses the local version.
 *
 * Otsu is also unstable on a *unimodal* histogram (a flat page): the variance maximum lands
 * wherever the mode is, and a bogus "paper mask" gets built out of nothing. `otsuSeparation`
 * reports how bimodal the buffer actually is so callers can refuse instead of inventing corners.
 */
object Thresholds {

    const val LEVELS = 256

    fun histogram(values: IntArray, levels: Int = LEVELS): IntArray {
        val hist = IntArray(levels)
        for (value in values) {
            if (value in 0 until levels) hist[value] += 1 else hist[value.coerceIn(0, levels - 1)] += 1
        }
        return hist
    }

    /** Otsu's between-class-variance optimum as a threshold index in `0 until levels`. */
    fun otsu(hist: IntArray): Int {
        val total = hist.sum().toDouble()
        if (total <= 0.0) return 0
        var sum = 0.0
        for (index in hist.indices) sum += index * hist[index]
        var weightLow = 0.0
        var sumLow = 0.0
        var best = -1.0
        var threshold = 0
        for (index in hist.indices) {
            weightLow += hist[index]
            if (weightLow == 0.0) continue
            val weightHigh = total - weightLow
            if (weightHigh <= 0.0) break
            sumLow += index * hist[index]
            val meanLow = sumLow / weightLow
            val meanHigh = (sum - sumLow) / weightHigh
            val delta = meanLow - meanHigh
            val between = weightLow * weightHigh * delta * delta
            if (between > best) {
                best = between
                threshold = index
            }
        }
        return threshold
    }

    fun otsuOf(values: IntArray, levels: Int = LEVELS): Int = otsu(histogram(values, levels))

    /**
     * Normalised between-class variance, 0..1.
     *
     * `total variance` is the denominator, so a buffer with no ink at all scores ~0 and a clean
     * two-population scan scores near 1. Below ~0.35 there is no honest split to make and the
     * detector must say so instead of guessing a rectangle.
     */
    fun otsuSeparation(hist: IntArray): Double {
        val total = hist.sum().toDouble()
        if (total <= 1.0) return 0.0
        var sum = 0.0
        for (index in hist.indices) sum += index * hist[index]
        val mean = sum / total
        var variance = 0.0
        for (index in hist.indices) {
            val delta = index - mean
            variance += delta * delta * hist[index]
        }
        variance /= total
        if (variance <= 0.0) return 0.0
        var weightLow = 0.0
        var sumLow = 0.0
        var best = 0.0
        for (index in hist.indices) {
            weightLow += hist[index]
            if (weightLow == 0.0) continue
            val weightHigh = total - weightLow
            if (weightHigh <= 0.0) break
            sumLow += index * hist[index]
            val delta = sumLow / weightLow - (sum - sumLow) / weightHigh
            val between = weightLow * weightHigh * delta * delta / (total * total)
            if (between > best) best = between
        }
        return (best / variance).coerceIn(0.0, 1.0)
    }

    /** Percentile of a histogram as a level index; used for the ink/blank extremes of a page. */
    fun percentile(hist: IntArray, fraction: Double): Int {
        val total = hist.sum()
        if (total == 0) return 0
        val target = max(1, ceil(total * fraction).toInt())
        var running = 0
        for (index in hist.indices) {
            running += hist[index]
            if (running >= target) return index
        }
        return hist.lastIndex
    }

    /**
     * Local-mean adaptive binarisation (Bradley & Roth, integral-image formulation).
     *
     * A pixel becomes ink when it is at least [sensitivity] darker than the mean of the
     * [radius]-wide window around it. Because the comparison is local, a page that goes from
     * 120 to 220 grey across a shadow still yields its pen stroke; a global threshold on that
     * same image either clips the shadow to black or misses the stroke entirely.
     *
     * Returns `true` for ink. The integral table is built in Long to survive a 2400x3200 page.
     */
    fun adaptive(
        source: LumaPlane,
        radius: Int,
        sensitivity: Double = 0.15,
        minInk: Int = 0,
        maxInk: Int = 255
    ): BooleanArray {
        val width = source.width
        val height = source.height
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            val current = (y + 1) * (width + 1)
            val previous = y * (width + 1)
            for (x in 0 until width) {
                rowSum += source.pixels[y * width + x]
                integral[current + x + 1] = integral[previous + x + 1] + rowSum
            }
        }
        val ink = BooleanArray(width * height)
        var inkCount = 0
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height, y + radius + 1)
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width, x + radius + 1)
                val count = ((bottom - top) * (right - left)).toLong()
                val total = integral[bottom * (width + 1) + right] -
                    integral[top * (width + 1) + right] -
                    integral[bottom * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                val mean = total.toDouble() / count
                val here = y * width + x
                if (source.pixels[here] < mean * (1.0 - sensitivity)) {
                    ink[here] = true
                    inkCount += 1
                }
            }
        }
        // A window that finds nothing (or everything) has mis-read the page: hand back "no ink"
        // rather than a mask that would make the filter flip half the sheet to black.
        if (inkCount < minInk || inkCount > maxInk) return BooleanArray(width * height)
        return ink
    }

    /** Global binarisation with the ink polarity the page actually has (dark ink on light paper). */
    fun globalInk(source: LumaPlane, threshold: Int): BooleanArray {
        val ink = BooleanArray(source.size)
        for (index in source.pixels.indices) ink[index] = source.pixels[index] < threshold
        return ink
    }

    /** Fraction of samples flagged, in 0..1 — the sanity gate every mask passes before use. */
    fun fractionTrue(mask: BooleanArray): Double =
        if (mask.isEmpty()) 0.0 else mask.count { it }.toDouble() / mask.size

    /**
     * Threshold for a gradient-magnitude buffer: half Otsu, half a hard floor.
     *
     * Otsu on a gradient map of a blank page still returns a "best split" out of sensor noise, so
     * the floor (default 24/255 ≈ a 10 % step per pixel) is what stops a textureless desk from
     * producing an outline.
     */
    fun gradientThreshold(hist: IntArray, floor: Int = 24): Int =
        max(otsu(hist), floor)
}

/** Integer square root — the gradient magnitude needs one per pixel and no floating-point round trip. */
internal fun isqrt(value: Int): Int {
    if (value <= 0) return 0
    var root = sqrt(value.toDouble()).toInt()
    while ((root + 1) * (root + 1) <= value) root += 1
    while (root * root > value) root -= 1
    return root
}
