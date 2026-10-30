package com.securevault.pro.presentation.screens.stego

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.securevault.pro.crypto.stego.DeterministicRandom
import com.securevault.pro.crypto.stego.HaarLl
import com.securevault.pro.crypto.stego.LlBand
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.StegoLimits
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixels in, pixels out: the only bridge between an Android `Bitmap` and the stego core's
 * [LumaPlane].
 *
 * The core deliberately speaks an 8-bit luminance grid, because the mark lives in luminance only —
 * so the carrier the owner saves is a *grey* JPEG. That is not a limitation dressed up as a feature,
 * it is the requirement: a black-and-white print and a photocopy are grey too, and a carrier that
 * only survives as a colour file would fail the one test the owner actually cares about. Colour is
 * dropped here, once, on purpose, and every path that produces or reads a carrier goes through this
 * file, so there is no second place where a different luma formula could disagree with the first.
 *
 * This is the only file in the package that imports `android.graphics`, and it depends on nothing
 * outside `crypto/stego`. The scanner module has its own decode helpers; borrowing them would tie
 * another agent's internals to this feature, and the luma maths here is four lines of BT.601.
 */
object StegoImages {

    /**
     * Longest edge a decoded carrier is allowed to have, before the area-average fit to
     * [StegoLimits.MAX_WORKING_PIXELS].
     *
     * 1700 px keeps a square photo at 2.9 MP in memory and lets the working fit land on the core's
     * own 2 MP cap in one step. The lattice is normalised, so a smaller carrier reads back
     * identically after a print — capacity, not survivability, is what scales with resolution.
     */
    const val DECODE_MAX_EDGE = 1700

    /** BT.601 weights as integers, so a plane costs one shift and never a float multiply. */
    private const val LUMA_R = 77
    private const val LUMA_G = 150
    private const val LUMA_B = 29
    private const val LUMA_SHIFT = 8

    /**
     * Target spread for a generated cover's LL band, in grey levels.
     *
     * The QIM step is `0.36 x band RMS`, so a band spread near this puts the modulation at roughly
     * 6-8 levels: invisible on screen, wide enough that a 300 dpi laser print and a photocopier's own
     * noise do not flatten it into the grain. Below it the carrier reads as flat and the encoder
     * refuses; far above it, the print looks deliberately dithered.
     */
    const val COVER_TARGET_SPREAD = 20f

    /** The window a generated cover is calibrated into, and what the label words describe. */
    private const val COVER_MIN_SPREAD = 14f
    private const val COVER_MAX_SPREAD = 30f

    /** Grey luminance grid of a bitmap, at the bitmap's own size. */
    fun planeOf(bitmap: Bitmap): LumaPlane {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val samples = IntArray(pixels.size) { index ->
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            (red * LUMA_R + green * LUMA_G + blue * LUMA_B) shr LUMA_SHIFT
        }
        return LumaPlane(width, height, samples)
    }

