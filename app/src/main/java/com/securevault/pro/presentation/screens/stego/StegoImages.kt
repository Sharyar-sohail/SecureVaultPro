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
 * The core deliberately speaks an 8-bit luminance grid, because the mark lives in luminance and the
 * reader re-extracts luminance from whatever file comes back. That is exactly what lets a carrier
 * keep its colour: [tinted] paints the marked level onto the photo it came from, so the file that
 * leaves the app is an ordinary colour JPEG whose BT.601 luminance *is* the marked plane — the reader
 * sees the same grid either way, and a black-and-white print or a photocopy reads back identically,
 * because a print is luminance too. The colours a picked photo arrives with are its own; a generated
 * procedural cover has no colour source, so it stays a grey JPEG.
 *
 * One luma formula serves every path — encode, tint, decode — because every path that produces or
 * reads a carrier goes through this file. There is no second place it could disagree with the first.
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

    /** The number of integer luma steps one grey level occupies, i.e. the width of one level's window. */
    private const val LUMA_WINDOW = 1 shl LUMA_SHIFT

    /** An ARGB pixel's alpha, kept untouched by the tint: the mark has no business with opacity. */
    private const val ALPHA_MASK = -0x1000000

    /**
     * How many times the residual luma error may be chased into the channels after a clamp.
     *
     * Three saturating channels can close any shortfall the clamping creates, so this never runs
     * long; the bound is only there so a pathological pixel cannot spin.
     */
    private const val TINT_CORRECTIONS = 6

    /** Channel luma weights by index: 0 = red, 1 = green, 2 = blue. */
    private val CHANNEL_WEIGHTS = intArrayOf(LUMA_R, LUMA_G, LUMA_B)

    /** The order a residual is spent in: green carries the most luma weight, blue the least. */
    private val TINT_ORDER = intArrayOf(1, 0, 2)

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

    /**
     * BT.601 luminance of one packed ARGB pixel — the one luma formula the whole subsystem uses.
     *
     * Public because the tint and the decoder must agree to the last integer: the reader recomputes
     * exactly this from the saved file's pixels, so a carrier painted any other way is a carrier that
     * reads back wrong. No `android.graphics` type crosses this signature, so the maths is testable.
     */
    fun lumaOf(color: Int): Int {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return (red * LUMA_R + green * LUMA_G + blue * LUMA_B) shr LUMA_SHIFT
    }

    /** Grey luminance grid of a bitmap, at the bitmap's own size. */
    fun planeOf(bitmap: Bitmap): LumaPlane {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return LumaPlane(width, height, IntArray(pixels.size) { index -> lumaOf(pixels[index]) })
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
     * The plane a thumbnail is painted from, at [maxEdge] or smaller.
     *
     * Split out of [previewOf] because a colour thumbnail needs the small *grid*, not a grey bitmap:
     * the tint runs on the preview-sized pixels, which keeps the full-size colour array as the only
     * full-size buffer a carrier holds.
     */
    fun previewGridOf(plane: LumaPlane, maxEdge: Int = PREVIEW_LONGEST): LumaPlane {
        val longest = max(plane.width, plane.height)
        if (longest <= maxEdge || longest <= 0) return plane
        val scale = maxEdge.toFloat() / longest
        return StegoPlanes.resampled(
            plane,
            max(1, (plane.width * scale).roundToInt()),
            max(1, (plane.height * scale).roundToInt())
        )
    }

    /**
     * A small bitmap of a plane, for a thumbnail strip.
     *
     * Previewed at [maxEdge] rather than at working size because a screen does not need the samples
     * the mark lives in — and holding a 1700x1275 ARGB bitmap (8 MB) beside the plane it came from
     * (2 MB of ints) is the kind of memory a phone app should not spend twice for one row of pixels.
     */
    fun previewOf(plane: LumaPlane, maxEdge: Int = PREVIEW_LONGEST): Bitmap =
        bitmapOf(previewGridOf(plane, maxEdge))

    /** An opaque bitmap of pixels this app already holds as ARGB, at [pixels]' own grid. */
    fun bitmapOfArgb(width: Int, height: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    /**
     * The marked luminance, painted back onto the photo it was taken from.
     *
     * The mark's whole footprint is `delta = marked level - the pixel's own luma`, and adding the
     * same delta to R, G and B leaves the pixel's hue and saturation exactly as the camera saw them:
     * only its brightness moves. A channel already pinned at 0 or 255 cannot pay its share of that
     * delta, so the residual is chased into the channels that still have headroom, biggest luma weight
     * first, until the pixel's BT.601 luminance sits on the marked level. Where a pixel is saturated
     * on all three channels the level is unreachable and the closest reachable value is written —
     * pure black and pure white are the two tones a photograph has no room to shift anyway.
     *
     * The upshot is the invariant the reader depends on: [lumaOf] of a tinted pixel is the marked
     * sample, so the file is a colour photograph and the mark still reads.
     */
    fun tinted(marked: LumaPlane, source: IntArray): IntArray {
        val out = IntArray(marked.samples.size)
        val shared = min(marked.samples.size, source.size)
        for (index in 0 until shared) {
            out[index] = tintOne(marked.samples[index].coerceIn(0, 255), source[index])
        }
        // A plane and its colour source are supposed to be the same grid. If they somehow are not, the
        // surplus samples take the marked grey rather than an invented colour, so the reader still
        // measures the mark and the screen still shows a picture.
        for (index in shared until out.size) {
            val level = marked.samples[index].coerceIn(0, 255)
            out[index] = ALPHA_MASK or (level shl 16) or (level shl 8) or level
        }
        return out
    }

    private fun tintOne(level: Int, color: Int): Int {
        val channels = intArrayOf(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF
        )
        val delta = level - lumaOf(color)
        for (channel in channels.indices) {
            channels[channel] = (channels[channel] + delta).coerceIn(0, 255)
        }
        val floor = level shl LUMA_SHIFT
        var corrections = 0
        while (corrections < TINT_CORRECTIONS) {
            val weighted = channels[0] * LUMA_R + channels[1] * LUMA_G + channels[2] * LUMA_B
            if (weighted >= floor && weighted < floor + LUMA_WINDOW) break
            corrections += 1
            val rising = weighted < floor
            val shortage = if (rising) floor - weighted else weighted - (floor + LUMA_WINDOW - 1)
            for (order in TINT_ORDER) {
                val room = if (rising) 255 - channels[order] else channels[order]
                if (room <= 0) continue
                val weight = CHANNEL_WEIGHTS[order]
                val move = min(room, (shortage + weight - 1) / weight)
                channels[order] = if (rising) channels[order] + move else channels[order] - move
                break
            }
        }
        return (color and ALPHA_MASK) or
            (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    /** The marked plane worn by the photo it was sealed into: colour out, same luminance as [marked]. */
    fun colorBitmapOf(marked: LumaPlane, source: IntArray): Bitmap =
        bitmapOfArgb(marked.width, marked.height, tinted(marked, source))

    /** Longest edge of a thumbnail strip, in samples. */
    const val PREVIEW_LONGEST = 480

    /**
     * A photo's bytes reduced to the core's working luminance grid; null when it is not an image.
     *
     * This is the read-side path: a carrier is measured in luminance, so a reveal never needs the
     * colours and must not pay for keeping them. The hide side takes [photoOf], which is this plus
     * the pixels the tint wears.
     *
     * EXIF is deliberately not applied. A carrier's pixels *are* the coordinate system the mark is
     * written in, [StegoCodec]'s acquisition searches all four quarter turns, and the JPEG this file
     * writes carries no orientation tag at all — so there is nothing for a tag to disagree with.
     */
    fun planeOf(bytes: ByteArray): LumaPlane? {
        if (bytes.isEmpty()) return null
        val decoded = decode(bytes, DECODE_MAX_EDGE) ?: return null
        return try {
            // Fitted, not canonicalised. A received copy is put straight into the reader, whose first
            // geometry is its own size — which is what every carrier this app wrote before the grid was
            // fixed needs to keep reading. Canonicalising here would pre-empt that first try and make
            // the reader's second geometry pointless.
            StegoPlanes.fitted(planeOf(decoded), StegoLimits.MAX_WORKING_PIXELS)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /**
     * A picked photo, cut to the encoder's working size twice over: as the [Photo.plane] the engine
     * marks and as the [Photo.pixels] the colours came from.
     *
     * The colour grid is resampled to the *canonical* plane's exact width and height, not to the decode
     * bound, because a tint only means something when pixel `i` of the plane and pixel `i` of the
     * photo are the same patch of the same photograph.
     *
     * Canonical, and not merely fitted: [StegoLimits.CANONICAL_LONGEST] is the grid every carrier this
     * app writes is sealed on, so a copy that comes back at a chat app's own size can be put back on
     * it and read. A mark does not die of requantisation — measured on the JVM, the same mark rescaled
     * to any other grid does — it dies of the reader's cells disagreeing with the writer's.
     */
    fun photoOf(bytes: ByteArray): Photo? {
        if (bytes.isEmpty()) return null
        val decoded = decode(bytes, DECODE_MAX_EDGE) ?: return null
        return try {
            val fitted = StegoPlanes.fitted(planeOf(decoded), StegoLimits.MAX_WORKING_PIXELS)
            val plane = StegoPlanes.canonicalised(fitted)
            val sized = if (decoded.width == plane.width && decoded.height == plane.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, plane.width, plane.height, true)
            }
            val pixels = IntArray(plane.samples.size)
            sized.getPixels(pixels, 0, plane.width, 0, 0, plane.width, plane.height)
            if (sized !== decoded && !sized.isRecycled) sized.recycle()
            Photo(plane, pixels)
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

    /**
     * What this picture really looks like on the other end of a chat app send, measured rather than
     * modelled: the carrier is written at the app's own quality, decoded and rescaled to the size a
     * phone send uses, requantised once more at send quality, and read back as luminance.
     *
     * Two genuine encoder passes and one resize — the same three things that happen between "Shared"
     * and the receiver's gallery. The encoder runs this over the carrier it just made, before it tells
     * the owner the message will survive, so the promise is a measurement taken on their own photo.
     *
     * The rescale goes to [StegoChannel.TIGHT_SEND_LONGEST], not the app's own working size, on
     * purpose. A copy that lands back on the grid the mark was written needs no rescue; measuring that
     * one would let the seal promise survival on the easy geometry and be wrong on the messenger that
     * trims harder. This is the harsher of the two real sends, so passing it covers both.
     *
     * Returns null when the round trip cannot be measured (an allocation the handset refused), which
     * the caller reads as "not tested" and says out loud rather than as a pass.
     */
    fun sendRoundTrip(marked: LumaPlane, source: IntArray? = null): LumaPlane? {
        val saved = if (source != null) colorJpegOf(marked, source) else jpegOf(marked)
        val resized = decode(saved, StegoChannel.TIGHT_SEND_LONGEST) ?: return null
        val sent = try {
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, SEND_QUALITY, stream)
            decode(stream.toByteArray(), StegoChannel.TIGHT_SEND_LONGEST)
        } finally {
            if (!resized.isRecycled) resized.recycle()
        } ?: return null
        return try {
            planeOf(sent)
        } finally {
            if (!sent.isRecycled) sent.recycle()
        }
    }

    /**
     * The quality a chat app re-encodes a "photo" send at.
     *
     * Not the app's own [com.securevault.pro.crypto.stego.StegoLimits.JPEG_QUALITY]: a messenger
     * discards the file it was given and writes its own, and middling quality is what it writes.
     */
    const val SEND_QUALITY = 70

    /** The carrier as it goes to disk when there is no colour to wear: a grey JPEG. */
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
     * The carrier as it goes to disk when it came from a real photo: the marked luminance inside the
     * photo's own colours, at the core's own quality.
     *
     * JPEG rounding moves a saved pixel a few levels, which is why the encoder's margins are measured
     * at this quality and not at a lossless one; the tint cannot dodge that any better than the grey
     * path could, and the reader's soft votes and error correction are what absorb it.
     */
    fun colorJpegOf(
        marked: LumaPlane,
        source: IntArray,
        quality: Int = StegoLimits.JPEG_QUALITY
    ): ByteArray {
        val bitmap = colorBitmapOf(marked, source)
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

/**
 * A picked photo at the encoder's working size, kept two ways: the [plane] the engine marks, and the
 * ARGB [pixels] the colours came from, on exactly the same `width x height` grid.
 *
 * [pixels] is the one extra buffer a carrier is allowed to hold — a thumbnail-sized copy is what the
 * screen paints, never a second bitmap.
 */
data class Photo(val plane: LumaPlane, val pixels: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is Photo && other.plane === plane && other.pixels.contentEquals(pixels)

    override fun hashCode(): Int = plane.hashCode() * 31 + pixels.contentHashCode()
}

/** Uniform in `0 until 1`, sign-safe, so the generator never has to think about negatives. */
internal fun DeterministicRandom.unit(): Float = nextIndex(1 shl 20) / 1048576f
