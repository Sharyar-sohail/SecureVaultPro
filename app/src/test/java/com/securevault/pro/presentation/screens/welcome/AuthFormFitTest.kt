package com.securevault.pro.presentation.screens.welcome

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.screens.lock.lockFormHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard for the clipped action button on the two auth screens.
 *
 * Both screens pick a *tier* from the height they are given, and a mis-read tier used to cut the
 * primary button off below the fold: the weighted spacers collapsed to nothing, the column had no
 * scroll container, and Compose clips whatever does not fit the window. The screens now ask a
 * second, separate question — does the content this tier draws actually fit? — and answer it with
 * dp maths over the block heights the shared components themselves document. `MAX_FONT_SCALE` is
 * 1.0 (theme/Type.kt), so nothing inflates underneath these numbers.
 *
 * A failing test here means the promise is wrong: the estimate said a form fits a window it cannot
 * draw, or it said a form does not fit a window that had room to spare and stole the centred
 * layout for nothing.
 *
 * Pure JVM: no Robolectric, no Compose runtime, no device.
 */
class AuthFormFitTest {

    /** The board's height, pinned to the panel's own constants (SecureKeyboardPanel.kt). */
    @Test
    fun `the docked secure board measures the height the panel draws`() {
        val grid = 62.dp * 4 + 5.dp * 3     // four dialer rows and the gaps between them
        val header = 26.dp + 5.dp           // the layer header, plus the gap under it
        val chrome = 6.dp * 2 + 1.dp * 2    // the board's padding and its border
        assertEquals(grid + header + chrome, SECURE_BOARD_HEIGHT)
        assertEquals(308.dp, SECURE_BOARD_HEIGHT)
    }

