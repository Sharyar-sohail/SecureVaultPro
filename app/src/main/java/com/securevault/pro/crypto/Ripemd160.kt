package com.securevault.pro.crypto

/** Pure-Kotlin RIPEMD-160 so address derivation does not depend on provider availability. */
object Ripemd160 {

    private val RL = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
    )
    private val RR = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
    )
    private val SL = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
    )
    private val SR = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
    )
    private val KL = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
    private val KR = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

    fun hash(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8L
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        System.arraycopy(message, 0, padded, 0, message.size)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val x = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                x[i] = (padded[base].toInt() and 0xFF) or
                    ((padded[base + 1].toInt() and 0xFF) shl 8) or
                    ((padded[base + 2].toInt() and 0xFF) shl 16) or
                    ((padded[base + 3].toInt() and 0xFF) shl 24)
            }

            var al = h0
            var bl = h1
            var cl = h2
            var dl = h3
            var el = h4
            var ar = h0
            var br = h1
            var cr = h2
            var dr = h3
            var er = h4

            for (j in 0 until 80) {
                val round = j / 16
                var t = add(al, f(round, bl, cl, dl), x[RL[j]], KL[round])
                t = Integer.rotateLeft(t, SL[j])
                t = add(t, el)
                al = el
                el = dl
                dl = Integer.rotateLeft(cl, 10)
                cl = bl
                bl = t

                var t2 = add(ar, f(4 - round, br, cr, dr), x[RR[j]], KR[round])
                t2 = Integer.rotateLeft(t2, SR[j])
                t2 = add(t2, er)
                ar = er
                er = dr
                dr = Integer.rotateLeft(cr, 10)
                cr = br
                br = t2
            }

            val nextH0 = add(h1, cl, dr)
            val nextH1 = add(h2, dl, er)
            val nextH2 = add(h3, el, ar)
            val nextH3 = add(h4, al, br)
            val nextH4 = add(h0, bl, cr)
            h0 = nextH0
            h1 = nextH1
            h2 = nextH2
            h3 = nextH3
            h4 = nextH4
            offset += 64
        }

        val out = ByteArray(20)
        writeLe(out, 0, h0)
        writeLe(out, 4, h1)
        writeLe(out, 8, h2)
        writeLe(out, 12, h3)
        writeLe(out, 16, h4)
        return out
    }

    private fun f(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        3 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }

    private fun add(vararg values: Int): Int {
        var sum = 0L
        for (value in values) sum += value.toLong() and 0xFFFFFFFFL
        return sum.toInt()
    }

    private fun writeLe(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
