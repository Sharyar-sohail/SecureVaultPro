package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.CarrierGeometry
import com.securevault.pro.crypto.stego.LatticeSide
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.ReedSolomon
import com.securevault.pro.crypto.stego.StegoCapacityException
import com.securevault.pro.crypto.stego.StegoLimits
import com.securevault.pro.crypto.stego.StegoProfile

/**
 * How much secret a given photo can actually carry, and what the mark will cost on the lattice.
 *
 * The stego core under `crypto/stego` ships the maths (wavelet band, QIM chips, Reed–Solomon,
 * frame format) but no orchestrator, so the sizing rules live here. Every number this file reports
 * is derived from the real constants and the real error-control code — [StegoLimits],
 * [ReedSolomon.encodedBitCount] and [LatticeSide] slot counts — because the UI promises the owner a
 * capacity figure and an invented one is worse than none.
 *
 * No Android types: the same arithmetic runs in the JVM unit tests and on the handset.
 */
data class StegoPlan(
    val lattice: LatticeSide,
    val chipRate: Int,
    val frameBits: Int,
    val payloadSlots: Int,
    val cellsWide: Int,
    val cellsHigh: Int,
    val profile: StegoProfile = StegoProfile.CLASSIC
) {
    /** Chip slots actually occupied by the frame, out of the interior slots the lattice offers. */
    val chipsUsed: Int get() = frameBits * chipRate

    /** 0..1 — how much of this lattice the payload eats. Above ~0.9 there is no repair headroom. */
    val utilisation: Double
        get() = if (payloadSlots <= 0) 1.0 else (chipsUsed.toDouble() / payloadSlots).coerceAtMost(1.0)

    /**
     * True when the rate reaches [StegoLimits.TARGET_CHIP_RATE]; below it the mark is real but the
     * soft-decision vote is thin, so the screen must show it as a caution instead of a green light.
     */
    val comfortable: Boolean get() = chipRate >= StegoLimits.TARGET_CHIP_RATE

    /** Lattice side the mark was written on, for the [CarrierGeometry] the archive seals. */
    val side: Int get() = lattice.side

    val marginSteps: Float
        get() = (chipRate * profile.step * 0.5f)
}

/** The frame a carrier will hold: sealed envelope blocks, their padding, and the header over them. */
data class StegoFrameShape(
    val secretSizes: List<Int>,
    val metaBytes: Int = StegoLimits.META_BYTES
) {
    /**
     * One sealed block: `iv(12) + digest(16) + kind(1) + length(2) + fingerprint(9) + padded body`
     * plus the `tag(16)` GCM appends, wrapped by the two length bytes the payload carries.
     * Matches [com.securevault.pro.crypto.stego.StegoFrame.sealEnvelope] byte for byte.
     */
    private val blockOverhead: Int = 12 + StegoLimits.DIGEST_BYTES + 1 + 2 +
        StegoLimits.FINGERPRINT_BYTES + StegoLimits.GCM_TAG_BYTES

    /** Every block pads to the longest secret, so no envelope can be ranked by size. */
    val paddedBytes: Int get() = secretSizes.maxOrNull() ?: 0

    val envelopeCount: Int get() = secretSizes.size

    /** Total RS-protected payload: header + count + one length-prefixed block each. */
    val payloadBytes: Int
        get() = metaBytes + 1 + secretSizes.sumOf { 2 + blockOverhead + paddedBytes }
}

object StegoCapacity {

    /** One RS instance is enough; building the generator polynomial per call would be waste. */
    private val coder = ReedSolomon()

    /** Interior slots of a lattice — the ring is reserved for the directory. */
    fun payloadSlotsOf(side: LatticeSide): Int = (side.side - 2) * (side.side - 2)

    /**
     * Band cells a working plane yields on this profile: one average of `cellSize x cellSize` pixels
     * per cell, so the coarse grid has half as many cells along each axis.
     */
    fun cellsOf(plane: LumaPlane, profile: StegoProfile = StegoProfile.CLASSIC): Pair<Int, Int> =
        profile.cellsFor(plane.width) to profile.cellsFor(plane.height)

    /**
     * The largest frame the wire format can even express. `frameBits` travels in fourteen bits of
     * directory, so the ceiling is [StegoLimits.MAX_FRAME_BITS] regardless of how big the photo is.
     */
    val maxFrameBits: Int = StegoLimits.MAX_FRAME_BITS

    fun encodedBitsFor(payloadBytes: Int): Int = coder.encodedBitCount(payloadBytes)

    fun encodedBytesFor(payloadBytes: Int): Int = coder.encodedByteCount(payloadBytes)

    /**
     * True when a lattice of this side is physically present in the band, and present *sparsely*.
     *
     * Two limits, both measured rather than styled:
     *
     *  - More than two slots per band cell in either axis is refused outright: the bilinear taps would
     *    land on the same cells and the chips would fight each other.
     *  - More lattice slots than band cells is refused as well. [MarkWriter] sweeps every slot through
     *    the same cell, so once slots outnumber cells the Gauss–Seidel corrections have nowhere to go
     *    but back into cells they already moved; on a coarse band over a 640x480 photo that turned a
     *    12-grey-level mark into an 83-grey-level one and the payload stopped decoding. A sparse
     *    lattice is the same mark, quietly written.
     */
    private fun admissible(side: LatticeSide, cellsWide: Int, cellsHigh: Int): Boolean {
        if (cellsWide <= 4 || cellsHigh <= 4) return false
        if (side.side > 2 * minOf(cellsWide, cellsHigh)) return false
        return side.side.toLong() * side.side <= cellsWide.toLong() * cellsHigh
    }

