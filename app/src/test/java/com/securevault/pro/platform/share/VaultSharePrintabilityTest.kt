package com.securevault.pro.platform.share

import com.securevault.pro.platform.print.PrintSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The decision "can this reach paper" is pure name-based logic, and it is the only thing a screen
 * consults before enabling its Print button. A wrong answer here is either a dead button that
 * silently does nothing or a promise the print pipeline then refuses, so the three families the
 * printer really handles — PDF, image, text — are pinned here, together with the three it does not.
 */
class VaultSharePrintabilityTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pdf image and text documents are printable`() {
        listOf("scan_20261030.pdf", "receipt.JPG", "page.png", "notes.txt", "list.csv", "dump.log")
            .forEach { name ->
                assertTrue("$name should be printable", VaultShare.isPrintable(name))
            }
    }

    @Test
    fun `archives media and the sealed container are not printable`() {
        listOf("backup.zip", "clip.mp4", "voice.mp3", "vault-file.svf", "report.docx", "noextension")
            .forEach { name ->
                assertTrue("$name should not be printable", !VaultShare.isPrintable(name))
            }
    }

    @Test
    fun `text file becomes a text print source rather than unprintable`() {
        val file = folder.newFile("notes.txt")
        file.writeText("first line\nsecond line")

        val source = PrintSource.ofFile(file, ShareMime.guess(file.name))

        assertTrue("a .txt must be routed to the text composer", source is PrintSource.TextBytes)
        assertEquals(file.name, source.displayName)
    }

    @Test
    fun `pdf and jpeg keep their dedicated print sources`() {
        val pdf = folder.newFile("scan.pdf").apply { writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) }
        val jpeg = folder.newFile("page.jpg").apply { writeBytes(byteArrayOf(0x01, 0x02)) }

        assertTrue(PrintSource.ofFile(pdf, ShareMime.guess(pdf.name)) is PrintSource.PdfFile)
        assertTrue(PrintSource.ofFile(jpeg, ShareMime.guess(jpeg.name)) is PrintSource.StegoImage)
    }

    @Test
    fun `a type the printer cannot rasterise is reported, never silently accepted`() {
        val video = folder.newFile("clip.mp4").apply { writeBytes(byteArrayOf(0x01, 0x02)) }

        val source = PrintSource.ofFile(video, ShareMime.guess(video.name))

        assertTrue(source is PrintSource.Unsupported)
        assertTrue((source as PrintSource.Unsupported).reason.isNotBlank())
    }

    @Test
    fun `share mime survives names with directories and no extension`() {
        assertEquals("application/pdf", ShareMime.guess("Scans/2026/invoice.PDF"))
        assertEquals(ShareMime.BINARY, ShareMime.guess("README"))
        assertEquals("txt", ShareMime.extensionOf("folder.with.dot/name.txt"))
    }
}