    // -----------------------------------------------------------------------------------------
    // The decision itself.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `content exactly as tall as the window does not get a scroll container`() {
        assertFalse(authFormNeedsScroll(available = 500.dp, content = 500.dp))
        assertFalse(authFormNeedsScroll(available = 500.dp, content = 499.dp))
    }

    @Test
    fun `one dp more than the window scrolls`() {
        assertTrue(authFormNeedsScroll(available = 500.dp, content = 501.dp))
        assertTrue(authFormNeedsScroll(available = 0.dp, content = 1.dp))
    }

    @Test
    fun `a window shorter than the board still reports a viewport, never a negative one`() {
        assertEquals(0.dp, viewportAboveBoard(afterBars = 200.dp))
        assertEquals(0.dp, viewportAboveBoard(afterBars = 0.dp))
        assertEquals(436.dp, viewportAboveBoard(afterBars = 744.dp))
        assertEquals(744.dp, viewportAboveBoard(afterBars = 744.dp, board = 0.dp))
    }

    // -----------------------------------------------------------------------------------------
    // The windows the reported phones actually give these screens.
    //
    //   OPPO A-class 720x1600 @2.0  -> 360x800dp: 744dp after a 32dp status bar and a 24dp
    //     gesture bar; the master-password board takes 308dp of that, leaving 436dp.
    //   Common 1080x2400 @2.625     -> 412x914dp: 858dp after the bars, 550dp with the board.
    //   Small 720x1280 @2.0         -> 360x640dp: 584dp after the bars, 276dp with the board.
    //   Split view on the A-class   -> 380dp after the bars, 72dp with the board.
    //   A 240dp-tall strip          -> the board alone outgrows it, so the viewport is zero.
    // -----------------------------------------------------------------------------------------

    private data class Window(val name: String, val afterBars: Dp)

    private val phones = listOf(
        Window("OPPO A-class 720x1600 (360x800dp)", 744.dp),
        Window("common 1080x2400 mid-range (412x914dp)", 858.dp),
        Window("small 720x1280 (360x640dp)", 584.dp),
        Window("split view on the A-class", 380.dp),
        Window("240dp-tall strip", 240.dp)
    )

    /** Everything one step can put on screen at once: banner, explanation, mismatch, fault. */
    private fun loadedSetup(step: Int, compact: Boolean, dense: Boolean) = setupFormHeight(
        step = step,
        compact = compact,
        dense = dense,
        notice = true,
        error = true,
        mismatch = true,
        longCopy = true,
        biometric = true
    )

    @Test
    fun `the dense master step is what the keyboard-docked A-class window really has to hold`() {
        val viewport = viewportAboveBoard(744.dp)
        assertEquals(436.dp, viewport)
        val plain = setupFormHeight(step = 1, compact = true, dense = true)
        // Nothing extra is showing and the dense form pays for itself out of 436dp, so no scroll
        // container is added: the layout the app already shipped is kept where it worked.
        assertTrue("dense master form ($plain) must fit $viewport", plain < viewport)
        assertFalse(authFormNeedsScroll(viewport, plain))

        // The same window once the restore banner, the long explanation, the mismatch line and a
        // fault are stacked: this is the state that used to lose the button below the fold.
        val busy = setupFormHeight(
            step = 1,
            compact = true,
            dense = true,
            notice = true,
            error = true,
            mismatch = true,
            longCopy = true
        )
        assertTrue("banner + error + long copy ($busy) must exceed $viewport", busy > viewport)
        assertTrue(authFormNeedsScroll(viewport, busy))
    }

    @Test
    fun `a loaded step beats every board-docked window on the phones that report one`() {
        for (window in phones) {
            val afterBoard = viewportAboveBoard(window.afterBars)
            for (step in listOf(1, 2)) {
                val loaded = loadedSetup(step = step, compact = true, dense = false)
                assertTrue(
                    "step $step, compact tier, loaded ($loaded) in ${window.name} ($afterBoard dp)",
                    authFormNeedsScroll(afterBoard, loaded)
                )
            }
        }
    }

    @Test
    fun `a window with room for the form keeps its centred weighted layout`() {
        val tall = 858.dp // 1080x2400 mid-range, bars only: regular tier, board away
        for (step in listOf(1, 2)) {
            val plain = setupFormHeight(step = step)
            assertFalse(
                "step $step ($plain) fits $tall and must not be given a scroll container",
                authFormNeedsScroll(tall, plain)
            )
            // Fully loaded, the same phone genuinely does not fit — which is the whole point.
            assertTrue(
                "step $step loaded (${loadedSetup(step, false, false)}) must scroll on $tall",
                authFormNeedsScroll(tall, loadedSetup(step, compact = false, dense = false))
            )
        }
    }

    @Test
    fun `a short window with the board up scrolls instead of hiding the button`() {
        val plainDense = setupFormHeight(step = 1, compact = true, dense = true)
        // The 720x1280 phone with the board docked has 276dp; even the dense master step needs
        // more, and it used to be clipped there.
        assertTrue(authFormNeedsScroll(viewportAboveBoard(584.dp), plainDense))
        assertTrue(authFormNeedsScroll(viewportAboveBoard(380.dp), plainDense))
        // A strip shorter than the board leaves no viewport at all.
        assertEquals(0.dp, viewportAboveBoard(240.dp))
        assertTrue(authFormNeedsScroll(viewportAboveBoard(240.dp), plainDense))
        assertTrue(
            authFormNeedsScroll(
                viewportAboveBoard(240.dp),
                lockFormHeight(
                    ready = true,
                    biometricGate = false,
                    masterMode = true,
                    compact = true,
                    dense = true
                )
            )
        )
    }

    @Test
    fun `the estimate grows with everything the screen can add`() {
        val bare = setupFormHeight(step = 1)
        assertTrue(setupFormHeight(step = 1, notice = true) > bare)
        assertTrue(setupFormHeight(step = 1, error = true) > bare)
        assertTrue(setupFormHeight(step = 1, mismatch = true) > bare)
        assertTrue(setupFormHeight(step = 1, longCopy = true) > bare)
        assertTrue(setupFormHeight(step = 2, biometric = true) > setupFormHeight(step = 2))
        // Each tier draws a shorter form than the one above it.
        val compact = setupFormHeight(step = 1, compact = true)
        val dense = setupFormHeight(step = 1, compact = true, dense = true)
        assertTrue("dense ($dense) must be shorter than compact ($compact)", dense < compact)
        assertTrue("compact ($compact) must be shorter than the regular form ($bare)", compact < bare)
    }

    // -----------------------------------------------------------------------------------------
    // The unlock screen: pad, secure field, or biometric button.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the pad at its regular size fits a tall phone without scrolling`() {
        val viewport = 858.dp
        val height = lockFormHeight(ready = true, biometricGate = false, masterMode = false)
        assertFalse("regular pad ($height) fits $viewport", authFormNeedsScroll(viewport, height))
    }

    @Test
    fun `the keypad shrinks with the tier, so the pad is not what forces the scroll`() {
        val regular = lockFormHeight(ready = true, biometricGate = false, masterMode = false)
        val compact = lockFormHeight(
            ready = true, biometricGate = false, masterMode = false, compact = true
        )
        val dense = lockFormHeight(
            ready = true, biometricGate = false, masterMode = false, compact = true, dense = true
        )
        assertTrue("compact $compact must be shorter than the regular pad $regular", compact < regular)
        assertTrue("dense $dense must be shorter again", dense < compact)
        // The dense pad still needs more than a 240dp split-view window once the bars are gone.
        assertTrue(authFormNeedsScroll(240.dp, dense))
        assertFalse(authFormNeedsScroll(744.dp, dense))
    }

    @Test
    fun `master password with the board docked reaches its unseal button`() {
        // The A-class case from the report: 744dp after the bars, and the board takes 308dp of them.
        val viewport = viewportAboveBoard(744.dp)
        val master = lockFormHeight(
            ready = true,
            biometricGate = false,
            masterMode = true,
            compact = true,
            dense = true
        )
        assertFalse("plain master unlock ($master) fits $viewport", authFormNeedsScroll(viewport, master))

        // The same window with a lock-out and a long OEM fault on top: it no longer fits, and the
        // decision says so instead of letting the fold cut the button off.
        val loaded = lockFormHeight(
            ready = true,
            biometricGate = false,
            masterMode = true,
            lockedOut = true,
            messageLength = 240,
            biometricEntry = true,
            compact = true,
            dense = true
        )
        assertTrue("loaded master unlock $loaded must exceed $viewport", loaded > viewport)
        assertTrue(authFormNeedsScroll(viewport, loaded))
    }

    @Test
    fun `a long fault message is counted in full, not ellipsised away`() {
        val shortMessage = lockFormHeight(
            ready = true, biometricGate = false, masterMode = false, messageLength = 20
        )
        val longMessage = lockFormHeight(
            ready = true, biometricGate = false, masterMode = false, messageLength = 180
        )
        // 180 characters at the line's own measure is five more wrapped lines than 20, and each
        // one is paid for in the height the screen asks for.
        assertTrue(longMessage - shortMessage >= 16.dp * 4)
    }

    @Test
    fun `the biometric branch draws less than the pad`() {
        val withPad = lockFormHeight(ready = true, biometricGate = false, masterMode = false)
        val withField = lockFormHeight(ready = true, biometricGate = false, masterMode = true)
        val biometric = lockFormHeight(ready = true, biometricGate = true, masterMode = false)
        val loading = lockFormHeight(ready = false, biometricGate = false, masterMode = false)
        assertTrue("biometric ($biometric) must draw less than the pad ($withPad)", biometric < withPad)
        assertTrue("still unsealing ($loading) must not outdraw the pad", loading < withPad)
        assertTrue("secure field ($withField) must draw less than the pad", withField < withPad)
    }
}
