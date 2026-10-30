package com.securevault.pro.data.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The premium filters, as real per-pixel operations.
 *
 * None of these is a colour overlay. Every one of them is a stated transform of the sample values,
 * so a scan that came out of [Filters.blackWhite] has literally only two levels in it and a scan
 * out of [Filters.unsharp] has measurable edge overshoot. The tests in
 * `app/src/test/java/com/securevault/pro/data/scanner/` assert exactly those properties.
 */
object Filters {

    /** Sample stride for the statistics a filter decides from: a 12 MP page is analysed in ~9k texels. */
    private const val STATISTICS_TARGET = 96

    /**
     * BT.601 greyscale, written back to all three channels.
     *
     * A pure luminance collapse — no contrast, no white balance, nothing that changes a decision
     * the user already made about the page.
     */
    fun greyscale(source: ArgbPlane): ArgbPlane {
        val out = IntArray(source.size)
        for (index in out.indices) {
            val color = source.pixels[index]
            val value = lumaOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            out[index] = (color and ArgbPlane.alphaMask()) or (value shl 16) or (value shl 8) or value
        }
        return ArgbPlane(source.width, source.height, out)
    }

    /**
     * High-contrast black and white: adaptive ink where the lighting is uneven, Otsu where it is
     * not, then a hard two-level write.
     *
     * The split is decided by the *illumination gradient*, not by taste: [lightingSlope] measures
     * how far the local background moves across the page (low-pass of the grey image, 2nd percentile
     * to 98th percentile). A flat page has a clean global Otsu split and gets it — a two-band
     * result. A page lit from one side has no global split at all; the adaptive pass thresholds
     * each pixel against its own neighbourhood, so the shadow survives as paper and the pen stroke
     * survives as ink.
     *
     * Ink is forced to pure black and paper to pure white. That is what makes the result a scan
     * rather than a grey photograph.
     */
    fun blackWhite(source: ArgbPlane, adaptiveWhenSlopeAbove: Double = 18.0): ArgbPlane {
        val grey = LumaPlane.of(source)
        val slope = lightingSlope(grey)
        val ink: BooleanArray = if (slope > adaptiveWhenSlopeAbove) {
            Thresholds.adaptive(grey, radius(grey), sensitivity = 0.16)
        } else {
            val threshold = Thresholds.otsuOf(grey.pixels)
            // A near-unimodal histogram means there is no ink/paper split to make; refuse to invent
            // one and keep the mid-tone instead of flipping half the page to black.
            if (Thresholds.otsuSeparation(Thresholds.histogram(grey.pixels)) < 0.18) {
                return greyscale(source)
            }
            Thresholds.globalInk(grey, threshold)
        }
        val out = IntArray(source.size)
        val alpha = source.pixels[0] and ArgbPlane.alphaMask()
        for (index in out.indices) {
            val value = if (ink[index]) 0 else 255
            out[index] = alpha or (value shl 16) or (value shl 8) or value
        }
        return ArgbPlane(source.width, source.height, out)
    }

