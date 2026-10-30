package com.securevault.pro.crypto.stego

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealing for stego envelopes only.
 *
 * This is the subsystem's own container — the vault's PBKDF2/AES-GCM helpers and the `{v:3}` backup
 * envelope are untouched and uninvolved, which is the point of keeping twelve lines of cipher code
 * here instead of reaching into `security/`. The tag is what makes a wrong passphrase answer
 * "nothing" rather than "maybe": a false plaintext can only be produced by a key that authenticates.
 *
 * Wire form: `iv(12) || ciphertext || tag(16)`, which is exactly the byte layout a GCM `doFinal`
 * returns, so the payload of an envelope block is one contiguous run of bits on the lattice.
 */
object StegoCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == StegoLimits.KEY_BYTES) { "Stego envelopes need a 32 byte key" }
        require(iv.size == StegoLimits.IV_BYTES) { "Stego envelopes need a 12 byte nonce" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    /** Null whenever the tag does not verify: wrong key, damaged bits, or a re-ordered frame. */
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? {
        if (key.size != StegoLimits.KEY_BYTES) return null
        val overhead = StegoLimits.IV_BYTES + StegoLimits.GCM_TAG_BYTES
        if (blob.size <= overhead) return null
        return try {
            val iv = blob.copyOfRange(0, StegoLimits.IV_BYTES)
            val body = blob.copyOfRange(StegoLimits.IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (_: Throwable) {
            null
        }
    }

    fun digest(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Constant-time comparison of two digests, so a probe cannot be timed by an attacker. */
    fun matches(expected: ByteArray, observed: ByteArray): Boolean =
        MessageDigest.isEqual(expected, observed)
}
