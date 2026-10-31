package com.securevault.pro.security.backup

import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files sealed by the original app's own writer, opened by the restore pipeline.
 *
 * The sealing code here is a transcription of the shipped `CloudSyncManager.writeBackup` +
 * `KeyDerivation.deriveKey` + `CryptoManager.encryptWithDerivedKey`: PBKDF2-HMAC-SHA256 at the
 * count under test, a 32-byte salt, a 12-byte IV, AES-256-GCM, `base64(iv).base64(ciphertext+tag)`.
 *
 * The counts are the ones the original restore loop iterated over — 600,000, 100,000, 10,000,
 * 1,000 — and the keys are the strings that loop offered. A restore must open each one; the cases
 * below are the ones the old reader could open, so anything failing here is a file an owner can no
 * longer get back.
 */
class OriginalReaderParityTest {

    private val opener = LegacyEnvelopeOpener(KeyDerivation())

    private companion object {
        const val PAYLOAD =
            "{\"version\":3,\"entries\":[{\"id\":\"e1\",\"title\":\"Bank\"," +
                "\"username\":\"owner@example.com\",\"password\":\"S3cret-Passw0rd!\"}]," +
                "\"notes\":[],\"cards\":[],\"totps\":[],\"contacts\":[],\"files\":[]}"

        const val IV_HEX = "0a1b2c3d4e5f60718293a4b5"
        const val SALT_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** The original `KeyDerivation.deriveKey` line for line, including its empty-input fallbacks. */
        fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val safeSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
            val safePassword = if (password.isNotEmpty()) password else "SecureVaultProDefaultKey"
            val spec = PBEKeySpec(safePassword.toCharArray(), safeSalt, iterations, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** The original `CryptoManager.encryptWithKey`: 12-byte IV, tag appended, dotted base64. */
        fun seal(plaintext: String, key: ByteArray, iv: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(iv) + "." +
                Base64.getEncoder().encodeToString(body)
        }

        /** What `CloudSyncManager` wrote to Drive: `{"v":3,"salt":...,"data":...}`. */
        fun envelope(secret: String, salt: ByteArray, iv: ByteArray, iterations: Int): String {
            val data = seal(PAYLOAD, derive(secret, salt, iterations), iv)
            return "{\"v\":3,\"salt\":\"${Base64.getEncoder().encodeToString(salt)}\"," +
                "\"data\":\"$data\"}"
        }

        /** The exact decision chain `CloudSyncManager.decryptBackup` runs for a stored envelope. */
        fun restore(envelopeJson: String, emails: List<String> = listOf("owner@example.com")): LegacyEnvelopeOpener.OpenAttempt {
            val parsed = BackupEnvelopeFormat.parse(envelopeJson.toByteArray())
                ?: error("envelope did not parse")
            assertTrue("envelope must be recognised", parsed.recognised)
            return LegacyEnvelopeOpener(KeyDerivation()).openWithSalts(
                salts = LegacySaltPlan.variants(parsed.salt),
                data = parsed.data,
                secrets = LegacyKeyPlan.ordered(emails = emails, version = parsed.version),
                iterationProfiles = LegacyEnvelopeOpener.exactProfiles(parsed.version)
            )
        }
    }

    @Test
    fun `the writer's own 600000-round slot opens`() {
        val salt = hex(SALT_HEX)
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, salt, hex(IV_HEX), 600_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened?.kind)
        assertEquals(600_000, attempt.opened?.iterations)
    }

    @Test
    fun `a slot sealed at the reader's 100000 count opens`() {
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX), 100_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(100_000, attempt.opened?.iterations)
    }

    @Test
    fun `a slot sealed at the reader's 10000 count opens`() {
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX), 10_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(10_000, attempt.opened?.iterations)
    }

    @Test
    fun `a slot sealed at the reader's 1000 count opens`() {
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX), 1_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(1_000, attempt.opened?.iterations)
    }

    @Test
    fun `a v3 envelope sealed with the v2 universal string still opens`() {
        val attempt = restore(envelope(LEGACY_CLOUD_KEY_V2, hex(SALT_HEX), hex(IV_HEX), 600_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened?.kind)
    }

    @Test
    fun `a v3 envelope sealed with the v1 universal string still opens`() {
        val attempt = restore(envelope(LEGACY_CLOUD_KEY_V1, hex(SALT_HEX), hex(IV_HEX), 600_000))
        assertEquals(PAYLOAD, attempt.opened?.json)
    }

    @Test
    fun `a file nobody's key opens reports a wrong key, not a hang`() {
        val attempt = restore(envelope("not-a-shipped-secret", hex(SALT_HEX), hex(IV_HEX), 10_000))
        assertEquals(null, attempt.opened)
        assertTrue(attempt.candidatesTried > 0)
    }

    @Test
    fun `the parity test payload is the shape the parser expects`() {
        val opened = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX), 600_000))
        assertNotNull(opened.opened)
        val root = com.google.gson.JsonParser.parseString(opened.opened!!.json).asJsonObject
        assertEquals(1, root.getAsJsonArray("entries").size())
    }
}
