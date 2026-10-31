package com.securevault.pro.security.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The reader that decides whether a file is an envelope at all.
 *
 * A tolerant reader is what makes old files openable; an *over*-tolerant one is how a plaintext
 * export gets handed to the JSON reader and reported as "corrupted". So the contract here is exact:
 * every shape the app has written must be recognised, and anything else must say so instead of
 * being guessed at.
 */
class BackupEnvelopeFormatTest {

    private val salt = ByteArray(32) { (it * 7 + 3).toByte() }
    private val saltB64 = Base64.getEncoder().encodeToString(salt)
    private val body = "AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAA"

    private fun envelope(vararg fields: Pair<String, String>): ByteArray {
        val json = fields.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"$k\":\"$v\"" }
        return json.toByteArray()
    }

    @Test
    fun `the shipped v-salt-data triple parses`() {
        val parsed = BackupEnvelopeFormat.parse(envelope("v" to "3", "salt" to saltB64, "data" to body))
        assertNotNull(parsed)
        assertEquals(3, parsed!!.version)
        assertEquals(32, parsed.saltBytes)
        assertEquals(body, parsed.data)
        assertEquals("iv.payload", parsed.payloadShape)
        assertTrue(parsed.recognised)
    }

    @Test
    fun `every salt alias ever written is read`() {
        for (field in BackupEnvelope.SALT_FIELDS) {
            val parsed = BackupEnvelopeFormat.parse(envelope(field to saltB64, "data" to body))
            assertTrue("$field must be accepted as a salt", parsed?.recognised == true)
            assertEquals("$field", 32, parsed!!.saltBytes)
        }
    }

    @Test
    fun `every body alias ever written is read`() {
        for (field in BackupEnvelope.DATA_FIELDS) {
            val parsed = BackupEnvelopeFormat.parse(envelope("salt" to saltB64, field to body))
            assertTrue("$field must be accepted as the body", parsed?.recognised == true)
            assertEquals("$field", body, parsed!!.data)
        }
    }

    @Test
    fun `every iteration alias and the bare number form are read`() {
        for (field in BackupEnvelope.ITERATION_FIELDS) {
            val json = """{"salt":"$saltB64","data":"$body","$field":210000}"""
            assertEquals("$field", 210_000, BackupEnvelopeFormat.parse(json.toByteArray())?.iterations)
        }
        val absent = """{"salt":"$saltB64","data":"$body"}"""
        assertNull("a file that does not state a count must report null", BackupEnvelopeFormat.parse(absent.toByteArray())?.iterations)
    }

    @Test
    fun `a split iv field is joined back onto the body`() {
        val json = """{"salt":"$saltB64","iv":"AAAAAAAAAAAAAAAA","data":"AAAAAAAAAAAAAAAAAAAAAAAA"}"""
        val parsed = BackupEnvelopeFormat.parse(json.toByteArray())
        assertEquals("AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAA", parsed?.data)
        assertTrue(parsed!!.recognised)
    }

    @Test
    fun `a version field is honoured and defaults to 3`() {
        val v1 = BackupEnvelopeFormat.parse(envelope("version" to "1", "salt" to saltB64, "data" to body))
        assertEquals(1, v1?.version)
        val none = BackupEnvelopeFormat.parse(envelope("salt" to saltB64, "data" to body))
        assertEquals(3, none?.version)
    }

    @Test
    fun `a file that states no version is marked as unstated`() {
        val none = BackupEnvelopeFormat.parse(envelope("salt" to saltB64, "data" to body))
        assertFalse(
            "an unstated version must not be read as a current one, or its oldest keys are skipped",
            none!!.statedVersion
        )
        val stated = BackupEnvelopeFormat.parse(envelope("v" to "3", "salt" to saltB64, "data" to body))
        assertTrue(stated!!.statedVersion)
    }

    @Test
    fun `a base64-wrapped envelope file is unwrapped`() {
        val inner = """{"v":3,"salt":"$saltB64","data":"$body"}""".toByteArray()
        val wrapped = Base64.getEncoder().encodeToString(inner).toByteArray()
        val parsed = BackupEnvelopeFormat.parse(wrapped)
        assertTrue(parsed?.recognised == true)
        assertEquals(body, parsed!!.data)
    }

    @Test
    fun `a plaintext vault export is reported as not an envelope`() {
        val plain = """{"passwords":[{"id":"p-1"}],"notes":[]}""".toByteArray()
        val parsed = BackupEnvelopeFormat.parse(plain)
        assertNotNull(parsed)
        assertFalse("a plaintext export must not be treated as an envelope", parsed!!.recognised)
    }

    @Test
    fun `bytes that contain no JSON at all return null`() {
        assertNull(BackupEnvelopeFormat.parse(ByteArray(0)))
        assertNull(BackupEnvelopeFormat.parse("not a backup, just text".toByteArray()))
        assertNull(BackupEnvelopeFormat.parse(ByteArray(64) { 0xFF.toByte() }))
    }

    @Test
    fun `a body without an iv and without a dot is not accepted as an envelope`() {
        val parsed = BackupEnvelopeFormat.parse(envelope("salt" to saltB64, "data" to "AAAA"))
        assertFalse(parsed!!.recognised)
    }

    @Test
    fun `a short salt is not accepted as an envelope`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(4))
        val parsed = BackupEnvelopeFormat.parse(envelope("salt" to short, "data" to body))
        assertFalse("a 4-byte salt cannot be a real envelope", parsed!!.recognised)
    }

    @Test
    fun `envelopeJson round-trips through the reader`() {
        val json = BackupEnvelopeFormat.envelopeJson(3, saltB64, body)
        val parsed = BackupEnvelopeFormat.parse(json.toByteArray())
        assertTrue(parsed!!.recognised)
        assertEquals(3, parsed.version)
        assertEquals(body, parsed.data)
        assertTrue(parsed.salt.contentEquals(salt))
    }

    @Test
    fun `a leading BOM and surrounding whitespace do not hide an envelope`() {
        val json = "\uFEFF  \n" + BackupEnvelopeFormat.envelopeJson(3, saltB64, body) + "\n\n"
        val parsed = BackupEnvelopeFormat.parse(json.toByteArray())
        assertTrue(parsed?.recognised == true)
    }
}
