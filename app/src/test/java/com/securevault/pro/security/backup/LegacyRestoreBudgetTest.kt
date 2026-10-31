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
 * Proof that a legacy restore finishes in bounded time, and on the exact key the writer used.
 *
 * The 3.4.9 restore could sit at "Decrypting vault..." while it derived PBKDF2 ahead of the real
 * key; on a handset that is seconds per candidate and the time budget ended the sweep at four. The
 * universal-key vector is the shape every pre-keyring build actually wrote, so opening it on the
 * first candidate in under two seconds is the property that makes the Google restore work.
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

    private fun emailVector(): Vector = vector("E-email-600k")

    @Test
    fun `the key every pre-keyring writer used opens its backup in one derivation under two seconds`() {
        val v = vector("A-universal-v3-600k")
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            vaultSecret = null
        )
        assertEquals(
            "the writer's own key must be the first thing tried when no vault key exists",
            "SecureVaultPro_Universal_Cloud_Key_v3",
            plan.first().value
        )

        val started = System.nanoTime()
        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = plan,
            budgetMs = 2_000L
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNotNull("the writer's own key must open its own backup", attempt.opened)
        assertEquals(v.plaintext, attempt.opened!!.json)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, attempt.opened!!.kind)
        assertEquals("the real key must be reached on the first derivation", 1, attempt.candidatesTried)
        assertTrue("one derivation took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun `a legacy address-sealed backup opens in one derivation within two seconds`() {
        val v = emailVector()
        val started = System.nanoTime()

        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = listOf(OpeningSecret(v.secret, OpeningSecretKind.ACCOUNT_EMAIL)),
            iterationProfiles = listOf(v.envelope.iterations ?: LegacyEnvelopeOpener.CURRENT_ITERATIONS),
            budgetMs = 2_000L
        )

        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNotNull("the account address must open its own backup", attempt.opened)
        assertEquals(v.plaintext, attempt.opened!!.json)
        assertEquals(OpeningSecretKind.ACCOUNT_EMAIL, attempt.opened!!.kind)
        assertEquals("the correct key must be found on the first candidate", 1, attempt.candidatesTried)
        assertTrue("one derivation took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun `a wrong-key sweep gives up at its deadline instead of hanging`() {
        val v = emailVector()
        val wrong = (1..12).map {
            OpeningSecret("wrong-$it@example.com", OpeningSecretKind.ACCOUNT_EMAIL)
        }
        val started = System.nanoTime()

        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = wrong,
            iterationProfiles = listOf(v.envelope.iterations ?: LegacyEnvelopeOpener.CURRENT_ITERATIONS),
            budgetMs = 400L
        )

        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNull(attempt.opened)
        assertTrue("the budget must be reported as exhausted", attempt.budgetExhausted)
        assertTrue(
            "12 wrong keys must not run to completion; took ${elapsedMs}ms",
            attempt.candidatesTried < wrong.size
        )
        assertTrue("the sweep overran the requested bound: ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun `the stop signal ends the sweep before the next derivation`() {
        val v = emailVector()
        var polls = 0

        val attempt = opener.openWithin(
            salt = v.envelope.salt,
            data = v.envelope.data,
            secrets = (1..12).map {
                OpeningSecret("wrong-$it@example.com", OpeningSecretKind.ACCOUNT_EMAIL)
            },
            iterationProfiles = listOf(v.envelope.iterations ?: LegacyEnvelopeOpener.CURRENT_ITERATIONS),
            budgetMs = 60_000L,
            shouldStop = { polls++ >= 1 }
        )

        assertNull(attempt.opened)
        assertTrue("a stopped sweep is reported, not treated as a wrong key", attempt.budgetExhausted)
        assertEquals("only the derivation already in flight may run", 1, attempt.candidatesTried)
    }
}
