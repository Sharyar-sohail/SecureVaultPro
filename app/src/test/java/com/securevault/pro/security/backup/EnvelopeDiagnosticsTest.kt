package com.securevault.pro.security.backup

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The failure sentence has to name the mismatching parameter.
 *
 * AES-GCM's tag rejection cannot by itself distinguish a wrong key from a wrong salt, but the
 * widths can: salt, IV and tag are fixed in every envelope this app wrote. These tests hold the
 * diagnosis to "salt mismatch" / "IV mismatch" when the widths differ, and to "the key is the
 * mismatch" when they do not — so the owner never again sees a bare "4 candidates tried".
 */
class EnvelopeDiagnosticsTest {

    private fun shippedVector(): BackupEnvelope {
        val stream = javaClass.getResourceAsStream("/legacy_vectors.json")
            ?: error("legacy_vectors.json is missing from the test classpath")
        val root = stream.use { JsonParser.parseReader(it.reader()).asJsonObject }
        val element = root.getAsJsonArray("vectors")
            .first { it.asJsonObject.get("name").asString == "A-universal-v3-600k" }
            .asJsonObject
        return BackupEnvelopeFormat.parse(Base64.getDecoder().decode(element.get("envelopeBase64").asString))!!
    }

    private fun b64(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { 7 })

    @Test
    fun `a file in the shipped shape reports every parameter intact and names the key`() {
        val envelope = shippedVector()
        val structure = EnvelopeDiagnostics.inspect(envelope)

        assertTrue("the shipped vector must be structurally intact", structure.intact)
        assertEquals(32, structure.saltBytes)
        assertEquals(12, structure.ivBytes)
        assertTrue(structure.problems.isEmpty())

        val message = EnvelopeDiagnostics.describe(envelope, secretsTried = 4, stoppedEarly = false)
        assertTrue(message, message.contains("the key is"))
        assertFalse("the diagnosis must not blame the file's shape", message.contains("structurally different"))
        assertFalse(message.contains("candidates tried"))
        assertFalse(
            "the restore screen accepts no passphrase or phrase, so the sentence must not offer one",
            message.contains("passphrase", ignoreCase = true) || message.contains("phrase", ignoreCase = true)
        )
    }

    @Test
    fun `a short salt is named as a salt mismatch`() {
        val envelope = BackupEnvelope(
            salt = ByteArray(16),
            data = b64(12) + "." + b64(48),
            version = 3,
            iterations = null,
            recognised = true
        )
        val structure = EnvelopeDiagnostics.inspect(envelope)

        assertFalse(structure.intact)
        assertTrue(structure.problems.first().startsWith("Salt mismatch"))
        val message = EnvelopeDiagnostics.describe(envelope, secretsTried = 2, stoppedEarly = false)
        assertTrue(message, message.contains("Salt mismatch"))
        assertTrue(message, message.contains("not your account key"))
    }

    @Test
    fun `a long IV is named as an IV mismatch`() {
        val envelope = BackupEnvelope(
            salt = ByteArray(32),
            data = b64(16) + "." + b64(48),
            version = 3,
            iterations = null,
            recognised = true
        )
        val structure = EnvelopeDiagnostics.inspect(envelope)

        assertEquals(16, structure.ivBytes)
        assertTrue(structure.problems.first().startsWith("IV mismatch"))
        assertTrue(EnvelopeDiagnostics.describe(envelope, 2, false).contains("IV mismatch"))
    }

    @Test
    fun `a payload too short to hold a tag is named as a ciphertext mismatch`() {
        val envelope = BackupEnvelope(
            salt = ByteArray(32),
            data = b64(12) + "." + b64(16),
            version = 3,
            iterations = null,
            recognised = true
        )
        assertTrue(EnvelopeDiagnostics.inspect(envelope).problems.any { it.startsWith("Ciphertext mismatch") })
    }

    @Test
    fun `a stopped sweep says the budget ended it rather than claiming every key was tried`() {
        val message = EnvelopeDiagnostics.describe(shippedVector(), secretsTried = 4, stoppedEarly = true)
        assertTrue(message, message.contains("time budget"))
        assertTrue(message, message.contains("some keys were not tried"))
    }
}
