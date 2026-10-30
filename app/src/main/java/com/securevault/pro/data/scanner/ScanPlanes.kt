package com.securevault.pro.data.scanner

import kotlin.math.max
import kotlin.math.min

/**
 * The scanner's whole image maths runs on these two buffers and nothing else.
 *
 * They are deliberately free of `android.*` types: a camera frame arrives as a YUV plane, a
 * gallery import arrives as JPEG bytes and a review-sheet edit arrives as a bitmap, but every one
 * of them is flattened into the same luma/ARGB pair before a single pixel is read. That is what
 * makes the edge detector, the homography and the filters unit-testable on a JVM with no device
 * attached, and it is why the live viewfinder and the still capture path cannot drift apart.
 *
 * Pixels are row-major, stride == width, ARGB packed as `0xAARRGGBB` (the same layout
 * [android.graphics.Bitmap.getPixels] hands back), channels are non-premultiplied 0..255.
 */
class LumaPlane(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "a plane needs a positive size, was ${width}x$height" }
        require(pixels.size == width * height) {
            "expected ${width * height} samples for a ${width}x$height plane, got ${pixels.size}"
        }
    }

    val size: Int get() = pixels.size

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value
    }

    /** Clamped lookup: edge pixels replicate outward instead of throwing or going black. */
    fun sampleClamped(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }

    fun copy(): LumaPlane = LumaPlane(width, height, pixels.copyOf())

    fun toArgb(background: Int = OPAQUE_WHITE): ArgbPlane {
        val out = IntArray(pixels.size)
        val alpha = background and ALPHA_MASK
        for (index in pixels.indices) {
            val value = pixels[index].coerceIn(0, 255)
            out[index] = alpha or (value shl 16) or (value shl 8) or value
        }
        return ArgbPlane(width, height, out)
    }

    companion object {
        const val OPAQUE_WHITE: Int = -1

        fun of(argb: ArgbPlane): LumaPlane {
            val out = IntArray(argb.size)
            val src = argb.pixels
            for (index in src.indices) {
                val color = src[index]
                out[index] = lumaOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            }
            return LumaPlane(argb.width, argb.height, out)
        }

        fun filled(width: Int, height: Int, value: Int): LumaPlane =
            LumaPlane(width, height, IntArray(width * height) { value })
    }
}

/** A full-colour buffer; the filters always take and return one of these so nothing mutates in place. */
class ArgbPlane(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "a plane needs a positive size, was ${width}x$height" }
        require(pixels.size == width * height) {
            "expected ${width * height} samples for a ${width}x$height plane, got ${pixels.size}"
        }
    }

    val size: Int get() = pixels.size

    /** Rough heap cost of the buffer, used by the page cache budget. */
    val byteCost: Int get() = pixels.size * 4

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, color: Int) {
        pixels[y * width + x] = color
    }

    fun redAt(index: Int): Int = (pixels[index] shr 16) and 0xFF
    fun greenAt(index: Int): Int = (pixels[index] shr 8) and 0xFF
    fun blueAt(index: Int): Int = pixels[index] and 0xFF
    fun alphaAt(index: Int): Int = (pixels[index] ushr 24) and 0xFF

    fun copy(): ArgbPlane = ArgbPlane(width, height, pixels.copyOf())

    fun toArgb(): ArgbPlane = this

    companion object {
        /** The alpha byte of a packed colour, so a filter can carry transparency through untouched. */
        fun alphaMask(): Int = ALPHA_MASK

        fun pack(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)

        /** Grey the way the BT.601 weights define it — the same numbers the luma plane uses. */
        fun grey(red: Int, green: Int, blue: Int): Int = lumaOf(red, green, blue)

        fun filled(width: Int, height: Int, color: Int): ArgbPlane =
            ArgbPlane(width, height, IntArray(width * height) { color })
    }
}

/** Mask for the alpha byte of a packed ARGB int. */
const val ALPHA_MASK: Int = 0xFF000000.toInt()

/** BT.601 luma weights, as exact floats.
 *
 * The integer approximation the live viewfinder uses ((77/150/29) >> 8) is a 0.4 % error; the
 * filters and the white-point solver are tested against these weights instead, so one number is
 * authoritative and a test can assert an exact value rather than a band.
 */
const val LUMA_RED: Double = 0.299
const val LUMA_GREEN: Double = 0.587
const val LUMA_BLUE: Double = 0.114

fun lumaOf(red: Int, green: Int, blue: Int): Int =
    (LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue + 0.5).toInt()

fun lumaFloatOf(red: Int, green: Int, blue: Int): Double =
    LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue

/** Integer clamp used everywhere a channel is written back. */
fun clampChannel(value: Double): Int = if (value <= 0.0) 0 else if (value >= 255.0) 255 else value.toInt()

fun clampChannel(value: Int): Int = value.coerceIn(0, 255)

fun clamp(value: Int, low: Int, high: Int): Int = if (value < low) low else if (value > high) high else value

fun clamp(value: Float, low: Float, high: Float): Float =
    if (value < low) low else if (value > high) high else value

fun clamp(value: Double, low: Double, high: Double): Double =
    if (value < low) low else if (value > high) high else value

/** Nearest-neighbour resize; used for the tiny probe buffers the detector runs on. */
fun LumaPlane.resized(targetWidth: Int, targetHeight: Int): LumaPlane {
    val w = max(1, targetWidth)
    val h = max(1, targetHeight)
    if (w == width && h == height) return this
    val out = IntArray(w * h)
    val xRatio = width.toDouble() / w
    val yRatio = height.toDouble() / h
    for (y in 0 until h) {
        val sourceY = min(height - 1, (y * yRatio).toInt())
        val row = y * w
        val sourceRow = sourceY * width
        for (x in 0 until w) {
            out[row + x] = pixels[sourceRow + min(width - 1, (x * xRatio).toInt())]
        }
    }
    return LumaPlane(w, h, out)
}

/** Separable 3x3 binomial blur, edge-replicating; the cheapest smoothing that cannot ring. */
fun LumaPlane.smooth3x3(passes: Int = 2): LumaPlane {
    var current = this
    repeat(passes.coerceAtLeast(0)) {
        val width = current.width
        val height = current.height
        val horizontal = IntArray(current.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = current.pixels[row + max(0, x - 1)]
                val center = current.pixels[row + x]
                val right = current.pixels[row + min(width - 1, x + 1)]
                horizontal[row + x] = (left + center * 2 + right) / 4
            }
        }
        val vertical = IntArray(current.size)
        for (y in 0 until height) {
            val up = max(0, y - 1) * width
            val row = y * width
            val down = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                vertical[row + x] =
                    (horizontal[up + x] + horizontal[row + x] * 2 + horizontal[down + x]) / 4
            }
        }
        current = LumaPlane(width, height, vertical)
    }
    return current
}

/** Mean absolute deviation of a buffer — the "is there anything but flat colour here?" probe. */
fun IntArray.meanAbsoluteDeviation(mean: Double): Double {
    if (isEmpty()) return 0.0
    var total = 0.0
    for (value in this) total += kotlin.math.abs(value - mean)
    return total / size
}
