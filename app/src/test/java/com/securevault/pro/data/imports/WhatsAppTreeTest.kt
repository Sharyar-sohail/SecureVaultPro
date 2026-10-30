package com.securevault.pro.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn WhatsApp's folder into "all my chats", "this chat" and "the files",
 * without the user touching a file manager.
 */
class WhatsAppTreeTest {

    @Test
    fun `the picker is dropped in front of the app's own folder`() {
        assertEquals(
            "Android/media/com.whatsapp/WhatsApp",
            WhatsAppTree.rootRelativePath(WhatsAppPackages.MAIN)
        )
        assertEquals(
            "Android/media/com.whatsapp.w4b/WhatsApp Business",
            WhatsAppTree.rootRelativePath(WhatsAppPackages.BUSINESS)
        )
        assertEquals(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp",
            WhatsAppTree.initialTreeUri(WhatsAppTree.rootRelativePath(WhatsAppPackages.MAIN))
        )
    }

    @Test
    fun `exports are chats and media folders are files`() {
        assertEquals(
            WhatsAppTree.Kind.ExportsFolder,
            WhatsAppTree.classify("WhatsApp Exports", true)
        )
        assertEquals(
            WhatsAppTree.Kind.ChatExport,
            WhatsAppTree.classify("WhatsApp Exports/WhatsApp Chat with Ali.zip", false)
        )
        assertEquals(
            WhatsAppTree.Kind.MediaFolder,
            WhatsAppTree.classify("WhatsApp Media/Voice Notes", true)
        )
        assertEquals(
            WhatsAppTree.Kind.MediaFile,
            WhatsAppTree.classify("WhatsApp Media/Video Messages/VID-2026-05-01-WA0002.mp4", false)
        )
        assertEquals(
            WhatsAppTree.Kind.Unreadable,
            WhatsAppTree.classify("Backups/msgstore.db.crypt14", false)
        )
        assertEquals(
            WhatsAppTree.Kind.Skip,
            WhatsAppTree.classify("Profile Photos/me.jpg", false)
        )
        assertEquals(
            WhatsAppTree.Kind.Skip,
            WhatsAppTree.classify("WhatsApp Media/Document Thumbnails/x.pdf.tmb.jpg", false)
        )
        assertEquals(
            WhatsAppTree.Kind.Skip,
            WhatsAppTree.classify("WhatsApp Media/Images/.nomedia", false)
        )
    }

    @Test
    fun `folders worth opening are the ones the walker descends into`() {
        assertTrue(WhatsAppTree.descends(WhatsAppTree.Kind.RootFolder))
        assertTrue(WhatsAppTree.descends(WhatsAppTree.Kind.ExportsFolder))
        assertTrue(WhatsAppTree.descends(WhatsAppTree.Kind.MediaFolder))
        assertFalse(WhatsAppTree.descends(WhatsAppTree.Kind.Skip))
        assertFalse(WhatsAppTree.descends(WhatsAppTree.Kind.MediaFile))
    }

    @Test
    fun `older whatsapp layouts are still recognised`() {
        // Granted the root of internal storage: the containers sit two levels down.
        assertEquals(
            WhatsAppTree.Kind.ExportsFolder,
            WhatsAppTree.classify("WhatsApp/WhatsApp Exports", true)
        )
        assertEquals(
            WhatsAppTree.Kind.ChatExport,
            WhatsAppTree.classify("WhatsApp/WhatsApp Exports/WhatsApp Chat with Ali.txt", false)
        )
        assertEquals(
            WhatsAppTree.Kind.MediaFile,
            WhatsAppTree.classify("WhatsApp/WhatsApp Media/Images/IMG_2026.jpg", false)
        )
    }

    @Test
    fun `every re-export of a conversation keys to the same chat`() {
        assertEquals("ali", WhatsAppTree.chatKey("WhatsApp Chat with Ali.txt"))
        assertEquals("ali", WhatsAppTree.chatKey("WhatsApp Chat with Ali (3).zip"))
        assertEquals("ali", WhatsAppTree.chatKey("WhatsApp-Chat-with-Ali.zip"))
        assertEquals("family", WhatsAppTree.chatKey("WhatsApp Chat with Family Chat.zip"))
        assertEquals("bank support", WhatsAppTree.chatKey("Conversation with Bank Support.txt"))
        assertTrue(
            WhatsAppTree.chatKey("WhatsApp Chat with Ali.txt") ==
                WhatsAppTree.chatKey("WhatsApp Chat with Ali (2).zip")
        )
    }

    @Test
    fun `a tapped chat picks its own exports and nobody else's`() {
        val ali = WhatsAppTree.chatKey("WhatsApp Chat with Ali.txt")
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Ali (1).zip", ali))
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Ali.txt", "Ali"))
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Ali.txt", "ali"))
        assertFalse(WhatsAppTree.matchesChat("WhatsApp Chat with Bilal.txt", ali))
        assertTrue(
            "a group named after both people is still the chat called Ali",
            WhatsAppTree.matchesChat("WhatsApp Chat with Ali and Bilal.txt", ali)
        )
    }

    @Test
    fun `a blank or all search takes every chat`() {
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Anyone.txt", ""))
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Anyone.txt", "all"))
        assertTrue(WhatsAppTree.matchesChat("WhatsApp Chat with Anyone.txt", "  ALL  "))
    }

    @Test
    fun `a long group subject survives whatsapp's truncation`() {
        // The export name is cut short, so the typed name is longer than the file's key.
        assertTrue(
            WhatsAppTree.matchesChat(
                "WhatsApp Chat with Project Alpha Deliver.txt",
                "Project Alpha Deliverables"
            )
        )
    }

    @Test
    fun `the list shows the conversation the way whatsapp named it`() {
        assertEquals("Ali", WhatsAppTree.displayName("WhatsApp Chat with Ali (3).txt"))
        assertEquals("Bank Support", WhatsAppTree.displayName("Conversation with Bank Support.txt"))
        assertEquals("Ali", WhatsAppTree.displayName("ExportChat-Ali.zip"))
    }

    @Test
    fun `a media folder is found by the name the user recognises`() {
        assertTrue(WhatsAppTree.folderMatchesQuery("Voice Notes", "voice"))
        assertTrue(WhatsAppTree.folderMatchesQuery("Video Messages", ""))
        assertTrue(WhatsAppTree.folderMatchesQuery("Image Messages", "all"))
        assertFalse(WhatsAppTree.folderMatchesQuery("Document Thumbnails", "voice"))
    }
}
