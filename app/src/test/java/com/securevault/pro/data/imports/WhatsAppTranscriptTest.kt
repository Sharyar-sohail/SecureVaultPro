package com.securevault.pro.data.imports

import com.securevault.pro.data.imports.WhatsAppTranscript.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join that replaces folder browsing: a transcript names its attachments with the exact
 * filename the phone saved, and these tests pin the reading of that name for every WhatsApp
 * dialect the exports in the wild use.
 */
class WhatsAppTranscriptTest {

    private val standard = """
        15/08/2024, 10:23 - Ali Raza: Salaam
        15/08/2024, 10:24 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)
        15/08/2024, 10:25 - You: Got it
        16/08/2024, 21:02 - You: PTT-20240816-WA0001.opus (file attached)
        16/08/2024, 21:03 - Ali Raza: <Media omitted>
    """.trimIndent()

    @Test
    fun `counts messages and names participants from the android dialect`() {
        val parsed = WhatsAppTranscript.parse(standard)
        assertEquals(5, parsed.messageCount)
        assertEquals(listOf("Ali Raza", "You"), parsed.participants)
        assertEquals("15/08/2024, 10:23", parsed.firstStamp)
        assertEquals("16/08/2024, 21:03", parsed.lastStamp)
        assertEquals(1, parsed.omittedMedia)
    }

    @Test
    fun `reads an image and a voice note as separate kinds`() {
        val refs = WhatsAppTranscript.parse(standard).references
        assertEquals(2, refs.size)
        assertEquals("IMG-20240815-WA0007.jpg", refs[0].fileName)
        assertEquals(MediaKind.Image, refs[0].kind)
        assertTrue(refs[0].attached)
        assertEquals("PTT-20240816-WA0001.opus", refs[1].fileName)
        assertEquals(MediaKind.Audio, refs[1].kind)
    }

    @Test
    fun `reads the newer file attached colon dialect`() {
        val parsed = WhatsAppTranscript.parse(
            "15/08/2024, 10:24 - Ali: (FILE ATTACHED: VID-20240815-WA0002.mp4)"
        )
        assertEquals(1, parsed.references.size)
        assertEquals("VID-20240815-WA0002.mp4", parsed.references[0].fileName)
        assertEquals(MediaKind.Video, parsed.references[0].kind)
        assertTrue(parsed.references[0].attached)
    }

    @Test
    fun `reads the spaced names ios style exports use`() {
        val parsed = WhatsAppTranscript.parse(
            "15 Aug 2024, 10:24:11 - Ali: WhatsApp Image 2024-08-15 at 10.23.45.jpeg"
        )
        assertEquals(1, parsed.references.size)
        assertEquals(MediaKind.Image, parsed.references[0].kind)
        assertFalse("a bare mention is not an attachment", parsed.references[0].attached)
    }

    @Test
    fun `bracketed timestamps are the other dialect and still count`() {
        val parsed = WhatsAppTranscript.parse(
            "[15/08/2024, 10:23:45] Ali Raza: hello\n[15/08/2024, 10:24:02] You: Document.pdf (file attached)\n"
        )
        assertEquals(2, parsed.messageCount)
        assertEquals("Document.pdf", parsed.references[0].fileName)
        assertEquals(MediaKind.Document, parsed.references[0].kind)
    }

    @Test
    fun `a colon inside a message body is not a sender`() {
        val parsed = WhatsAppTranscript.parse("15/08/2024, 10:23 - Ali: see you at 10:30 sharp")
        assertEquals(listOf("Ali"), parsed.participants)
        assertTrue(parsed.references.isEmpty())
    }

    @Test
    fun `bidi marks do not change the digest or the count`() {
        val withMarks = "15/08/2024, 10:23 - Ali: ‎hello‏"
        val parsed = WhatsAppTranscript.parse(withMarks)
        assertEquals(WhatsAppTranscript.sha256Of("15/08/2024, 10:23 - Ali: hello"), parsed.sha256)
        assertEquals(64, parsed.sha256.length)
    }

    @Test
    fun `day and time come out of a whatsapp media name`() {
        assertEquals("20240815", WhatsAppTranscript.dayOf("IMG-20240815-WA0007.jpg"))
        assertEquals("20240815", WhatsAppTranscript.dayOf("WhatsApp Image 2024-08-15 at 10.23.45.jpeg"))
        assertEquals(10 * 3600 + 23 * 60 + 45, WhatsAppTranscript.timeOf("WhatsApp Image 2024-08-15 at 10.23.45.jpeg"))
        assertEquals("", WhatsAppTranscript.dayOf("Document.pdf"))
        assertEquals(-1, WhatsAppTranscript.timeOf("Document.pdf"))
    }

    @Test
    fun `the two names for one photo share an equivalence key`() {
        assertEquals(
            WhatsAppTranscript.equivalenceKey("IMG-20240815-WA0007.jpg"),
            WhatsAppTranscript.equivalenceKey("img-20240815-wa0007.JPG")
        )
        assertEquals(
            "img|20240815|0007",
            WhatsAppTranscript.equivalenceKey("IMG-20240815-WA0007.jpg")
        )
    }

    @Test
    fun `a document named invoice is not read as a voice note`() {
        assertEquals(MediaKind.Document, WhatsAppTranscript.kindOf("Invoices 2024.pdf"))
        assertEquals(MediaKind.Document, WhatsAppTranscript.kindOf("Invoice Jan 2025.pdf"))
        assertEquals(MediaKind.Audio, WhatsAppTranscript.kindOf("Voice message 2024-08-15 at 10.23.45"))
    }

