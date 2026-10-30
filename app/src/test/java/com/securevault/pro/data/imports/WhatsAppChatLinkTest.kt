package com.securevault.pro.data.imports

import com.securevault.pro.domain.model.NoteAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker can hand over a `.txt` and the photos that came with it in one go. Their names are the
 * only key a bubble and a sealed file share, so this merge decides whether the chat view draws the
 * photo it was sent or "media not included in this export".
 */
class WhatsAppChatLinkTest {

    private fun file(id: String, name: String) =
        NoteAttachment(id = id, name = name, mimeType = "image/jpeg", size = 10L)

    @Test
    fun `picked files are added to the chat's own attachment list`() {
        val existing = listOf(file("a", "IMG-20240815-WA0001.jpg"))
        val merged = WhatsAppChatLink.merge(existing, listOf(file("b", "IMG-20240815-WA0002.jpg")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `re-picking the same file updates one entry instead of growing the list`() {
        val existing = listOf(file("old", "IMG-20240815-WA0001.jpg"))
        val merged = WhatsAppChatLink.merge(existing, listOf(file("new", "img-20240815-wa0001.JPG")))
        assertEquals(1, merged.size)
        assertEquals("new", merged.single().id)
    }

    @Test
    fun `the order the owner saw first is kept`() {
        val existing = listOf(file("a", "one.jpg"), file("b", "two.jpg"))
        val merged = WhatsAppChatLink.merge(existing, listOf(file("c", "three.jpg")))
        assertEquals(listOf("one.jpg", "two.jpg", "three.jpg"), merged.map { it.name })
    }

    @Test
    fun `a chat with more files than the ledger keeps is capped, never unbounded`() {
        val existing = (1..200).map { file("e$it", "e$it.jpg") }
        val added = (1..200).map { file("a$it", "a$it.jpg") }
        assertEquals(WhatsAppChatLink.MAX_NOTE_ATTACHMENTS, WhatsAppChatLink.merge(existing, added).size)
        assertEquals(12, WhatsAppChatLink.merge(existing, added, cap = 12).size)
    }
}
