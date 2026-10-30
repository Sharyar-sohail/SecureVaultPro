package com.securevault.pro.data.remote.firebase

import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyring protocol on its own, with no Android in the room.
 *
 * These are the guarantees the web companion is built on: one passphrase opens one vault key, and
 * anything else — a wrong passphrase, a borrowed salt, a trimmed iteration count, a byte flipped in
 * the ciphertext — answers "no" instead of handing back a key that looks fine and decrypts garbage.
 *
 * Passphrases here are generated per test run. A fixture must not contain a usable secret either.
 */
class KeyringCodecTest {

    private val derivation = KeyDerivation()

    private fun fixturePassphrase(label: String) = "$label-${System.nanoTime()}-fixture"

    private fun aVaultKey() = KeyringCodec.generateVaultKey()

    @Test
    fun `a wrapped vault key unwraps to the same bytes`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val passphrase = fixturePassphrase("round-trip")

        val wrap = KeyringCodec.seal(key, passphrase, salt, FAST_ITERATIONS, derivation)
        val opened = KeyringCodec.unwrap(wrap, passphrase, salt, FAST_ITERATIONS, derivation)

        assertArrayEquals(key, opened)
    }

    /**
     * The envelope shape both frontends parse: base64(iv).base64(ciphertext+tag), with a 96-bit
     * IV and a 128-bit tag, which is exactly the container `CryptoManager` writes for a field.
     */
    @Test
    fun `the wrap is the same envelope shape the rest of the app uses`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val wrap = KeyringCodec.seal(key, fixturePassphrase("shape"), salt, FAST_ITERATIONS, derivation)

        val parts = wrap.split('.')
        assertEquals("wrap must be iv.body", 2, parts.size)
        val iv = KeyringCodec.decodeBase64(parts[0])
        val body = KeyringCodec.decodeBase64(parts[1])
        assertNotNull(iv)
        assertNotNull(body)
        assertEquals(KeyringCodec.IV_BYTES, iv!!.size)
        // 32 key bytes plus a 16-byte GCM tag.
        assertEquals(key.size + KeyringCodec.TAG_BITS / 8, body!!.size)
    }

    @Test
    fun `a wrong passphrase yields no key at all`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val right = fixturePassphrase("right")
        val wrong = fixturePassphrase("wrong")

        val wrap = KeyringCodec.seal(key, right, salt, FAST_ITERATIONS, derivation)

        assertNull(KeyringCodec.unwrap(wrap, wrong, salt, FAST_ITERATIONS, derivation))
    }

    @Test
    fun `a wrong salt yields no key at all`() {
        val key = aVaultKey()
        val passphrase = fixturePassphrase("salt")
        val sealSalt = KeyringCodec.generateSalt(derivation)
        val otherSalt = KeyringCodec.generateSalt(derivation)

        val wrap = KeyringCodec.seal(key, passphrase, sealSalt, FAST_ITERATIONS, derivation)

        assertNotEquals(sealSalt.toList(), otherSalt.toList())
        assertNull(KeyringCodec.unwrap(wrap, passphrase, otherSalt, FAST_ITERATIONS, derivation))
    }

    /**
     * Proves the iteration count is really part of the key and not decoration: a wrap made at one
     * count does not open at another, and the spec's 600,000 profile opens end to end.
     */
    @Test
    fun `the iteration count is honoured`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val passphrase = fixturePassphrase("iters")

        val wrap = KeyringCodec.seal(key, passphrase, salt, FAST_ITERATIONS, derivation)
        assertArrayEquals(key, KeyringCodec.unwrap(wrap, passphrase, salt, FAST_ITERATIONS, derivation))
        assertNull(KeyringCodec.unwrap(wrap, passphrase, salt, FAST_ITERATIONS * 2, derivation))
        assertNull(KeyringCodec.unwrap(wrap, passphrase, salt, 1, derivation))

        val specWrap = KeyringCodec.seal(key, passphrase, salt, KeyDerivation.PBKDF2_ITERATIONS, derivation)
        assertArrayEquals(
            key,
            KeyringCodec.unwrap(specWrap, passphrase, salt, KeyDerivation.PBKDF2_ITERATIONS, derivation)
        )
    }

    @Test
    fun `the default profile is the one the spec names`() {
        assertEquals(600_000, KeyDerivation.PBKDF2_ITERATIONS)
        assertEquals(32, KeyDerivation.SALT_SIZE_BYTES)
        assertEquals(32, KeyringCodec.KEY_BYTES)
        assertEquals(12, KeyringCodec.IV_BYTES)
        assertEquals(128, KeyringCodec.TAG_BITS)
        assertEquals("AES-256-GCM", KeyringCodec.ALGORITHM)
        assertEquals("AES/GCM/NoPadding", KeyringCodec.TRANSFORMATION)
        assertEquals(600_000, KeyringProtocol.ITERATIONS)
        assertEquals("svp1", RealtimePaths.ROOT)
    }

    /** Two seals of the same key must not look alike, or the wrap would be a fingerprint. */
    @Test
    fun `sealing twice produces a different wrap`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val passphrase = fixturePassphrase("fresh-iv")

        val first = KeyringCodec.seal(key, passphrase, salt, FAST_ITERATIONS, derivation)
        val second = KeyringCodec.seal(key, passphrase, salt, FAST_ITERATIONS, derivation)

        assertNotEquals(first, second)
        assertArrayEquals(key, KeyringCodec.unwrap(first, passphrase, salt, FAST_ITERATIONS, derivation))
        assertArrayEquals(key, KeyringCodec.unwrap(second, passphrase, salt, FAST_ITERATIONS, derivation))
    }

    /**
     * AES-GCM authenticates, so a modified wrap releases nothing. A vault key that came back
     * corrupted would silently decrypt every record to nonsense; this is the test that says it
     * cannot.
     */
    @Test
    fun `a tampered or truncated wrap is refused`() {
        val key = aVaultKey()
        val salt = KeyringCodec.generateSalt(derivation)
        val passphrase = fixturePassphrase("tamper")
        val wrap = KeyringCodec.seal(key, passphrase, salt, FAST_ITERATIONS, derivation)

        val iv = wrap.substringBefore('.')
        val body = wrap.substringAfter('.')
        val flipped = body.mapIndexed { index, char ->
            if (index == body.length / 2) if (char == 'A') 'B' else 'A' else char
        }.joinToString("")
        assertNotEquals(body, flipped)

        assertNull(KeyringCodec.unwrap("$iv.$flipped", passphrase, salt, FAST_ITERATIONS, derivation))
        assertNull(KeyringCodec.unwrap(body, passphrase, salt, FAST_ITERATIONS, derivation))
        assertNull(KeyringCodec.unwrap("$iv.$body.$iv", passphrase, salt, FAST_ITERATIONS, derivation))
        assertNull(KeyringCodec.unwrap("", passphrase, salt, FAST_ITERATIONS, derivation))
        assertNull(KeyringCodec.unwrap(wrap, passphrase, ByteArray(0), FAST_ITERATIONS, derivation))
    }

    @Test
    fun `sealing a record body uses the vault key directly`() {
        val key = aVaultKey()
        val record = """{"id":"note-1","title":"keep"}""".toByteArray(Charsets.UTF_8)

        val envelope = KeyringCodec.sealBytes(record, key)
        assertArrayEquals(record, KeyringCodec.openBytes(envelope, key))
    }

    @Test
    fun `a record body sealed under another vault key does not open`() {
        val record = "a sealed note".toByteArray(Charsets.UTF_8)
        val envelope = KeyringCodec.sealBytes(record, aVaultKey())

        assertNull(KeyringCodec.openBytes(envelope, aVaultKey()))
    }

    @Test
    fun `a vault key is exactly 256 bits and can be wiped`() {
        val key = VaultKey(aVaultKey())
        assertEquals(32, key.bytes.size)
        assertTrue(key.sameKeyAs(VaultKey(key.bytes.copyOf())))
        assertNotEquals(key, VaultKey(aVaultKey()))

        val copy = key.copy()
        key.destroy()
        assertArrayEquals(ByteArray(32), key.bytes)
        assertEquals(32, copy.bytes.size)
        assertTrue(copy.sameKeyAs(copy))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short key is never accepted as a vault key`() {
        VaultKey(ByteArray(16))
    }

    @Test
    fun `a bad base64 string decodes to null instead of throwing`() {
        assertNull(KeyringCodec.decodeBase64("not base64 !!!"))
        assertNull(KeyringCodec.decodeBase64(""))
        assertNull(KeyringCodec.decodeBase64(null))
        val bytes = byteArrayOf(1, 2, 3, -1, -128)
        assertArrayEquals(bytes, KeyringCodec.decodeBase64(KeyringCodec.encodeBase64(bytes)))
    }

    private companion object {
        /** Real PBKDF2 is slow on purpose; the spec profile is exercised once, the rest is fast. */
        const val FAST_ITERATIONS = 4_096
    }
}
