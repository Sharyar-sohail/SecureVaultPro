package com.securevault.pro.data.imports

import com.securevault.pro.data.local.dao.WhatsAppChatDao
import com.securevault.pro.data.local.entity.WhatsAppChatEntity
import com.securevault.pro.data.local.entity.WhatsAppChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the streaming importer relies on, proved without an emulator: a half-written chat is
 * invisible, a retry resumes rather than duplicates, a changed transcript replaces its own bubbles,
 * and every word leaves the store sealed on the way in and unsealed on the way out.
 */
class WhatsAppChatStoreTest {

    private val cipher = object : ChatFieldCipher {
        override fun seal(value: String): String = "SEAL($value)"
        override fun open(sealedValue: String): String =
            if (sealedValue.startsWith("SEAL(") && sealedValue.endsWith(")")) {
                sealedValue.removePrefix("SEAL(").removeSuffix(")")
            } else {
                sealedValue
            }
    }

    private fun draft(sha: String = "sha-one", state: String = WhatsAppChatDraft.STATE_READY) =
        WhatsAppChatDraft(
            chatId = "chat-1",
            title = "Alice and Bob",
            contactName = "Alice",
            sourceName = "WhatsApp Chat with Alice.txt",
            participants = listOf("Alice", "Bob"),
            transcriptSha = sha,
            noteId = "chat-1",
            importState = state,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastImportedAt = 2_000L
        )

    private fun batch(from: Int, count: Int) = (0 until count).map { index ->
        WhatsAppMessageDraft(
            ordinal = from + index,
            day = "10/28/2026",
            clock = "09:0${index % 10}",
            sender = "Alice",
            body = "message ${from + index}",
            attachments = if (index == 0) listOf("IMG_0001.jpg") else emptyList()
        )
    }

