package com.securevault.pro.crypto.stego

/**
 * A channel choice: how coarse the band cells are, and how wide the mark's comb is on them.
 *
 * The two numbers travel together, because the amplitude a chip needs is a fraction of the band's
 * own spread and the spread depends on how many pixels were averaged into a cell. A profile is
 * therefore one decision — *which grid the mark rides* — not two tunables that can be set apart.
 *
 * [ROBUST] averages eight pixels per side. Eight is not an arbitrary round number: it is the block
 * a JPEG encoder commits to. That encoder prices one whole 8x8 average (its DC coefficient) very
 * cheaply and throws away the finer variation inside the block (its AC coefficients), so a mark
 * written on eight-wide cells sits in the one quantity the chat app carries through, while a
 * four-wide mark straddles two blocks and half of it lands in the part that gets discarded. Combined
 * with the wider comb, [ROBUST] is what a photo that is about to be resent through a messaging app
 * should use.
 *
 * [CLASSIC] averages four pixels per side, which is the quieter mark: it moves the photo by about
 * half as much. It is also what every carrier this app wrote before this change holds, so the reader
 * must keep finding it, and it still wins when the photo is too small for the coarse grid to host a
 * lattice at all.
 *
 * Nothing on the image names the profile. The reader measures the ring directory on each candidate in
 * [searched] order and accepts the first one whose directory carries the format's magic and its
 * checksum, which is a twenty-four-bit self-test that a photograph cannot pass by accident — and
 * which the payload's own error correction and header checksum then confirm.
 */
enum class StegoProfile(
    /** Pixels per band-cell side. */
    val cellSize: Int,
    /** QIM comb spacing in units of the band's RMS. */
    val step: Float
) {

    /** Coarse grid on the JPEG block boundary, wide comb: rides a chat app's redraw. */
    ROBUST(StegoLimits.ROBUST_CELL, StegoLimits.ROBUST_QIM_STEP),

    /** Fine grid, quiet comb: the quietest mark and the format of every older carrier. */
    CLASSIC(LlBand.DEFAULT_CELL, StegoLimits.QIM_STEP);

    /** The band this profile reads and writes on. */
    fun bandOf(plane: LumaPlane): LlBand = HaarLl.of(plane, cellSize)

    /** Band cells along one axis for a plane this long. */
    fun cellsFor(length: Int): Int = LlBand.cellsFor(length, cellSize)

    /** Synthesise this profile's band delta back into pixels. */
    fun synthesize(plane: LumaPlane, delta: FloatArray): LumaPlane =
        HaarLl.synthesize(plane, delta, cellSize)

    /** One sentence for the owner, from the two numbers that actually differ. */
    val label: String
        get() = if (this == ROBUST) "built to be sent as a photo" else "built to be printed"

    companion object {

        /**
         * Search order, and it is load-bearing.
         *
         * Robust first: a photo that came out of a messaging app has been rescaled and requantised,
         * and its four-wide detail is the part that was lost, so only the coarse grid can still answer.
         * An *old* carrier cannot be mistaken for a robust one, because a robust read of it would have
         * to invent a valid directory checksum on the coarse grid.
         */
        val searched: List<StegoProfile> = listOf(ROBUST, CLASSIC)

        /** Every profile a carrier may be sealed on, best first. */
        val preferred: List<StegoProfile> = listOf(ROBUST, CLASSIC)
    }
}
