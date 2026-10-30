package com.securevault.pro.crypto.stego

/**
 * The on-carrier wire format: a 7-byte ring directory, a 22-byte frame header, then one opaque
 * envelope block per passphrase.
 *
 * Nothing in the clear names a decoy. The directory only says how densely the bits are spread and
 * how long the frame is — the same numbers any ordinary watermark would carry — and each envelope
 * block is AES-256-GCM ciphertext with a key derived from the passphrase that opens it. Both blocks
 * are padded to the same length and placed in slots chosen by [StegoKeySchedule], so with two
 * passphrases hidden in one photo the image is one byte stream longer and nothing else.
 */
object StegoFrame {

    const val VERSION = 1
    const val DIRECTORY_BYTES = 7
    const val META_BYTES = StegoLimits.META_BYTES

    /** Everything the reader needs before it has any key at all. */
    data class Directory(
        val mode: StegoMode,
        val shareTotal: Int,
        val shareIndex: Int,
        val chipRate: Int,
        val lattice: LatticeSide,
        val frameBits: Int,
        val oneRead: Boolean
    )

    /** The frame header: carrier geometry, the per-carrier salt and the envelope count. */
    data class Meta(
        val lattice: LatticeSide,
        val mode: StegoMode,
        val blockCount: Int,
        val bandCellsWide: Int,
        val bandCellsHigh: Int,
        val planeWidth: Int,
        val planeHeight: Int,
        val salt: ByteArray,
        val shareTotal: Int,
        val shareIndex: Int,
        val oneRead: Boolean
    ) {
        /** The bytes bound into every envelope's digest and additional-authenticated-data. */
        fun geometryCode(): ByteArray = byteArrayOf(
            (bandCellsWide ushr 8).toByte(), bandCellsWide.toByte(),
            (bandCellsHigh ushr 8).toByte(), bandCellsHigh.toByte(),
            (planeWidth ushr 8).toByte(), planeWidth.toByte(),
            (planeHeight ushr 8).toByte(), planeHeight.toByte(),
            lattice.code.toByte(),
            (shareTotal shl 3 or shareIndex).toByte(),
            if (oneRead) 1 else 0
        )

        override fun equals(other: Any?): Boolean = other is Meta && packed().contentEquals(other.packed())
        override fun hashCode(): Int = packed().contentHashCode()

        fun packed(): ByteArray = pack(this)
    }

    // ---- directory ---------------------------------------------------------------------------

    fun packDirectory(directory: Directory): ByteArray {
        val out = ByteArray(DIRECTORY_BYTES)
        out[0] = (StegoLimits.DIRECTORY_MAGIC ushr 8).toByte()
        out[1] = (StegoLimits.DIRECTORY_MAGIC and 0xFF).toByte()
        out[2] = (
            (directory.mode.code shl 6) or
                ((directory.shareTotal - 1).coerceIn(0, 3) shl 4) or
                (directory.shareIndex.coerceIn(0, 7) shl 1) or
                (if (directory.oneRead) 1 else 0)
            ).toByte()
        out[3] = (((directory.chipRate - 1).coerceIn(0, 63) shl 2) or directory.lattice.code).toByte()
        out[4] = (directory.frameBits ushr 6).toByte()
        out[5] = (directory.frameBits and 0x3F).toByte()
        out[6] = StegoBits.crc8(out, 6).toByte()
        return out
    }

    /** Null for anything that is not a carrier of this app, which is the whole point of C. */
    fun unpackDirectory(bytes: ByteArray): Directory? {
        if (bytes.size < DIRECTORY_BYTES) return null
        if (StegoBits.crc8(bytes, 6) != (bytes[6].toInt() and 0xFF)) return null
        val magic = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (magic != StegoLimits.DIRECTORY_MAGIC) return null
        val mode = StegoMode.fromCode((bytes[2].toInt() ushr 6) and 0x3)
        val shareTotal = ((bytes[2].toInt() ushr 4) and 0x3) + 1
        val shareIndex = (bytes[2].toInt() ushr 1) and 0x7
        val oneRead = (bytes[2].toInt() and 1) == 1
        val chipRate = ((bytes[3].toInt() ushr 2) and 0x3F) + 1
        val lattice = LatticeSide.fromCode(bytes[3].toInt() and 0x3) ?: return null
        val frameBits = (((bytes[4].toInt() and 0xFF) shl 6) or (bytes[5].toInt() and 0x3F))
        if (frameBits <= 0 || frameBits > StegoLimits.MAX_FRAME_BITS) return null
        return Directory(mode, shareTotal, shareIndex, chipRate, lattice, frameBits, oneRead)
    }

