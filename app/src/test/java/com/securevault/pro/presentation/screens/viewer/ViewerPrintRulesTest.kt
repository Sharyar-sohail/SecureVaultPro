package com.securevault.pro.presentation.screens.viewer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only decision the viewer's Print bar makes: print, or disable with the line the owner reads.
 * It is pure, so it is pinned here — the device's print preview cannot be exercised on the JVM.
 */
class ViewerPrintRulesTest {

    @Test
    fun `pdf image and text are printable`() {
        assertNull(printBlockedReason(ViewerKind.PDF, hasText = false, printAvailable = true))
        assertNull(printBlockedReason(ViewerKind.IMAGE, hasText = false, printAvailable = true))
        assertNull(printBlockedReason(ViewerKind.TEXT, hasText = true, printAvailable = true))
    }

    @Test
    fun `audio and video are refused by name`() {
        val audio = printBlockedReason(ViewerKind.AUDIO, hasText = false, printAvailable = true)
        val video = printBlockedReason(ViewerKind.VIDEO, hasText = false, printAvailable = true)

        assertNotNull(audio)
        assertNotNull(video)
        assertTrue("the audio reason names audio", audio!!.contains("audio"))
        assertTrue("the video reason names video", video!!.contains("video"))
    }

    @Test
    fun `office text is printable only when text was extracted`() {
        assertNull(printBlockedReason(ViewerKind.OFFICE, hasText = true, printAvailable = true))
        assertNotNull(printBlockedReason(ViewerKind.OFFICE, hasText = false, printAvailable = true))
    }

    @Test
    fun `no print service disables an otherwise printable page`() {
        val reason = printBlockedReason(ViewerKind.PDF, hasText = false, printAvailable = false)

        assertNotNull(reason)
        assertTrue(reason!!.contains("print service"))
    }
}
