package com.securevault.pro.security.backup

import com.google.gson.JsonParser
import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Every writer this product has shipped, opened through the exact pipeline a restore runs.
 *
 * The fixtures are produced by `tools/legacy_vectors/generate.py`, an independent Python
 * implementation of PBKDF2-HMAC-SHA256 + AES-256-GCM, and each one reproduces one writer's real
 * choices: its sealing secret, its salt width, its iteration count, and whether the salt travelled
 * inside the JSON envelope or in a separate `key.salt` file.
 *
 * The test does not call the opener with a hand-picked secret. It names the key the way a restore
 * does — [LegacyKeyPlan.exactSecret] for a stored envelope, [LegacyKeyPlan.bareBlobSecret] for the
 * salt-less shape — with the salts from [LegacySaltPlan], which is what
 * `CloudSyncManager.decryptBackup` runs. So a regression in how a file's one key is chosen fails
 * here even though the cipher still works, and every case asserts a single derivation.
 *
 * The final case is a genuine file written by another program: a Node/WebCrypto export with a
 * 16-byte salt, the shape the Blogger companion writes to Drive as `secure_wallet_web.enc`.
 */
class LegacyWriterCompatibilityTest {

    private val derivation = KeyDerivation()
    private val opener = LegacyEnvelopeOpener(derivation)

    private data class Writer(
        val name: String,
        val secret: String,
        val iterations: Int,
        val shape: String,
        val salt: ByteArray,
        val data: String,
        val keySalt: ByteArray?,
        val saltBytes: Int
    )

    private fun loadWriters(): Pair<String, List<Writer>> {
        val stream = javaClass.getResourceAsStream("/legacy_vectors.json")
            ?: error("legacy_vectors.json is missing from the test classpath")
        val root = stream.use { JsonParser.parseReader(it.reader()).asJsonObject }
        val plaintext = root.get("plaintext").asString
        val writers = root.getAsJsonArray("writers").map { element ->
            val obj = element.asJsonObject
            Writer(
                name = obj.get("name").asString,
                secret = obj.get("secret").asString,
                iterations = obj.get("iterations").asInt,
                shape = obj.get("shape").asString,
                salt = Base64.getDecoder().decode(obj.get("saltBase64").asString),
                data = obj.get("data").asString,
                keySalt = obj.get("keySaltBase64")?.asString?.let { Base64.getDecoder().decode(it) },
                saltBytes = obj.get("saltBytes").asInt
            )
        }
        return plaintext to writers
    }

    private fun writer(name: String): Pair<String, Writer> {
        val (plaintext, writers) = loadWriters()
        val found = writers.firstOrNull { it.name == name } ?: error("no writer fixture $name")
        return plaintext to found
    }

    /** Exactly what `CloudSyncManager.decryptBackup` does for a stored envelope. */
    private fun openEnvelope(
        w: Writer,
        passphrase: String? = null,
        email: String? = null,
        vaultSecret: String? = null,
        keyId: String? = null
    ): LegacyEnvelopeOpener.OpenAttempt {
        val envelope = BackupEnvelopeFormat.parse(
            BackupEnvelopeFormat.envelopeJson(
                3,
                Base64.getEncoder().encodeToString(w.salt),
                w.data,
                keyId
            ).toByteArray()
        ) ?: error("${w.name} is not parseable")
        assertTrue("${w.name} must parse as an envelope", envelope.recognised)
        val secret = LegacyKeyPlan.exactSecret(
            keyId = envelope.keyId,
            passphrase = passphrase,
            vaultSecret = vaultSecret
        )
        return opener.openWithSalts(
            salts = LegacySaltPlan.variants(envelope.salt),
            data = envelope.data,
            secrets = listOfNotNull(secret)
        )
    }

    /** Exactly what `CloudSyncManager.decryptBackup` does for a salt-less `iv.ciphertext` blob. */
    private fun openBareBlob(
        w: Writer,
        email: String? = null,
        accountId: String? = null
    ): LegacyEnvelopeOpener.OpenAttempt {
        val secret = LegacyKeyPlan.bareBlobSecret(email = email, accountId = accountId)
        val salt = w.keySalt ?: secret?.value?.let { LegacySaltPlan.padAddress(it) }
        if (secret == null || salt == null) {
            return LegacyEnvelopeOpener.OpenAttempt(null, 0, false)
        }
        return opener.openWithSalts(
            salts = listOf(salt),
            data = w.data,
            secrets = listOf(secret)
        )
    }

    @Test
    fun `the original app's Drive slot opens with the universal key it was sealed with`() {
        val (plaintext, w) = writer("W4-original-app-universal-32b-600k")
        val attempt = openEnvelope(w, email = "owner@example.com")
        assertEquals(plaintext, attempt.opened?.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened?.kind)
        assertEquals(600_000, attempt.opened?.iterations)
        assertEquals("the file names its key, so one derivation is enough", 1, attempt.candidatesTried)
    }