    // ---- frame header ------------------------------------------------------------------------

    fun pack(meta: Meta): ByteArray {
        val out = ByteArray(META_BYTES)
        out[0] = VERSION.toByte()
        out[1] = meta.lattice.code.toByte()
        out[2] = (
            (meta.mode.code shl 6) or
                ((meta.shareTotal - 1).coerceIn(0, 3) shl 4) or
                (meta.shareIndex.coerceIn(0, 7) shl 1) or
                (if (meta.oneRead) 1 else 0)
            ).toByte()
        out[3] = meta.blockCount.toByte()
        StegoBits.writeUInt16(out, 4, meta.bandCellsWide)
        StegoBits.writeUInt16(out, 6, meta.bandCellsHigh)
        StegoBits.writeUInt16(out, 8, meta.planeWidth)
        StegoBits.writeUInt16(out, 10, meta.planeHeight)
        meta.salt.copyInto(out, 12, 0, minOf(meta.salt.size, 8))
        StegoBits.writeUInt16(out, 20, StegoBits.crc16(out, 20))
        return out
    }

    fun parse(bytes: ByteArray): Meta? {
        if (bytes.size < META_BYTES) return null
        if (bytes[0].toInt() != VERSION) return null
        if (StegoBits.crc16(bytes, 20) != StegoBits.readUInt16(bytes, 20)) return null
        val lattice = LatticeSide.fromCode(bytes[1].toInt() and 0x3) ?: return null
        val flags = bytes[2].toInt() and 0xFF
        val blockCount = bytes[3].toInt() and 0xFF
        if (blockCount !in 1..4) return null
        return Meta(
            lattice = lattice,
            mode = StegoMode.fromCode((flags ushr 6) and 0x3),
            blockCount = blockCount,
            bandCellsWide = StegoBits.readUInt16(bytes, 4),
            bandCellsHigh = StegoBits.readUInt16(bytes, 6),
            planeWidth = StegoBits.readUInt16(bytes, 8),
            planeHeight = StegoBits.readUInt16(bytes, 10),
            salt = bytes.copyOfRange(12, 20),
            shareTotal = ((flags ushr 4) and 0x3) + 1,
            shareIndex = (flags ushr 1) and 0x7,
            oneRead = (flags and 1) == 1
        )
    }

    // ---- envelope blocks ---------------------------------------------------------------------

    /**
     * Seals one secret into a block. `paddedBytes` pads every block of a carrier to the same length,
     * so an observer cannot rank the envelopes by size; the real length travels inside the ciphertext.
     */
    fun sealEnvelope(
        key: ByteArray,
        iv: ByteArray,
        meta: Meta,
        blockIndex: Int,
        kind: SecretKind,
        secret: ByteArray,
        fingerprint: ByteArray,
        paddedBytes: Int,
        deriver: StegoKeyDeriver
    ): ByteArray {
        require(secret.size <= paddedBytes) { "A padded envelope cannot be smaller than its secret" }
        val body = ByteArray(1 + 2 + StegoLimits.FINGERPRINT_BYTES + paddedBytes)
        var offset = 0
        body[offset++] = kind.code.toByte()
        StegoBits.writeUInt16(body, offset, secret.size); offset += 2
        fingerprint.copyInto(body, offset); offset += StegoLimits.FINGERPRINT_BYTES
        secret.copyInto(body, offset)
        // Whatever fills the tail is random and inside the ciphertext, so it leaks nothing. It must
        // start *after* the secret: writing it at `offset` overwrote the first bytes of every
        // envelope shorter than the carrier's padded length, so a two-envelope carrier returned a
        // mangled secret for whichever envelope was not the longest.
        val filler = DeterministicRandom(
            (blockIndex + 1) * 0x9E37 xor (StegoBits.crc16(secret) + StegoBits.crc16(meta.salt))
        )
        filler.nextBytes(paddedBytes - secret.size).copyInto(body, offset + secret.size)
        val digest = StegoCipher.digest(meta.salt + meta.geometryCode() + body)
            .copyOfRange(0, StegoLimits.DIGEST_BYTES)
        val plaintext = digest + body
        val aad = pack(meta) + byteArrayOf(blockIndex.toByte())
        return StegoCipher.seal(key, iv, plaintext, aad)
    }

