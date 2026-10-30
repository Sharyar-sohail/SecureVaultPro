package com.securevault.pro.data.imports

import com.securevault.pro.data.imports.WhatsAppTranscript.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The part that decides whether "open this chat and its files" tells the truth: every tier of
 * the transcript-to-disk join, and the proof block that makes the archive an evidence copy.
 */
class WhatsAppArchiveTest {

    /**
     * Same-day matching is judged in the phone's own timezone, so the fixtures build epoch
     * millis from a local calendar instead of hard-coding a UTC instant that lands on the
     * wrong day east or west of Greenwich.
     */
    private fun localStamp(day: Int, hour: Int = 10, minute: Int = 30, second: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.AUGUST, day, hour, minute, second)
        }.timeInMillis

    private fun doc(name: String, path: String = "WhatsApp Media/Images/$name", size: Long = 1_000L) =
        WhatsAppArchive.MediaDoc(name = name, relativePath = path, size = size, modifiedAt = 0L)

    private fun timedDoc(name: String, folder: String, millis: Long) =
        WhatsAppArchive.MediaDoc(name = name, relativePath = "$folder/$name", size = 1_000L, modifiedAt = millis)

    private fun ref(name: String, attached: Boolean = true, line: Int = 1) =
        WhatsAppTranscript.Reference(
            fileName = name,
            kind = WhatsAppTranscript.kindOf(name),
            line = line,
            attached = attached
        )

    @Test
    fun `an exact filename joins first`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0007.jpg")),
            docs = listOf(doc("IMG-20240815-WA0007.jpg"))
        )
        assertEquals(1, plan.found)
        assertEquals("exact", plan.matches[0].how)
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `the photo bundled inside the export is the one sealed`() {
        val insideZip = WhatsAppArchive.MediaDoc(
            name = "IMG-20240815-WA0007.jpg",
            relativePath = "zip:WhatsApp Chat with Ali/IMG-20240815-WA0007.jpg",
            size = 2_000L,
            modifiedAt = 0L
        )
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0007.jpg")),
            docs = listOf(insideZip, doc("IMG-20240815-WA0007.jpg"))
        )
        assertEquals(1, plan.found)
        assertEquals(insideZip.relativePath, plan.matches[0].doc.relativePath)
    }

    @Test
    fun `a renamed photo still joins through date and sequence`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0007.jpg")),
            docs = listOf(doc("img-20240815-wa0007 (1).jpeg"))
        )
        assertEquals(1, plan.found)
        assertEquals("equivalent", plan.matches[0].how)
    }

    @Test
    fun `the same photo is never handed to two lines`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0007.jpg"), ref("IMG-20240815-WA0007.jpg")),
            docs = listOf(doc("IMG-20240815-WA0007.jpg"))
        )
        assertEquals(1, plan.found)
        assertEquals(1, plan.missing.size)
    }

    @Test
    fun `a prose mention cannot steal a real attachment`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(
                ref("IMG-20240815-WA0009.jpg", attached = false, line = 2),
                ref("IMG-20240815-WA0009.jpg", attached = true, line = 9)
            ),
            docs = listOf(doc("IMG-20240815-WA0009.jpg"))
        )
        assertEquals(1, plan.found)
        assertEquals(9, plan.matches[0].reference.line)
        assertTrue(plan.matches[0].reference.attached)
    }

    @Test
    fun `a referenced name with no date is never guessed at`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("photo from holiday.jpg")),
            docs = listOf(doc("IMG-20240815-WA0012.jpg"))
        )
        // Neither side carries a comparable date, so the honest answer is "not found".
        assertEquals(0, plan.found)
        assertEquals(1, plan.missing.size)
    }

    @Test
    fun `a file the owner renamed still joins on the day it was written`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0044.jpg")),
            docs = listOf(timedDoc("holiday photo.jpg", "WhatsApp Media/Images", localStamp(15)))
        )
        assertEquals(1, plan.found)
        assertEquals("same day", plan.matches[0].how)
        assertEquals(1_000L, plan.bytes)
    }

    @Test
    fun `a crowded day is refused unless the clock times agree`() {
        val crowded = (1..5).map { index ->
            timedDoc(
                "holiday $index.jpg",
                "WhatsApp Media/Images",
                localStamp(15, 10, 31, 0) + index * 60_000L
            )
        }
        val ambiguous = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0044.jpg")),
            docs = crowded
        )
        assertEquals("five photos on one day is not a match", 0, ambiguous.found)

        val timed = WhatsAppArchive.plan(
            references = listOf(ref("WhatsApp Image 2024-08-15 at 10.33.00.jpeg")),
            docs = crowded
        )
        assertEquals(1, timed.found)
        assertEquals("same day", timed.matches[0].how)
        assertEquals("holiday 2.jpg", timed.matches[0].doc.name)
    }

    @Test
    fun `a same day join never crosses the media type`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("IMG-20240815-WA0044.jpg")),
            docs = listOf(timedDoc("VID-20240815-WA0001.mp4", "WhatsApp Media/Video", localStamp(15)))
        )
        assertEquals(0, plan.found)
    }

    @Test
    fun `a filename buried in a sentence still finds the file`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(ref("the signed Report FINAL.pdf")),
            docs = listOf(doc("Report FINAL.pdf", "WhatsApp Media/Documents/Report FINAL.pdf"))
        )
        assertEquals(1, plan.found)
        assertEquals("in name", plan.matches[0].how)
    }

    @Test
    fun `counts and missing groups are reported per kind`() {
        val plan = WhatsAppArchive.plan(
            references = listOf(
                ref("IMG-20240815-WA0007.jpg"),
                ref("VID-20240815-WA0002.mp4"),
                ref("PTT-20240816-WA0001.opus"),
                ref("Invoices 2024.pdf")
            ),
            docs = listOf(
                doc("IMG-20240815-WA0007.jpg", "WhatsApp Media/Images/IMG-20240815-WA0007.jpg", 1_000L),
                doc("VID-20240815-WA0002.mp4", "WhatsApp Media/Video/VID-20240815-WA0002.mp4", 9_000L)
            )
        )
        assertEquals(2, plan.found)
        assertEquals(1, plan.images)
        assertEquals(1, plan.videos)
        assertEquals(0, plan.audio)
        assertEquals(10_000L, plan.bytes)
        val missing = WhatsAppArchive.missingByKind(plan)
        assertEquals(setOf(MediaKind.Audio, MediaKind.Document), missing.keys)
        assertEquals("PTT-20240816-WA0001.opus", missing[MediaKind.Audio]!![0].fileName)
    }

    @Test
    fun `the manifest names the source, the digest and every gap`() {
        val parsed = WhatsAppTranscript.parse(
            """
                15/08/2024, 10:23 - Ali Raza: Salaam
                15/08/2024, 10:24 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)
                16/08/2024, 21:02 - You: PTT-20240816-WA0001.opus (file attached)
            """.trimIndent()
        )
        val plan = WhatsAppArchive.plan(
            references = parsed.references,
            docs = listOf(doc("IMG-20240815-WA0007.jpg", size = 1_536_000L))
        )
        val manifest = WhatsAppArchive.manifest(
            chat = "Ali Raza",
            sourceName = "WhatsApp Chat with Ali Raza.txt",
            sourcePath = "Internal storage/WhatsApp Exports",
            sourceBytes = 40_000L,
            sourceModifiedAt = 1_723_700_000_000L,
            parsed = parsed,
            plan = plan,
            hashes = mapOf("WhatsApp Media/Images/IMG-20240815-WA0007.jpg" to "abcdef0123456789" + "0".repeat(48)),
            provenance = "this phone",
            archivedAt = 1_723_700_000_000L
        )

        assertTrue(manifest.contains("Chat: Ali Raza"))
        assertTrue(manifest.contains("Source file: WhatsApp Chat with Ali Raza.txt"))
        assertTrue("the digest of the transcript itself is the evidence", manifest.contains(parsed.sha256))
        assertTrue(manifest.contains("Messages: 3"))
        assertTrue(manifest.contains("Ali Raza"))
        assertTrue(manifest.contains("1.5 MB"))
        assertTrue(manifest.contains("Joined by filename: 1"))
        assertTrue(manifest.contains("abcdef0123456789"))
        assertTrue("a gap is stated, never hidden", manifest.contains("PTT-20240816-WA0001.opus"))
        assertTrue(manifest.contains("not on this phone: 1"))
        assertTrue(manifest.contains("AES-256-GCM"))
    }

    @Test
    fun `a transcript read end to end joins to the folder without a manual pick`() {
        val parsed = WhatsAppTranscript.parse(
            """
                15/08/2024, 10:23 - Ali Raza: Salaam
                15/08/2024, 10:24 - Ali Raza: IMG-20240815-WA0007.jpg (file attached)
                15/08/2024, 10:30 - Ali Raza: Business Report FINAL.pdf (file attached)
                16/08/2024, 21:02 - You: PTT-20240816-WA0001.opus (file attached)
            """.trimIndent()
        )
        assertEquals(3, parsed.references.size)
        assertEquals(
            listOf("IMG-20240815-WA0007.jpg", "Business Report FINAL.pdf", "PTT-20240816-WA0001.opus"),
            parsed.references.map { it.fileName })
        val plan = WhatsAppArchive.plan(
            references = parsed.references,
            docs = listOf(
                doc("IMG-20240815-WA0007.jpg", "WhatsApp Media/Images/IMG-20240815-WA0007.jpg"),
                doc("Business Report FINAL.pdf", "WhatsApp Media/Documents/Business Report FINAL.pdf"),
                doc("PTT-20240816-WA0001.opus", "WhatsApp Media/Voice Notes/PTT-20240816-WA0001.opus")
            )
        )
        assertEquals(3, plan.found)
        assertTrue(plan.matches.all { it.how == "exact" })
    }
}
