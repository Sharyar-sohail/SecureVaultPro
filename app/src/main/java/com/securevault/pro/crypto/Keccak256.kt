package com.securevault.pro.crypto

/**
 * Original Keccak-256 (padding 0x01), as used by Ethereum/BSC/Polygon/Tron.
 * This is not NIST SHA3-256 (padding 0x06).
 */
object Keccak256 {

    private const val RATE = 136

    private val ROUND_CONSTANTS = longArrayOf(
        1L, 32898L, -9223372036854742902L, -9223372034707259392L,
        32907L, 2147483649L, -9223372034707259263L, -9223372036854743031L,
        138L, 136L, 2147516425L, 2147483658L,
        2147516555L, -9223372036854775669L, -9223372036854742903L, -9223372036854743037L,
        -9223372036854743038L, -9223372036854775680L, 32778L, -9223372034707292150L,
        -9223372034707259263L, -9223372036854742912L, 2147483649L, -9223372034707259384L
    )

    private val ROTATION = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
        27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    )

    private val PI_LANES = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
        15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    )

    fun hash(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        while (offset + RATE <= input.size) {
            absorb(state, input, offset)
            permute(state)
            offset += RATE
        }
        val last = ByteArray(RATE)
        val remainder = input.size - offset
        System.arraycopy(input, offset, last, 0, remainder)
        last[remainder] = 0x01
        last[RATE - 1] = (last[RATE - 1].toInt() or 0x80).toByte()
        absorb(state, last, 0)
        permute(state)

        val out = ByteArray(32)
        for (i in 0 until 4) {
            var lane = state[i]
            for (j in 0 until 8) {
                out[i * 8 + j] = (lane and 0xFF).toByte()
                lane = lane ushr 8
            }
        }
        return out
    }

    private fun absorb(state: LongArray, block: ByteArray, offset: Int) {
        for (i in 0 until RATE / 8) {
            var lane = 0L
            for (j in 0 until 8) {
                lane = lane or ((block[offset + i * 8 + j].toLong() and 0xFFL) shl (8 * j))
            }
            state[i] = state[i] xor lane
        }
    }

    private fun permute(state: LongArray) {
        val column = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) {
                column[i] = state[i] xor state[i + 5] xor state[i + 10] xor state[i + 15] xor state[i + 20]
            }
            for (i in 0 until 5) {
                val delta = column[(i + 4) % 5] xor java.lang.Long.rotateLeft(column[(i + 1) % 5], 1)
                for (j in 0 until 25 step 5) {
                    state[j + i] = state[j + i] xor delta
                }
            }

            var carry = state[1]
            for (i in 0 until 24) {
                val lane = PI_LANES[i]
                val next = state[lane]
                state[lane] = java.lang.Long.rotateLeft(carry, ROTATION[i])
                carry = next
            }

            for (j in 0 until 25 step 5) {
                for (i in 0 until 5) column[i] = state[j + i]
                for (i in 0 until 5) {
                    state[j + i] = column[i] xor (column[(i + 1) % 5].inv() and column[(i + 2) % 5])
                }
            }

            state[0] = state[0] xor ROUND_CONSTANTS[round]
        }
    }
}
