package com.securevault.pro.data.imports

import com.securevault.pro.data.file.VaultFileMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The WhatsApp chat import broke in two places that no screen test can see: the
 * launcher resolved a package id that has never existed, so WhatsApp Business always
 * reported as absent, and every voice note or clip inside an "Attach media" export was
 * stored as an opaque Doc because the MIME table knew only a handful of extensions.
 * Both are pure functions, so both are pinned here.
 */
class WhatsAppImportTest {

    @Test
    fun `voice notes and chat media reach a playable type`() {
        val expected = listOf(
            "WhatsApp Audio 2026-05-01 at 10_23.opus" to "audio/ogg",
            "PTT-20260501-WA0001.ptt" to "audio/ogg",
            "Voice note.m4a" to "audio/mp4",
            "Audio 2026-05-01 102333.aac" to "audio/aac",
            "VID-20260501-WA0002.mp4" to "video/mp4",
            "WhatsApp Video 2026-05-01 at 10_24.3gp" to "video/3gpp",
            "IMG-20260501-WA0000.jpg" to "image/jpeg",
            "WhatsApp Chat with Ali.txt" to "text/plain",
            "WhatsApp Chat with Ali.zip" to "application/zip"
        )
        for ((name, mime) in expected) {
            assertEquals(name, mime, VaultFileMime.mime(name))
        }
    }

    @Test
    fun `media lands in the categories the file list filters on`() {
        assertEquals("Audio", VaultFileMime.category("PTT-20260501-WA0001.ptt"))
        assertEquals("Audio", VaultFileMime.category("WhatsApp Audio 2026-05-01 at 10_23.opus"))
        assertEquals("Media", VaultFileMime.category("VID-20260501-WA0002.mp4"))
        assertEquals("Media", VaultFileMime.category("IMG-20260501-WA0000.jpg"))
        assertEquals("Docs", VaultFileMime.category("invoice.qwe"))
    }

    @Test
    fun `an unknown extension stays opaque instead of claiming a type`() {
        assertEquals("application/octet-stream", VaultFileMime.mime("mystery"))
        assertEquals("application/octet-stream", VaultFileMime.mime("notes.txt.zip.bak"))
    }

    @Test
    fun `whatsapp business uses the package that actually ships`() {
        assertEquals("com.whatsapp", WhatsAppPackages.MAIN)
        assertEquals("com.whatsapp.w4b", WhatsAppPackages.BUSINESS)
        assertEquals(
            listOf("com.whatsapp", "com.whatsapp.w4b"),
            WhatsAppPackages.launchable.map { it.first }
        )
    }

    /**
     * Package visibility is all or nothing: a launch intent for an id missing from
     * `<queries>` is null even when the app is installed, which is how "Open WhatsApp
     * Business" turned into a dead button. The manifest and the launcher constants are
     * edited by different people, so they are checked against each other.
     */
    @Test
    fun `the manifest declares every package the launcher can open`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.isFile }
        assumeTrue("sources not next to the test run", manifest != null)
        val text = manifest!!.readText()
        for ((id, label) in WhatsAppPackages.launchable) {
            assertTrue("$label ($id) needs a <queries> entry", text.contains("""<package android:name="$id" />"""))
        }
        assertTrue(
            "the retired Business id must not come back",
            text.contains("com.whatsapp.wmbusiness").not()
        )
    }
}
