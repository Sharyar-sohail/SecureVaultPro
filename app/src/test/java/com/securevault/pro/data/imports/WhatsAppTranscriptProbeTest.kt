package com.securevault.pro.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The transcript probe proven on real fixtures instead of on a handset.
 *
 * WhatsApp names the conversation file after the chat, and that name is not stable: Android
 * writes `… _chat.txt`, iOS writes `… _CHAT.txt`, Business writes `business_chat-92345…@c.us.txt`,
 * some builds write `_WAChatSource.txt`, and every one of them can add a `_MISSING.txt` or
 * `MEDIAS REMOVED.txt` manifest that is *not* the chat. So the rule under test is the content
 * rule — of the eligible `.txt` files in an archive, the one carrying the most parsed messages
 * wins — exercised through a real zip, plus the count parity the owner sees: the number the
 * import reports, the number the archive summary reports and the number of bubbles the chat
 * viewer draws must be the same number.
 *
 * No passphrase or key appears here: those are derived at runtime and never stored in fixtures.
 */
class WhatsAppTranscriptProbeTest {

    // --- fixtures: the exports as WhatsApp actually writes them -------------------------------

    /** Android, `Attach media`, the name every guide screenshots. */
    private val androidChat = """
        15/08/2024, 10:23 - Ali Raza: Salaam
        15/08/2024, 10:24 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)
        15/08/2024, 10:25 - You: Got it
        16/08/2024, 21:02 - You: PTT-20240816-WA0001.opus (file attached)
        16/08/2024, 21:03 - Ali Raza: <Media omitted>
    """.trimIndent() + "\n"

    /** iOS, month-first stamps with a meridiem, and the uppercase `_CHAT` token. */
    private val iosChat = """
        Aug 15, 2024, 10:24:11 AM - Ali Raza: Salaam
        Aug 15, 2024, 10:25:02 AM - You: here is the photo
        Aug 15, 2024, 10:25:30 AM - Ali Raza: WhatsApp Image 2024-08-15 at 10.25.29.jpeg (file attached)
    """.trimIndent() + "\n"

    /** A number-jid export from WhatsApp Business, bracketed dialect. */
    private val businessChat = """
        [15/08/2024, 10:23:45] 9234567890: Salaam
        [15/08/2024, 10:24:02] You: Invoice Dec 2024.pdf (file attached)
        [15/08/2024, 10:24:40] 9234567890: thank you
    """.trimIndent() + "\n"

    /** ISO dialect from a handset set to a locale that writes the year first. */
    private val isoChat = """
        2024-08-15, 10:23 - Ali Raza: Salaam
        2024-08-15, 10:24 - You: ok
        2024-08-16, 09:10 - Ali Raza: see you
    """.trimIndent() + "\n"

    /** Two-digit year, `dd/mm/yy`, the oldest Android dialect still in the wild. */
    private val shortYearChat = """
        15/08/24, 10:23 - Ali Raza: Salaam
        15/08/24, 10:26 - You: received
    """.trimIndent() + "\n"

    /** WhatsApp's own manifest: names what it could not include, and holds no conversation. */
    private val missingManifest = """
        Some media items were removed due to storage limitations on your device
        medias/IMG-20240815-WA0012.jpg
        medias/PTT-20240816-WA0003.opus
    """.trimIndent() + "\n"

    /** A plain `.txt` the owner dropped into the export folder — prose, not a chat. */
    private val proseNote = """
        Notes about the case
        Please bring the file to the office next week, thanks.
        The receipt is in the drawer.
    """.trimIndent() + "\n"

    private val chats = linkedMapOf(
        "Ali Raza _chat.txt" to androidChat,
        "Family _CHAT.txt" to iosChat,
        "business_chat-9234567890@c.us.txt" to businessChat,
        "_WAChatSource.txt" to isoChat,
        "9234567890@c.us _chat.txt" to shortYearChat
    )

    // --- the probe, over a real zip -----------------------------------------------------------

