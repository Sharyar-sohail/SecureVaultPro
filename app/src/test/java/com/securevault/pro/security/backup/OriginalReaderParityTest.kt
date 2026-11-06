package com.securevault.pro.security.backup

import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files sealed by the app's own writers, opened by the restore pipeline exactly the way the
 * pipeline opens them: one marker read off the file, one key, one derivation.
 *
 * The sealing code here is a transcription of the shipped `CloudSyncManager.writeBackup` +
 * `KeyDerivation.deriveKey` + `CryptoManager.encryptWithDerivedKey`: PBKDF2-HMAC-SHA256 at 600,000,
 * a 32-byte salt, a 12-byte IV, AES-256-GCM, `base64(iv).base64(ciphertext+tag)`.
 *
 * The last two cases are the ones that matter most: a copy this device cannot open must cost one
 * derivation and no more. Anything that makes them cost two is the loop the owner told us to stop
 * running.
 */
class OriginalReaderParityTest {

    private companion object {
        const val PAYLOAD =
            "{\"version\":3,\"entries\":[{\"id\":\"e1\",\"title\":\"Bank\"," +
                "\"username\":\"owner@example.com\",\"password\":\"S3cret-Passw0rd!\"}]," +
                "\"notes\":[],\"cards\":[],\"totps\":[],\"contacts\":[],\"files\":[]}"

        const val VAULT_SECRET = "dmF1bHQta2V5LXRoaXMtYWNjb3VudC1vbmx5"
        const val IV_HEX = "0a1b2c3d4e5f60718293a4b5"
        const val SALT_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** The shipped `KeyDerivation.deriveKey`, including its empty-input fallbacks. */
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

        /** The shipped `CryptoManager.encryptWithKey`: 12-byte IV, tag appended, dotted base64. */
        fun seal(plaintext: String, key: ByteArray, iv: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(iv) + "." +
                Base64.getEncoder().encodeToString(body)
        }

        /** What `CloudSyncManager` writes to Drive, with the key marker the current writer stamps. */
        fun envelope(
            secret: String,
            salt: ByteArray,
            iv: ByteArray,
            iterations: Int = 600_000,
            keyId: String? = null
        ): String {
            val data = seal(PAYLOAD, derive(secret, salt, iterations), iv)
            val marker = keyId?.let { "\"kid\":\"$it\"," }.orEmpty()
            return "{\"v\":3,$marker\"salt\":\"${Base64.getEncoder().encodeToString(salt)}\"," +
                "\"data\":\"$data\"}"
        }
    }

    /**
     * What `CloudSyncManager.decryptBackup` now runs for a stored envelope: parse the file, ask the
     * plan for the one key its marker names, derive it at the one count, and stop there.
     */
    private fun restore(envelopeJson: String, vaultSecret: String? = null): LegacyEnvelopeOpener.OpenAttempt {
        val parsed = BackupEnvelopeFormat.parse(envelopeJson.toByteArray())
            ?: error("envelope did not parse")
        assertTrue("envelope must be recognised", parsed.recognised)
        val secret = LegacyKeyPlan.exactSecret(keyId = parsed.keyId, vaultSecret = vaultSecret)
            ?: return LegacyEnvelopeOpener.OpenAttempt(null, 0, false)
        return LegacyEnvelopeOpener(KeyDerivation()).openWithSalts(
            salts = listOf(parsed.salt),
            data = parsed.data,
            secrets = listOf(secret)
        )
    }

    @Test
    fun `a Drive copy from the pre-keyring writer opens with its one key`() {
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX)))
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened?.kind)
        assertEquals(600_000, attempt.opened?.iterations)
        assertEquals("one marker, one key, one derivation", 1, attempt.candidatesTried)
    }

    @Test
    fun `a copy marked as the account's opens with the vault key this device holds`() {
        val attempt = restore(
            envelope(VAULT_SECRET, hex(SALT_HEX), hex(IV_HEX), keyId = LegacyKeyPlan.KEY_ID_VAULT),
            vaultSecret = VAULT_SECRET
        )
        assertEquals(PAYLOAD, attempt.opened?.json)
        assertEquals(OpeningSecretKind.VAULT_KEY, attempt.opened?.kind)
        assertEquals(1, attempt.candidatesTried)
    }

    @Test
    fun `a copy marked as the account's names no key on a device that lacks it`() {
        val attempt = restore(
            envelope(VAULT_SECRET, hex(SALT_HEX), hex(IV_HEX), keyId = LegacyKeyPlan.KEY_ID_VAULT)
        )
        assertNull(attempt.opened)
        assertEquals("nothing is tried when the key is not on the device", 0, attempt.candidatesTried)
    }

    @Test
    fun `a copy this device cannot open costs one derivation, not a list`() {
        val attempt = restore(envelope("not-a-shipped-secret", hex(SALT_HEX), hex(IV_HEX)))
        assertNull(attempt.opened)
        assertEquals(1, attempt.candidatesTried)
    }

    @Test
    fun `the parity payload is the shape the parser expects`() {
        val attempt = restore(envelope(PRIMARY_CLOUD_KEY, hex(SALT_HEX), hex(IV_HEX)))
        assertNotNull(attempt.opened)
        val root = com.google.gson.JsonParser.parseString(attempt.opened!!.json).asJsonObject
        assertEquals(1, root.getAsJsonArray("entries").size())
    }
}
