package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoCodec
import com.securevault.pro.presentation.screens.stego.StegoFrameShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-password deniability: one photo, two envelopes, no role marker anywhere.
 *
 * The claim under test is the engine's, not the screen's:
 *
 *  - each passphrase opens exactly one envelope and is a dead key for the other;
 *  - both envelopes are padded to the longer secret before sealing, so the same photo holding one
 *    secret and holding two produces the *same sized* frame — one RS chunk either way;
 *  - the shorter secret is not corrupted by that padding (a bug this suite caught: the random
 *    filler used to be written over the first bytes of the secret);
 *  - the typing order, not the image, decides which message a two-passphrase read presents.
 */
class StegoDeniabilityTest {

    private val secretA = StegoFixtures.secret("alpha: the real vault is at 12 Rue")
    private val secretB = StegoFixtures.secret("bravo: nothing to see here, move along")
    private val passA = "alpha-pass"
    private val passB = "bravo-pass"

    private fun sealOne(): StegoCodec.Sealed = StegoFixtures.codec().seal(
        StegoEncodeRequest(
            carrier = StegoFixtures.gentleCarrier(),
            envelopes = listOf(StegoFixtures.envelope(secretA, passA)),
            salt = StegoFixtures.SALT
        )
    )

    private fun sealTwo(): StegoCodec.Sealed = StegoFixtures.codec().seal(
        StegoEncodeRequest(
            carrier = StegoFixtures.gentleCarrier(),
            envelopes = listOf(
                StegoFixtures.envelope(secretA, passA),
                StegoFixtures.envelope(secretB, passB)
            ),
            salt = StegoFixtures.SALT
        )
    )

    @Test
    fun eachPassphraseOpensOnlyItsOwnEnvelope() {
        val sealed = sealTwo()
        assertEquals(2, sealed.envelopeCount)

        val withA = StegoFixtures.scan(sealed.marked, passA)
        val withB = StegoFixtures.scan(sealed.marked, passB)
        assertArrayEquals("passphrase A must return A", secretA, withA.revealed!!.secret)
        assertArrayEquals("passphrase B must return B", secretB, withB.revealed!!.secret)
        assertEquals(0, withA.revealed!!.openedWithKey)
        // `openedWithKey` numbers the *offered* passphrase, not the block, so B typed alone is key 0.
        assertEquals(0, withB.revealed!!.openedWithKey)
        assertEquals(
            "B lives on the second block, even though it was the first key offered",
            1,
            StegoFixtures.codec().openAll(sealed.marked, listOf(passB.toCharArray())).single().blockIndex
        )

        val wrong = StegoFixtures.scan(sealed.marked, "neither of them")
        assertNotNull("a third passphrase must answer Locked, got ${wrong.read}", wrong.locked)
        assertNull(wrong.revealed)
    }

    @Test
    fun theShorterSecretSurvivesThePaddingIntact() {
        // A is 34 bytes, B is 38: the two-envelope carrier pads A by four bytes. The filler must go
        // after the secret, never over its first bytes.
        assertTrue("the fixture must exercise padding", secretA.size < secretB.size)
        val sealed = sealTwo()

        val openedA = StegoFixtures.codec().openAll(sealed.marked, listOf(passA.toCharArray()))
        assertEquals(1, openedA.size)
        assertEquals(secretA.size, openedA[0].secret.size)
        assertArrayEquals(secretA, openedA[0].secret)

        val openedB = StegoFixtures.codec().openAll(sealed.marked, listOf(passB.toCharArray()))
        assertEquals(1, openedB.size)
        assertArrayEquals(secretB, openedB[0].secret)
    }

    @Test
    fun bothPassphrasesFindBothEnvelopesAndNothingExtra() {
        val sealed = sealTwo()
        val openings = StegoFixtures.codec().openAll(
            sealed.marked, listOf(passA.toCharArray(), passB.toCharArray())
        )
        assertEquals("every offered key must open exactly one block", 2, openings.size)
        assertTrue(openings.any { it.keyIndex == 0 && it.secret.contentEquals(secretA) })
        assertTrue(openings.any { it.keyIndex == 1 && it.secret.contentEquals(secretB) })
        // The first key opens A only, the second B only: neither key is a master key.
        assertTrue(openings.none { it.keyIndex == 0 && it.secret.contentEquals(secretB) })
        assertTrue(openings.none { it.keyIndex == 1 && it.secret.contentEquals(secretA) })
    }

    @Test
    fun theTypingOrderDecidesWhichMessageIsPresented() {
        val sealed = sealTwo()

        val aFirst = StegoFixtures.scan(sealed.marked, passA, passB)
        assertArrayEquals(secretA, aFirst.revealed!!.secret)
        assertEquals(0, aFirst.revealed!!.openedWithKey)

        val bFirst = StegoFixtures.scan(sealed.marked, passB, passA)
        assertArrayEquals(secretB, bFirst.revealed!!.secret)
        assertEquals(0, bFirst.revealed!!.openedWithKey)
    }

    @Test
    fun oneAndTwoEnvelopeCarriersHaveTheSameShape() {
        val one = sealOne()
        val two = sealTwo()

        // Both blocks pad to the longer secret, so both carriers need exactly one 255-byte RS
        // chunk: the frame the photo carries is the same size whether it holds one secret or two.
        val padded = StegoFrameShape(listOf(secretA.size, secretB.size)).paddedBytes
        assertEquals(secretB.size, padded)
        // One envelope pads only to its own secret, so its payload is four bytes smaller; the point of
        // the deniability claim is that both payloads still fit the same single RS chunk.
        val blockOnWire = 2 + 56 + padded
        assertEquals(
            StegoLimits.META_BYTES + 1 + 2 + 56 + secretA.size,
            one.payloadBytes
        )
        assertEquals(
            StegoLimits.META_BYTES + 1 + 2 * blockOnWire,
            two.payloadBytes
        )

        assertEquals("frame size must not grow with the second envelope", one.frameBytes, two.frameBytes)
        assertEquals(one.plan.frameBits, two.plan.frameBits)
        assertEquals(one.plan.lattice, two.plan.lattice)
        assertEquals(one.chipRate, two.chipRate)
        assertEquals(one.marked.width, two.marked.width)
        assertEquals(one.marked.height, two.marked.height)
        assertEquals(one.marked.samples.size, two.marked.samples.size)
        assertEquals(1, one.envelopeCount)
        assertEquals(2, two.envelopeCount)
    }
}
