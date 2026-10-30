package com.securevault.pro.crypto

import java.math.BigInteger

data class ExtendedPrivateKey(
    val key: BigInteger,
    val chainCode: ByteArray
) {
    val publicPoint: Secp256k1.Point get() = Secp256k1.multiply(key)
    val publicKey: ByteArray get() = Secp256k1.compress(publicPoint)
}

object Bip32 {

    const val HARDENED = 0x80000000.toInt()

    fun master(seed: ByteArray): ExtendedPrivateKey {
        val i = Hash.hmacSha512("Bitcoin seed".toByteArray(Charsets.US_ASCII), seed)
        return ExtendedPrivateKey(
            key = BigInteger(1, i.copyOfRange(0, 32)),
            chainCode = i.copyOfRange(32, 64)
        )
    }

    fun derivePath(seed: ByteArray, path: String): ExtendedPrivateKey {
        var current = master(seed)
        val segments = path.trim().split('/')
        for (segment in segments) {
            if (segment.isBlank() || segment.equals("m", ignoreCase = true)) continue
            val hardened = segment.endsWith("'") || segment.endsWith("h", ignoreCase = true)
            val number = segment.trimEnd('\'', 'h', 'H').toIntOrNull()
                ?: throw IllegalArgumentException("Invalid derivation path")
            val index = if (hardened) number + HARDENED else number
            current = deriveChild(current, index)
        }
        return current
    }

    fun deriveChild(parent: ExtendedPrivateKey, index: Int): ExtendedPrivateKey {
        val data = ByteArray(37)
        if (index and HARDENED != 0) {
            data[0] = 0
            Secp256k1.copyFixed(parent.key, data, 1)
        } else {
            System.arraycopy(parent.publicKey, 0, data, 0, 33)
        }
        data[33] = ((index ushr 24) and 0xFF).toByte()
        data[34] = ((index ushr 16) and 0xFF).toByte()
        data[35] = ((index ushr 8) and 0xFF).toByte()
        data[36] = (index and 0xFF).toByte()
        val i = Hash.hmacSha512(parent.chainCode, data)
        val tweak = BigInteger(1, i.copyOfRange(0, 32))
        require(tweak < Secp256k1.N) { "Invalid BIP32 tweak" }
        val childKey = tweak.add(parent.key).mod(Secp256k1.N)
        require(childKey.signum() != 0) { "Invalid BIP32 child key" }
        return ExtendedPrivateKey(childKey, i.copyOfRange(32, 64))
    }
}