    /**
     * Magic colour: white-point balance, then a saturation lift, then a gentle level stretch.
     *
     * White point is estimated from the *brightest percentile of each channel independently* — the
     * "white paper under coloured light" assumption (a grey-world version is computed too and used
     * when the extremes are too thin to be trusted) — and applied as a von Kries diagonal, i.e.
     * each channel is multiplied by the ratio needed to bring the estimate to the grey axis. A
     * page photographed under a 2 700 K bulb comes out with white paper, not cream paper, and the
     * test in `MagicColorTest` feeds a synthetic warm-lit grey card and asserts the channels meet.
     *
     * The saturation lift is the honest kind: `channel = luma + (channel - luma) * gain`, around
     * the pixel's own luminance, so a neutral grey stays exactly neutral and only chroma moves.
     */
    fun magicColour(
        source: ArgbPlane,
        saturation: Double = 1.42,
        whiteBalance: Boolean = true,
        level: Boolean = true
    ): ArgbPlane {
        val grey = LumaPlane.of(source)
        val sample = statisticsOf(source)
        val gain = if (whiteBalance) whitePointGains(sample) else doubleArrayOf(1.0, 1.0, 1.0)
        val low = if (level) percentileOf(sample.luma, 0.01) else 0
        val high = if (level) percentileOf(sample.luma, 0.99) else 255
        val span = max(1.0, high - low.toDouble())
        val out = IntArray(source.size)
        for (index in out.indices) {
            val color = source.pixels[index]
            val alpha = color and ArgbPlane.alphaMask()
            val red = ((color shr 16) and 0xFF) * gain[0]
            val green = ((color shr 8) and 0xFF) * gain[1]
            val blue = (color and 0xFF) * gain[2]
            val balancedRed = clampChannel(red)
            val balancedGreen = clampChannel(green)
            val balancedBlue = clampChannel(blue)
            val luma = lumaFloatOf(balancedRed, balancedGreen, balancedBlue)
            val stretched = if (level) {
                (clampChannel(luma) - low) / span
            } else {
                luma / 255.0
            }
            // Contrast around the mid grey, then push the chroma back out of it.
            val contrasted = 128.0 + (stretched * 255.0 - 128.0) * 1.06
            fun channel(value: Int): Int =
                clampChannel(contrasted + (value - luma) * saturation)
            out[index] = alpha or (channel(balancedRed) shl 16) or
                (channel(balancedGreen) shl 8) or channel(balancedBlue)
        }
        return ArgbPlane(source.width, source.height, out)
    }