    /** Reads an archive the way the importer does: only eligible `.txt` files compete. */
    private fun candidatesOf(zipBytes: ByteArray): List<WhatsAppTranscript.TranscriptCandidate> {
        val found = ArrayList<WhatsAppTranscript.TranscriptCandidate>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (!WhatsAppTranscript.isTranscriptCandidate(name)) continue
                found += WhatsAppTranscript.TranscriptCandidate(name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        return found
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("IMG-20240815-WA0007.jpg"))
            zip.write(ByteArray(64) { 7 })
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `every exported name is eligible as a transcript candidate`() {
        for (name in chats.keys) {
            assertTrue("$name must be allowed to compete", WhatsAppTranscript.isTranscriptCandidate(name))
        }
    }

    @Test
    fun `whatsapp's own manifests never compete for the transcript`() {
        for (name in listOf(
            "Missing.txt",
            "_MISSING.txt",
            "MEDIAS REMOVED.txt",
            "_MEDIAS REMOVED.txt",
            "medias_removed.txt",
            "_WEBSITES_listing.txt"
        )) {
            assertTrue("$name is a manifest", WhatsAppTranscript.isTranscriptManifest(name))
            assertFalse("$name must never be the chat", WhatsAppTranscript.isTranscriptCandidate(name))
        }
    }

    @Test
    fun `the zip probe picks the richest conversation, not the luckiest name`() {
        val archive = zipOf(
            "Missing.txt" to missingManifest,
            "notes.txt" to proseNote,
            "Ali Raza _chat.txt" to androidChat,
            "Family _CHAT.txt" to iosChat
        )
        val winner = WhatsAppTranscript.bestTranscript(candidatesOf(archive))
        assertNotNull("a chat was inside the archive", winner)
        assertEquals("Ali Raza _chat.txt", winner!!.name)
        assertEquals(5, winner.messages)
    }

    @Test
    fun `an ios uppercase export wins when it is the only one carrying messages`() {
        val archive = zipOf(
            "readme.txt" to proseNote,
            "_MISSING.txt" to missingManifest,
            "Family _CHAT.txt" to iosChat
        )
        val winner = WhatsAppTranscript.bestTranscript(candidatesOf(archive))
        assertEquals("Family _CHAT.txt", winner!!.name)
        assertEquals(3, winner.messages)
    }

    @Test
    fun `an archive whose text files hold no timestamps has no transcript at all`() {
        val archive = zipOf("Missing.txt" to missingManifest, "notes.txt" to proseNote)
        val candidates = candidatesOf(archive)
        assertTrue("the manifest is never even read as a candidate", candidates.map { it.name } == listOf("notes.txt"))
        assertNull(WhatsAppTranscript.bestTranscript(candidates))
    }

    @Test
    fun `the probe ignores the folder a transcript is nested in`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("WhatsApp Chat - Ali/_WEBSITES_listing.txt"))
            zip.write(missingManifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("WhatsApp Chat - Ali/Ali Raza _chat.txt"))
            zip.write(androidChat.toByteArray())
            zip.closeEntry()
        }
        val candidates = candidatesOf(out.toByteArray())
        assertEquals(1, candidates.size)
        assertEquals("Ali Raza _chat.txt", WhatsAppTranscript.bestTranscript(candidates)!!.name)
    }

    // --- the title the owner recognises ------------------------------------------------------

    @Test
    fun `the chat name comes out of every export filename dialect`() {
        assertEquals("Ali Raza", WhatsAppTranscript.titleFrom("Ali Raza _chat.txt"))
        assertEquals("Family", WhatsAppTranscript.titleFrom("Family _CHAT.txt"))
        assertEquals("Chat · +9234567890", WhatsAppTranscript.titleFrom("9234567890@c.us _chat.txt"))
        assertEquals("Chat · +9234567890", WhatsAppTranscript.titleFrom("business_chat-9234567890@c.us.txt"))
        assertEquals("Ali Raza", WhatsAppTranscript.titleFrom("WhatsApp Chat with Ali Raza.txt"))
        assertEquals("Ali Raza", WhatsAppTranscript.titleFrom("storage/emulated/0/Download/Ali Raza _chat.zip"))
        assertEquals("WhatsApp chat", WhatsAppTranscript.titleFrom("_WAChatSource.txt"))
        assertEquals("Ali Raza", WhatsAppTranscript.titleFrom("Ali Raza.zip"))
    }

    // --- count parity: import == summary == drawn bubbles -------------------------------------

    @Test
    fun `the reported count, the summary count and the drawn bubbles always agree`() {
        for ((name, text) in chats) {
            val parsed = WhatsAppTranscript.parse(text)
            val drawn = WhatsAppTranscript.messages(text)
            assertFalse("$name must parse as a conversation", parsed.empty)
            assertEquals("$name", drawn.size, WhatsAppTranscript.messageCount(text))
            assertEquals("$name: parse and messages agree", parsed.messageCount, drawn.size)
        }
        assertEquals(5, WhatsAppTranscript.parse(androidChat).messageCount)
        assertEquals(3, WhatsAppTranscript.parse(iosChat).messageCount)
        assertEquals(3, WhatsAppTranscript.parse(businessChat).messageCount)
        assertEquals(3, WhatsAppTranscript.parse(isoChat).messageCount)
        assertEquals(2, WhatsAppTranscript.parse(shortYearChat).messageCount)
        assertEquals("a manifest is not a chat", 0, WhatsAppTranscript.messageCount(missingManifest))
        assertEquals("prose is not a chat", 0, WhatsAppTranscript.messageCount(proseNote))
    }

    /** The note body as [WhatsAppArchiver] writes it: header, marker, transcript, then the proof block. */
    private fun archivedNote(title: String, transcript: String): String {
        val parsed = WhatsAppTranscript.parse(transcript)
        val plan = WhatsAppArchive.plan(parsed.references, emptyList())
        val manifest = WhatsAppArchive.manifest(
            chat = title,
            sourceName = "$title _chat.txt",
            sourcePath = "WhatsApp Exports",
            sourceBytes = transcript.toByteArray().size.toLong(),
            sourceModifiedAt = 1_700_000_000_000L,
            parsed = parsed,
            plan = plan,
            hashes = emptyMap(),
            provenance = "test fixture",
            archivedAt = 1_700_000_000_000L
        )
        return buildString {
            appendLine("WhatsApp archive · $title")
            appendLine(
                "${parsed.messageCount} messages · ${parsed.references.size} files referenced · " +
                    "sealed on this device"
            )
            if (parsed.firstStamp.isNotBlank()) appendLine("Span: ${parsed.firstStamp} → ${parsed.lastStamp}")
            appendLine()
            appendLine(WhatsAppTranscript.CONVERSATION_MARKER)
            append(WhatsAppTranscript.stripBidi(transcript.replace("\r\n", "\n")).trim())
            appendLine()
            appendLine()
            append(manifest)
        }
    }

    @Test
    fun `an archived note draws exactly the messages the archive reported`() {
        for ((name, text) in chats) {
            val reported = WhatsAppTranscript.parse(text).messageCount
            val note = archivedNote(WhatsAppTranscript.titleFrom(name), text)
            assertTrue("$name must open as a chat", WhatsAppTranscript.isChat(note))
            val conversation = WhatsAppTranscript.transcriptOf(note)
            assertEquals("$name: same bubbles", reported, WhatsAppTranscript.messages(conversation).size)
            assertFalse(
                "$name: the proof block must never be glued to the last message",
                conversation.contains(WhatsAppTranscript.PROOF_MARKER)
            )
        }
    }

    @Test
    fun `the proof block cannot masquerade as messages after the conversation`() {
        val note = """
            WhatsApp archive · Ali Raza
            2 messages · 0 files referenced · sealed on this device

            ${WhatsAppTranscript.CONVERSATION_MARKER}
            15/08/2024, 10:23 - Ali Raza: Salaam
            15/08/2024, 10:24 - You: got it
            ${WhatsAppTranscript.PROOF_MARKER}
            2024-08-15, 10:25 - System: this line is bookkeeping, not a message
        """.trimIndent()
        assertEquals(2, WhatsAppTranscript.messages(WhatsAppTranscript.transcriptOf(note)).size)
        assertEquals("got it", WhatsAppTranscript.messages(WhatsAppTranscript.transcriptOf(note)).last().body)
    }

    @Test
    fun `a pasted transcript is a chat without the archive header`() {
        assertTrue(WhatsAppTranscript.isChat(androidChat))
        assertTrue(WhatsAppTranscript.isChat("WhatsApp transcript · Ali Raza\n\n$androidChat"))
        assertFalse(WhatsAppTranscript.isChat(proseNote))
        assertFalse(WhatsAppTranscript.isChat(missingManifest))
    }

    // --- dialects and notice lines ------------------------------------------------------------

    @Test
    fun `each stamp dialect splits into the day and clock the owner saw`() {
        val ios = WhatsAppTranscript.messages(iosChat).first()
        assertEquals("Aug 15, 2024", ios.day)
        assertEquals("10:24:11 AM", ios.clock)
        assertEquals("Ali Raza", ios.sender)

        val iso = WhatsAppTranscript.messages(isoChat).first()
        assertEquals("2024-08-15", iso.day)
        assertEquals("10:23", iso.clock)

        val short = WhatsAppTranscript.messages(shortYearChat).first()
        assertEquals("15/08/24", short.day)
        assertEquals("10:23", short.clock)

        val bracketed = WhatsAppTranscript.messages(businessChat).first()
        assertEquals("15/08/2024", bracketed.day)
        assertEquals("10:23:45", bracketed.clock)
        assertEquals("9234567890", bracketed.sender)
    }

    @Test
    fun `whatsapp notices are marked and never given a speaker`() {
        val text = """
            ${WhatsAppTranscript.CONVERSATION_MARKER}
            15/08/2024, 10:22 - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
            15/08/2024, 10:23 - Ali Raza: Salaam
            15/08/2024, 10:24 - Missed voice call
            15/08/2024, 10:25 - You: <Media omitted>
        """.trimIndent()
        val rows = WhatsAppTranscript.messages(text)
        assertEquals(4, rows.size)
        assertTrue(rows[0].notice)
        assertNull(rows[0].sender)
        assertFalse(rows[1].notice)
        assertEquals("Ali Raza", rows[1].sender)
        assertTrue(rows[2].notice)
        assertNull(rows[2].sender)
        assertFalse("an omitted media line is still the sender's message", rows[3].notice)
        assertEquals(1, WhatsAppTranscript.parse(text).omittedMedia)
        assertEquals(listOf("Ali Raza", "You"), WhatsAppTranscript.parse(text).participants)
    }

    @Test
    fun `wrapped lines belong to the message above and do not change the count`() {
        val text = """
            15/08/2024, 10:23 - Ali Raza: first line
            second line of the same message

            15/08/2024, 10:24 - You: ok
        """.trimIndent()
        assertEquals(2, WhatsAppTranscript.parse(text).messageCount)
        assertEquals(2, WhatsAppTranscript.messages(text).size)
        assertEquals("first line\nsecond line of the same message", WhatsAppTranscript.messages(text)[0].body)
    }

    @Test
    fun `the attachment a message carries is listed on that message only`() {
        val rows = WhatsAppTranscript.messages(androidChat)
        assertEquals(listOf("IMG-20240815-WA0007.jpg"), rows[1].files)
        assertEquals("", rows[1].body)
        assertEquals(listOf("PTT-20240816-WA0001.opus"), rows[3].files)
        assertTrue(rows[0].files.isEmpty())
        assertTrue(rows[2].files.isEmpty())
        assertEquals(
            listOf("IMG-20240815-WA0007.jpg", "PTT-20240816-WA0001.opus"),
            WhatsAppTranscript.parse(androidChat).references.map { it.fileName }
        )
    }

    @Test
    fun `the same chat hashes to the same digest however whatsapp wraps its lines`() {
        val crlf = androidChat.replace("\n", "\r\n")
        val marked = "\u200e$crlf\u200f"
        assertEquals(
            WhatsAppTranscript.parse(androidChat).sha256,
            WhatsAppTranscript.parse(marked).sha256
        )
        assertEquals(64, WhatsAppTranscript.parse(marked).sha256.length)
    }
}