    /**
     * Opens one block, returning what it protects or null. Null is the answer to a wrong
     * passphrase, to a block that did not survive, and to a carrier whose geometry no longer matches
     * the digest — three cases the caller reports differently only because the owner asked to be
     * told *why* a secret refused to come out.
     */
    data class Opened(val kind: SecretKind, val secret: ByteArray, val fingerprint: ByteArray)

    fun openEnvelope(
        key: ByteArray,
        blob: ByteArray,
        meta: Meta,
        blockIndex: Int
    ): Opened? {
        val aad = pack(meta) + byteArrayOf(blockIndex.toByte())
        val plaintext = StegoCipher.open(key, blob, aad) ?: return null
        if (plaintext.size < StegoLimits.DIGEST_BYTES + 3 + StegoLimits.FINGERPRINT_BYTES) return null
        val digest = plaintext.copyOfRange(0, StegoLimits.DIGEST_BYTES)
        val body = plaintext.copyOfRange(StegoLimits.DIGEST_BYTES, plaintext.size)
        val expected = StegoCipher.digest(meta.salt + meta.geometryCode() + body)
            .copyOfRange(0, StegoLimits.DIGEST_BYTES)
        if (!StegoCipher.matches(digest, expected)) return null
        val kind = SecretKind.fromCode(body[0].toInt() and 0xFF)
        val secretLength = StegoBits.readUInt16(body, 1)
        if (secretLength < 0) return null
        val start = 3 + StegoLimits.FINGERPRINT_BYTES
        if (secretLength > body.size - start) return null
        return Opened(
            kind = kind,
            secret = body.copyOfRange(start, start + secretLength),
            fingerprint = body.copyOfRange(3, 3 + StegoLimits.FINGERPRINT_BYTES)
        )
    }

    // ---- frame assembly ----------------------------------------------------------------------

    /** meta || blockCount || [len || blob]..., the exact byte stream the error code then protects. */
    fun buildPayload(meta: Meta, blocks: List<ByteArray>): ByteArray {
        val size = META_BYTES + 1 + blocks.sumOf { it.size + 2 }
        val out = ByteArray(size)
        var offset = 0
        pack(meta).copyInto(out, offset); offset += META_BYTES
        out[offset++] = blocks.size.toByte()
        blocks.forEach { blob ->
            StegoBits.writeUInt16(out, offset, blob.size); offset += 2
            blob.copyInto(out, offset); offset += blob.size
        }
        return out
    }

    data class Payload(val meta: Meta, val blocks: List<ByteArray>)

    /** Reads back [buildPayload]; trailing zero padding of the last RS block is ignored. */
    fun parsePayload(bytes: ByteArray): Payload? {
        val meta = parse(bytes) ?: return null
        if (bytes.size <= META_BYTES) return null
        val blockCount = bytes[META_BYTES].toInt() and 0xFF
        if (blockCount != meta.blockCount) return null
        var offset = META_BYTES + 1
        val blocks = ArrayList<ByteArray>(blockCount)
        for (index in 0 until blockCount) {
            if (offset + 2 > bytes.size) return null
            val length = StegoBits.readUInt16(bytes, offset)
            offset += 2
            if (length <= 0 || offset + length > bytes.size) return null
            blocks.add(bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return Payload(meta, blocks)
    }
}