    @Test
    fun `a new chat is prepared invisible and counted at zero`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)

        val resume = store.prepare(draft())

        assertEquals(0, resume.startOrdinal)
        assertTrue(resume.isFresh)
        assertFalse(resume.existedBefore)
        val row = dao.chat("chat-1")
        assertNotNull(row)
        assertEquals(WhatsAppChatEntity.IMPORT_STATE_IMPORTING, row?.importState)
        assertEquals(0, row?.messageCount)
        // Hidden from the list while the run is still writing.
        assertTrue(dao.chats().isEmpty())
    }

    @Test
    fun `bubbles are sealed in and unsealed out in order`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft())
        store.append("chat-1", batch(0, 3))

        val stored = dao.allMessages("chat-1")
        assertEquals(3, stored.size)
        assertEquals("SEAL(Alice)", stored.first().sender)
        assertEquals("SEAL(message 0)", stored.first().body)
        assertEquals("SEAL([\"IMG_0001.jpg\"])", stored.first().attachmentsJson)
        assertEquals("10/28/2026", stored.first().day)
        // Timestamps stay plain so the viewer can group without opening a single bubble.
        assertEquals("09:00", stored.first().clock)

        val reader = WhatsAppChatReader(dao, cipher)
        store.finish(
            WhatsAppChatCommit(
                chatId = "chat-1",
                messages = 3,
                media = 1,
                participants = listOf("Alice"),
                firstStamp = "10/28/2026 09:00",
                lastStamp = "10/28/2026 09:02"
            ),
            draft()
        )

        val bubbles = reader.bubbles("chat-1")
        assertEquals(listOf(0, 1, 2), bubbles.map { it.ordinal })
        assertEquals("Alice", bubbles.first().sender)
        assertEquals("message 0", bubbles.first().body)
        assertEquals(listOf("IMG_0001.jpg"), bubbles.first().attachments)

        val chat = reader.chat("chat-1")
        assertNotNull(chat)
        assertEquals(3, chat?.messageCount)
        assertEquals(1, chat?.mediaCount)
        assertEquals(listOf("Alice"), chat?.participants)
        val drawn = bubbles.first().asMessage()
        assertEquals("Alice", drawn.sender)
        assertEquals("message 0", drawn.body)
        assertEquals(listOf("IMG_0001.jpg"), drawn.files)
    }

    @Test
    fun `the same transcript resumes after what is already written`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft())
        store.append("chat-1", batch(0, 10))
        dao.upsertChat(dao.chat("chat-1")!!.copy(importState = WhatsAppChatEntity.IMPORT_STATE_READY, messageCount = 10))

        val resume = store.prepare(draft())

        assertFalse(resume.isFresh)
        assertTrue(resume.existedBefore)
        assertEquals(10, resume.startOrdinal)
        assertEquals(10, dao.messageCount("chat-1"))
    }

    @Test
    fun `a changed transcript clears its own bubbles instead of interleaving`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft(sha = "sha-one"))
        store.append("chat-1", batch(0, 10))

        val resume = store.prepare(draft(sha = "sha-two"))

        assertTrue(resume.isFresh)
        assertEquals(0, resume.startOrdinal)
        assertEquals(0, dao.messageCount("chat-1"))
    }

    @Test
    fun `stopping a brand-new chat leaves no trace in the list`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        val resume = store.prepare(draft())
        store.append("chat-1", batch(0, 4))

        store.abandon("chat-1", resume.startOrdinal, resume.existedBefore)

        assertNull(dao.chat("chat-1"))
        assertEquals(0, dao.messageCount("chat-1"))
        assertTrue(WhatsAppChatReader(dao, cipher).chats().isEmpty())
    }

    @Test
    fun `stopping a re-import keeps exactly the bubbles the previous run committed`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft())
        store.append("chat-1", batch(0, 6))
        store.finish(
            WhatsAppChatCommit("chat-1", 6, 0, listOf("Alice"), "10/28/2026 09:00", "10/28/2026 09:05"),
            draft()
        )
        // The same file re-imported: the run resumes at six and is stopped halfway through batch two.
        val resume = store.prepare(draft())
        store.append("chat-1", batch(6, 5))

        store.abandon("chat-1", resume.startOrdinal, resume.existedBefore)

        assertEquals(6, dao.messageCount("chat-1"))
        assertEquals(WhatsAppChatEntity.IMPORT_STATE_INTERRUPTED, dao.chat("chat-1")?.importState)
        assertEquals(6, dao.chat("chat-1")?.messageCount)
        // An interrupted chat is still the conversation the owner had, with the messages it had.
        val chat = WhatsAppChatReader(dao, cipher).chat("chat-1")
        assertNotNull(chat)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), WhatsAppChatReader(dao, cipher).bubbles("chat-1").map { it.ordinal })
    }

    @Test
    fun `a page of bubbles is a page and not the whole chat`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft())
        store.append("chat-1", batch(0, 5))
        store.finish(
            WhatsAppChatCommit("chat-1", 5, 0, listOf("Alice"), "", ""),
            draft()
        )
        val reader = WhatsAppChatReader(dao, cipher)

        assertEquals(listOf(3, 4), reader.bubbles("chat-1", limit = 2, offset = 3).map { it.ordinal })
        assertEquals(5, reader.bubbles("chat-1").size)
    }

    /** The chat row alone, no bubbles: proves the reader reads the row rather than a note blob. */
    @Test
    fun `an empty committed chat reports zero bubbles`() = runBlocking {
        val dao = FakeWhatsAppChatDao()
        val store = WhatsAppChatStore(dao, cipher)
        store.prepare(draft())
        store.finish(WhatsAppChatCommit("chat-1", 0, 0, emptyList(), "", ""), draft())

        assertTrue(WhatsAppChatReader(dao, cipher).bubbles("chat-1").isEmpty())
        assertEquals(0, WhatsAppChatReader(dao, cipher).chat("chat-1")?.messageCount)
    }

    /** In-memory stand-in for Room, faithful to the queries the store and reader actually use. */
    private class FakeWhatsAppChatDao : WhatsAppChatDao {
        private val chats = LinkedHashMap<String, WhatsAppChatEntity>()
        private val messages = LinkedHashMap<String, MutableList<WhatsAppChatMessageEntity>>()
        private val changes = MutableStateFlow(0)

        override fun observeChats(): Flow<List<WhatsAppChatEntity>> =
            changes.map {
                chats.values
                    .filter { it.importState != WhatsAppChatEntity.IMPORT_STATE_IMPORTING }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.contactName })
            }

        override suspend fun chats(): List<WhatsAppChatEntity> =
            chats.values.filter { it.importState != WhatsAppChatEntity.IMPORT_STATE_IMPORTING }

        override suspend fun chat(chatId: String): WhatsAppChatEntity? = chats[chatId]

        override suspend fun upsertChat(chat: WhatsAppChatEntity) {
            chats[chat.chatId] = chat
            changes.value = changes.value + 1
        }

        override suspend fun deleteChat(chatId: String) {
            chats.remove(chatId)
            messages.remove(chatId)
            changes.value = changes.value + 1
        }

        override suspend fun clearMessages(chatId: String) {
            messages[chatId] = mutableListOf()
        }

        override suspend fun insertMessages(messages: List<WhatsAppChatMessageEntity>) {
            messages.forEach { row ->
                val bucket = this.messages.getOrPut(row.chatId) { mutableListOf() }
                bucket.removeAll { it.ordinal == row.ordinal }
                bucket += row
            }
        }

        override suspend fun messages(chatId: String, limit: Int, offset: Int): List<WhatsAppChatMessageEntity> =
            allMessages(chatId).drop(offset).take(limit)

        override suspend fun allMessages(chatId: String): List<WhatsAppChatMessageEntity> =
            (messages[chatId] ?: emptyList()).sortedBy { it.ordinal }

        override suspend fun messageCount(chatId: String): Int = allMessages(chatId).size

        override suspend fun nextOrdinal(chatId: String): Int = allMessages(chatId).size

        override suspend fun truncateFrom(chatId: String, from: Int) {
            messages[chatId]?.removeAll { it.ordinal >= from }
        }

        override suspend fun containerOf(chatId: String): String? = chats[chatId]?.container
    }
}
