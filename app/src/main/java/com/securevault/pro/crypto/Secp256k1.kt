package com.securevault.pro.crypto

import java.math.BigInteger

object Secp256k1 {

    val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val HALF_P_PLUS_ONE = P.add(ONE).shiftRight(2)

    data class Point(val x: BigInteger, val y: BigInteger)

    val G: Point = Point(GX, GY)
    val INFINITY: Point = Point(ZERO, ZERO)

    fun isValidPrivateKey(value: BigInteger): Boolean = value.signum() > 0 && value < N

    fun multiply(privateKey: BigInteger, base: Point = G): Point {
        var scalar = privateKey.mod(N)
        var result = INFINITY
        var addend = base
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = double(addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    fun add(first: Point, second: Point): Point {
        if (first.x == ZERO && first.y == ZERO) return second
        if (second.x == ZERO && second.y == ZERO) return first
        if (first.x == second.x) {
            if (first.y.add(second.y).mod(P) == ZERO) return INFINITY
            return double(first)
        }
        val slope = second.y.subtract(first.y)
            .multiply(second.x.subtract(first.x).modInverse(P))
            .mod(P)
        val x = slope.multiply(slope).subtract(first.x).subtract(second.x).mod(P)
        val y = slope.multiply(first.x.subtract(x)).subtract(first.y).mod(P)
        return Point(x, y)
    }

    fun double(point: Point): Point {
        if (point.x == ZERO && point.y == ZERO) return INFINITY
        if (point.y.signum() == 0) return INFINITY
        val slope = point.x.multiply(point.x).multiply(THREE)
            .multiply(point.y.multiply(TWO).modInverse(P))
            .mod(P)
        val x = slope.multiply(slope).subtract(point.x.multiply(TWO)).mod(P)
        val y = slope.multiply(point.x.subtract(x)).subtract(point.y).mod(P)
        return Point(x, y)
    }

    fun compress(point: Point): ByteArray {
        val out = ByteArray(33)
        out[0] = if (point.y.testBit(0)) 0x03 else 0x02
        copyFixed(point.x, out, 1)
        return out
    }

    fun uncompress(point: Point): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        copyFixed(point.x, out, 1)
        copyFixed(point.y, out, 33)
        return out
    }

    fun parseCompressed(bytes: ByteArray): Point {
        require(bytes.size == 33 && (bytes[0].toInt() == 0x02 || bytes[0].toInt() == 0x03)) {
            "Invalid compressed public key"
        }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val ySquared = x.modPow(THREE, P).add(SEVEN).mod(P)
        var y = ySquared.modPow(HALF_P_PLUS_ONE, P)
        val wantOdd = bytes[0].toInt() == 0x03
        if (y.testBit(0) != wantOdd) y = P.subtract(y)
        return Point(x, y)
    }

    fun copyFixed(value: BigInteger, out: ByteArray, offset: Int) {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        java.util.Arrays.fill(out, offset, offset + 32, 0)
        System.arraycopy(stripped, 0, out, offset + (32 - stripped.size), stripped.size)
    }

    fun toFixed32(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        copyFixed(value, out, 0)
        return out
    }
}
