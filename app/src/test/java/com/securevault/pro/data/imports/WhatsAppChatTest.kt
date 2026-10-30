package com.securevault.pro.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choosing a chat used to be impossible: the app launched WhatsApp with the launcher intent,
 * which only ever restores whatever conversation was last on screen. These rules decide where
 * the file picker opens, how a named chat is addressed, and which of the two importers a picked
 * file belongs to — all pure, so all pinned here instead of on a handset.
 */
class WhatsAppChatTest {

    @Test
    fun `numbers reach the deep link as bare digits with the country code`() {
        assertEquals("923001234567", WhatsAppChat.normalizeNumber("+92 300 1234567"))
        assertEquals("923001234567", WhatsAppChat.normalizeNumber("0092-300-1234567"))
        assertEquals("923001234567", WhatsAppChat.normalizeNumber("923001234567"))
        assertEquals("https://wa.me/923001234567", WhatsAppChat.deepLink("923001234567"))
    }

    @Test
    fun `unusable numbers are refused rather than opened in the wrong chat`() {
        assertNull(WhatsAppChat.normalizeNumber(""))
        assertNull(WhatsAppChat.normalizeNumber("300 123"))
        assertNull(WhatsAppChat.normalizeNumber("not a number"))
        assertNull(WhatsAppChat.normalizeNumber("9230012345678901234"))
    }

    @Test
    fun `both packages keep their files under the folder the picker opens on`() {
        assertEquals("WhatsApp", WhatsAppChat.folderName(WhatsAppPackages.MAIN))
        assertEquals("WhatsApp Business", WhatsAppChat.folderName(WhatsAppPackages.BUSINESS))
        assertEquals(
            "primary:Android/media/com.whatsapp/WhatsApp",
            WhatsAppChat.browseDocId(WhatsAppPackages.MAIN)
        )
        assertEquals(
            "primary:Android/media/com.whatsapp.w4b/WhatsApp Business",
            WhatsAppChat.browseDocId(WhatsAppPackages.BUSINESS)
        )
        // Both trees sit under it, so it is the one folder that always exists.
        assertEquals("primary:Android/media", WhatsAppChat.MEDIA_ROOT_DOC_ID)
    }

    @Test
    fun `exported conversations go to the chat importer`() {
        assertEquals(WhatsAppPick.Conversation, WhatsAppChat.route("WhatsApp Chat with Ali.txt", "text/plain"))
        assertEquals(WhatsAppPick.Conversation, WhatsAppChat.route("WhatsApp Chat with Ali.zip", "application/zip"))
        assertEquals(WhatsAppPick.Conversation, WhatsAppChat.route("ExportChat-2.zip", null))
        assertEquals(WhatsAppPick.Conversation, WhatsAppChat.route("WhatsAppChatwithAli.zip", null))
        assertEquals(WhatsAppPick.Conversation, WhatsAppChat.route("Conversation with Bank.txt", null))
        assertEquals(
            WhatsAppPick.Conversation,
            WhatsAppChat.route("storage/emulated/0/Android/media/com.whatsapp/WhatsApp/WhatsApp Chat with Ali.zip", null)
        )
    }

    @Test
    fun `videos voice notes photos and documents seal as files`() {
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("VID-20260501-WA0002.mp4", "video/mp4"))
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("VID-2026-05-01-WA0002.zip", "application/zip"))
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("PTT-20260501-WA0001.opus", "audio/ogg"))
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("IMG-20260501-WA0000.jpg", "image/jpeg"))
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("Statement.pdf", "application/pdf"))
        assertEquals(WhatsAppPick.Media, WhatsAppChat.route("random download.bin", null))
    }

    @Test
    fun `message databases are skipped because no app can read them`() {
        for (name in listOf(
            "msgstore.db.crypt14",
            "backup_msgstore.db.crypt12",
            "ChatLock.db.crypt14",
            "msgstore.db",
            "msgstore.db-wal",
            "wa.db.crypt14"
        )) {
            assertEquals(name, WhatsAppPick.Ignored, WhatsAppChat.route(name, "application/octet-stream"))
        }
    }
}
