package com.securevault.pro.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyDerivation @Inject constructor() {

    companion object {
        const val PBKDF2_ITERATIONS = 600_000
        const val PIN_ITERATIONS = 210_000
        const val SALT_SIZE_BYTES = 32
        const val KEY_SIZE_BITS = 256
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    }

    private val secureRandom = SecureRandom()

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        require(password.isNotEmpty()) { "Password cannot be empty" }
        require(salt.isNotEmpty()) { "Salt cannot be empty" }
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun generateSalt(size: Int = SALT_SIZE_BYTES): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun generateIv(size: Int = 12): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
