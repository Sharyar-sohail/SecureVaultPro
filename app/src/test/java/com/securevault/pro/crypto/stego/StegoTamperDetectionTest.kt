package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tamper detection: the reader must answer "damaged" or "refused" — never hand back a secret it
 * cannot stand behind.
 *
 * Two attacks are used because they destroy different things:
 *
 *  - a contiguous repaint, which is what an editor's fill/clone tool leaves behind. Big enough to
 *    overwhelm Reed–Solomon, small enough that the directory still points at the payload.
 *  - a rewritten ring directory, which is a valid CRC-8 directory that lies about the payload
 *    length. The payload bits are untouched; only the instruction of how to read them is forged.
 *
 * A small edit inside the carrier's own tolerance is allowed through by design, and is checked to
 * still return the *correct* secret — that is the "never silently wrong" half of the requirement.
 */
class StegoTamperDetectionTest {

    private val message = StegoFixtures.secret("tamper-evident payload: 7731")
    private val passphrase = "tamper pass"

    private fun sealed(): StegoCodec.Sealed =
        StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)

    @Test
    fun theUntouchedCarrierStillReveals() {
        val sealed = sealed()
        val finding = StegoFixtures.scan(sealed.marked, passphrase)
        assertNotNull("control read failed: ${finding.read}", finding.revealed)
        assertArrayEquals(message, finding.revealed!!.secret)
    }

    @Test
    fun aLargeContiguousRepaintIsRefusedOrReportedDamaged() {
        val sealed = sealed()
        val repainted = StegoFixtures.painted(
            plane = sealed.marked,
            left = 40, top = 40, width = 160, height = 160, value = 128
        )
        val finding = StegoFixtures.scan(repainted, passphrase)

        assertNull("a repainted carrier must not reveal", finding.revealed)
        assertTrue(
            "a 160x160 repaint must be Damaged or Refused, got ${finding.read}",
            finding.read is StegoRead.Damaged || finding.read is StegoRead.Refused
        )
    }

    @Test
    fun aRewrittenDirectoryIsRefusedOrReportedDamaged() {
        val sealed = sealed()
        val band = HaarLl.of(sealed.marked)
        val lattice = CanonicalLattice(sealed.plan.side, band.cellsWide, band.cellsHigh)
        // A CRC-valid directory that claims a 100-bit frame instead of the 2040 bits really there.
        val forged = StegoFrame.packDirectory(
            StegoFrame.Directory(
                mode = StegoMode.SINGLE,
                shareTotal = 1,
                shareIndex = 0,
                chipRate = sealed.chipRate,
                lattice = sealed.plan.lattice,
                frameBits = 100,
                oneRead = false
            )
        )
        val delta = MarkWriter.write(
            band = band,
            lattice = lattice,
            directoryBits = StegoBits.toBits(forged),
            payloadBits = IntArray(0),
            chipRate = sealed.chipRate,
            step = StegoLimits.QIM_STEP
        )
        val rewritten = HaarLl.synthesize(sealed.marked, delta)

        val finding = StegoFixtures.scan(rewritten, passphrase)
        assertNull("a forged directory must not reveal", finding.revealed)
        assertTrue(
            "a forged directory must be Damaged or Refused, got ${finding.read}",
            finding.read is StegoRead.Damaged || finding.read is StegoRead.Refused
        )
    }

    @Test
    fun aSmallEditInsideToleranceNeverProducesAWrongSecret() {
        val sealed = sealed()
        val touched = StegoFixtures.painted(
            plane = sealed.marked,
            left = 40, top = 40, width = 64, height = 64, value = 128
        )
        val finding = StegoFixtures.scan(touched, passphrase)

        // The engine's own tolerance may allow this read through (drift 1 of 36 regions, below the
        // 5-region seal); what it may never do is return different bytes.
        if (finding.revealed != null) {
            assertArrayEquals(
                "a tolerated edit must still return the sealed message, not a different one",
                message,
                finding.revealed!!.secret
            )
        }
    }
}
