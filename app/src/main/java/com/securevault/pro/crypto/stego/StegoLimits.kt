package com.securevault.pro.crypto.stego

/**
 * Every constant that decides how a carrier behaves, in one place so the survival margins can be
 * retuned without hunting through the maths.
 *
 * The numbers are measured, not guessed: the table in `HANDOFF.md` records the bit-error rate and
 * the recovery rate per transform at these settings.
 */
object StegoLimits {

    /**
     * Hard ceiling on the working luminance grid (samples, not bytes). A 4000x3000 carrier is
     * area-averaged down to fit, which keeps the whole encode inside roughly
     * `MAX_WORKING_PIXELS * 4` bytes per float buffer — about 8 MB — no matter how large the
     * original photo is. The marked photo is written back at this bounded resolution, so the
     * carrier stays an ordinary-looking ~1.8 MP jpeg instead of risking an OOM at 12 MP.
     */
    const val MAX_WORKING_PIXELS = 2_000_000

    /** Below this the LL band cannot host even the sparsest lattice; such photos are refused. */
    const val MIN_WORKING_LONGEST = 240

    /**
     * The side every carrier is sealed on, in pixels, measured along the longer axis.
     *
     * A chat app rescales the photo it is given, and a rescale does not just attenuate the mark — it
     * slides the receiver's cell grid past the writer's, by up to a fifth of a cell for every cell
     * along the row. Measured on the JVM: an 8-wide mark written at 1280 and read back at the 0.8
     * scale a send produces is gone, while the *same* mark resampled back to 1280 reads with zero
     * drift, after requantisation and blur. The damage was never the compression; it was the grid
     * disagreeing with itself.
     *
     * So sealing works at this one size, and a reader that cannot find a mark tries the photo again
     * after rescaling it here. Every number that decides the cell count — the lattice's slots per
     * band cell, and the coarse grid's alignment with the JPEG block — then belongs to one grid the
     * encoder actually measured, not to whatever megapixel count a phone happened to hand over.
     */
    const val CANONICAL_LONGEST = 1280

    /** A secret larger than this is a document, not a message; the encoder refuses it outright. */
    const val MAX_SECRET_BYTES = 3_072

    /** QIM step in units of the band's own RMS. Small = invisible, large = print-survivable. */
    const val QIM_STEP = 0.36f

    /**
     * The coarse grid's cell side, in pixels. Eight because that is the block a JPEG encoder works
     * on: it keeps a whole 8x8 average nearly intact and throws away the detail inside the block, so
     * a mark sized to that block is the one quantity a messaging app's redraw preserves. See
     * [StegoProfile.ROBUST].
     */
    const val ROBUST_CELL = 8

    /** The coarse grid's comb, nearly twice the fine one: the price of riding a requantised block. */
    const val ROBUST_QIM_STEP = 0.62f

    /** Correction passes, so splat interference between neighbouring lattice cells settles down. */
    const val EMBED_REFINEMENT_PASSES = 3

    /** Chip repetitions per payload bit at the fragile end of the scale. */
    const val MIN_CHIP_RATE = 2

    /** Chip repetitions per payload bit at the rugged end (tiny payloads on big carriers). */
    const val MAX_CHIP_RATE = 12

    /** The encoder prefers a rate at least this high before it accepts a lattice at all. */
    const val TARGET_CHIP_RATE = 4

    /** Ring slots per directory bit must reach this or the acquisition is not trusted. */
    const val MIN_DIRECTORY_RATE = 3

    /** Directory: magic + mode + share fields + chip rate + frame length + CRC-8. */
    const val DIRECTORY_BITS = 56
    const val DIRECTORY_MAGIC = 0x5356

    /** Frame length is transmitted in bits and cannot exceed this. */
    const val MAX_FRAME_BITS = 16_383

    /** Reed–Solomon: RS(255,223) shortened per chunk, correcting 16 symbols each. */
    const val RS_DATA_CHUNK = 223
    const val RS_PARITY_CHUNK = 32

    /** Derivation cost, deliberately identical to the vault's own PBKDF2 policy. */
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 8
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val GCM_TAG_BYTES = 16

    /** Integrity digest, carrier fingerprint and its tolerated drift, in cells of the 6x6 grid. */
    const val DIGEST_BYTES = 16
    const val FINGERPRINT_SIDE = 6
    const val FINGERPRINT_BYTES = (FINGERPRINT_SIDE * FINGERPRINT_SIDE) / 4
    const val FINGERPRINT_TOLERANCE = 5

    /** meta = version, lattice, mode, geometry, kind, share pair, one-read, salt, CRC-16. */
    const val META_BYTES = 22

    const val JPEG_QUALITY = 95

    /** CameraX frames below this many samples are not worth attempting. */
    const val MIN_SCAN_LONGEST = 320

    /** Decode is bounded by profiles x lattice candidates x orientations; this is the safety cap. */
    const val MAX_ACQUISITION_TRIES = 24
}
