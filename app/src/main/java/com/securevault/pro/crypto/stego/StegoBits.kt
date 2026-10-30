package com.securevault.pro.crypto.stego

/**
 * Bit packing, small checksums and the deterministic slot shuffle.
 *
 * Nothing here is secret: the shuffle seed is a public constant because its only job is to spread
 * consecutive payload bits over the whole lattice so a local scratch or a cloned patch cannot wipe
 * a run of them. The secrecy lives in [StegoCipher] and [StegoKeys].
 */
object StegoBits {

    /** Most-significant bit first, which is the order the frame writer uses everywhere. */
    fun toBits(bytes: ByteArray): IntArray {
        val bits = IntArray(bytes.size * 8)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            for (bit in 0 until 8) bits[index * 8 + bit] = (value ushr (7 - bit)) and 1
        }
        return bits
    }

    /** Reads `bitCount` bits out of a 0/1 array, MSB first, zero-padding a short tail. */
    fun fromBits(bits: IntArray, bitCount: Int = bits.size): ByteArray {
        val whole = (bitCount + 7) / 8
        val out = ByteArray(whole)
        for (index in 0 until bitCount) {
            if (index / 8 >= whole) break
            val value = bits[index] and 1
            out[index / 8] = ((out[index / 8].toInt() or (value shl (7 - index % 8))) ).toByte()
        }
        return out
    }

    fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    /** CRC-8 (poly 0x07) over the directory, so a half-corrupted acquisition never steers a decode. */
    fun crc8(data: ByteArray, length: Int = data.size): Int {
        var crc = 0
        for (index in 0 until length) {
            crc = crc xor (data[index].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc and 0xFF
    }

    /** CRC-16/CCITT (poly 0x1021, init 0xFFFF) over the frame meta. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (index in 0 until length) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Hamming distance between two byte arrays of equal meaning; short arrays count as maximal. */
    fun distance(a: ByteArray, b: ByteArray): Int {
        if (a.size != b.size) return Int.MAX_VALUE / 2
        var total = 0
        for (index in a.indices) {
            total += Integer.bitCount((a[index].toInt() xor b[index].toInt()) and 0xFF)
        }
        return total
    }
}

/**
 * Small deterministic generator (xorshift32). Used only for shuffles and for the dispersion
 * filler, never as a key stream — real randomness comes from [java.security.SecureRandom].
 */
class DeterministicRandom(seed: Int) {
    private var state = if (seed == 0) 0x9E3779B9.toInt() else seed

    fun nextInt(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }

    /** Uniform in `0 until bound`, sign-safe. */
    fun nextIndex(bound: Int): Int {
        if (bound <= 1) return 0
        return ((nextInt().toLong() and 0x7FFF_FFFFL) % bound).toInt()
    }

    fun nextBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        for (index in 0 until count) out[index] = nextInt().toByte()
        return out
    }
}

/** The public chip shuffle: consecutive payload bits land far apart on the lattice. */
object SlotShuffle {
    private const val SEED = 0x356E31A
    private val cache = HashMap<Int, IntArray>()

    @Synchronized
    fun of(size: Int): IntArray = cache.getOrPut(size) {
        val order = IntArray(size) { it }
        val random = DeterministicRandom(SEED xor size)
        for (index in size - 1 downTo 1) {
            val swap = random.nextIndex(index + 1)
            val held = order[index]
            order[index] = order[swap]
            order[swap] = held
        }
        order
    }
}
