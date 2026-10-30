package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoBranch
import com.securevault.pro.presentation.screens.stego.stegoBranchOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seal → scan on a synthetic luminance carrier, the path the hide screen and the reveal screen take.
 *
 * The message is deliberately short: any payload under 224 bytes is one Reed–Solomon chunk, so
 * these tests pin the whole pipeline (envelope → frame → RS → directory → QIM chips → LL delta →
 * pixels → back) without also being a capacity test. The carrier is synthetic and seeded, so every
 * number below is reproducible on a bare JVM.
 */
class StegoCodecRoundTripTest {

    private val message = StegoFixtures.secret("meet at the usual place at nine; bring the ledger")
    private val passphrase = "correct horse battery staple"

    @Test
    fun gentleCarrierRevealsTheExactSecret() {
        val sealed = StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)
        val finding = StegoFixtures.scan(sealed.marked, passphrase)

        val revealed = finding.revealed
        assertNotNull("Expected Revealed, got ${finding.read}", revealed)
        assertArrayEquals(message, revealed!!.secret)
        assertEquals(SecretKind.MESSAGE, revealed.kind)
        assertEquals(0, revealed.openedWithKey)
        assertTrue("a clean carrier must report a measured drift, not -1", revealed.drift >= 0)
        assertEquals(1, sealed.envelopeCount)
        assertEquals(StegoBranch.REVEALED, stegoBranchOf(finding.read))
    }

    @Test
    fun noisyCarrierRevealsTheExactSecret() {
        val sealed = StegoFixtures.seal(StegoFixtures.noisyCarrier(), message, passphrase)
        val finding = StegoFixtures.scan(sealed.marked, passphrase)

        val revealed = finding.revealed
        assertNotNull("Expected Revealed, got ${finding.read}", revealed)
        assertArrayEquals(message, revealed!!.secret)
        assertEquals(0, revealed.openedWithKey)
        assertEquals(StegoBranch.REVEALED, stegoBranchOf(finding.read))
    }

    @Test
    fun readOnceSealReportsItsFlagAndTheReadOnceBranch() {
        val sealed = StegoFixtures.seal(
            StegoFixtures.gentleCarrier(), message, passphrase, oneRead = true
        )
        val finding = StegoFixtures.scan(sealed.marked, passphrase)

        val revealed = finding.revealed
        assertNotNull("Expected Revealed, got ${finding.read}", revealed)
        assertTrue(revealed!!.oneRead)
        assertEquals(StegoBranch.READ_ONCE_REVEALED, stegoBranchOf(finding.read))
    }

    @Test
    fun wrongPassphraseLocksTheCarrierAndOpensNothing() {
        val sealed = StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)
        val finding = StegoFixtures.scan(sealed.marked, "not the passphrase")

        assertNull("a wrong key must not produce a revealed secret", finding.revealed)
        assertNotNull("expected Locked, got ${finding.read}", finding.locked)
        assertEquals(StegoBranch.LOCKED, stegoBranchOf(finding.read))

        // The same wrong key against every block of the frame must return no plaintext at all.
        val openings = StegoFixtures.codec().openAll(
            sealed.marked, listOf("not the passphrase".toCharArray())
        )
        assertTrue("a wrong key opened ${openings.size} envelopes", openings.isEmpty())
    }

    @Test
    fun theCorrectPassphraseInTheSecondSlotStillOpensASingleEnvelope() {
        val sealed = StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)
        val finding = StegoFixtures.scan(sealed.marked, "not the passphrase", passphrase)

        assertNotNull(
            "the second offered passphrase must be tried against every block; got ${finding.read}",
            finding.revealed
        )
        assertArrayEquals(message, finding.revealed!!.secret)
        assertEquals(1, finding.revealed!!.openedWithKey)
    }

    @Test
    fun anUnmarkedPhotographIsNotACarrier() {
        val plain = StegoFixtures.gentleCarrier()
        val finding = StegoFixtures.scan(plain, passphrase)
        assertTrue("an ordinary photo must not parse as a carrier", finding.read is StegoRead.NoCarrier)
        assertNull(StegoFixtures.codec().probe(plain))
    }

    @Test
    fun theMarkStaysWithinAFewGreyLevelsAndReallyChangesThePixels() {
        val carrier = StegoFixtures.gentleCarrier()
        val sealed = StegoFixtures.seal(carrier, message, passphrase)

        assertFalse("a sealed carrier must differ from its source", sealed.marked.samples.contentEquals(carrier.samples))
        assertTrue(
            "the written mark peaked at ${sealed.peakGreyLevel} grey levels; a visible rewrite is not hidden",
            sealed.peakGreyLevel <= 16.0f
        )
        // One RS chunk is 255 bytes, of which 223 are payload: the frame this short message needs
        // fits in that single chunk, which is what makes the plan deterministic.
        assertEquals(255, sealed.frameBytes)
        assertEquals(255 * 8, sealed.plan.frameBits)
    }
}