    @Test
    fun `the browser companion's 16-byte-salt copy opens when its passphrase is offered`() {
        val (plaintext, w) = writer("W1-web-companion-16b-passphrase")
        assertEquals("the web writer emits a 16-byte salt", 16, w.salt.size)
        val attempt = openEnvelope(w, passphrase = w.secret, email = "owner@example.com")
        assertEquals(plaintext, attempt.opened?.json)
        assertEquals(OpeningSecretKind.PASSPHRASE, attempt.opened?.kind)
        assertEquals("the salt is used exactly as the browser wrote it", 16, attempt.opened?.saltBytes)
        assertEquals(1, attempt.candidatesTried)
    }

    @Test
    fun `a copy stamped as the account's opens with the vault key and nothing else`() {
        val (plaintext, w) = writer("W4-original-app-universal-32b-600k")
        val marked = openEnvelope(w, vaultSecret = w.secret, keyId = LegacyKeyPlan.KEY_ID_VAULT)
        assertEquals(plaintext, marked.opened?.json)
        assertEquals(OpeningSecretKind.VAULT_KEY, marked.opened?.kind)
        assertEquals(1, marked.candidatesTried)
    }

    @Test
    fun `the earliest bare iv ciphertext opens with key dot salt and the account address`() {
        val (plaintext, w) = writer("W2-web-portal-keysalt-email")
        assertNotNull("this shape carries its salt in key.salt", w.keySalt)
        val attempt = openBareBlob(w, email = w.secret)
        assertEquals(plaintext, attempt.opened?.json)
        assertEquals(OpeningSecretKind.ACCOUNT_EMAIL, attempt.opened?.kind)
        assertEquals(600_000, attempt.opened?.iterations)
        assertEquals(1, attempt.candidatesTried)
    }

    @Test
    fun `the password protector's bare blob opens with the numeric Google account id`() {
        val (plaintext, w) = writer("W3-password-protector-keysalt-accountid")
        val attempt = openBareBlob(w, accountId = w.secret)
        assertEquals(plaintext, attempt.opened?.json)
        assertEquals(OpeningSecretKind.ACCOUNT_ID, attempt.opened?.kind)
        assertEquals(1, attempt.candidatesTried)
    }

    @Test
    fun `a real export written by another program opens end to end`() {
        val bytes = javaClass.getResourceAsStream("/external_export_16b.json")
            ?.use { it.readBytes() }
            ?: error("external_export_16b.json is missing from the test classpath")
        val envelope = BackupEnvelopeFormat.parse(bytes)
            ?: error("the external export must parse as an envelope")
        assertTrue(envelope.recognised)
        assertEquals("this file was written with a 16-byte salt", 16, envelope.salt.size)

        val secret = LegacyKeyPlan.exactSecret(
            keyId = envelope.keyId,
            passphrase = "testpass123",
            vaultSecret = null
        )
        val attempt = opener.openWithSalts(
            salts = LegacySaltPlan.variants(envelope.salt),
            data = envelope.data,
            secrets = listOfNotNull(secret)
        )

        assertEquals(EXTERNAL_PLAINTEXT, attempt.opened?.json)
        assertEquals(OpeningSecretKind.PASSPHRASE, attempt.opened?.kind)
        assertEquals(600_000, attempt.opened?.iterations)
        assertEquals(16, attempt.opened?.saltBytes)
        assertEquals("one key for one file", 1, attempt.candidatesTried)
    }

    @Test
    fun `the external export stays sealed to anyone without its passphrase`() {
        val bytes = javaClass.getResourceAsStream("/external_export_16b.json")
            ?.use { it.readBytes() }
            ?: error("external_export_16b.json is missing from the test classpath")
        val envelope = BackupEnvelopeFormat.parse(bytes)!!
        assertFalse(
            "a passphrase-sealed file must not open with a guessed key",
            opener.opensWith(envelope.salt, envelope.data, "not-the-passphrase", 600_000)
        )
    }

    private companion object {
        /** Decoded from the external export with PBKDF2("testpass123", 16-byte salt, 600,000). */
        const val EXTERNAL_PLAINTEXT =
            "{\"version\":3,\"exportedAt\":1793083799521,\"entries\":[{\"id\":\"e1\"," +
                "\"title\":\"GitHub\",\"username\":\"demo@example.com\"," +
                "\"password\":\"Sup3r-Secret!\",\"url\":\"https://github.com\",\"notes\":\"demo\"}]," +
                "\"cards\":[],\"totps\":[{\"id\":\"t1\",\"issuer\":\"GitHub\"," +
                "\"accountName\":\"demo@example.com\",\"secret\":\"JBSWY3DPEHPK3PXP\",\"digits\":6," +
                "\"period\":30,\"algorithm\":\"SHA1\"}],\"notes\":[{\"id\":\"n1\"," +
                "\"title\":\"Recovery codes\",\"content\":\"one two three\"}]," +
                "\"contacts\":[{\"id\":\"c1\",\"name\":\"Ali Khan\",\"phone\":\"+923001234567\"," +
                "\"email\":\"ali@example.com\"}],\"files\":[]}"
    }
}
