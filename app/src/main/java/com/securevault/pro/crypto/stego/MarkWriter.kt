package com.securevault.pro.crypto.stego

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Writes a bit stream into a luminance band as QIM chips on the canonical lattice.
 *
 * The loop is a Gauss–Seidel sweep: every slot is measured through the *current* band (original plus
 * the corrections already applied), quantised to the comb its bit selects, and the residual is
 * splatted back over the two or four band cells that the slot's bilinear tap reads. Because the
 * normalisation (DC level and spread) is re-measured from the marked band on each pass, the encoder
 * converges on exactly the numbers a decoder will compute from the finished photo — which is the
 * only reason a bounded-amplitude mark survives printing.
 */
object MarkWriter {

    /**
     * @return the band-space delta, in grey levels per LL cell, that turns `band` into the marked
     *         band. Passing it to [HaarLl.synthesize] writes the carrier.
     */
    fun write(
        band: LlBand,
        lattice: CanonicalLattice,
        directoryBits: IntArray,
        payloadBits: IntArray,
        chipRate: Int,
        step: Float,
        passes: Int = StegoLimits.EMBED_REFINEMENT_PASSES
    ): FloatArray {
        val table = lattice.tapTable()
        val cells = band.values.size
        val delta = FloatArray(cells)
        val current = band.values.copyOf()

        val ring = lattice.ringSlots
        val payloadSlots = lattice.payloadSlots
        val order = SlotShuffle.of(payloadSlots.size)
        val chips = payloadBits.size * chipRate
        val chipSlots = IntArray(chips) { chip -> payloadSlots[order[chip % payloadSlots.size]] }

        for (pass in 0 until max(1, passes)) {
            var total = 0.0
            for (value in current) total += value
            val mean = (total / current.size).toFloat()
            var energy = 0.0
            for (value in current) {
                val offset = value - mean
                energy += offset * offset
            }
            val spread = (sqrt(energy / current.size).toFloat()).coerceAtLeast(LlBand.MIN_SPREAD)

            for (index in ring.indices) {
                writeSlot(
                    table, current, delta, ring[index],
                    directoryBits[index % directoryBits.size], mean, spread, step
                )
            }
            for (chip in 0 until chips) {
                writeSlot(table, current, delta, chipSlots[chip], payloadBits[chip / chipRate], mean, spread, step)
            }
        }
        return delta
    }

    private fun writeSlot(
        table: LatticeTapTable,
        current: FloatArray,
        delta: FloatArray,
        slot: Int,
        bit: Int,
        mean: Float,
        spread: Float,
        step: Float
    ) {
        val measured = table.measure(current, slot)
        val normalised = (measured - mean) / spread
        val target = Qim.embed(normalised, bit, step)
        val correction = target * spread + mean - measured
        if (correction == 0f || correction.isNaN() || correction.isInfinite()) return
        table.splat(current, slot, correction)
        table.splat(delta, slot, correction)
    }

    /**
     * Reads the mark back as soft chip confidences. Positive favours a 1, negative a 0, and the
     * magnitude is the margin in QIM steps, so summing them is a soft-decision vote.
     */
    fun readConfidences(
        band: LlBand,
        lattice: CanonicalLattice,
        bitCount: Int,
        chipRate: Int,
        step: Float,
        directory: Boolean = false
    ): FloatArray {
        val table = lattice.tapTable()
        val mean = band.mean
        val spread = band.spread(mean)
        val slots = if (directory) lattice.ringSlots else lattice.payloadSlots
        val order = if (directory) null else SlotShuffle.of(slots.size)
        val votes = FloatArray(bitCount)
        val chips = bitCount * chipRate
        for (chip in 0 until chips) {
            val slot = if (directory) {
                slots[(chip % bitCount) % slots.size]
            } else {
                slots[order!![chip % slots.size]]
            }
            val measured = table.measure(band.values, slot)
            votes[chip / chipRate] += Qim.demod((measured - mean) / spread, step)
        }
        return votes
    }

    /** Peak absolute band movement of a delta, in grey levels: the invisibility budget. */
    fun peakGreyLevel(delta: FloatArray): Float {
        var peak = 0f
        for (value in delta) {
            val magnitude = Math.abs(value)
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }
}
