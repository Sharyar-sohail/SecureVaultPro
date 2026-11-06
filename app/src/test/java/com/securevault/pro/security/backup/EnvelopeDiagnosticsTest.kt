package com.securevault.pro.security.backup

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * What a failed restore is allowed to say.
 *
 * A wrong key and a broken file cannot be told apart by the cipher alone, but they can by the
 * widths: salt, IV and seal are fixed in every envelope this app wrote. So a file whose widths
 * differ names the broken parameter, and a file whose widths all match says the key simply is not
 * on this device.
 *
 * Nothing in either sentence may count keys or derivations, and nothing may use internal vocabulary
 * — the owner reads this text on screen.
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

    private fun envelope(saltBytes: Int, data: String) = BackupEnvelope(
        salt = ByteArray(saltBytes),
        data = data,
        version = 3,
        iterations = null,
        recognised = true
    )

    @Test
    fun `a file in the shipped shape reports every parameter intact`() {
        val structure = EnvelopeDiagnostics.inspect(shippedVector())

        assertTrue("the shipped vector must be structurally intact", structure.intact)
        assertEquals(32, structure.saltBytes)
        assertEquals(12, structure.ivBytes)
        assertTrue(structure.problems.isEmpty())
    }

    @Test
    fun `a sound file that will not open says the key is not on this device`() {
        val message = EnvelopeDiagnostics.describe(shippedVector())
        assertEquals(EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE, message)
        assertFalse(message, message.contains("structurally different"))
    }

    @Test
    fun `a short salt is named as a salt mismatch`() {
        val envelope = envelope(16, b64(12) + "." + b64(48))
        val structure = EnvelopeDiagnostics.inspect(envelope)

        assertFalse(structure.intact)
        assertTrue(structure.problems.first().startsWith("Salt mismatch"))

        val message = EnvelopeDiagnostics.describe(envelope)
        assertTrue(message, message.startsWith("This backup file looks damaged"))
        assertTrue(message, message.contains("Salt mismatch"))
        assertFalse(
            "a damaged file must not be blamed on the owner's key",
            message == EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE
        )
    }

    @Test
    fun `a long IV is named as an IV mismatch`() {
        val envelope = envelope(32, b64(16) + "." + b64(48))

        assertEquals(16, EnvelopeDiagnostics.inspect(envelope).ivBytes)
        assertTrue(EnvelopeDiagnostics.inspect(envelope).problems.first().startsWith("IV mismatch"))
        assertTrue(EnvelopeDiagnostics.describe(envelope).contains("IV mismatch"))
    }

    @Test
    fun `a payload too short to hold a seal is named as a ciphertext mismatch`() {
        val envelope = envelope(32, b64(12) + "." + b64(16))
        assertTrue(
            EnvelopeDiagnostics.inspect(envelope).problems.any { it.startsWith("Ciphertext mismatch") }
        )
    }

    @Test
    fun `a salt-less blob in the shipped shape says the key is missing, not which key`() {
        val message = EnvelopeDiagnostics.describeBareBlob(
            data = b64(12) + "." + b64(48),
            saltSource = "key.salt on Drive (32 bytes)"
        )
        assertEquals(EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE, message)
    }

    @Test
    fun `a salt-less blob with a wrong-width IV is named as an IV mismatch`() {
        val message = EnvelopeDiagnostics.describeBareBlob(
            data = b64(8) + "." + b64(48),
            saltSource = "no key.salt on Drive"
        )
        assertTrue(message, message.contains("IV mismatch"))
        assertTrue(message, message.contains("no key.salt on Drive"))
        assertTrue(message, message.startsWith("This backup file looks damaged"))
    }

    @Test
    fun `no failure sentence counts keys or uses internal vocabulary`() {
        val sentences = listOf(
            EnvelopeDiagnostics.describe(shippedVector()),
            EnvelopeDiagnostics.describe(envelope(16, b64(12) + "." + b64(48))),
            EnvelopeDiagnostics.describe(envelope(32, b64(16) + "." + b64(48))),
            EnvelopeDiagnostics.describe(envelope(32, b64(12) + "." + b64(16))),
            EnvelopeDiagnostics.describeBareBlob(b64(12) + "." + b64(48), "key.salt on Drive"),
            EnvelopeDiagnostics.describeBareBlob(b64(8) + "." + b64(48), "no key.salt on Drive")
        )
        for (sentence in sentences) {
            for (banned in listOf(
                "GCM", "PBKDF2", "derivation", "candidate", "keys this account",
                "Invalid Legacy Key", "passphrase", "phrase", "iteration"
            )) {
                assertFalse(
                    "the owner-facing sentence must not contain \"$banned\": $sentence",
                    sentence.contains(banned, ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `the missing-key sentence tells the owner what to do`() {
        val message = EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE
        assertTrue(message, message.contains("Sign in with the Google account"))
        assertTrue(message, message.contains("device that made the backup"))
    }
}
