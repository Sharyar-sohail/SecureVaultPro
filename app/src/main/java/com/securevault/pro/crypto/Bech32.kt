package com.securevault.pro.crypto

object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3
    private val GENERATOR = intArrayOf(
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    )

    /** Native SegWit v0 address (P2WPKH) for a 20-byte public-key hash. */
    fun segwitV0(hrp: String, hash160: ByteArray): String {
        val data = intArrayOf(0) + convertBits(hash160, 8, 5)
        return encode(hrp, data)
    }

    fun encode(hrp: String, data: IntArray): String {
        val checksum = createChecksum(hrp, data)
        val builder = StringBuilder(hrp).append('1')
        for (value in data) builder.append(CHARSET[value])
        for (value in checksum) builder.append(CHARSET[value])
        return builder.toString()
    }

    /** Decodes a SegWit v0 address and returns the witness program bytes. */
    fun decodeSegwit(address: String, expectedHrp: String = "bc"): ByteArray? {
        val decoded = decodeWitness(address, expectedHrp) ?: return null
        return if (decoded.first == 0) decoded.second else null
    }

    /**
     * Decodes any SegWit address (bech32 for v0, bech32m for v1+), returning the
     * witness version and program. Taproot recipients use this path.
     */
    fun decodeWitness(address: String, expectedHrp: String = "bc"): Pair<Int, ByteArray>? {
        val lower = address.trim().lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 7 > lower.length) return null
        val hrp = lower.substring(0, separator)
        if (!hrp.equals(expectedHrp, ignoreCase = true)) return null
        val dataPart = lower.substring(separator + 1)
        val data = IntArray(dataPart.length)
        for (index in dataPart.indices) {
            val value = CHARSET.indexOf(dataPart[index])
            if (value < 0) return null
            data[index] = value
        }
        val checksum = polymod(hrpExpand(hrp) + data)
        val payload = data.copyOfRange(0, data.size - 6)
        if (payload.isEmpty()) return null
        val version = payload[0]
        if (version > 16) return null
        val expected = if (version == 0) 1 else BECH32M_CONST
        if (checksum != expected) return null
        val program = convertBits(payload.copyOfRange(1, payload.size), 5, 8)
            ?: return null
        if (program.size !in 2..40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null
        return version to program
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int): ByteArray? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl toBits) - 1
        val out = ArrayList<Byte>(data.size * fromBits / toBits + 1)
        for (value in data) {
            if (value < 0 || value ushr fromBits != 0) return null
            accumulator = ((accumulator shl fromBits) or value) and 0xFFFF
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out += ((accumulator ushr bits) and maxValue).toByte()
            }
        }
        if (bits >= fromBits || ((accumulator shl (toBits - bits)) and maxValue) != 0) return null
        return out.toByteArray()
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val polymod = polymod(values) xor 1
        return IntArray(6) { index -> (polymod ushr (5 * (5 - index))) and 31 }
    }

    private fun hrpExpand(hrp: String): IntArray {
        val high = IntArray(hrp.length) { (hrp[it].code ushr 5) and 31 }
        val low = IntArray(hrp.length) { hrp[it].code and 31 }
        return high + intArrayOf(0) + low
    }

    private fun polymod(values: IntArray): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) {
                if (((top ushr i) and 1) == 1) checksum = checksum xor GENERATOR[i]
            }
        }
        return checksum
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int): IntArray {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        val out = ArrayList<Int>(data.size * 8 / toBits + 1)
        for (byte in data) {
            accumulator = ((accumulator shl fromBits) or (byte.toInt() and 0xFF)) and maxAccumulator
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out += (accumulator ushr bits) and maxValue
            }
        }
        if (bits > 0) out += (accumulator shl (toBits - bits)) and maxValue
        return out.toIntArray()
    }
}
