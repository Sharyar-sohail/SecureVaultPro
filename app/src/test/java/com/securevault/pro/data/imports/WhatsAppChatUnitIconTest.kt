package com.securevault.pro.data.imports

import com.securevault.pro.presentation.components.ChatGlyphBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule behind "every chat is its own module, with its own icon".
 *
 * The container path is what keeps two conversations' files apart and the icon slot is what makes
 * each conversation recognisable at a glance, so both must be pure functions of the chat id: a
 * re-export, a folder-hub listing and the vault row have to show the *same* glyph for the same
 * chat, with no state anywhere in between.
 */
class WhatsAppChatUnitIconTest {

    @Test
    fun idIsStablePerChatAndDistinctAcrossChats() {
        assertEquals(WhatsAppChatUnit.idFor("Ali", "chat.zip"), WhatsAppChatUnit.idFor("Ali", "other.zip"))
        assertNotEquals(WhatsAppChatUnit.idFor("Ali"), WhatsAppChatUnit.idFor("Family"))
    }

    @Test
    fun containerRoundTripsBackToItsChatId() {
        val id = WhatsAppChatUnit.idFor("Family", "wa-chat.zip")
        val container = WhatsAppChatUnit.containerOf(id)
        assertEquals(id, WhatsAppChatUnit.idFromContainer(container))
        assertTrue(container.startsWith(WhatsAppChatUnit.CONTAINER_ROOT + "/"))
    }

    /** A legacy flat file, a stray name and a hashed fallback all refuse to claim a chat. */
    @Test
    fun onlyAChatContainerNamesAChat() {
        assertNull(WhatsAppChatUnit.idFromContainer(null))
        assertNull(WhatsAppChatUnit.idFromContainer(""))
        assertNull(WhatsAppChatUnit.idFromContainer("documents"))
        assertNull(WhatsAppChatUnit.idFromContainer("chats/"))
        assertNull(WhatsAppChatUnit.idFromContainer("chats/not a uuid!"))
    }

    /**
     * The cross-client pin. `chatIconSlot` in the Blogger theme implements the same FNV-1a in
     * JavaScript; these vectors were computed there, so any drift in either implementation shows up
     * as a phone and a browser wearing different glyphs for one conversation.
     */
    @Test
    fun theSlotMatchesTheWebCompanion() {
        mapOf(
            "0f8fad5b-d9cb-469f-a165-70867728950e" to 1,
            "7c9e6679-7425-40de-944b-e07fc1f90ae7" to 3,
            "5ac4af8d-1f2b-4c3a-9d7e-0b6f4a2c1d3e" to 3,
            "11111111-2222-3333-4444-555555555555" to 9,
            "a" to 12
        ).forEach { (id, expected) ->
            assertEquals(
                "slot for $id must equal the web companion's",
                expected,
                WhatsAppChatUnit.iconSlot(id, ChatGlyphBoard.glyphs.size)
            )
        }
    }

    @Test
    fun theBoardIsStableAndUsesDistinctHouseGlyphs() {
        val id = WhatsAppChatUnit.idFor("Rehan", "")
        val first = ChatGlyphBoard.glyphFor(id)
        repeat(20) { assertEquals("the board must not shuffle", first, ChatGlyphBoard.glyphFor(id)) }
        assertEquals(first, ChatGlyphBoard.glyphFor("Rehan", "whatever.zip"))
        assertTrue(
            "no board entry may share a drawable with another: " +
                ChatGlyphBoard.glyphs.map { it.asset }.distinct(),
            ChatGlyphBoard.glyphs.map { it.asset }.distinct().size == ChatGlyphBoard.glyphs.size
        )
        assertTrue(
            "every board entry resolves to a real drawable id",
            ChatGlyphBoard.glyphs.all { it.drawableId != 0 }
        )
    }

    /** Identity is the point: a list of chats must not render as one repeated mark. */
    @Test
    fun distinctChatsSpreadOverTheBoard() {
        val glyphs = (1..40).map { ChatGlyphBoard.glyphFor(WhatsAppChatUnit.idFor("Chat $it")) }
        assertTrue(
            "40 chats drew only ${glyphs.distinct().size} of ${ChatGlyphBoard.glyphs.size} glyphs",
            glyphs.distinct().size >= 12
        )
    }

    /** The board has to exist as drawn vectors, not just as enum entries. */
    @Test
    fun everyBoardGlyphHasADrawableOnDisk() {
        val roots = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .take(6)
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "app/src/main/res/drawable"),
                    File(dir, "src/main/res/drawable")
                )
            }
            .firstOrNull { it.isDirectory } ?: return
        val missing = ChatGlyphBoard.glyphs.filter { !File(roots, it.asset + ".xml").isFile }
        assertTrue("board glyphs with no drawable: " + missing.map { it.asset }, missing.isEmpty())
    }
}
