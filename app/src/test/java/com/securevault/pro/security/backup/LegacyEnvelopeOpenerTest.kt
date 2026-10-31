package com.securevault.pro.security.backup

import com.google.gson.JsonParser
import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Proof that an old backup still opens.
 *
 * Every envelope here was produced by `tools/legacy_vectors/generate.py` — a Python implementation
 * of PBKDF2-HMAC-SHA256 + AES-256-GCM that shares no code with the app — so these tests show the
 * Kotlin opener reads files written by *another* program, not that it round-trips its own output.
 * The plaintext is a realistic vault body, and the assertion is on the exact JSON text.
 *
 * If this file ever fails, the app has broken backward compatibility with data already in owners'
 * Google Drive. That is the one failure the owner said is unacceptable, twice.
 */
class LegacyEnvelopeOpenerTest {

    private val derivation = KeyDerivation()
    private val opener = LegacyEnvelopeOpener(derivation)

    private data class Vector(
        val name: String,
        val secret: String,
        val iterations: Int,
        val shape: String,
        val envelope: ByteArray
    )

    private fun loadVectors(): Pair<String, List<Vector>> {
        val stream = javaClass.getResourceAsStream("/legacy_vectors.json")
            ?: error("legacy_vectors.json is missing from the test classpath")
        val root = stream.use { JsonParser.parseReader(it.reader()).asJsonObject }
        val plaintext = root.get("plaintext").asString
        val vectors = root.getAsJsonArray("vectors").map { element ->
            val obj = element.asJsonObject
            Vector(
                name = obj.get("name").asString,
                secret = obj.get("secret").asString,
                iterations = obj.get("iterations").asInt,
                shape = obj.get("shape").asString,
                envelope = Base64.getDecoder().decode(obj.get("envelopeBase64").asString)
            )
        }
        return plaintext to vectors
    }

    private fun vector(name: String): Pair<String, Vector> {
        val (plaintext, vectors) = loadVectors()
        val found = vectors.firstOrNull { it.name == name } ?: error("no vector $name")
        return plaintext to found
    }

    private fun open(v: Vector, secrets: List<OpeningSecret>, profiles: List<Int> = LegacyEnvelopeOpener.exactProfiles(3)): LegacyEnvelopeOpener.Opened? {
        val envelope = BackupEnvelopeFormat.parse(v.envelope) ?: error("${v.name} is not parseable")
        assertTrue("${v.name} must parse as an envelope", envelope.recognised)
        return opener.open(envelope.salt, envelope.data, secrets, profiles)
    }

