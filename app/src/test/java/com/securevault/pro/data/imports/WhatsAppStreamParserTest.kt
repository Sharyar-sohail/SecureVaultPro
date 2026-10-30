package com.securevault.pro.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The streaming reader is the door a multi-gigabyte export walks through, so it has to see exactly
 * what the whole-text parser sees — bubble for bubble, day for day, file for file — while never
 * holding the file. These tests pin that equivalence on one small export that carries every shape
 * WhatsApp actually writes, and then pin the two properties the owner's reports were about: the
 * reader is bounded, and reading the same bytes twice produces the same bubbles at the same
 * positions (which is what makes a re-import an update instead of a duplicate).
 */
class WhatsAppStreamParserTest {

    /** Header, system line, wrapped message, emoji, attachment, a system notice, a date rollover. */
    private val sample = buildString {
        appendLine("WhatsApp Chat with Ali Raza")
        appendLine("Exported on 17/08/2024")
        appendLine("15/08/2024, 09:00 - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.")
        appendLine("15/08/2024, 10:23 - Ali Raza: Salaam")
        appendLine("15/08/2024, 10:23 - Ali Raza: first line of a long message")
        appendLine("second line, still the same bubble")
        appendLine("15/08/2024, 10:24 - Ali Raza: sending the invoice now")
        appendLine("15/08/2024, 10:24 - Ali Raza: Report FINAL.pdf (file attached)")
        appendLine("15/08/2024, 10:25 - You: 🔥 shukriya 🎉👨‍👩‍👧‍👦")
        appendLine("15/08/2024, 10:26 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)")
        appendLine("16/08/2024, 21:02 - Ali Raza: Missed voice call")
        appendLine("16/08/2024, 21:03 - You: kal milte hain")
    }

    private fun read(text: String): Pair<List<WhatsAppTranscript.ChatMessage>, WhatsAppStreamParser.Report> {
        val messages = mutableListOf<WhatsAppTranscript.ChatMessage>()
        val report = WhatsAppStreamParser.read(
            source = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            listener = object : WhatsAppStreamParser.Listener {
                override fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int) {
                    assertEquals(messages.size, ordinal)
                    messages += message
                }
            }
        )
        return messages to report
    }

    @Test
    fun `a streamed export parses exactly like the whole text parser`() {
        val (streamed, report) = read(sample)
        val whole = WhatsAppTranscript.messages(sample)
        assertEquals(whole.size, streamed.size)
        assertEquals(whole, streamed)
        assertEquals(whole.size, report.messages)
        assertEquals(WhatsAppTranscript.sha256Of(sample), report.sha256)
    }

    @Test
    fun `header lines before the first message never become bubbles`() {
        val (messages, _) = read(sample)
        // The encryption line is a timestamped system notice, so it is kept as a centred notice
        // row; the first spoken bubble is still Salaam and neither export header leaks in.
        assertEquals("Salaam", messages.first { !it.notice }.body)
        assertFalse(messages.any { it.body.contains("WhatsApp Chat with Ali Raza") })
        assertFalse(messages.any { it.body.contains("Exported on") })
    }

    @Test
    fun `system lines are notices and never bubbles with a speaker`() {
        val (messages, _) = read(sample)
        val encryption = messages.first()
        assertTrue(encryption.notice)
        assertNull(encryption.sender)
        assertTrue(messages.any { it.body == "Missed voice call" && it.notice })
    }

    @Test
    fun `multi-line messages keep their second line`() {
        val (messages, _) = read(sample)
        val wrapped = messages.single { it.body.startsWith("first line") }
        assertEquals("first line of a long message\nsecond line, still the same bubble", wrapped.body)
    }

    @Test
    fun `emoji survive the line reader`() {
        val (messages, _) = read(sample)
        val emoji = messages.single { it.body.startsWith("🔥") }
        assertEquals("🔥 shukriya 🎉👨‍👩‍👧‍👦", emoji.body)
        assertEquals("You", emoji.sender)
    }

    @Test
    fun `an attachment is named on the message that carried it`() {
        val (messages, report) = read(sample)
        val invoice = messages.single { it.files.contains("Report FINAL.pdf") }
        assertEquals("", invoice.body)
        assertEquals("Ali Raza", invoice.sender)
        val photo = messages.single { it.files.contains("IMG-20240815-WA0007.jpg") }
        assertEquals("", photo.body)
        val names = report.references.filter { it.attached }.map { it.fileName }.toSet()
        assertEquals(setOf("Report FINAL.pdf", "IMG-20240815-WA0007.jpg"), names)
    }

    @Test
    fun `the date rollover starts a new day`() {
        val (messages, _) = read(sample)
        val days = messages.map { it.day }.distinct()
        assertEquals(listOf("15/08/2024", "16/08/2024"), days)
        assertEquals("21:03", messages.last().clock)
    }

    @Test
    fun `reading the same bytes twice produces the same bubbles and digest`() {
        val (first, firstReport) = read(sample)
        val (second, secondReport) = read(sample)
        assertEquals(first, second)
        assertEquals(firstReport.sha256, secondReport.sha256)
        assertEquals(
            first.indices.toList(),
            second.indices.toList()
        )
    }

    @Test
    fun `an oversized line is cut short instead of buffered`() {
        // WhatsApp never writes a 300 KB "message"; a binary renamed .txt does. It must be counted
        // and abandoned, never kept whole.
        val huge = "x".repeat(300_000)
        val (messages, report) = read("$huge\n15/08/2024, 10:23 - Ali: still readable\n")
        assertEquals(1, report.truncatedLines)
        assertEquals(1, messages.size)
        assertEquals("still readable", messages.single().body)
        assertTrue(messages.single().body.length < WhatsAppStreamParser.MAX_LINE_CHARS)
    }

    @Test
    fun `a byte order mark and windows line endings are not part of the first message`() {
        val text = "\uFEFF15/08/2024, 10:23 - Ali: hi\r\n15/08/2024, 10:24 - Ali: there\r\n"
        val messages = mutableListOf<WhatsAppTranscript.ChatMessage>()
        WhatsAppStreamParser.read(
            source = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            listener = object : WhatsAppStreamParser.Listener {
                override fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int) {
                    messages += message
                }
            }
        )
        assertEquals(2, messages.size)
        assertEquals("hi", messages[0].body)
        assertEquals("there", messages[1].body)
    }

    @Test
    fun `a blank line inside a wrapped message does not open a new bubble`() {
        val text = "15/08/2024, 10:23 - Ali: para one\n\npara two\n15/08/2024, 10:24 - Ali: next\n"
        val (messages, _) = read(text)
        assertEquals(2, messages.size)
        assertEquals("para one\n\npara two", messages[0].body)
    }
}
