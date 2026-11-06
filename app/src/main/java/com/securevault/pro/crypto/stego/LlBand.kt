package com.securevault.pro.crypto.stego

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The LL sub-band of a Haar wavelet on the luminance plane, at a chosen cell size.
 *
 * Separable Haar averaging turns a pixel grid into a grid of `cell x cell` means, so the band is
 * stored as one float per cell and pixel `x` belongs to cell `x / cell` in each axis. Edge cells are
 * *shortened* rather than replicated: a cell averages only the pixels that really exist, which keeps
 * the inverse honest — adding a constant to every pixel of a cell moves that cell's mean by exactly
 * that constant. That property is the whole reason the mark is invisible: only the LL branch is
 * touched, every horizontal/vertical/diagonal detail coefficient is left alone, so texture, edges and
 * noise of the photo are byte-for-byte preserved.
 *
 * [cellSize] is a channel choice, not a cosmetic one. Four is the fine grid, which is the quietest
 * mark and the one that survives a print. Eight is the grid a JPEG encoder commits to: its block
 * quantiser prices a whole 8x8 average almost free while it throws away the finer detail a 4x4 grid
 * leans on, so a mark written on eight-wide cells rides a chat app's redraw instead of being erased
 * by it. Both sizes go through the same maths and the same lattice, so the reader tells them apart by
 * measuring, never by trusting a label.
 */
class LlBand(
    val planeWidth: Int,
    val planeHeight: Int,
    val cellsWide: Int,
    val cellsHigh: Int,
    val values: FloatArray,
    val cellSize: Int = DEFAULT_CELL
) {
    val cellCount: Int get() = values.size

    /** DC level of the band; removing it is what makes the mark survive a brightness shift. */
    val mean: Float
        get() {
            if (values.isEmpty()) return 0f
            var total = 0.0
            for (value in values) total += value
            return (total / values.size).toFloat()
        }

    /** Spread of the band around its mean; dividing by it makes the mark survive contrast changes. */
    fun spread(mean: Float): Float {
        if (values.isEmpty()) return 1f
        var total = 0.0
        for (value in values) {
            val delta = value - mean
            total += delta * delta
        }
        val rms = sqrt(total / values.size).toFloat()
        return if (rms < MIN_SPREAD) MIN_SPREAD else rms
    }

    /** Copy of the band with DC removed and gain normalised to unit spread. */
    fun normalised(): FloatArray {
        val base = mean
        val spread = spread(base)
        return FloatArray(values.size) { (values[it] - base) / spread }
    }

    fun copy(): LlBand = LlBand(planeWidth, planeHeight, cellsWide, cellsHigh, values.copyOf(), cellSize)

    companion object {
        /** Guards against a flat field where the normaliser would otherwise divide by zero. */
        const val MIN_SPREAD = 0.75f

        /** The fine grid: the quietest mark, and the one every carrier written before this change uses. */
        const val DEFAULT_CELL = 4

        /** Cell count for one axis at [cellSize]: ceil(n / cellSize), the support of the average. */
        fun cellsFor(length: Int): Int = (length + DEFAULT_CELL - 1) / DEFAULT_CELL

        fun cellsFor(length: Int, cellSize: Int): Int {
            val cell = cellSize.coerceAtLeast(1)
            return (length + cell - 1) / cell
        }
    }
}

/**
 * Forward band transform and the matching block-constant synthesis back into pixels.
 */
object HaarLl {

    /**
     * Averages each `cellSize x cellSize` block of the plane (shortened at the right/bottom edge)
     * into one band cell. Cost is one pass over the pixels, so a carrier at the
     * [StegoLimits.MAX_WORKING_PIXELS] budget needs a few megabytes for the band rather than a full
     * multi-level coefficient pyramid.
     */
    fun of(plane: LumaPlane, cellSize: Int = LlBand.DEFAULT_CELL): LlBand {
        val cell = cellSize.coerceAtLeast(1)
        val cellsWide = LlBand.cellsFor(plane.width, cell)
        val cellsHigh = LlBand.cellsFor(plane.height, cell)
        val sums = FloatArray(cellsWide * cellsHigh)
        val counts = IntArray(cellsWide * cellsHigh)
        for (y in 0 until plane.height) {
            val cellY = (y / cell).coerceAtMost(cellsHigh - 1)
            val row = y * plane.width
            val rowBase = cellY * cellsWide
            for (x in 0 until plane.width) {
                val cellX = (x / cell).coerceAtMost(cellsWide - 1)
                val index = rowBase + cellX
                sums[index] += plane.samples[row + x].toFloat()
                counts[index] += 1
            }
        }
        val values = FloatArray(sums.size) { index ->
            val count = counts[index]
            if (count == 0) 0f else sums[index] / count
        }
        return LlBand(plane.width, plane.height, cellsWide, cellsHigh, values, cell)
    }

