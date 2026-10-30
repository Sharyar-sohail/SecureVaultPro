package com.securevault.pro.crypto.stego

/** Why a carrier refused to give up its secret, phrased so the owner is told the actual reason. */
enum class RefusalReason(val explanation: String) {
    CARRIER_EDITED(
        "The photo's luminance structure no longer matches the seal: something repainted, " +
            "cloned or filled part of it."
    ),
    GEOMETRY_MOVED(
        "The carrier's proportions differ from the sealed ones, so the picture was cropped or " +
            "stretched."
    ),
    FRAME_LOST(
        "Too many mark cells were lost to rebuild the frame. Move closer and fill the frame."
    ),
    FLAT_CARRIER(
        "This image has no luminance detail to carry a mark — a flat field, a blank page or a " +
            "heavily crushed shadow."
    )
}

/**
 * Everything the reader can answer about a photo, from "nothing is hidden here" to "here is your
 * secret" and the awkward middle cases in between.
 */
sealed class StegoRead {

    /** No directory, no sync, no carrier: an ordinary photograph, or a QR code, or a screenshot. */
    object NoCarrier : StegoRead()

    /** The directory matched but the error code could not rebuild the frame: too damaged to read. */
    data class Damaged(
        val lattice: LatticeSide,
        val chipRate: Int,
        val shareTotal: Int,
        val shareIndex: Int
    ) : StegoRead()

    /** A carrier was found and read, but none of the offered passphrases open it. */
    data class Locked(
        val meta: StegoFrame.Meta,
        val frameBytes: ByteArray,
        val fingerprintDrift: Int
    ) : StegoRead()

    /**
     * A dispersal share recovered cleanly. The secret itself is not knowable until every share of
     * the set is back; see [SecretShares] for why two of three reveal nothing at all.
     */
    data class ShareRecovered(
        val meta: StegoFrame.Meta,
        val shareBytes: ByteArray,
        val shareTotal: Int,
        val shareIndex: Int
    ) : StegoRead()

    /** The carrier authenticates as untouched and the frame no longer matches its own digest. */
    data class Refused(val meta: StegoFrame.Meta, val reason: RefusalReason, val drift: Int) : StegoRead()

    /** The secret, plus the honest measurement of how far the carrier drifted from its seal. */
    data class Revealed(
        val meta: StegoFrame.Meta,
        val kind: SecretKind,
        val secret: ByteArray,
        val drift: Int,
        val oneRead: Boolean,
        val openedWithKey: Int
    ) : StegoRead()
}

/** One secret to hide, with the passphrase that will open it (null = readable by the app alone). */
data class StegoEnvelopeRequest(
    val secret: ByteArray,
    val kind: SecretKind,
    val passphrase: CharArray?
) {
    val secretBytes: Int get() = secret.size

    init {
        require(secret.isNotEmpty()) { "There is no point hiding nothing" }
    }
}

/** A whole encode job: the photo, the secrets, and how the set is dispersed. */
data class StegoEncodeRequest(
    val carrier: LumaPlane,
    val envelopes: List<StegoEnvelopeRequest>,
    val shareTotal: Int = 1,
    val shareIndex: Int = 0,
    val oneRead: Boolean = false,
    val salt: ByteArray = StegoKeySchedule.salt()
) {
    init {
        require(envelopes.isNotEmpty()) { "At least one envelope is needed" }
        require(envelopes.size <= 4) { "A carrier holds at most four envelopes" }
        require(shareTotal in 1..4) { "Dispersal supports up to four carriers" }
        require(shareIndex in 0 until shareTotal) { "Share index must sit inside the set" }
        require(salt.size == StegoLimits.SALT_BYTES) { "The carrier salt must be 8 bytes" }
    }

    val deniable: Boolean get() = envelopes.size > 1
    val mode: StegoMode get() = if (deniable) StegoMode.DENIABLE else StegoMode.SINGLE
}

/** What a decode job needs: the pixels, and whichever passphrases the owner is willing to type. */
data class StegoDecodeRequest(
    val carrier: LumaPlane,
    val passphrases: List<CharArray?> = emptyList()
)
