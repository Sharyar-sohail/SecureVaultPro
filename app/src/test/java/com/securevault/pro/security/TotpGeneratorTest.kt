package com.securevault.pro.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 6238 Appendix B vectors are the contract: a code this app shows must match
 * every server in the world. Values below were recomputed with an independent HMAC
 * implementation before being pinned, and cross-checked against RFC 4226 Appendix D
 * (counter 1 with the same 20-byte secret yields the 6-digit prefix 287082).
 *
 * parseOtpAuth is intentionally not exercised here: it uses android.net.Uri, which is a
 * throwing stub in plain JVM unit tests (no Robolectric in this project).
 */
class TotpGeneratorTest {

    private val sha1Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // ASCII "12345678901234567890"
    private val sha256Secret =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
    private val sha512Secret =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"

    @Test
    fun `rfc 6238 sha1 vectors at eight digits`() {
        assertEquals("94287082", TotpGenerator.generate(sha1Secret, "SHA1", 8, 30, 59_000L))
        assertEquals("07081804", TotpGenerator.generate(sha1Secret, "SHA1", 8, 30, 1_111_111_109_000L))
        assertEquals("14050471", TotpGenerator.generate(sha1Secret, "SHA1", 8, 30, 1_111_111_111_000L))
        assertEquals("89005924", TotpGenerator.generate(sha1Secret, "SHA1", 8, 30, 1_234_567_890_000L))
    }

    @Test
    fun `rfc 6238 six-digit codes match the public vectors`() {
        // Truncating the same step to 6 digits must agree with the 8-digit values.
        assertEquals("287082", TotpGenerator.generate(sha1Secret, "SHA1", 6, 30, 59_000L))
        assertEquals("005924", TotpGenerator.generate(sha1Secret, "SHA1", 6, 30, 1_234_567_890_000L))
    }

    @Test
    fun `rfc 6238 sha256 and sha512 vectors`() {
        assertEquals("46119246", TotpGenerator.generate(sha256Secret, "SHA256", 8, 30, 59_000L))
        assertEquals("68084774", TotpGenerator.generate(sha256Secret, "SHA256", 8, 30, 1_111_111_109_000L))
        assertEquals("90693936", TotpGenerator.generate(sha512Secret, "SHA512", 8, 30, 59_000L))
        assertEquals("25091201", TotpGenerator.generate(sha512Secret, "SHA512", 8, 30, 1_111_111_109_000L))
    }

    @Test
    fun `sixty second period steps on the sixty second boundary`() {
        // T=59 with period 60 is still step 0 -> same HMAC input as T=0 six-digit:
        val atFiftyNine = TotpGenerator.generate(sha1Secret, "SHA1", 8, 60, 59_000L)
        val atOne = TotpGenerator.generate(sha1Secret, "SHA1", 8, 60, 1_000L)
        assertEquals(atFiftyNine, atOne)
        val atSixtyOne = TotpGenerator.generate(sha1Secret, "SHA1", 8, 60, 61_000L)
        assertFalse(atFiftyNine == atSixtyOne)
    }

    @Test
    fun `base32 decoding tolerates case spacing hyphens and padding like rivals do`() {
        val expected = TotpGenerator.decodeBase32(sha1Secret)
        assertContentEquals(expected, TotpGenerator.decodeBase32(sha1Secret.lowercase()))
        assertContentEquals(
            expected,
            TotpGenerator.decodeBase32("gezd gnbv gy3t qojq-gezd gnbv gy3t qojq=")
        )
        assertContentEquals(expected, TotpGenerator.decodeBase32("GEZD\u00A0GNBV\u200BGY3TQOJQGEZDGNBVGY3TQOJQ"))
        // Look-alike digits that are not in the Base32 alphabet can only be typos.
        assertContentEquals(expected, TotpGenerator.decodeBase32(sha1Secret.replace('O', '0').replace('I', '1')))
    }

    @Test
    fun `invalid secrets stay invalid and never leak a code`() {
        assertFalse(TotpGenerator.isValidSecret(""))
        assertFalse(TotpGenerator.isValidSecret("!!!not base32!!!"))
        assertEquals("------", TotpGenerator.generate("!!!", "SHA1"))
        assertEquals("------", TotpGenerator.generate("", "SHA1"))
    }

    @Test
    fun `canonicalSecret normalises without changing the key bytes`() {
        val canonical = TotpGenerator.canonicalSecret("gezdg nbvgy3tqojq-gezdgnbvgY3tqojq=")
        assertNotNull(canonical)
        assertEquals(sha1Secret, canonical)
        assertNull(TotpGenerator.canonicalSecret("????"))
        // The code from a canonical secret equals the code from the sloppy original.
        val sloppy = "jbsw y3dp erhk 3pxp"
        val clean = TotpGenerator.canonicalSecret(sloppy)!!
        assertEquals(
            TotpGenerator.generate(sloppy, "SHA1", 6, 30, 1_234_567_890_000L),
            TotpGenerator.generate(clean, "SHA1", 6, 30, 1_234_567_890_000L)
        )
    }

    @Test
    fun `encodeBase32 round trips the rfc secret`() {
        assertEquals(sha1Secret, TotpGenerator.encodeBase32(TotpGenerator.decodeBase32(sha1Secret)))
        assertTrue(TotpGenerator.decodeBase32(sha1Secret).size == 20)
    }

    @Test
    fun `countdown stays inside the period and never shows zero`() {
        val thirty = TotpGenerator.secondsRemaining(30, 59_000L)
        assertTrue(thirty in 1..30)
        assertEquals(30, TotpGenerator.secondsRemaining(30, 60_000L))
        assertEquals(1, TotpGenerator.secondsRemaining(30, 89_000L))
        assertEquals(60, TotpGenerator.secondsRemaining(60, 60_000L))
        val progress = TotpGenerator.progress(30, 75_000L)
        assertTrue(progress > 0.4f && progress < 0.6f)
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.joinToString()} but was ${actual.joinToString()}",
            expected.contentEquals(actual)
        )
    }
}