    @Test
    fun `the bracketed dialect keeps its own clock in the span`() {
        assertEquals(
            "15/08/2024, 10:23:45",
            WhatsAppTranscript.parse("[15/08/2024, 10:23:45] Ali: hello").firstStamp
        )
    }

    @Test
    fun `the sender is never part of the attachment name`() {
        val parsed = WhatsAppTranscript.parse(
            "15/08/2024, 10:30 - Ali Raza: Business Report FINAL.pdf (file attached)"
        )
        assertEquals(1, parsed.references.size)
        assertEquals("Business Report FINAL.pdf", parsed.references[0].fileName)
        assertEquals(MediaKind.Document, parsed.references[0].kind)
        assertTrue(parsed.references[0].attached)
    }

    @Test
    fun `prose before an attachment name is not read as part of it`() {
        val parsed = WhatsAppTranscript.parse(
            "15/08/2024, 10:30 - Ali Raza: here is the Invoice Dec 2024.pdf (file attached)"
        )
        assertEquals("Invoice Dec 2024.pdf", parsed.references.single().fileName)
    }

    @Test
    fun `the full spelling of an ios attachment wins over its tail`() {
        val parsed = WhatsAppTranscript.parse(
            "15 Aug 2024, 10:24:11 - Ali: WhatsApp Image 2024-08-15 at 10.23.45.jpeg (file attached)"
        )
        assertEquals(
            "WhatsApp Image 2024-08-15 at 10.23.45.jpeg",
            parsed.references.single().fileName
        )
        assertTrue(parsed.references.single().attached)
    }

    @Test
    fun `a file mentioned first in prose and later attached is an attachment`() {
        val parsed = WhatsAppTranscript.parse(
            """
                15/08/2024, 10:23 - Ali Raza: did you get IMG-20240815-WA0007.jpg yet
                15/08/2024, 10:24 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)
            """.trimIndent()
        )
        assertEquals(1, parsed.references.size)
        assertTrue(parsed.references.single().attached)
    }

    @Test
    fun `a media type folder is never mistaken for an attachment`() {
        val parsed = WhatsAppTranscript.parse("15/08/2024, 10:23 - Ali: check .nomedia and msgstore.db.crypt15")
        assertTrue(parsed.references.isEmpty())
    }

    @Test
    fun `messages split the stamp into day, clock and speaker`() {
        val rows = WhatsAppTranscript.messages(standard)
        assertEquals(5, rows.size)
        assertEquals("15/08/2024", rows[0].day)
        assertEquals("10:23", rows[0].clock)
        assertEquals("Ali Raza", rows[0].sender)
        assertEquals("Salaam", rows[0].body)
        assertEquals("You", rows[2].sender)
    }

    @Test
    fun `a message carries its own media and hides whatsapp's marker`() {
        val rows = WhatsAppTranscript.messages(standard)
        assertEquals(listOf("IMG-20240815-WA0007.jpg"), rows[1].files)
        assertEquals("", rows[1].body)
        assertEquals(listOf("PTT-20240816-WA0001.opus"), rows[3].files)
    }

    @Test
    fun `words around an attachment survive as the caption`() {
        val rows = WhatsAppTranscript.messages(
            "15/08/2024, 10:30 - Ali: here is the invoice Report FINAL.pdf (file attached) pay by friday"
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].files.single().endsWith("Report FINAL.pdf"))
        assertFalse(rows[0].body.contains("(file attached)"))
        assertEquals("here is the pay by friday", rows[0].body)
    }

    @Test
    fun `a wrapped message keeps its second line`() {
        val rows = WhatsAppTranscript.messages(
            """
                15/08/2024, 10:23 - Ali: first line
                second line of the same message
                15/08/2024, 10:24 - You: ok
            """.trimIndent()
        )
        assertEquals(2, rows.size)
        assertEquals("first line\nsecond line of the same message", rows[0].body)
    }

    @Test
    fun `whatsapp notices are marked and never bubbled as speech`() {
        val rows = WhatsAppTranscript.messages(
            """
                15/08/2024, 10:23 - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
                15/08/2024, 10:24 - Ali: hi
                15/08/2024, 10:25 - Missed voice call
            """.trimIndent()
        )
        assertTrue(rows[0].notice)
        assertFalse(rows[1].notice)
        assertTrue(rows[2].notice)
    }

    @Test
    fun `a bracketed dialect keeps its clock`() {
        val rows = WhatsAppTranscript.messages("[15/08/2024, 10:23:45] Ali Raza: Salaam")
        assertEquals("15/08/2024", rows[0].day)
        assertEquals("10:23:45", rows[0].clock)
        assertEquals("Ali Raza", rows[0].sender)
    }

    @Test
    fun `an archived note is recognised and cut back to its conversation`() {
        val note = """
            WhatsApp transcript · Ali Raza
            Imported 1700000000000 · encrypted on device
            Attachments sealed in the File vault: 1

            $standard
        """.trimIndent()
        assertTrue(WhatsAppTranscript.isChat(note))
        assertEquals(5, WhatsAppTranscript.messages(WhatsAppTranscript.transcriptOf(note)).size)
    }

    @Test
    fun `an ordinary note is never drawn as a chat`() {
        val note = "Shopping list\n\n- milk\n- eggs\n- bread"
        assertFalse(WhatsAppTranscript.isChat(note))
    }
}
