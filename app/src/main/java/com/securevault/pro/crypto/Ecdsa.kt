package com.securevault.pro.crypto

import java.math.BigInteger

/**
 * Deterministic ECDSA over secp256k1 (RFC 6979) with canonical low-S signatures.
 * Used only inside the crypto wallet transfer pipeline; keys never leave the device.
 */
object Ecdsa {

    data class Signature(val r: BigInteger, val s: BigInteger, val recoveryId: Int) {
        fun compact(): ByteArray {
            val out = ByteArray(65)
            Secp256k1.copyFixed(r, out, 0)
            Secp256k1.copyFixed(s, out, 32)
            out[64] = recoveryId.toByte()
            return out
        }

        /** DER encoding used by Bitcoin witness signatures. */
        fun der(): ByteArray {
            // BigInteger.toByteArray() already emits the minimal signed form,
            // including the mandatory 0x00 pad when the high bit is set.
            val rBytes = r.toByteArray()
            val sBytes = s.toByteArray()
            val size = 6 + rBytes.size + sBytes.size
            val out = ByteArray(size)
            var index = 0
            out[index++] = 0x30
            out[index++] = (size - 2).toByte()
            out[index++] = 0x02
            out[index++] = rBytes.size.toByte()
            System.arraycopy(rBytes, 0, out, index, rBytes.size)
            index += rBytes.size
            out[index++] = 0x02
            out[index++] = sBytes.size.toByte()
            System.arraycopy(sBytes, 0, out, index, sBytes.size)
            return out
        }
    }

    private val HALF_N = Secp256k1.N.shiftRight(1)
    private val ONE = BigInteger.ONE

    /**
     * @param hash 32-byte digest
     * @param privateKey scalar in [1, n-1]
     */
    fun sign(hash: ByteArray, privateKey: BigInteger): Signature {
        require(hash.size == 32) { "ECDSA expects a 32-byte digest" }
        require(Secp256k1.isValidPrivateKey(privateKey)) { "Invalid private key" }
        val z = BigInteger(1, hash)
        val zMod = z.mod(Secp256k1.N)
        val kStream = rfc6979(privateKey, hash)
        repeat(1024) {
            val k = kStream()
            if (k.signum() == 0 || k >= Secp256k1.N) return@repeat
            val point = Secp256k1.multiply(k)
            if (point.x.signum() == 0) return@repeat
            val r = point.x.mod(Secp256k1.N)
            if (r.signum() == 0) return@repeat
            val s = k.modInverse(Secp256k1.N)
                .multiply(zMod.add(r.multiply(privateKey)))
                .mod(Secp256k1.N)
            if (s.signum() == 0) return@repeat
            var recovery = (point.y.testBit(0).let { if (it) 1 else 0 }) or
                (if (point.x >= Secp256k1.N) 2 else 0)
            var canonical = s
            if (s > HALF_N) {
                canonical = Secp256k1.N.subtract(s)
                recovery = recovery xor 1
            }
            return Signature(r, canonical, recovery)
        }
        error("Unable to produce a deterministic signature")
    }

    fun verify(hash: ByteArray, signature: Signature, publicKey: Secp256k1.Point): Boolean {
        val z = BigInteger(1, hash)
        val w = signature.s.modInverse(Secp256k1.N)
        val u1 = z.multiply(w).mod(Secp256k1.N)
        val u2 = signature.r.multiply(w).mod(Secp256k1.N)
        val point = Secp256k1.add(Secp256k1.multiply(u1), Secp256k1.multiply(u2, publicKey))
        if (point.x.signum() == 0 && point.y.signum() == 0) return false
        return point.x.mod(Secp256k1.N) == signature.r
    }

    /** RFC 6979 deterministic nonce generator (HMAC-SHA256). */
    private fun rfc6979(privateKey: BigInteger, hash: ByteArray): () -> BigInteger {
        val x = Secp256k1.toFixed32(privateKey)
        val h1 = Secp256k1.toFixed32(BigInteger(1, hash).mod(Secp256k1.N))
        var k = ByteArray(32)
        var v = ByteArray(32) { 0x01 }
        k = Hash.hmacSha256(k, v + byteArrayOf(0x00) + x + h1)
        v = Hash.hmacSha256(k, v)
        k = Hash.hmacSha256(k, v + byteArrayOf(0x01) + x + h1)
        v = Hash.hmacSha256(k, v)
        return {
            v = Hash.hmacSha256(k, v)
            val candidate = BigInteger(1, v)
            if (candidate.signum() == 0 || candidate >= Secp256k1.N) {
                k = Hash.hmacSha256(k, v + byteArrayOf(0x00))
                v = Hash.hmacSha256(k, v)
                v = Hash.hmacSha256(k, v)
                BigInteger(1, v)
            } else {
                candidate
            }
        }
    }

    /** Recover a public key from a signature; also used by unit tests. */
    fun recover(hash: ByteArray, signature: Signature): Secp256k1.Point? {
        val n = Secp256k1.N
        val p = Secp256k1.P
        val alpha = signature.r
        val beta = signature.s
        if (alpha.signum() <= 0 || alpha >= n || beta.signum() <= 0 || beta >= n) return null
        val x = if (signature.recoveryId and 2 != 0) alpha.add(n) else alpha
        if (x >= p) return null
        val ySquared = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        var y = ySquared.modPow(p.add(ONE).shiftRight(2), p)
        if (y.multiply(y).mod(p) != ySquared) return null
        if (y.testBit(0) != (signature.recoveryId and 1 == 1)) y = p.subtract(y)
        val point = Secp256k1.Point(x, y)
        val z = BigInteger(1, hash).mod(n)
        val rInv = alpha.modInverse(n)
        val sR = Secp256k1.multiply(beta.multiply(rInv).mod(n), point)
        val minusZ = n.subtract(z).multiply(rInv).mod(n)
        return Secp256k1.add(sR, Secp256k1.multiply(minusZ))
    }

    fun toLowS(signature: Signature): Signature =
        if (signature.s > HALF_N) {
            signature.copy(s = Secp256k1.N.subtract(signature.s), recoveryId = signature.recoveryId xor 1)
        } else {
            signature
        }
}