    /**
     * Synthesises the marked plane from a band-space delta. Because only LL moved, the inverse
     * two-level Haar is a piecewise-constant offset: every pixel of a cell takes the cell's delta.
     * Rounding to 8 bits is the only quantisation the channel sees, and the encoder's refinement
     * passes re-measure the result so the written pixels really carry the intended chips.
     */
    fun synthesize(
        plane: LumaPlane,
        delta: FloatArray,
        cellSize: Int = LlBand.DEFAULT_CELL
    ): LumaPlane {
        val cell = cellSize.coerceAtLeast(1)
        val cellsWide = LlBand.cellsFor(plane.width, cell)
        val cellsHigh = LlBand.cellsFor(plane.height, cell)
        require(delta.size == cellsWide * cellsHigh) {
            "Band delta must be ${cellsWide * cellsHigh} cells, found ${delta.size}"
        }
        val out = IntArray(plane.samples.size)
        for (y in 0 until plane.height) {
            val cellY = (y / cell).coerceAtMost(cellsHigh - 1)
            val row = y * plane.width
            val rowBase = cellY * cellsWide
            for (x in 0 until plane.width) {
                val cellX = (x / cell).coerceAtMost(cellsWide - 1)
                val value = plane.samples[row + x] + delta[rowBase + cellX]
                out[row + x] = value.roundToInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(plane.width, plane.height, out)
    }

    /**
     * The band a decoder would compute from the already-marked plane, i.e. the plane band minus the
     * delta. Used by the refinement loop so the encoder converges on the decoder's own numbers.
     */
    fun residual(band: LlBand, delta: FloatArray): FloatArray =
        FloatArray(band.values.size) { band.values[it] - delta[it] }
}

/**
 * The canonical mark lattice: a fixed side count (64/96/128) spread over the band in *normalised*
 * coordinates, so the same lattice is found again however many pixels the received photo happens to
 * have. That resolution independence is what carries the payload through a 50% downscale and
 * through a printer-to-scanner resample, where a fixed-pixel grid would drift off the mark.
 */
class CanonicalLattice(
    val side: Int,
    val cellsWide: Int,
    val cellsHigh: Int
) {
    /** Band cells at the very edge are the first thing a printer or a crop loses. */
    private val inset: Int = 2

    val slotCount: Int get() = side * side

    init {
        require(side >= 8) { "A lattice needs at least 8 slots per side" }
        require(cellsWide > inset * 2 && cellsHigh > inset * 2) { "Band too small for the lattice" }
    }

    val ringSlots: IntArray by lazy {
        val list = ArrayList<Int>(side * 4)
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (row == 0 || col == 0 || row == side - 1 || col == side - 1) list.add(row * side + col)
            }
        }
        list.toIntArray()
    }

    val payloadSlots: IntArray by lazy {
        val list = ArrayList<Int>(side * side)
        for (row in 1 until side - 1) {
            for (col in 1 until side - 1) list.add(row * side + col)
        }
        list.toIntArray()
    }

    /** Continuous band coordinates of a slot's sampling point. */
    fun centerOf(slot: Int): FloatArray {
        val row = slot / side
        val col = slot - row * side
        return floatArrayOf(axis(col, spanWide), axis(row, spanHigh))
    }

    private val spanWide: Int get() = (cellsWide - inset * 2).coerceAtLeast(1)
    private val spanHigh: Int get() = (cellsHigh - inset * 2).coerceAtLeast(1)

    private fun axis(index: Int, span: Int): Float = inset + (index + 0.5f) * span / side

    /**
     * The up-to-four bilinear taps of a slot, with coincident taps merged. Merging matters: if it is
     * skipped, [splat] under-corrects exactly where the mark touches a band edge and the decoder's
     * bilinear read no longer returns the value the encoder wrote.
     */
    private fun taps(slot: Int): BilinearTaps {
        val row = slot / side
        val col = slot - row * side
        val u = axis(col, spanWide).coerceIn(0f, (cellsWide - 1).toFloat())
        val v = axis(row, spanHigh).coerceIn(0f, (cellsHigh - 1).toFloat())
        val x0 = u.toInt().coerceIn(0, cellsWide - 1)
        val y0 = v.toInt().coerceIn(0, cellsHigh - 1)
        val fx = u - x0
        val fy = v - y0
        val x1 = (x0 + 1).coerceAtMost(cellsWide - 1)
        val y1 = (y0 + 1).coerceAtMost(cellsHigh - 1)
        val wideX = floatArrayOf(1f - fx, fx)
        val wideY = floatArrayOf(1f - fy, fy)
        val columnTo = intArrayOf(x0, x1)
        val rowTo = intArrayOf(y0, y1)
        val cells = ArrayList<Int>(4)
        val weights = ArrayList<Float>(4)
        for (ry in 0 until 2) {
            for (rx in 0 until 2) {
                val target = rowTo[ry] * cellsWide + columnTo[rx]
                val weight = wideY[ry] * wideX[rx]
                val known = cells.indexOf(target)
                if (known >= 0) weights[known] = weights[known] + weight
                else {
                    cells.add(target)
                    weights.add(weight)
                }
            }
        }
        var energy = 0f
        for (weight in weights) energy += weight * weight
        return BilinearTaps(cells.toIntArray(), weights.toFloatArray(), energy)
    }

