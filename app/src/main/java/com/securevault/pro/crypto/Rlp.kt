package com.securevault.pro.crypto

import java.math.BigInteger

/** Minimal RLP encoder for EIP-155 legacy transactions. */
object Rlp {

    fun encode(value: Any?): ByteArray = when (value) {
        null -> encodeBytes(ByteArray(0))
        is ByteArray -> encodeBytes(value)
        is BigInteger -> encodeBytes(minimalBytes(value))
        is Int -> encodeBytes(minimalBytes(BigInteger.valueOf(value.toLong())))
        is Long -> encodeBytes(minimalBytes(BigInteger.valueOf(value)))
        is List<*> -> encodeList(value.map { encode(it) })
        else -> encodeBytes(value.toString().toByteArray(Charsets.UTF_8))
    }

    fun minimalBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return ByteArray(0)
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    }

    private fun encodeBytes(bytes: ByteArray): ByteArray {
        return if (bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80) {
            bytes
        } else {
            prefix(bytes.size, 0x80) + bytes
        }
    }

    private fun encodeList(items: List<ByteArray>): ByteArray {
        val payload = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return prefix(payload.size, 0xC0) + payload
    }

    private fun prefix(length: Int, offset: Int): ByteArray {
        return when {
            length <= 55 -> byteArrayOf((offset + length).toByte())
            else -> {
                val lengthBytes = minimalBytes(BigInteger.valueOf(length.toLong()))
                byteArrayOf((offset + 55 + lengthBytes.size).toByte()) + lengthBytes
            }
        }
    }
}
