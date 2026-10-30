package com.securevault.pro.data.imports

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Re-importing the same chat is one of the two defects the audit named: every photo was copied
 * again and the conversation grew a second copy of itself. The engine keys a bubble on
 * (`chatId`, ordinal) and the ordinal is the position in the transcript, so the same bytes must
 * always answer with the same rows.
 *
 * The sink is an in-memory recorder: this is a JVM test, with no Room, no Android and no file.
 */
class WhatsAppImportDedupTest {

    private val base = buildString {
        appendLine("15/08/2024, 10:23 - Ali Raza: Salaam")
        appendLine("15/08/2024, 10:24 - Ali Raza: first line")
        appendLine("wrapped line")
        appendLine("15/08/2024, 10:25 - You: got it 👍")
        appendLine("16/08/2024, 09:00 - Ali Raza: IMG-20240816-WA0001.jpg (file attached)")
    }

    private val chatId = WhatsAppChatUnit.idFor("Ali Raza", "WhatsApp Chat with Ali Raza.txt")

    private fun importer(sink: WhatsAppChatSink) = WhatsAppStreamingImporter(sink = sink)

    private fun request() = WhatsAppStreamingImporter.Request(
        chatId = chatId,
        title = "Ali Raza",
        sourceName = "WhatsApp Chat with Ali Raza.txt"
    )

    private fun importText(importer: WhatsAppStreamingImporter, text: String) = runBlocking {
        importer.importTranscript(request(), { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) })
    }

    @Test
    fun `re-importing the same transcript replaces rows instead of duplicating them`() {
        val sink = FakeSink()
        val first = importText(importer(sink), base)
        assertTrue(first.isSuccess)
        val count = first.getOrThrow().messages
        assertEquals(4, count)
        assertEquals(count, sink.rows.size)

        val second = importText(importer(sink), base)
        assertTrue(second.isSuccess)
        assertEquals(count, second.getOrThrow().messages)
        assertEquals("a re-import must not add a second copy", count, sink.rows.size)
        assertEquals((0 until count).toList(), sink.rows.keys.toList())
        assertEquals(first.getOrThrow().transcriptSha, second.getOrThrow().transcriptSha)
        assertTrue(second.getOrThrow().resumed)
    }

    @Test
    fun `a re-export with new messages rewrites the same chat without stale rows`() {
        val sink = FakeSink()
        importText(importer(sink), base).getOrThrow()
        val longer = base + "17/08/2024, 08:00 - Ali Raza: ek aur baat\n"
        val second = importText(importer(sink), longer)
        assertTrue(second.isSuccess)
        assertEquals(5, second.getOrThrow().messages)
        assertEquals(5, sink.rows.size)
        assertEquals((0..4).toList(), sink.rows.keys.toList())
        assertEquals("ek aur baat", sink.rows.getValue(4).body)
        assertNull("the chat keeps its identity across a re-export", second.exceptionOrNull())
    }

    @Test
    fun `a failed write is rolled back and leaves no half-written chat`() {
        val sink = FakeSink(failOnAppend = true)
        val outcome = importText(importer(sink), base)
        assertTrue(outcome.isFailure)
        assertNotNull(outcome.exceptionOrNull())
        assertTrue(sink.abandoned)
        assertTrue("no bubbles survive the failure", sink.rows.isEmpty())
        assertTrue("a chat that never existed is deleted outright", sink.chats.isEmpty())
    }

    /**
     * The store contract in miniature: same digest and a finished chat → resume at the next
     * ordinal; a different digest → the old bubbles no longer describe this file, so they go.
     */
    private class FakeSink(private val failOnAppend: Boolean = false) : WhatsAppChatSink {

        private class Chat(var sha: String, var messages: Int, var ready: Boolean)

        val chats = HashMap<String, Chat>()
        val rows = LinkedHashMap<Int, WhatsAppMessageDraft>()
        var abandoned = false

        override suspend fun prepare(draft: WhatsAppChatDraft): WhatsAppResumePoint {
            val existing = chats[draft.chatId]
            val sameFile = existing != null && existing.ready &&
                draft.transcriptSha.isNotBlank() && existing.sha == draft.transcriptSha
            if (sameFile) {
                return WhatsAppResumePoint(rows.size, isFresh = false, existedBefore = true)
            }
            if (existing != null) rows.clear()
            chats[draft.chatId] = Chat(draft.transcriptSha, 0, ready = false)
            return WhatsAppResumePoint(0, isFresh = existing != null, existedBefore = existing != null)
        }

        override suspend fun append(chatId: String, batch: List<WhatsAppMessageDraft>) {
            if (failOnAppend) throw IllegalStateException("the database refused the batch")
            batch.forEach { rows[it.ordinal] = it }
        }

        override suspend fun finish(commit: WhatsAppChatCommit, mirror: WhatsAppChatDraft) {
            chats[commit.chatId] = Chat(mirror.transcriptSha, commit.messages, ready = true)
        }

        override suspend fun abandon(chatId: String, fromOrdinal: Int, existedBefore: Boolean) {
            abandoned = true
            rows.keys.filter { it >= fromOrdinal }.forEach { rows.remove(it) }
            if (!existedBefore) chats.remove(chatId)
        }
    }
}