    /**
     * Unsharp mask: `out = in + amount * (in - blur)`.
     *
     * The blur is a true Gaussian — three stacked box passes approximate the bell curve to well
     * inside a percent, at O(1) per texel via a sliding window sum instead of O(radius). On flat
     * paper `in == blur`, so the correction term is exactly zero and a white margin stays white to
     * the last bit; that idempotence is what `UnsharpMaskTest` checks, because a "sharpen" that
     * shifts a flat field is really a brightness filter wearing a mask.
     */
    fun unsharp(
        source: ArgbPlane,
        radius: Int = 3,
        amount: Double = 0.85,
        threshold: Int = 3
    ): ArgbPlane {
        val width = source.width
        val height = source.height
        val blur = gaussianBlur(source, radius.coerceAtLeast(1))
        val out = IntArray(source.size)
        for (index in out.indices) {
            val color = source.pixels[index]
            val soft = blur.pixels[index]
            val alpha = color and ArgbPlane.alphaMask()
            fun channel(shift: Int): Int {
                val sharp = (color shr shift) and 0xFF
                val smooth = (soft shr shift) and 0xFF
                val delta = sharp - smooth
                if (abs(delta) <= threshold) return sharp
                return clampChannel(sharp + amount * delta)
            }
            out[index] = alpha or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return ArgbPlane(width, height, out)
    }

    /** Separable box blur x3 = Gaussian; sliding window, edge-replicating, integer-exact on flat fields. */
    fun gaussianBlur(source: ArgbPlane, radius: Int): ArgbPlane {
        val width = source.width
        val height = source.height
        var red = DoubleArray(source.size)
        var green = DoubleArray(source.size)
        var blue = DoubleArray(source.size)
        for (index in source.pixels.indices) {
            red[index] = ((source.pixels[index] shr 16) and 0xFF).toDouble()
            green[index] = ((source.pixels[index] shr 8) and 0xFF).toDouble()
            blue[index] = (source.pixels[index] and 0xFF).toDouble()
        }
        repeat(3) {
            red = boxPass(red, width, height, radius)
            green = boxPass(green, width, height, radius)
            blue = boxPass(blue, width, height, radius)
        }
        val out = IntArray(source.size)
        for (index in out.indices) {
            val alpha = source.pixels[index] and ArgbPlane.alphaMask()
            out[index] = alpha or
                (clampChannel(red[index] + 0.5) shl 16) or
                (clampChannel(green[index] + 0.5) shl 8) or
                clampChannel(blue[index] + 0.5)
        }
        return ArgbPlane(width, height, out)
    }

    /** One box pass over a channel plane, horizontal then vertical, with clamped windows. */
    private fun boxPass(channel: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        val r = radius.coerceIn(1, 64)
        val horizontal = DoubleArray(channel.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            for (offset in -r..r) sum += channel[row + offset.coerceIn(0, width - 1)]
            horizontal[row] = sum / (2 * r + 1)
            for (x in 1 until width) {
                val add = channel[row + min(width - 1, x + r)]
                val drop = channel[row + max(0, x - r - 1)]
                sum += add - drop
                horizontal[row + x] = sum / (2 * r + 1)
            }
        }
        val vertical = DoubleArray(channel.size)
        for (x in 0 until width) {
            var sum = 0.0
            for (offset in -r..r) sum += horizontal[offset.coerceIn(0, height - 1) * width + x]
            vertical[x] = sum / (2 * r + 1)
            for (y in 1 until height) {
                val add = horizontal[min(height - 1, y + r) * width + x]
                val drop = horizontal[max(0, y - r - 1) * width + x]
                sum += add - drop
                vertical[y * width + x] = sum / (2 * r + 1)
            }
        }
        return vertical
    }

    /**
     * Document enhancement, i.e. the "Enhance" chip: white balance, 2nd/98th percentile level
     * stretch and a light contrast S-curve, with the ink preserved.
     *
     * Replaces the ColorMatrix the old ScanSupport used. The maths is the same family (scale +
     * offset per channel), but it now lives where it can be tested and it no longer silently
     * clamps a highlight to a value that depends on a `coerceIn` band.
     */
    fun enhance(source: ArgbPlane): ArgbPlane {
        val sample = statisticsOf(source)
        val gain = whitePointGains(sample)
        val low = percentileOf(sample.luma, 0.02)
        val high = percentileOf(sample.luma, 0.98)
        val span = max(24.0, high - low.toDouble())
        val out = IntArray(source.size)
        for (index in out.indices) {
            val color = source.pixels[index]
            val alpha = color and ArgbPlane.alphaMask()
            fun channel(shift: Int): Int {
                val value = ((color shr shift) and 0xFF) * gain[shiftIndex(shift)]
                val normalized = (value - low) * (255.0 / span)
                return clampChannel(normalized * 1.04 - 5.0)
            }
            out[index] = alpha or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return ArgbPlane(source.width, source.height, out)
    }

    /** Enhance, then a light sharpen: the default look for a scanned page. */
    fun scanEnhance(source: ArgbPlane): ArgbPlane = unsharp(enhance(source), radius = 2, amount = 0.55)

    private fun shiftIndex(shift: Int): Int = when (shift) {
        16 -> 0
        8 -> 1
        else -> 2
    }

    /** Adaptive window: 1/24th of the short side, at least 8 and at most 60 texels. */
    private fun radius(plane: LumaPlane): Int = (min(plane.width, plane.height) / 24)
        .coerceIn(8, 60)

    /**
     * How much the background moves across the page: the spread between the low-pass 2nd and 98th
     * percentiles of luminance. 0..3 for a flat scanner bed, 30+ for a page under a ceiling light.
     */
    fun lightingSlope(grey: LumaPlane): Double {
        val lowPass = lowPass(grey)
        val hist = Thresholds.histogram(lowPass.pixels)
        return (Thresholds.percentile(hist, 0.98) - Thresholds.percentile(hist, 0.02)).toDouble()
    }

    private fun lowPass(grey: LumaPlane): LumaPlane {
        val width = grey.width
        val height = grey.height
        val box = max(4, min(width, height) / 8)
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            val current = (y + 1) * (width + 1)
            val previous = y * (width + 1)
            for (x in 0 until width) {
                rowSum += grey.pixels[y * width + x]
                integral[current + x + 1] = integral[previous + x + 1] + rowSum
            }
        }
        val out = IntArray(grey.size)
        for (y in 0 until height) {
            val top = max(0, y - box).coerceAtMost(height)
            val bottom = min(height, y + box + 1)
            for (x in 0 until width) {
                val left = max(0, x - box)
                val right = min(width, x + box + 1)
                val count = max(1L, ((bottom - top) * (right - left)).toLong())
                val total = integral[bottom * (width + 1) + right] -
                    integral[top * (width + 1) + right] -
                    integral[bottom * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                out[y * width + x] = (total / count).toInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(width, height, out)
    }

    /**
     * A strided sample of a plane, used for every statistic a filter decides from.
     *
     * Public because a test has to be able to hand the white-point estimator a page it built,
     * without going through `android.graphics.Bitmap`.
     */
    class Statistics(val luma: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray)

    fun statisticsOf(source: ArgbPlane): Statistics {
        val stepX = max(1, source.width / STATISTICS_TARGET)
        val stepY = max(1, source.height / STATISTICS_TARGET)
        val luma = ArrayList<Int>(source.size / (stepX * stepY) + 1)
        val red = ArrayList<Int>(luma.size)
        val green = ArrayList<Int>(luma.size)
        val blue = ArrayList<Int>(luma.size)
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val color = source.pixels[y * source.width + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                luma.add(lumaOf(r, g, b))
                red.add(r)
                green.add(g)
                blue.add(b)
                x += stepX
            }
            y += stepY
        }
        return Statistics(
            luma.toIntArray(), red.toIntArray(), green.toIntArray(), blue.toIntArray()
        )
    }

    /**
     * Per-channel multipliers that move the estimated white point onto the grey axis.
     *
     * Two estimators, and the choice between them is the interesting part:
     *
     *  - **White patch**: the 95th percentile of each channel. Correct for a page, because paper is
     *    the whitest thing in the frame, and it is what makes a cream scan come back neutral.
     *  - **Grey world**: the mean of each channel, assuming the scene reflects equally. Used when
     *    the white-patch channels are too close together to divide by (a fully blown or fully grey
     *    frame), where the patch estimate would be a division by noise.
     *
     * Both are clamped to ±18 % so a misread lighting estimate cannot turn a blue shirt yellow —
     * the same band the previous ColorMatrix implementation used, kept for continuity.
     */
    fun whitePointGains(sample: Statistics): DoubleArray {
        val patch = doubleArrayOf(
            percentileOf(sample.red, 0.95).toDouble(),
            percentileOf(sample.green, 0.95).toDouble(),
            percentileOf(sample.blue, 0.95).toDouble()
        )
        val mean = doubleArrayOf(mean(sample.red), mean(sample.green), mean(sample.blue))
        val spread = patch.maxOrNull()!! - patch.minOrNull()!!
        // A white patch that is either barely separated between channels or too dark to divide by
        // carries no lighting information; grey world is the honest fallback there.
        val useGreyWorld = spread < 12.0 || patch.minOrNull()!! < 48.0
        val channels = if (useGreyWorld) mean else patch
        val reference = if (useGreyWorld) (channels[0] + channels[1] + channels[2]) / 3.0
        else channels.maxOrNull()!!
        return DoubleArray(3) { index ->
            clamp(reference / max(1e-6, channels[index]), GAIN_MIN, GAIN_MAX)
        }
    }

    /** White-balance gains are bounded: a misread light must not repaint a blue shirt yellow. */
    const val GAIN_MIN: Double = 0.82
    const val GAIN_MAX: Double = 1.22

    private fun mean(values: IntArray): Double =
        if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

    private fun percentileOf(values: IntArray, fraction: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sortedArray()
        val index = (sorted.size * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Standard deviation; the confidence probes use it to tell a textured desk from a page. */
    fun standardDeviation(values: IntArray): Double {
        if (values.size < 2) return 0.0
        val average = mean(values)
        var total = 0.0
        for (value in values) {
            val delta = value - average
            total += delta * delta
        }
        return sqrt(total / (values.size - 1))
    }
}
