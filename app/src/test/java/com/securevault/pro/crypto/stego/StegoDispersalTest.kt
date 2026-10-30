package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.SecretShares
import com.securevault.pro.presentation.screens.stego.StegoBranch
import com.securevault.pro.presentation.screens.stego.StegoCodec
import com.securevault.pro.presentation.screens.stego.stegoBranchOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3-image dispersal: every carrier holds one full-length GF(256) share, and the message only exists
 * once all three are back.
 *
 * The shares are produced by the engine's own [StegoCodec.sharesFor] (a split over GF(256), not a
 * key), sealed into three carriers, read back through the real scan path, and finally joined. A
 * single share is asserted to be *uninformative*: it is a random-looking field element per byte and
 * says nothing about the secret, which is the property that makes the other two carriers optional
 * for an examiner rather than for the owner.
 */
class StegoDispersalTest {

    private val message = StegoFixtures.secret("three images, one message: 0xDEADBEEF")
    private val passphrase = "share-pass"
    private val codec = StegoFixtures.codec()

    @Test
    fun sharesForThreeProducesThreeFullLengthDistinctShares() {
        val shares = codec.sharesFor(message, 3)
        assertEquals(3, shares.size)
        assertTrue(shares.all { it.size == message.size })
        assertEquals("three shares must be distinct", 3, shares.map { it.toList() }.toSet().size)
        assertTrue("no share may be the secret itself", shares.none { it.contentEquals(message) })
        assertTrue(SecretShares.revealsNothing(2, 3))
        assertFalse(SecretShares.revealsNothing(3, 3))
    }

    @Test
    fun oneShareReadsAsAShareAndDoesNotRevealTheSecret() {
        val shares = codec.sharesFor(message, 3)
        val share = shares[0]
        val carrier = codec.sealCarrier(
            carrier = StegoFixtures.gentleCarrier(),
            primary = share,
            primaryPassphrase = passphrase.toCharArray(),
            shareTotal = 3,
            shareIndex = 0,
            salt = StegoFixtures.SALT
        )
        val finding = codec.scan(StegoDecodeRequest(carrier.marked, listOf(passphrase.toCharArray())))

        val recovered = finding.share
        assertNotNull("expected ShareRecovered, got ${finding.read}", recovered)
        assertEquals(0, recovered!!.shareIndex)
        assertEquals(3, recovered.shareTotal)
        assertArrayEquals("the share bytes must survive the carrier intact", share, recovered.shareBytes)
        assertFalse("a single share must not equal the secret", recovered.shareBytes.contentEquals(message))
        assertEquals(StegoBranch.SHARE_PARTIAL, stegoBranchOf(finding.read))
        assertNull("two of three shares must not rebuild the message", SecretShares.join(listOf(
            SecretShares.Share(0, shares[0]),
            SecretShares.Share(1, shares[1])
        ), 3))
    }

    @Test
    fun allThreeCarriersJoinBackToTheExactSecret() {
        val shares = codec.sharesFor(message, 3)
        val recovered = ArrayList<SecretShares.Share>(3)
        shares.forEachIndexed { index, share ->
            val carrier = codec.sealCarrier(
                carrier = StegoFixtures.gentleCarrier(),
                primary = share,
                primaryPassphrase = passphrase.toCharArray(),
                shareTotal = 3,
                shareIndex = index,
                salt = StegoFixtures.SALT
            )
            val finding = codec.scan(StegoDecodeRequest(carrier.marked, listOf(passphrase.toCharArray())))
            val read = finding.share
            assertNotNull("share $index did not read; got ${finding.read}", read)
            assertEquals(index, read!!.shareIndex)
            assertEquals(3, read.shareTotal)
            assertTrue("share $index came back corrupted", read.shareBytes.contentEquals(share))
            recovered += SecretShares.Share(read.shareIndex, read.shareBytes)
        }

        assertEquals(3, recovered.size)
        val joined = SecretShares.join(recovered, 3)
        assertNotNull("a complete set must rebuild", joined)
        assertArrayEquals(message, joined)
    }

    @Test
    fun sharesCannotBeJoinedWithoutDeclaringTheSetSize() {
        val shares = codec.sharesFor(message, 3)
        assertNull(SecretShares.join(emptyList(), 3))
        assertNull(SecretShares.join(listOf(SecretShares.Share(0, shares[0])), 3))
        assertNull(SecretShares.join(listOf(
            SecretShares.Share(0, shares[0]),
            SecretShares.Share(1, shares[1])
        ), 3))
        assertNotNull(SecretShares.join(shares.mapIndexed { index, bytes ->
            SecretShares.Share(index, bytes)
        }, 3))
    }
}
