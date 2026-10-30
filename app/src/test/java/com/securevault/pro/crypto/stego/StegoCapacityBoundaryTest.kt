package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoCapacity
import com.securevault.pro.presentation.screens.stego.StegoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capacity boundary the hide screen promises: a secret exactly at the photo's measured limit
 * seals and reads back, one byte more is refused with the documented exception, and the refusal
 * carries both numbers so the screen can say them out loud.
 */
class StegoCapacityBoundaryTest {

    private val passphrase = "capacity pass"

    @Test
    fun aSecretExactlyAtThePublishedLimitSealsAndReadsBack() {
        val carrier = StegoFixtures.gentleCarrier()
        val limit = StegoCapacity.maxSecretBytes(carrier, envelopeCount = 1)
        assertTrue("the fixture carrier must have a usable capacity", limit > 0)
        assertTrue(limit <= StegoLimits.MAX_SECRET_BYTES)

        val atLimit = ByteArray(limit) { (it * 37 + 11).toByte() }
        val sealed = StegoFixtures.codec().seal(
            StegoEncodeRequest(
                carrier = carrier,
                envelopes = listOf(StegoFixtures.envelope(atLimit, passphrase)),
                salt = StegoFixtures.SALT
            )
        )
        assertEquals(1, sealed.envelopeCount)

        val finding = StegoFixtures.scan(sealed.marked, passphrase)
        val revealed = finding.revealed
        assertNotNull("a secret at the limit must read back; got ${finding.read}", revealed)
        assertArrayEquals(atLimit, revealed!!.secret)
    }

    @Test
    fun oneByteOverTheLimitIsRefusedWithTheDocumentedException() {
        val carrier = StegoFixtures.gentleCarrier()
        val limit = StegoCapacity.maxSecretBytes(carrier, envelopeCount = 1)

        val refused = assertThrows(StegoCapacityException::class.java) {
            StegoFixtures.codec().seal(
                StegoEncodeRequest(
                    carrier = carrier,
                    envelopes = listOf(StegoFixtures.envelope(ByteArray(limit + 1), passphrase)),
                    salt = StegoFixtures.SALT
                )
            )
        }
        assertEquals(limit, refused.maxSecretBytes)
        assertEquals(limit + 1, refused.requestedSecretBytes)
        assertTrue(refused.message!!.contains(limit.toString()))
    }

    @Test
    fun theHardSecretCeilingIsEnforcedEvenOnAHugeCarrier() {
        val carrier = StegoFixtures.gentleCarrier()
        val limit = StegoCapacity.maxSecretBytes(carrier, envelopeCount = 1)

        val refused = assertThrows(StegoCapacityException::class.java) {
            StegoFixtures.codec().seal(
                StegoEncodeRequest(
                    carrier = carrier,
                    envelopes = listOf(
                        StegoFixtures.envelope(ByteArray(StegoLimits.MAX_SECRET_BYTES + 1), passphrase)
                    ),
                    salt = StegoFixtures.SALT
                )
            )
        }
        assertEquals(limit, refused.maxSecretBytes)
        assertEquals(StegoLimits.MAX_SECRET_BYTES + 1, refused.requestedSecretBytes)
    }

    @Test
    fun aCarrierBelowTheMinimumWorkingSizeIsRefusedAsInput() {
        val tooSmall = LumaPlane.filled(
            StegoLimits.MIN_WORKING_LONGEST - 1,
            StegoLimits.MIN_WORKING_LONGEST - 1,
            128
        )
        assertThrows(StegoInputException::class.java) {
            StegoFixtures.seal(tooSmall, StegoFixtures.secret("tiny"), passphrase)
        }
    }

    @Test
    fun aFlatFieldIsRefusedBecauseThereIsNothingToHideAMarkUnder() {
        val flat = LumaPlane.filled(640, 480, 128)
        val refused = assertThrows(StegoInputException::class.java) {
            StegoFixtures.seal(flat, StegoFixtures.secret("tiny"), passphrase)
        }
        assertTrue(
            "the refusal must be the flat-field one, not the size one: ${refused.message}",
            refused.message!!.contains("flat")
        )
    }
}