    @Test
    fun `the retired universal key opens a 600k backup from the last legacy build`() {
        val (plaintext, v) = vector("A-universal-v3-600k")
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)))
        assertNotNull("a universal-key backup must open", opened)
        assertEquals(plaintext, opened!!.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, opened.kind)
        assertEquals(600_000, opened.iterations)
    }

    @Test
    fun `every universal key release still opens its own backups`() {
        val (plaintext, vectors) = loadVectors()
        val universal = vectors.filter { it.secret.startsWith("SecureVaultPro_Universal_Cloud_Key") }
        assertEquals("the vector set must cover every retired key", 4, universal.map { it.secret }.distinct().size)
        for (v in universal) {
            val envelope = BackupEnvelopeFormat.parse(v.envelope)!!
            val opened = open(
                v,
                listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)),
                LegacyEnvelopeOpener.exactProfiles(envelope.version)
            )
            assertEquals("${v.name} must open with ${v.secret}", plaintext, opened?.json)
        }
    }

    @Test
    fun `a pre-v3 210k backup opens with the exact counts the old reader used for it`() {
        val (plaintext, v) = vector("B-universal-v2-210k")
        val opened = open(
            v,
            listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)),
            LegacyEnvelopeOpener.exactProfiles(2)
        )
        assertEquals(plaintext, opened?.json)
        assertEquals(210_000, opened?.iterations)
    }

    @Test
    fun `a pre-v3 120k backup opens with the exact counts the old reader used for it`() {
        val (plaintext, v) = vector("C-universal-v2-120k")
        val opened = open(
            v,
            listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)),
            LegacyEnvelopeOpener.exactProfiles(2)
        )
        assertEquals(plaintext, opened?.json)
        assertEquals(120_000, opened?.iterations)
    }

    @Test
    fun `a passphrase-sealed export opens with the passphrase the owner types`() {
        val (plaintext, v) = vector("D-passphrase-600k")
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.PASSPHRASE)))
        assertEquals(plaintext, opened?.json)
        assertEquals(OpeningSecretKind.PASSPHRASE, opened?.kind)
    }

    @Test
    fun `the earliest address-sealed backup opens with the account address`() {
        val (plaintext, v) = vector("E-email-600k")
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.ACCOUNT_EMAIL)))
        assertEquals(plaintext, opened?.json)
        assertEquals(OpeningSecretKind.ACCOUNT_EMAIL, opened?.kind)
    }

    @Test
    fun `the separate-tag shape opens`() {
        val (plaintext, v) = vector("F-universal-v2-tagged")
        assertEquals("iv.part.part", BackupEnvelopeFormat.parse(v.envelope)?.payloadShape)
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)))
        assertEquals(plaintext, opened?.json)
    }

    @Test
    fun `the swapped tag shape opens`() {
        val (plaintext, v) = vector("G-universal-v2-swapped")
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)))
        assertEquals(plaintext, opened?.json)
    }

    @Test
    fun `a base64-wrapped envelope file opens exactly as Drive stored it`() {
        val (plaintext, v) = vector("H-universal-v0-wrapped")
        val opened = open(v, listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)))
        assertEquals(plaintext, opened?.json)
    }

    @Test
    fun `a sweep past the wrong secrets still finds the right one`() {
        val (plaintext, v) = vector("A-universal-v3-600k")
        val secrets = listOf(
            OpeningSecret("not-the-passphrase", OpeningSecretKind.PASSPHRASE),
            OpeningSecret("someone@else.example", OpeningSecretKind.ACCOUNT_EMAIL),
            OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)
        )
        val opened = open(v, secrets)
        assertEquals(plaintext, opened?.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, opened?.kind)
    }

    @Test
    fun `a wrong secret fails loudly instead of returning a body`() {
        val (_, v) = vector("A-universal-v3-600k")
        val opened = open(v, listOf(OpeningSecret("wrong but plausible passphrase", OpeningSecretKind.PASSPHRASE)))
        assertNull("GCM must reject a wrong key, never yield garbage", opened)
    }

    @Test
    fun `opensWith answers per secret and per count`() {
        val (_, v) = vector("B-universal-v2-210k")
        val envelope = BackupEnvelopeFormat.parse(v.envelope)!!
        assertTrue(opener.opensWith(envelope.salt, envelope.data, v.secret, 210_000))
        assertFalse(opener.opensWith(envelope.salt, envelope.data, v.secret, 600_000))
        assertFalse(opener.opensWith(envelope.salt, envelope.data, "wrong", 210_000))
    }

    @Test
    fun `the derivation counts are the old code's own, never a sweep`() {
        assertEquals(listOf(600_000), LegacyEnvelopeOpener.exactProfiles(3))
        assertEquals(listOf(600_000), LegacyEnvelopeOpener.exactProfiles(4))
        assertEquals(listOf(600_000), LegacyEnvelopeOpener.exactProfiles(null))
        assertEquals(
            listOf(600_000, 210_000, 120_000),
            LegacyEnvelopeOpener.exactProfiles(2)
        )
        assertEquals(600_000, LegacyEnvelopeOpener.CURRENT_ITERATIONS)
        assertEquals(210_000, LegacyEnvelopeOpener.LEGACY_V2_ITERATIONS_210K)
        assertEquals(120_000, LegacyEnvelopeOpener.LEGACY_V2_ITERATIONS_120K)
    }

    @Test
    fun `the shape the old writer produced from the app's own derivation opens`() {
        // Mirrors CryptoManager.encryptWithDerivedKey + KeyDerivation exactly: same PBKDF2 class,
        // same 32-byte salt, same 12-byte IV, same `iv.body` envelope as sealPayload wrote.
        val json = """{"passwords":[{"id":"p-7","title":"Written by 3.4.1"}]}"""
        val secret = "SecureVaultPro_Universal_Cloud_Key_v3"
        val salt = derivation.generateSalt()
        val key = derivation.deriveKey(secret.toCharArray(), salt, LegacyEnvelopeOpener.CURRENT_ITERATIONS)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val blob = cipher.doFinal(json.toByteArray())
        val data = Base64.getEncoder().encodeToString(cipher.iv) + "." +
            Base64.getEncoder().encodeToString(blob)
        val envelope = BackupEnvelopeFormat.parse(
            BackupEnvelopeFormat.envelopeJson(3, Base64.getEncoder().encodeToString(salt), data).toByteArray()
        )!!

        val opened = opener.open(
            envelope.salt,
            envelope.data,
            listOf(OpeningSecret(secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
        )
        assertEquals(json, opened?.json)
    }

    @Test
    fun `a stated round count is enough on its own`() {
        val (plaintext, v) = vector("C-universal-v2-120k")
        // The shipped writers never recorded their count, so a self-describing file is simulated by
        // adding the field to an envelope whose body was genuinely sealed at 120,000 rounds.
        val stated = v.envelope.toString(Charsets.UTF_8)
            .replace("\"v\":2", "\"v\":2,\"iterations\":120000")
            .toByteArray()
        val envelope = BackupEnvelopeFormat.parse(stated)!!
        assertEquals(120_000, envelope.iterations)
        val opened = opener.open(
            envelope.salt,
            envelope.data,
            listOf(OpeningSecret(v.secret, OpeningSecretKind.RETIRED_UNIVERSAL_KEY)),
            listOf(120_000)
        )
        assertEquals(plaintext, opened?.json)
    }

    @Test
    fun `a backup sealed with the Google account id at the old 600000 rounds opens`() {
        val json = """{"entries":[{"id":"e-1","title":"Sealed by the original app"}]}"""
        val accountId = "118273645091827364509"
        val salt = derivation.generateSalt()
        val data = seal(json, accountId, salt, 600_000)

        val attempt = opener.openWithin(
            salt = salt,
            data = data,
            secrets = listOf(OpeningSecret(accountId, OpeningSecretKind.ACCOUNT_ID)),
            iterationProfiles = LegacyEnvelopeOpener.exactProfiles(3)
        )

        assertEquals(json, attempt.opened?.json)
        assertEquals(600_000, attempt.opened?.iterations)
        assertEquals(OpeningSecretKind.ACCOUNT_ID, attempt.opened?.kind)
    }

    @Test
    fun `a 16-byte salt opened with its padded copy, the way the original reader derived it`() {
        val json = """{"entries":[{"id":"e-2"}]}"""
        val short = ByteArray(16) { (it * 7 + 3).toByte() }
        val padded = short.copyOf(32)
        val data = seal(json, "owner@example.com", padded, 600_000)

        val attempt = opener.openWithSalts(
            salts = LegacySaltPlan.variants(short),
            data = data,
            secrets = listOf(OpeningSecret("owner@example.com", OpeningSecretKind.ACCOUNT_EMAIL)),
            iterationProfiles = listOf(600_000)
        )

        assertEquals(json, attempt.opened?.json)
        assertEquals("the padding is what opened it", 32, attempt.opened?.saltBytes)
        assertEquals("the written salt is tried before its padded copy", 2, attempt.candidatesTried)
    }

    @Test
    fun `a sweep reports every derivation it starts, so the overlay never looks frozen`() {
        val json = """{"entries":[{"id":"e-3"}]}"""
        val salt = derivation.generateSalt()
        val data = seal(json, "the-real-secret", salt, 600_000)
        val seen = mutableListOf<Int>()

        val attempt = opener.openWithin(
            salt = salt,
            data = data,
            secrets = listOf(
                OpeningSecret("wrong-1", OpeningSecretKind.OTHER),
                OpeningSecret("wrong-2", OpeningSecretKind.OTHER),
                OpeningSecret("the-real-secret", OpeningSecretKind.OTHER)
            ),
            iterationProfiles = listOf(600_000),
            onAttempt = { seen += it }
        )

        assertEquals(json, attempt.opened?.json)
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(3, attempt.candidatesTried)
    }

    private fun seal(json: String, secret: String, salt: ByteArray, iterations: Int): String {
        val key = derivation.deriveKey(secret.toCharArray(), salt, iterations)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val blob = cipher.doFinal(json.toByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv) + "." +
            Base64.getEncoder().encodeToString(blob)
    }
}
