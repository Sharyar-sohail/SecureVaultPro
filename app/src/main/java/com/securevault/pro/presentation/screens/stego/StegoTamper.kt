package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.LlBand
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.StegoFrame
import com.securevault.pro.crypto.stego.StegoLimits
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tamper detection for a carrier, measured in the wavelet band rather than hashed.
 *
 * A secret is worth hiding only if the owner can tell whether the photograph in front of them is
 * still the photograph that left the app. A hash is the wrong tool: a printer, a photocopier and a
 * phone camera all rewrite every pixel, so a hash would report "edited" for an honest print and
 * teach the owner to ignore the warning. The signal that survives the hostile channel is *rank*:
 * the band is averaged onto a coarse grid, each region's mean is normalised against the band's own
 * DC level and spread, and only which quarter of the distribution it falls in is stored. A
 * photocopy moves levels a lot and ranks very little; a clone stamp, a filled rectangle or a burn
 * moves ranks.
 *
 * This is the same idea `crypto/stego/CarrierFingerprint.kt` documents — that file is written but
 * sits outside every compiled source root, so it is not reachable from the app. It is reimplemented
 * here under a distinct name so the reveal path can use it today; when the manager moves the
 * original into `crypto/stego`, this file deletes cleanly (its handoff note records that).
 *
 * No Android types: the verdicts are unit-tested on synthetic bands, including the hostile
 * transforms (brightness, contrast, rescale) a print path actually applies.
 */
object StegoTamper {

    const val BITS_PER_CELL = 2
    const val LEVEL_LOW = 0
    const val LEVEL_BELOW = 1
    const val LEVEL_ABOVE = 2
    const val LEVEL_HIGH = 3

    /** Region boundaries in units of the band's own spread. */
    private val cuts = floatArrayOf(-0.55f, 0.05f, 0.65f)

    private val cells: Int get() = StegoLimits.FINGERPRINT_SIDE * StegoLimits.FINGERPRINT_SIDE

    /**
     * The carrier's coarse luminance signature, [StegoLimits.FINGERPRINT_BYTES] bytes: two bits for
     * each cell of the 6x6 grid.
     */
    fun fingerprintOf(band: LlBand): ByteArray {
        val mean = band.mean
        val spread = band.spread(mean)
        val side = StegoLimits.FINGERPRINT_SIDE
        val out = ByteArray(StegoLimits.FINGERPRINT_BYTES)
        for (cell in 0 until cells) {
            val region = regionMean(band, cell)
            val level = if (region == null) LEVEL_LOW else quantise((region - mean) / spread)
            val byte = cell / 4
            val shift = (3 - cell % 4) * BITS_PER_CELL
            out[byte] = (out[byte].toInt() or (level shl shift)).toByte()
        }
        return out
    }

    /** Normalised mean of one signature region, or null when the region is empty. */
    private fun regionMean(band: LlBand, cell: Int): Float? {
        val side = StegoLimits.FINGERPRINT_SIDE
        val row = cell / side
        val column = cell - row * side
        val fromY = row * band.cellsHigh / side
        val toY = ((row + 1) * band.cellsHigh / side).coerceAtLeast(fromY + 1).coerceAtMost(band.cellsHigh)
        val fromX = column * band.cellsWide / side
        val toX = ((column + 1) * band.cellsWide / side).coerceAtLeast(fromX + 1).coerceAtMost(band.cellsWide)
        var total = 0f
        var count = 0
        for (y in fromY until toY) {
            val offset = y * band.cellsWide
            for (x in fromX until toX) {
                total += band.values[offset + x]
                count += 1
            }
        }
        if (count == 0) return null
        return total / count
    }

    private fun quantise(value: Float): Int {
        var level = LEVEL_LOW
        for (cut in cuts) if (value >= cut) level += 1
        return level
    }

    /** The four-level cells back out of a packed signature, for display and for the tests. */
    fun cellsOf(signature: ByteArray): IntArray {
        val out = IntArray(cells)
        for (cell in out.indices) {
            val shift = (3 - cell % 4) * BITS_PER_CELL
            out[cell] = (signature[cell / 4].toInt() shr shift) and 0x3
        }
        return out
    }

    /** How many of the 36 regions changed rank band; mismatched lengths count as total divergence. */
    fun divergence(expected: ByteArray, observed: ByteArray): Int {
        if (expected.size != observed.size) return cells
        val before = cellsOf(expected)
        val after = cellsOf(observed)
        var moved = 0
        for (index in before.indices) if (before[index] != after[index]) moved += 1
        return moved
    }

    /** The band's own statistics, which need no key to compute and are reported alongside drift. */
    data class Statistics(val mean: Float, val spread: Float, val outlierRatio: Float) {
        /** A photocopied flat field, a blank page or a crushed shadow has nothing to hide a mark in. */
        val carriesDetail: Boolean get() = spread > MIN_DETAIL_SPREAD
    }

    /** Below this the LL band cannot carry a modulation at all, whatever the lattice says. */
    const val MIN_DETAIL_SPREAD = 1.5f