    /** Reads the band value the mark is carried on, using the same bilinear rule at both ends. */
    fun measure(band: FloatArray, slot: Int): Float {
        val taps = taps(slot)
        var total = 0f
        for (tap in 0 until taps.count) total += band[taps.cells[tap]] * taps.weights[tap]
        return total
    }

    /**
     * Writes `delta` so that [measure] would return exactly `previous + delta`. The taps are scaled
     * by 1/sum(w^2) because the bilinear read applies the weights again on the way back.
     */
    fun splat(band: FloatArray, slot: Int, delta: Float) {
        if (delta == 0f) return
        val taps = taps(slot)
        if (taps.energy < 1e-4f) return
        val gain = delta / taps.energy
        for (tap in 0 until taps.count) {
            val cell = taps.cells[tap]
            if (cell in band.indices) band[cell] += taps.weights[tap] * gain
        }
    }

    /** Slots that carry the directory, in order, repeated as often as the ring allows. */
    fun directorySlotFor(bitIndex: Int): Int = ringSlots[bitIndex % ringSlots.size]

    /** Slot carrying chip `chip` of the payload, after the public shuffle. */
    fun payloadSlotFor(chip: Int): Int {
        val order = SlotShuffle.of(payloadSlots.size)
        return payloadSlots[order[chip % payloadSlots.size]]
    }

    /**
     * Every slot's taps in one flat table. Reading and writing a mark touches each slot a handful of
     * times across the refinement passes, and building the table once keeps the inner loop free of
     * allocations on a handset.
     */
    fun tapTable(): LatticeTapTable {
        val cells = IntArray(slotCount * 4)
        val weights = FloatArray(slotCount * 4)
        val degree = IntArray(slotCount)
        val energy = FloatArray(slotCount)
        for (slot in 0 until slotCount) {
            val taps = taps(slot)
            degree[slot] = taps.count
            var sum = 0f
            for (index in 0 until taps.count) {
                cells[slot * 4 + index] = taps.cells[index]
                weights[slot * 4 + index] = taps.weights[index]
                sum += taps.weights[index] * taps.weights[index]
            }
            energy[slot] = sum
        }
        return LatticeTapTable(slotCount, cells, weights, degree, energy)
    }
}

/** Flattened bilinear tap geometry for a whole lattice; see [CanonicalLattice.tapTable]. */
class LatticeTapTable(
    val slotCount: Int,
    val cells: IntArray,
    val weights: FloatArray,
    val degree: IntArray,
    val energy: FloatArray
) {
    fun measure(band: FloatArray, slot: Int): Float {
        var total = 0f
        val base = slot * 4
        for (index in 0 until degree[slot]) total += band[cells[base + index]] * weights[base + index]
        return total
    }

    fun splat(band: FloatArray, slot: Int, delta: Float) {
        val power = energy[slot]
        if (delta == 0f || power < 1e-4f) return
        val gain = delta / power
        val base = slot * 4
        for (index in 0 until degree[slot]) {
            band[cells[base + index]] += weights[base + index] * gain
        }
    }

    /** The largest absolute change any single cell can receive, used by the invisibility tests. */
    fun peakCellChange(band: FloatArray, slot: Int, delta: Float): Float {
        val power = energy[slot]
        if (delta == 0f || power < 1e-4f) return 0f
        var peak = 0f
        val base = slot * 4
        for (index in 0 until degree[slot]) {
            val change = Math.abs(weights[base + index] * delta / power)
            if (change > peak) peak = change
        }
        return peak
    }
}

/** The merged bilinear taps of one lattice slot: which band cells it reads, and how hard. */
private class BilinearTaps(
    val cells: IntArray,
    val weights: FloatArray,
    val energy: Float
) {
    val count: Int get() = cells.size
}

/**
 * Quantisation-index modulation on a single coefficient: the bit chooses between two interleaved
 * combs half a step apart, so the maximum distortion written into the photo is step/2 and the
 * decoder only has to tell which comb the value landed in.
 */
object Qim {

    fun embed(value: Float, bit: Int, step: Float): Float {
        val offset = if (bit == 1) step * 0.5f else 0f
        val index = Math.round((value - offset) / step)
        return index * step + offset
    }

    /**
     * Signed confidence: positive favours bit 1, negative favours bit 0, and the magnitude is the
     * margin in steps. Callers sum these over chip repetitions, which is a soft-decision vote.
     */
    fun demod(value: Float, step: Float): Float {
        val scaled = value / step
        val nearZero = Math.abs(scaled - Math.round(scaled))
        val shifted = scaled - 0.5f
        val nearOne = Math.abs(shifted - Math.round(shifted))
        return (nearZero - nearOne)
    }
}
