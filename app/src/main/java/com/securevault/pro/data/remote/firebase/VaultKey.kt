package com.securevault.pro.data.remote.firebase

import com.securevault.pro.security.KeyDerivation
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The per-user vault key: one random 256-bit value per user, generated on first use, that seals
 * every record body on both frontends.
 *
 * The bytes are the secret. Nothing in this class formats, hashes or logs them, and callers must
 * not either — pass [bytes] straight into the cipher and call [destroy] when the session ends.
 */
class VaultKey(val bytes: ByteArray) {

    init {
        require(bytes.size == KeyringCodec.KEY_BYTES) { "A vault key is exactly 256 bits" }
    }

    /** Constant-time comparison; only ever answers "is this the same key", never what it is. */
    fun sameKeyAs(other: VaultKey): Boolean =
        KeyDerivation().constantTimeEquals(bytes, other.bytes)

    fun copy(): VaultKey = VaultKey(bytes.copyOf())

    /** Overwrites the material in place. After this the instance is unusable. */
    fun destroy() {
        bytes.fill(0)
    }
}

/**
 * Wraps and unwraps a [VaultKey] under a passphrase, and seals raw bytes under a key.
 *
 * Deliberately free of Android types so the whole protocol runs in JVM unit tests. The KDF is
 * [KeyDerivation] — PBKDF2-HMAC-SHA256 at 600,000 iterations with a 32-byte salt — exactly as the
 * rest of the app derives keys; the cipher is AES-256-GCM with a fresh 12-byte IV and a 128-bit
 * tag, matching `CryptoManager`. Envelope text is `base64(iv).base64(ciphertext+tag)`, the same
 * shape the app, the backups and the Blogger page already parse.
 */
object KeyringCodec {

    /**
     * Marker written into the `alg` field of a keyring node; the web matches on this string.
     * It is a name, not a JCE transformation — [TRANSFORMATION] is the one the cipher accepts.
     */
    const val ALGORITHM: String = "AES-256-GCM"

    /** The JCE transformation, identical to the one `CryptoManager` uses. */
    const val TRANSFORMATION: String = "AES/GCM/NoPadding"

    const val KEY_BYTES: Int = 32
    const val IV_BYTES: Int = 12
    const val TAG_BITS: Int = 128

    private val fallbackDerivation = KeyDerivation()
    private val fallbackRandom = SecureRandom()

    fun generateVaultKey(random: SecureRandom = fallbackRandom): ByteArray =
        ByteArray(KEY_BYTES).also(random::nextBytes)

    fun generateSalt(derivation: KeyDerivation = fallbackDerivation): ByteArray =
        derivation.generateSalt()

    /** `base64(iv).base64(ciphertext)` of the vault key under PBKDF2(passphrase, salt, iters). */
    fun seal(
        vaultKey: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iterations: Int = KeyDerivation.PBKDF2_ITERATIONS,
        derivation: KeyDerivation = fallbackDerivation
    ): String {
        require(vaultKey.size == KEY_BYTES) { "Only a full 256-bit vault key may be sealed" }
        require(passphrase.isNotEmpty()) { "A passphrase is required to seal the vault key" }
        require(salt.isNotEmpty()) { "A salt is required to seal the vault key" }
        val kek = derivation.deriveKey(passphrase.toCharArray(), salt, iterations)
        try {
            return sealBytes(vaultKey, kek)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Reverse of [seal]. Returns null for anything that is not this exact wrap: wrong passphrase,
     * wrong salt, wrong iteration count, truncated or tampered payload, foreign base64. GCM
     * authenticates before releasing plaintext, so a failure here yields no key at all rather than
     * a corrupted one.
     */
    fun unwrap(
        wrap: String,
        passphrase: String,
        salt: ByteArray,
        iterations: Int = KeyDerivation.PBKDF2_ITERATIONS,
        derivation: KeyDerivation = fallbackDerivation
    ): ByteArray? {
        if (passphrase.isEmpty() || salt.isEmpty()) return null
        val kek = runCatching {
            derivation.deriveKey(passphrase.toCharArray(), salt, iterations)
        }.getOrNull() ?: return null
        return try {
            val plain = openBytes(wrap, kek) ?: return null
            if (plain.size != KEY_BYTES) return null
            plain
        } catch (_: Throwable) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /** AES-256-GCM under an already-derived key; the record-body primitive as well as the wrap. */
    fun sealBytes(plain: ByteArray, key: ByteArray): String {
        require(key.size == KEY_BYTES) { "AES-256 needs a 32-byte key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "GCM IV must be 96 bits" }
        return encodeBase64(iv) + "." + encodeBase64(cipher.doFinal(plain))
    }

    /** Null when the envelope is malformed or does not authenticate under [key]. */
    fun openBytes(envelope: String, key: ByteArray): ByteArray? {
        return try {
            val parts = envelope.trim().split('.')
            if (parts.size != 2) return null
            val iv = decodeBase64(parts[0]) ?: return null
            val body = decodeBase64(parts[1]) ?: return null
            if (iv.size != IV_BYTES) return null
            if (body.size <= TAG_BITS / 8) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (_: Throwable) {
            null
        }
    }

    fun encodeBase64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    /** Null on any illegal base64, so callers never have to catch a decoder. */
    fun decodeBase64(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        return runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
    }
}