    /**
     * Chooses the lattice and chip rate for a frame of `frameBits` on a band of the given size.
     *
     * The preference order is deliberate: the smallest lattice that still reaches
     * [StegoLimits.TARGET_CHIP_RATE] wins, because a smaller lattice packs more repetitions into
     * every band cell and survives a photocopy better. When nothing reaches the target, the widest
     * lattice with the highest available rate is returned with `comfortable = false` — the mark is
     * still real, but the screen has to say the margin is thin.
     */
    fun planFor(
        frameBits: Int,
        cellsWide: Int,
        cellsHigh: Int,
        profile: StegoProfile = StegoProfile.CLASSIC
    ): StegoPlan? {
        if (frameBits <= 0 || frameBits > maxFrameBits) return null
        var fallback: StegoPlan? = null
        for (side in LatticeSide.ordered) {
            if (!admissible(side, cellsWide, cellsHigh)) continue
            val slots = payloadSlotsOf(side)
            val available = slots / frameBits
            if (available < StegoLimits.MIN_CHIP_RATE) continue
            val rate = available.coerceAtMost(StegoLimits.MAX_CHIP_RATE)
            val candidate = StegoPlan(side, rate, frameBits, slots, cellsWide, cellsHigh, profile)
            if (candidate.comfortable) return candidate
            if (fallback == null || rate > fallback.chipRate) fallback = candidate
        }
        return fallback
    }

    /** The plan for a whole frame shape, or null when the carrier cannot host it at all. */
    fun planFor(
        shape: StegoFrameShape,
        cellsWide: Int,
        cellsHigh: Int,
        profile: StegoProfile = StegoProfile.CLASSIC
    ): StegoPlan? {
        val payloadBytes = shape.payloadBytes
        if (payloadBytes <= 0) return null
        val frameBits = encodedBitsFor(payloadBytes)
        if (frameBits > maxFrameBits) return null
        return planFor(frameBits, cellsWide, cellsHigh, profile)
    }

    /**
     * Bytes of secret a plane of this size can hide for `envelopeCount` equally padded envelopes.
     *
     * Found by growing the payload until the plan runs out of lattice, because the relationship
     * (RS chunking, padding, chip rate) is a step function and only a search reports it exactly.
     * Returns 0 when even a single byte will not fit.
     */
    fun maxSecretBytes(plane: LumaPlane, envelopeCount: Int, profile: StegoProfile = StegoProfile.CLASSIC): Int {
        if (envelopeCount <= 0) return 0
        val cells = cellsOf(plane, profile)
        var low = 0
        var high = StegoLimits.MAX_SECRET_BYTES
        while (low < high) {
            val middle = (low + high + 1) / 2
            val shape = StegoFrameShape(List(envelopeCount) { middle })
            if (planFor(shape, cells.first, cells.second, profile) != null) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Bytes of secret a whole dispersal set can carry, over carriers of this size.
     *
     * NOT multiplied by [shareTotal], and the reason is worth keeping in one place: [SecretShares]
     * is a GF(256) *split*, so every share is exactly as long as the secret and each carrier holds
     * one full-length share. Three carriers therefore carry one message of the same size as a single
     * carrier would — dispersal buys distribution, never capacity. Multiplying here would promise the
     * owner three times the bytes and then fail at the third seal.
     *
     * A share count outside 1..4 (the range `StegoEncodeRequest` itself requires) answers 0 rather
     * than planning a set the codec is about to refuse.
     */
    fun maxSecretBytesForSet(plane: LumaPlane, envelopeCount: Int, shareTotal: Int): Int {
        val carriers = shareTotal.coerceIn(1, 4)
        if (carriers != shareTotal) return 0
        return maxSecretBytes(plane, envelopeCount.coerceAtLeast(1))
    }

    /** Rejects a secret that cannot be hidden before any key derivation is paid for. */
    fun requireFits(
        plane: LumaPlane,
        shape: StegoFrameShape,
        profile: StegoProfile = StegoProfile.CLASSIC
    ): StegoPlan {
        val cells = cellsOf(plane, profile)
        val requested = shape.secretSizes.maxOrNull() ?: 0
        val plan = planFor(shape, cells.first, cells.second, profile)
        if (plan != null) return plan
        val limit = maxSecretBytes(plane, shape.envelopeCount, profile)
        throw StegoCapacityException(
            "This photo can hide about $limit bytes per envelope at ${cells.first}x${cells.second} " +
                "band cells; the secret is $requested bytes.",
            limit,
            requested
        )
    }

    /** The geometry block a sealed carrier reports to the archive. */
    fun geometryOf(plane: LumaPlane, plan: StegoPlan): CarrierGeometry =
        CarrierGeometry(plane.width, plane.height, plan.side, plan.chipRate)

    /** True when the photo is big enough that the band can hold any lattice at all. */
    fun isWorkable(plane: LumaPlane): Boolean {
        val longest = maxOf(plane.width, plane.height)
        return longest >= StegoLimits.MIN_WORKING_LONGEST
    }

    /** Human sentence about a plan, built only from measured numbers. */
    fun describe(plan: StegoPlan): String =
        "${plan.lattice.name.lowercase()} lattice · ${plan.chipRate} chips/bit · " +
                "${plan.frameBits} frame bits · ${(plan.utilisation * 100).toInt()}% of the lattice"
}
