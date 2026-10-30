package com.securevault.pro.presentation.settings

import com.securevault.pro.data.remote.companion.CompanionUrl
import com.securevault.pro.presentation.screens.settings.CompanionPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPairingTest {

    @Test
    fun `payload round trips the address and code`() {
        val payload = CompanionPairing.payload("https://securevaultpro.blogspot.com", "K7M2QX")
        assertEquals("https://securevaultpro.blogspot.com#pair=K7M2QX", payload)
        val parsed = CompanionPairing.parse(payload)
        assertNotNull(parsed)
        assertEquals("https://securevaultpro.blogspot.com", parsed!!.first)
        assertEquals("K7M2QX", parsed.second)
    }

    /**
     * The web companion builds its own pairing QR in JavaScript with
     * `pageOrigin() + '#pair=' + code`. Pinned literally so a format change on either
     * side fails here, not on a phone.
     */
    @Test
    fun `the web companion QR format is accepted verbatim`() {
        val parsed = CompanionPairing.parse("https://securevaultpro.blogspot.com#pair=Z6YCMR")
        assertNotNull(parsed)
        assertEquals("https://securevaultpro.blogspot.com", parsed!!.first)
        assertEquals("Z6YCMR", parsed.second)
    }

    /** Codes already printed on old pairing screens must still scan. */
    @Test
    fun `the superseded custom-scheme payload still pairs`() {
        val parsed = CompanionPairing.parse(
            "securewallet://pair?u=https%3A%2F%2Fsecurevaultpro.blogspot.com&c=Z6YCMR"
        )
        assertNotNull(parsed)
        assertEquals("https://securevaultpro.blogspot.com", parsed!!.first)
        assertEquals("Z6YCMR", parsed.second)
    }

    @Test
    fun `a site path before the code survives the scan`() {
        val parsed = CompanionPairing.parse("https://example.com/vault/#pair=AB23CD")
        assertEquals("https://example.com/vault" to "AB23CD", parsed)
    }

    @Test
    fun `trailing slashes never leak into the stored address`() {
        val parsed = CompanionPairing.parse(
            CompanionPairing.payload("https://example.com/vault/", "ABCDE2")
        )
        assertEquals("https://example.com/vault", parsed?.first)
    }

    @Test
    fun `plain https links pair without a code`() {
        val parsed = CompanionPairing.parse("https://vault.example.org/")
        assertEquals("https://vault.example.org" to "", parsed)
    }

    @Test
    fun `cleartext and malformed payloads are refused`() {
        assertNull(CompanionPairing.parse("http://insecure.example.com"))
        assertNull(CompanionPairing.parse("securewallet://pair?u=http://insecure.example.com&c=ABC234"))
        assertNull(CompanionPairing.parse("securewallet://other?u=https://ok.example.com"))
        assertNull(CompanionPairing.parse("not a url at all"))
        assertTrue(CompanionPairing.rejectReason("http://x.example.com").contains("https://"))
    }

    @Test
    fun `cleartext stays limited to the hosts the manifest whitelists`() {
        assertTrue(CompanionUrl.isTrustworthy("http://localhost:8787"))
        assertTrue(CompanionUrl.isTrustworthy("http://127.0.0.1:8787"))
        assertTrue(CompanionUrl.isTrustworthy("http://10.0.2.2:8080"))
        assertFalse(CompanionUrl.isTrustworthy("http://192.168.1.20:8787"))
        assertFalse(CompanionUrl.isTrustworthy("ftp://example.com"))
        assertFalse(CompanionUrl.isTrustworthy(""))
    }

    @Test
    fun `codes avoid look-alike characters`() {
        repeat(40) {
            val code = CompanionPairing.newCode()
            assertEquals(6, code.length)
            assertTrue(code, code.matches(Regex("^[A-HJ-NP-Z2-9]{6}$")))
        }
    }
}
