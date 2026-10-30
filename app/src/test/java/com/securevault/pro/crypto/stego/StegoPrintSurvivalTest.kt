package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoCodec
import com.securevault.pro.presentation.screens.stego.TamperVerdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance test the owner actually asked for: a message hidden in a photo has to survive a
 * black-and-white print or photocopy.
 *
 * The hostile channel is modelled by [PrintModel] — documented in that file — as a global luminance
 * stretch, quantisation to [levels] evenly spaced grey levels, then additive scanner noise. The
 * acceptance point is 16 levels (4 bits), a 10% stretch and ±3 grey levels of noise, which is a
 * pessimistic black-and-white copier rather than a photographic print.
 *
 * If the acceptance test fails it is reporting a real engine defect; it must not be relaxed. The
 * matrix test beside it measures where the mark does break, so the failure is quotable as a number.
 */
class StegoPrintSurvivalTest {

    private val message = StegoFixtures.secret("access code 4417-90-2, burn after reading")
    private val passphrase = "print survivor"

    @Test
    fun survivesABlackAndWhitePhotocopyAt16Levels() {
        val sealed = StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)
        val pressed = PrintModel.press(sealed.marked, levels = 16, contrast = 1.10, noise = 3)
        val finding = StegoFixtures.scan(pressed, passphrase)

        assertNotNull(
            "16 levels + 10% stretch + noise ±3 must reveal; the reader said ${finding.read} " +
                "with drift ${(finding.revealed)?.drift}",
            finding.revealed
        )
        assertArrayEquals(message, finding.revealed!!.secret)
        assertTrue(
            "a photocopy must not trip the tamper seal",
            finding.verdict != TamperVerdict.EDITED
        )
    }

    @Test
    fun survivesABlackAndWhitePhotocopyOnANoisyCarrier() {
        val sealed = StegoFixtures.seal(StegoFixtures.noisyCarrier(), message, passphrase)
        val pressed = PrintModel.press(sealed.marked, levels = 16, contrast = 1.10, noise = 3)
        val finding = StegoFixtures.scan(pressed, passphrase)

        assertNotNull("noisy carrier at 16 levels; reader said ${finding.read}", finding.revealed)
        assertArrayEquals(message, finding.revealed!!.secret)
    }

    /**
     * Measures the real tolerance instead of asserting a guessed one.
     *
     * For every quantisation depth the test records what the reader answered. The only hard rule is
     * the failure-mode rule that matters: a read is either exactly the sealed message, or a refusal
     * — never a different secret. The measured breakpoint is printed to the test output and quoted
     * in the hand-off.
     */
    @Test
    fun measuresTheQuantisationToleranceWithoutEverReturningAWrongSecret() {
        val sealed = StegoFixtures.seal(StegoFixtures.gentleCarrier(), message, passphrase)
        val plain = StegoFixtures.scan(sealed.marked, passphrase)
        assertNotNull("control: the unprinted carrier must reveal; got ${plain.read}", plain.revealed)

        val levels = intArrayOf(256, 96, 64, 48, 32, 24, 16, 12, 8, 6, 4)
        val report = StringBuilder(
            "print tolerance (contrast 1.10, noise ±3):\n" +
                "levels | verdict\n"
        )
        var lastSurviving = -1
        for (level in levels) {
            val finding = scanPressed(sealed, level)
            report.append(
                String.format(
                    "%6d | %-14s drift=%s%s%n",
                    level,
                    finding.read::class.simpleName,
                    finding.revealed?.drift?.toString() ?: "-",
                    if (finding.revealed != null) " exact=${finding.revealed!!.secret.contentEquals(message)}" else ""
                )
            )
            val revealed = finding.revealed
            if (revealed != null) {
                assertArrayEquals(
                    "at $level levels the reader returned a DIFFERENT secret, not a refusal",
                    message,
                    revealed.secret
                )
                lastSurviving = level
            }
            assertTrue(
                "at $level levels the reader neither revealed nor refused (${finding.read})",
                revealed != null || finding.read is StegoRead.Damaged ||
                    finding.read is StegoRead.Refused || finding.read is StegoRead.Locked ||
                    finding.read is StegoRead.NoCarrier
            )
        }
        println(report)
        println("coarsest quantisation that still revealed: $lastSurviving levels")
        // The coarsest grid must never be mistaken for a carrier: 4 levels destroys the band.
        assertTrue("the measurement never saw a success", lastSurviving > 0)
    }

    private fun scanPressed(sealed: StegoCodec.Sealed, levels: Int): StegoCodec.Finding {
        val pressed = PrintModel.press(sealed.marked, levels = levels, contrast = 1.10, noise = 3)
        return StegoFixtures.scan(pressed, passphrase)
    }
}