    /** An opaque grey bitmap of a plane, for on-screen preview and for encoding. */
    fun bitmapOf(plane: LumaPlane): Bitmap {
        val pixels = IntArray(plane.samples.size) { index ->
            val level = plane.samples[index].coerceIn(0, 255)
            Color.rgb(level, level, level)
        }
        return Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, plane.width, 0, 0, plane.width, plane.height)
        }
    }

    /**
     * A small bitmap of a plane, for a thumbnail strip.
     *
     * Previewed at [maxEdge] rather than at working size because a screen does not need the samples
     * the mark lives in — and holding a 1700x1275 ARGB bitmap (8 MB) beside the plane it came from
     * (2 MB of ints) is the kind of memory a phone app should not spend twice for one row of pixels.
     */
    fun previewOf(plane: LumaPlane, maxEdge: Int = PREVIEW_LONGEST): Bitmap {
        val longest = max(plane.width, plane.height)
        val small = if (longest <= maxEdge || longest <= 0) plane else {
            val scale = maxEdge.toFloat() / longest
            StegoPlanes.resampled(
                plane,
                max(1, (plane.width * scale).roundToInt()),
                max(1, (plane.height * scale).roundToInt())
            )
        }
        return bitmapOf(small)
    }

    /** Longest edge of a thumbnail strip, in samples. */
    const val PREVIEW_LONGEST = 480

    /**
     * A photo's bytes, grey, fitted to the core's working budget; null when it is not an image.
     *
     * EXIF is deliberately not applied. A carrier's pixels *are* the coordinate system the mark is
     * written in, [StegoCodec]'s acquisition searches all four quarter turns, and the JPEG this file
     * writes carries no orientation tag at all — so there is nothing for a tag to disagree with.
     */
    fun planeOf(bytes: ByteArray): LumaPlane? {
        if (bytes.isEmpty()) return null
        val decoded = decode(bytes, DECODE_MAX_EDGE) ?: return null
        return try {
            StegoPlanes.fitted(planeOf(decoded), StegoLimits.MAX_WORKING_PIXELS)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /**
     * Decodes to a bitmap whose longest side is at most [maxEdge], in two stages: a power-of-two
     * `inSampleSize` on the way in, so a 108 MP original never allocates its own pixel array, then an
     * exact filtered scale to the bound.
     */
    fun decode(bytes: ByteArray, maxEdge: Int = DECODE_MAX_EDGE): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val sized = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        val longest = max(sized.width, sized.height)
        if (longest <= maxEdge || longest <= 0) return sized
        val scale = maxEdge.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            sized,
            max(1, (sized.width * scale).roundToInt()),
            max(1, (sized.height * scale).roundToInt()),
            true
        )
        if (scaled !== sized) sized.recycle()
        return scaled
    }

    /** The carrier as it goes to disk: a grey JPEG at the core's own quality. */
    fun jpegOf(plane: LumaPlane, quality: Int = StegoLimits.JPEG_QUALITY): ByteArray {
        val bitmap = bitmapOf(plane)
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * A lossless PNG of a plane, for an owner who wants the mark to survive with no JPEG rounding at
     * all. The core's published margins are measured at [StegoLimits.JPEG_QUALITY], so this is an
     * option, not the default.
     */
    fun pngOf(plane: LumaPlane): ByteArray {
        val bitmap = bitmapOf(plane)
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** The band a screen measures before it promises an encode: a flat one cannot carry a mark. */
    fun bandOf(plane: LumaPlane): LlBand = HaarLl.of(plane)

    /** Band statistics, the same measurement [StegoTamper] gates its tamper verdict on. */
    fun textureOf(plane: LumaPlane): StegoTamper.Statistics = StegoTamper.statisticsOf(bandOf(plane))

    /**
     * The line under a carrier thumbnail. It says what the measurement supports and nothing more —
     * "flat" here is the same condition the encoder will refuse on, so a screen never promises an
     * encode the codec is about to decline.
     */
    fun textureLabel(plane: LumaPlane): String {
        val statistics = textureOf(plane)
        return when {
            !statistics.carriesDetail -> "Flat — this photo has no grain to hide in"
            statistics.spread >= COVER_MAX_SPREAD -> "Very busy — the mark may show as texture"
            statistics.spread >= COVER_MIN_SPREAD -> "Good grain for print survival"
            else -> "Light grain — readable, but with little print margin"
        }
    }

    /**
     * A procedural cover image: deterministic from [seed], ordinary-looking, and guaranteed to carry
     * enough mid-scale structure for the encoder's flat-band refusal to pass.
     *
     * Built the way a small landscape photograph is: a tonal horizon (light sky over dark ground),
     * three interpolated value-noise octaves for clouds and terrain, and fine sensor grain on top.
     * The result is then contrast-calibrated to [COVER_TARGET_SPREAD], measured on its *own* LL band
     * at the size it is finally written at — a real measurement, not an assumed amplitude, which is
     * why a generated carrier and a picked carrier behave the same.
     */
    fun coverPlane(width: Int, height: Int, seed: Int): LumaPlane {
        val safeWidth = width.coerceIn(StegoLimits.MIN_WORKING_LONGEST, 2400)
        val safeHeight = height.coerceIn(StegoLimits.MIN_WORKING_LONGEST, 2400)
        val random = DeterministicRandom(seed)
        val horizon = 0.42f + random.unit() * 0.2f
        val skyBias = 24f + random.unit() * 40f
        val groundBias = 96f + random.unit() * 54f
        val clouds = octave(random, safeWidth, safeHeight, cells = 7, amplitude = 46f)
        val terrain = octave(random, safeWidth, safeHeight, cells = 21, amplitude = 24f)
        val detail = octave(random, safeWidth, safeHeight, cells = 64, amplitude = 11f)
        val samples = IntArray(safeWidth * safeHeight)
        for (y in 0 until safeHeight) {
            val row = y * safeWidth
            val vertical = y.toFloat() / safeHeight
            val base = if (vertical < horizon) {
                skyBias + (groundBias - skyBias) * (vertical / horizon) * 0.35f
            } else {
                groundBias + (skyBias - groundBias) * 0.25f *
                    (1f - (vertical - horizon) / (1f - horizon))
            }
            for (x in 0 until safeWidth) {
                val index = row + x
                val value = base + clouds[index] + terrain[index] + detail[index] +
                    (random.unit() - 0.5f) * 4f
                samples[index] = value.roundToInt().coerceIn(0, 255)
            }
        }
        return calibrated(LumaPlane(safeWidth, safeHeight, samples))
    }

    /** Convenience for a screen that only knows "make me a plausible photo". */
    fun coverPlane(seed: Int): LumaPlane = coverPlane(1280, 960, seed)

    /** One interpolated value-noise octave, bilinearly sampled onto the plane's own grid. */
    private fun octave(
        random: DeterministicRandom,
        width: Int,
        height: Int,
        cells: Int,
        amplitude: Float
    ): FloatArray {
        val grid = FloatArray((cells + 1) * (cells + 1)) { (random.unit() - 0.5f) * 2f * amplitude }
        val out = FloatArray(width * height)
        val stepX = cells.toFloat() / width
        val stepY = cells.toFloat() / height
        for (y in 0 until height) {
            val gy = (y * stepY).coerceIn(0f, cells - 0.001f)
            val y0 = gy.toInt()
            val ty = gy - y0
            for (x in 0 until width) {
                val gx = (x * stepX).coerceIn(0f, cells - 0.001f)
                val x0 = gx.toInt()
                val tx = gx - x0
                val top = grid[y0 * (cells + 1) + x0] * (1 - tx) +
                    grid[y0 * (cells + 1) + x0 + 1] * tx
                val bottom = grid[(y0 + 1) * (cells + 1) + x0] * (1 - tx) +
                    grid[(y0 + 1) * (cells + 1) + x0 + 1] * tx
                out[y * width + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }

    /**
     * Stretches a plane's contrast until its *LL band* spread lands in the survival window.
     *
     * Measured on the band, not on the pixels, because the band is the channel the mark is written
     * into: a plane can look textured and still have a dead-flat LL band (a fine checkerboard
     * averages away), which the encoder refuses. Gain is clamped so a near-constant photo cannot be
     * pushed into visible banding just to pass the gate.
     */
    private fun calibrated(plane: LumaPlane): LumaPlane {
        val band = HaarLl.of(plane)
        val mean = band.mean
        val spread = band.spread(mean)
        if (spread <= LlBand.MIN_SPREAD) return plane
        val gain = (COVER_TARGET_SPREAD / spread).coerceIn(0.35f, 3.0f)
        return plane.map { level -> (mean + (level - mean) * gain).roundToInt() }
    }

    /**
     * A filename that belongs in a camera roll.
     *
     * Plausible deniability is a property of the *file*, not only of its pixels: a carrier named
     * `stego_secret_3.jpg` is a confession written in the filename slot. The stock camera app's
     * `IMG_yyyyMMdd_HHmmss` shape is used, with the timestamp offset by [seed] so the same carrier
     * always names the same file and a set of three does not look like three copies of one photo.
     */
    fun cameraName(seed: Int, stamp: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val shifted = stamp - (seed.toLong() and 0xFFFFL) * 3_600_000L
        return "IMG_${format.format(Date(shifted))}.jpg"
    }

    /** `1280 × 960 · mean 138 · grain 21` — the numbers a capacity card has to quote. */
    fun describe(plane: LumaPlane): String {
        val band = HaarLl.of(plane)
        val mean = band.mean
        return "${plane.width} × ${plane.height} · mean ${mean.roundToInt()} · " +
            "grain ${band.spread(mean).roundToInt()}"
    }

    /** The smaller edge, which is what decides whether a lattice fits at all. */
    fun smallerSide(plane: LumaPlane): Int = min(plane.width, plane.height)

    /** The larger edge, for the same sentence as [smallerSide]. */
    fun longerSide(plane: LumaPlane): Int = max(plane.width, plane.height)

    /** How many original pixels sit behind one working sample, for "reduced by 2.1x" wording. */
    fun fitFactor(originalPixels: Long, plane: LumaPlane): Double {
        if (plane.width <= 0 || plane.height <= 0 || originalPixels <= 0L) return 1.0
        val working = (plane.width.toLong() * plane.height).coerceAtLeast(1L).toDouble()
        return sqrt(originalPixels.toDouble() / working)
    }
}

/** Uniform in `0 until 1`, sign-safe, so the generator never has to think about negatives. */
internal fun DeterministicRandom.unit(): Float = nextIndex(1 shl 20) / 1048576f
