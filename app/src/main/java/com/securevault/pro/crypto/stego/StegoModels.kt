package com.securevault.pro.crypto.stego

/**
 * One 8-bit luminance grid — the only picture type this subsystem speaks.
 *
 * The mark lives in luminance alone, so chroma is irrelevant to the maths: a colour photo, a
 * greyscale print and a rescanned photocopy all reduce to exactly this. Keeping the core on an
 * `IntArray` of 0..255 samples is what lets it run identically on a JVM (unit tests, the capacity
 * calculator) and on a handset (Bitmap or CameraX Y-plane adapter in `data/stego`), with no
 * `android.*` import anywhere under `crypto/stego`.
 */
class LumaPlane(
    val width: Int,
    val height: Int,
    val samples: IntArray
) {
    init {
        require(width > 0 && height > 0) { "A luma plane needs positive dimensions" }
        require(samples.size == width * height) {
            "Expected ${width * height} samples for ${width}x$height, found ${samples.size}"
        }
    }

    fun at(x: Int, y: Int): Int = samples[y * width + x]

    fun copy(): LumaPlane = LumaPlane(width, height, samples.copyOf())

    /** Deep copy with every sample pushed through `f`, used by the hostile-transform fixtures. */
    fun map(f: (Int) -> Int): LumaPlane {
        val out = IntArray(samples.size)
        for (index in samples.indices) out[index] = f(samples[index]).coerceIn(0, 255)
        return LumaPlane(width, height, out)
    }

    companion object {
        fun filled(width: Int, height: Int, value: Int = 128): LumaPlane =
            LumaPlane(width, height, IntArray(width * height) { value.coerceIn(0, 255) })

        /** True when every sample is a byte, which is the contract the band transform assumes. */
        fun isSane(plane: IntArray): Boolean = plane.none { it < 0 || it > 255 }
    }
}

/** What kind of secret a carrier holds. Transmitted inside the encrypted envelope, never in clear. */
enum class SecretKind(val code: Int, val label: String) {
    MESSAGE(0, "Message"),
    SEED_PHRASE(1, "Seed phrase"),
    KEYFILE(2, "Key material");

    companion object {
        fun fromCode(code: Int): SecretKind = entries.firstOrNull { it.code == code } ?: MESSAGE
    }
}

/**
 * How many key-scheduled envelope blocks the carrier carries. `DENIABLE` is the two-passphrase
 * layout; the code itself never says which block is the decoy, and both blocks are padded to the
 * same length, so the image is byte-for-byte as unremarkable with one secret as with two.
 */
enum class StegoMode(val code: Int) {
    SINGLE(0),
    DENIABLE(1);

    companion object {
        fun fromCode(code: Int): StegoMode = entries.firstOrNull { it.code == code } ?: SINGLE
    }
}

/** The canonical lattice the mark is written on. Bigger side = more capacity, less print margin. */
enum class LatticeSide(val side: Int) {
    SMALL(64),
    MEDIUM(96),
    LARGE(128);

    val code: Int get() = ordinal

    companion object {
        val ordered: List<LatticeSide> = listOf(SMALL, MEDIUM, LARGE)
        fun fromCode(code: Int): LatticeSide? = ordered.getOrNull(code)
        fun of(side: Int): LatticeSide? = ordered.firstOrNull { it.side == side }
    }
}

/** One carrier's worth of coordinates: what the mark believes the photo looked like. */
data class CarrierGeometry(
    val width: Int,
    val height: Int,
    val latticeSide: Int,
    val chipRate: Int
) {
    /** Compact code bound into the envelope's digest, so a cropped or stretched photo is refused. */
    fun codeBytes(): ByteArray = byteArrayOf(
        (width ushr 8).toByte(), width.toByte(),
        (height ushr 8).toByte(), height.toByte(),
        LatticeSide.of(latticeSide)?.code?.toByte() ?: 0,
        chipRate.toByte()
    )
}

/** Everything the encoder hands back: the marked pixels plus the facts the archive must seal. */
data class StegoEncodeResult(
    val marked: LumaPlane,
    val geometry: CarrierGeometry,
    val frameBytes: Int,
    val chipRate: Int,
    val envelopeCount: Int,
    val secretBytes: Int,
    val salt: ByteArray,
    val shareTotal: Int,
    val shareIndex: Int,
    val oneRead: Boolean
) {
    init {
        require(salt.isNotEmpty()) { "A sealed carrier always carries its derivation salt" }
    }
}

/** Why a secret could not be hidden, phrased for the owner rather than for a log. */
class StegoCapacityException(
    message: String,
    val maxSecretBytes: Int,
    val requestedSecretBytes: Int
) : Exception(message)

/** A carrier whose declared size cannot be honoured by the photo handed to it. */
class StegoInputException(message: String) : Exception(message)
