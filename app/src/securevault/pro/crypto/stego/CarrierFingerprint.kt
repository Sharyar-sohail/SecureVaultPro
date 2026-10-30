package com.securevault.pro.crypto.stego

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The carrier's coarse luminance signature: the wavelet LL band averaged onto a 6x6 grid, DC
 * removed, gain normalised, then stored at two bits a cell.
 *
 * Two bits is the whole trick. A printer, a photocopier or a JPEG re-compression moves a region's
 * average level a lot more than it moves its *rank* among the nine others, and the mark itself
 * averages out over the ~1000 band cells behind each signature cell, so the signature survives the
 * hostile channel. A Photoshop-style edit — a clone stamp, a burned-in patch, a crop, a region
 * repainted — does move those ranks, which is what [divergence] reports. It is a tamper signal, not
 * a hash: exact equality is not expected and the tolerance is calibrated instead (see HANDOFF).
 */
object CarrierFingerprint {

    private const val BITS_PER_CELL = 2
    private const val CELLS = StegoLimits.FINGERPRINT_SIDE * StegoLimits.FINGERPRINT_SIDE
    const val LEVEL_LOW = 0
    const val LEVEL_BELOW = 1
    const val LEVEL_ABOVE = 2
    const val LEVEL_HIGH = 3

    /** Thresholds in units of the band's own spread. */
    private val cuts = floatArrayOf(-0.55f, 0.05f, 0.65f)

    fun of(band: LlBand): ByteArray {
        val mean = band.mean
        val spread = band.spread(mean)
        val side = StegoLimits.FINGERPRINT_SIDE
        val out = ByteArray(StegoLimits.FINGERPRINT_BYTES)
        val cellsWide = band.cellsWide
        val cellsHigh = band.cellsHigh
        for (cell in 0 until CELLS) {
            val row = cell / side
            val col = cell - row * side
            val fromY = row * cellsHigh / side
            val toY = ((row + 1) * cellsHigh / side).coerceAtLeast(fromY + 1).coerceAtMost(cellsHigh)
            val fromX = col * cellsWide / side
            val toX = ((col + 1) * cellsWide / side).coerceAtLeast(fromX + 1).coerceAtMost(cellsWide)
            var total = 0.0
            var count = 0
            for (y in fromY until toY) {
                val offset = y * cellsWide
                for (x in fromX until toX) {
                    total += band.values[offset + x]
                    count += 1
                }
            }
            val level = if (count == 0) 0 else {
                val normalised = ((total / count) - mean) / spread
                quantise(normalised.toFloat())
            }
            val byte = cell / 4
            val shift = (3 - cell % 4) * BITS_PER_CELL
            out[byte] = ((out[byte].toInt() or (level shl shift))).toByte()
        }
        return out
    }

    fun cellsOf(signature: ByteArray): IntArray {
        val cells = IntArray(CELLS)
        for (cell in cells.indices) {
            val shift = (3 - cell % 4) * BITS_PER_CELL
            cells[cell] = (signature[cell / 4].toInt() shr shift) and 0x3
        }
        return cells
    }

    /** How many of the 36 regions changed rank band. Beyond tolerance, the carrier was edited. */
    fun divergence(expected: ByteArray, observed: ByteArray): Int {
        if (expected.size != observed.size) return CELLS
        val before = cellsOf(expected)
        val after = cellsOf(observed)
        var moved = 0
        for (index in before.indices) if (before[index] != after[index]) moved += 1
        return moved
    }

    /**
     * A second, cheaper tamper signal that needs no key: the band's own statistics have to stay
     * plausible. A flattened region (a big blur, a filled rectangle) collapses the spread, and a
     * gross level shift moves the DC; both are reported rather than silently decoded.
     */
    fun statistics(band: LlBand): BandStatistics {
        val mean = band.mean
        val spread = band.spread(mean)
        var outliers = 0
        for (value in band.values) {
            if (abs(value - mean) > spread * 4f) outliers += 1
        }
        val ratio = if (band.values.isEmpty()) 0f else outliers.toFloat() / band.values.size
        return BandStatistics(mean, spread, ratio)
    }

    private fun quantise(value: Float): Int {
        var level = 0
        for (cut in cuts) if (value >= cut) level += 1
        return level
    }

    /** Round-to-nearest helper kept for the calibration tests. */
    fun roundTripError(band: LlBand, signature: ByteArray): Float {
        val mean = band.mean
        val spread = band.spread(mean)
        val cells = cellsOf(signature)
        val side = StegoLimits.FINGERPRINT_SIDE
        var squared = 0.0
        for (cell in 0 until CELLS) {
            val row = cell / side
            val col = cell - row * side
            val fromY = row * band.cellsHigh / side
            val toY = ((row + 1) * band.cellsHigh / side).coerceAtLeast(fromY + 1).coerceAtMost(band.cellsHigh)
            val fromX = col * band.cellsWide / side
            val toX = ((col + 1) * band.cellsWide / side).coerceAtLeast(fromX + 1).coerceAtMost(band.cellsWide)
            var total = 0.0
            var count = 0
            for (y in fromY until toY) {
                val offset = y * band.cellsWide
                for (x in fromX until toX) {
                    total += band.values[offset + x]
                    count += 1
                }
            }
            if (count == 0) continue
            val observed = ((total / count) - mean) / spread
            val centre = levelCentre(cells[cell])
            squared += (observed - centre) * (observed - centre)
        }
        return sqrt(squared / CELLS).toFloat()
    }

    private fun levelCentre(level: Int): Float = when (level) {
        LEVEL_LOW -> -1.2f
        LEVEL_BELOW -> -0.25f
        LEVEL_ABOVE -> 0.35f
        else -> 1.2f
    }
}

/** The band's own DC, spread and outlier share, as measured by the reader. */
data class BandStatistics(val mean: Float, val spread: Float, val outlierRatio: Float) {
    /** A photocopied flat field has nothing to hide a mark in. */
    val carriesDetail: Boolean get() = spread > 1.5f
}
