package com.securevault.pro.crypto

import java.math.BigInteger

object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        var value = BigInteger(1, input)
        val base = BigInteger.valueOf(58)
        val builder = StringBuilder()
        while (value.signum() > 0) {
            val division = value.divideAndRemainder(base)
            builder.append(ALPHABET[division[1].toInt()])
            value = division[0]
        }
        repeat(zeros) { builder.append('1') }
        return builder.reverse().toString()
    }

    fun encodeCheck(payload: ByteArray): String {
        val checksum = Hash.doubleSha256(payload).copyOfRange(0, 4)
        return encode(payload + checksum)
    }

    fun decode(input: String): ByteArray? {
        if (input.isEmpty()) return null
        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (char in input) {
            val index = ALPHABET.indexOf(char)
            if (index < 0) return null
            value = value.multiply(base).add(BigInteger.valueOf(index.toLong()))
        }
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        val raw = if (value.signum() == 0) ByteArray(0) else value.toByteArray().let {
            if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        return ByteArray(zeros) + raw
    }

    /** Returns the payload without its 4-byte checksum, or null when invalid. */
    fun decodeCheck(input: String): ByteArray? {
        val decoded = decode(input) ?: return null
        if (decoded.size < 5) return null
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expected = Hash.doubleSha256(payload).copyOfRange(0, 4)
        return if (checksum.contentEquals(expected)) payload else null
    }
}