    fun statisticsOf(band: LlBand): Statistics {
        val mean = band.mean
        val spread = band.spread(mean)
        var outliers = 0
        for (value in band.values) {
            if (abs(value - mean) > spread * 4f) outliers += 1
        }
        val ratio = if (band.values.isEmpty()) 0f else outliers.toFloat() / band.values.size
        return Statistics(mean, spread, ratio)
    }
}

/** What the app concluded about a carrier's integrity, before it decides what to show. */
enum class TamperVerdict {
    /** Every rank still sits where the seal put it: the carrier is the one that was written. */
    INTACT,

    /** Some regions moved: handled by a copier, re-saved, or lightly retouched. Read is allowed. */
    SUSPECT,

    /** Beyond tolerance: repainted, cloned, filled or patched. The payload is refused, not shown. */
    EDITED,

    /** The band has no detail to carry a mark, so no verdict about tampering is knowable. */
    FLAT
}

/**
 * The one place that turns measured drift into a decision, so the rule is testable and the screens
 * cannot drift apart.
 *
 * The boundary is the carrier's own tolerance ([StegoLimits.FINGERPRINT_TOLERANCE] regions of 36):
 * past it the payload authenticates under the key but the image no longer matches the seal, and the
 * owner asked to be *told* rather than handed a secret of unknown provenance. Half that distance is
 * reported as suspicion — enough for a printer and a scanner, not enough to be ignored.
 */
object TamperReport {

    fun of(
        drift: Int,
        statistics: StegoTamper.Statistics,
        tolerance: Int = StegoLimits.FINGERPRINT_TOLERANCE
    ): TamperVerdict = when {
        !statistics.carriesDetail -> TamperVerdict.FLAT
        drift > tolerance -> TamperVerdict.EDITED
        drift * 2 > tolerance -> TamperVerdict.SUSPECT
        else -> TamperVerdict.INTACT
    }

    /** Plain sentence for the reveal screen; never claims more than the measurement supports. */
    fun explain(verdict: TamperVerdict, drift: Int, tolerance: Int = StegoLimits.FINGERPRINT_TOLERANCE): String =
        when (verdict) {
            TamperVerdict.INTACT ->
                "Untouched. All 36 signature regions still sit where the seal put them ($drift moved)."
            TamperVerdict.SUSPECT ->
                "Handled copy: $drift of 36 regions shifted rank, still inside the $tolerance-region seal."
            TamperVerdict.EDITED ->
                "Edited or repainted: $drift of 36 regions moved rank, past the $tolerance-region seal."
            TamperVerdict.FLAT ->
                "This image has no luminance detail to carry a mark, so nothing can be concluded."
        }
}

/**
 * Geometry gate: the frame header records the working size the mark was written on, and the mark is
 * laid out in normalised coordinates, so a rescale reads fine while a crop or a stretch silently
 * moves the ring. The refusal is made on the *ratio*, with a printed-copy margin.
 */
enum class GeometryVerdict { MATCHES, RESHAPED, CROPPED }

object StegoGeometry {

    /** Aspect tolerance for a scanned/print-and-scan round trip, in percent of the recorded ratio. */
    const val ASPECT_TOLERANCE_PERCENT = 4

    fun verdictOf(meta: StegoFrame.Meta, received: LumaPlane, tolerancePercent: Int = ASPECT_TOLERANCE_PERCENT): GeometryVerdict {
        if (meta.planeWidth <= 0 || meta.planeHeight <= 0 || received.width <= 0 || received.height <= 0) {
            return GeometryVerdict.CROPPED
        }
        val sealedRatio = meta.planeWidth.toFloat() / meta.planeHeight.toFloat()
        val receivedRatio = received.width.toFloat() / received.height.toFloat()
        val percent = abs(sealedRatio - receivedRatio) / sealedRatio * 100f
        return when {
            percent > CROP_SUSPECT_PERCENT -> GeometryVerdict.CROPPED
            percent > tolerancePercent -> GeometryVerdict.RESHAPED
            else -> GeometryVerdict.MATCHES
        }
    }

    /** Beyond this the photo is a different picture, not a different copy of the same one. */
    const val CROP_SUSPECT_PERCENT = 25

    /** The area the received plane lost against the sealed geometry, as a fraction. */
    fun lostArea(meta: StegoFrame.Meta, received: LumaPlane): Float {
        if (meta.planeWidth <= 0 || meta.planeHeight <= 0) return 0f
        val sealed = meta.planeWidth.toLong() * meta.planeHeight.toLong()
        val observed = received.width.toLong() * received.height.toLong()
        if (sealed <= 0L || observed >= sealed) return 0f
        return 1f - observed.toFloat() / sealed.toFloat()
    }

    /** Aspect-preserving fit of working dimensions into the encoder's pixel budget. */
    fun scaleToFit(width: Int, height: Int, maxPixels: Int = StegoLimits.MAX_WORKING_PIXELS): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return width to height
        val factor = sqrt(maxPixels.toDouble() / pixels.toDouble())
        val targetWidth = (width * factor).toInt().coerceAtLeast(MIN_EDGE)
        val targetHeight = (height * factor).toInt().coerceAtLeast(MIN_EDGE)
        return targetWidth to targetHeight
    }

    /** The shortest edge a fitted working grid may shrink to, in pixels. */
    val MIN_EDGE: Int = StegoLimits.MIN_WORKING_LONGEST
}
