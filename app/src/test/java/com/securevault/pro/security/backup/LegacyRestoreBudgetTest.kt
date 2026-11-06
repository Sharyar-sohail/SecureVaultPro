package com.securevault.pro.security.backup

import com.google.gson.JsonParser
import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Proof that a restore is one derivation, on the real vector the writer produced.
 *
 * A restore used to sit at "Decrypting vault..." while it worked through a list of keys that might
 * have sealed the file; on a handset each one is a second or more of PBKDF2 and the owner reads the
 * wait as a hang. The file names its own key now, so the honest timing property is: the right key
 * opens on the first and only derivation, and the wrong key costs the same one derivation.
 */
class LegacyRestoreBudgetTest {

    private val derivation = KeyDerivation()
    private val opener = LegacyEnvelopeOpener(derivation)

    private class Vector(
        val plaintext: String,
        val secret: String,
        val envelope: BackupEnvelope
    )

    private fun vector(name: String): Vector {
        val stream = javaClass.getResourceAsStream("/legacy_vectors.json")
            ?: error("legacy_vectors.json is missing from the test classpath")
        val root = stream.use { JsonParser.parseReader(it.reader()).asJsonObject }
        val plaintext = root.get("plaintext").asString
        val element = root.getAsJsonArray("vectors")
            .first { it.asJsonObject.get("name").asString == name }
            .asJsonObject
        val bytes = Base64.getDecoder().decode(element.get("envelopeBase64").asString)
        val envelope = BackupEnvelopeFormat.parse(bytes) ?: error("$name is not parseable")
        assertTrue("$name must parse as an envelope", envelope.recognised)
        return Vector(plaintext, element.get("secret").asString, envelope)
    }

    @Test
    fun `the key the pre-keyring writer used opens its backup in one derivation`() {
        val v = vector("A-universal-v3-600k")
        val secret = LegacyKeyPlan.exactSecret(keyId = v.envelope.keyId, vaultSecret = null)
        assertEquals(PRIMARY_CLOUD_KEY, secret?.value)

        val started = System.nanoTime()
        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = listOfNotNull(secret),
            budgetMs = 8_000L
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNotNull("the writer's own key must open its own backup", attempt.opened)
        assertEquals(v.plaintext, attempt.opened!!.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened!!.kind)
        assertEquals("one key, one derivation", 1, attempt.candidatesTried)
        assertTrue("one derivation took ${elapsedMs}ms", elapsedMs < 8_000L)
    }

    @Test
    fun `a copy this device cannot name costs that same one derivation`() {
        val v = vector("A-universal-v3-600k")
        val started = System.nanoTime()

        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = listOf(OpeningSecret("not-a-shipped-secret", OpeningSecretKind.OTHER)),
            budgetMs = 8_000L
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull(attempt.opened)
        assertEquals("a rejection must not queue another key", 1, attempt.candidatesTried)
        assertTrue("one failed derivation took ${elapsedMs}ms", elapsedMs < 8_000L)
    }

    @Test
    fun `the stop signal ends the work before the next derivation`() {
        val v = vector("A-universal-v3-600k")
        var polls = 0

        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = (1..12).map {
                OpeningSecret("wrong-$it@example.com", OpeningSecretKind.PASSPHRASE)
            },
            budgetMs = 60_000L,
            shouldStop = { polls++ >= 1 }
        )

        assertNull(attempt.opened)
        assertTrue("a stopped sweep is reported, not treated as a wrong key", attempt.budgetExhausted)
        assertEquals("only the derivation already in flight may run", 1, attempt.candidatesTried)
    }
}
