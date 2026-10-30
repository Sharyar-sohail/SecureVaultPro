package com.securevault.pro.data.imports

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * One pass over a real WhatsApp "Export chat (with media)" archive.
 *
 * It lives here, away from Android and away from the vault, for one reason: the archive walk is
 * where every import the owner has ever complained about actually broke. The transcript is not
 * always where the file name says it is — Android writes `Ali _chat.txt`, iOS writes
 * `WPP00001.txt` or `_…_media.txt`, a Business re-export writes `business_chat-…@c.us.txt` and
 * drops `_MISSING.txt` beside it, and the media sits at the root as `_MEDIA001.mp4` next to the
 * familiar `IMG-…jpg`. Deciding that needs nothing but the bytes, so it is decided here and
 * covered by [WhatsAppZipReaderTest] instead of by a handset.
 *
 * Media is handed to [onMedia] as it is read and never kept in a list, so a 250 MB archive costs
 * one attachment of peak memory, exactly as before.
 */
object WhatsAppZipReader {

    /** What the archive holds, once the walk is finished. */
    data class Found(
        val title: String,
        val transcript: String,
        val messages: Int,
        val sealed: Int,
        val skipped: Int,
        val transcriptsSeen: Int,
        val warnings: List<String>,
        /** The winning `.txt`'s own name, so the caller can say which file the chat came out of. */
        val transcriptName: String = "",
        /** WhatsApp's structured messages table, when the archive carried one beside the text. */
        val structured: WhatsAppStructuredCsv.Structured? = null,
        /** Every non-transcript file the archive held, by name: the pool the chat's references are checked against. */
        val mediaNames: List<String> = emptyList(),
        /** WhatsApp's own bookkeeping (`_MISSING.txt`, `_manifest.xml`, `_media.xml`) read and set aside. */
        val bookkeepingSeen: Int = 0
    ) {
        /**
         * The conversation the owner gets: the transcript when it carries bubbles, otherwise the
         * structured table. A `.txt` that parsed to nothing is not a conversation, and a Business
         * export can ship only the table — that chat is still real and must still import.
         */
        val conversation: String get() = if (messages > 0) transcript else structured?.transcript.orEmpty()

        /** True when the archive carried a conversation at all — the difference between a chat and a folder of photos. */
        val hasConversation: Boolean get() = conversation.isNotBlank() && WhatsAppTranscript.messageCount(conversation) > 0

        /** Bubbles in [conversation] — never a guess, always what the bytes carry. */
        val conversationMessages: Int get() = if (messages > 0) messages else structured?.messages ?: 0

        /** True when the archive held neither words nor a usable table: files only. */
        val nothingToImport: Boolean get() = conversation.isBlank() && sealed == 0
    }

    const val MAX_TEXT_BYTES = 25L * 1024L * 1024L
    const val MAX_MEDIA_BYTES = 100L * 1024L * 1024L
    const val MAX_TOTAL_MEDIA_BYTES = 600L * 1024L * 1024L
    const val MAX_ENTRIES = 4_000

    /** The transcript is missing and the archive holds only files — the UI says this, never a blank list. */
    const val NO_TRANSCRIPT_HINT =
        "That archive holds WhatsApp's files but no chat transcript, so there is no conversation to " +
            "import. In WhatsApp open the chat → ⋮ → More → Export chat and choose \"Attach media\" so " +
            "the words and the files travel together."

    /** Every `.txt` in the archive failed the timestamp probe — the owner got a document, not a chat. */
    const val NO_TIMESTAMPS_HINT = WhatsAppImporter.NO_TIMESTAMPS_HINT

    /**
     * Reads the archive. [onMedia] receives every attachment name and its bytes and answers whether
     * it landed in the vault, which is how skipped files are counted without this object knowing
     * anything about encryption.
     */
    suspend fun read(
        source: InputStream,
        archiveName: String,
        onProgress: (String, Int) -> Unit = { _, _ -> },
        onMedia: suspend (name: String, bytes: ByteArray) -> Boolean
    ): Found = ZipInputStream(source.buffered()).use { zip ->
        val warnings = mutableListOf<String>()
        val mediaNames = ArrayList<String>()
        var entries = 0
        var sealed = 0
        var skipped = 0
        var bookkeeping = 0
        var totalMediaBytes = 0L
        var totalCapWarned = false
        var transcriptsSeen = 0
        var messages = 0
        var transcript = ""
        var transcriptName = ""
        var transcriptBytes: ByteArray? = null
        var structured: WhatsAppStructuredCsv.Structured? = null

        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            if (entries > MAX_ENTRIES) {
                warnings += "Stopped after $MAX_ENTRIES entries — the rest of the archive was left alone."
                break
            }
            val rawName = entry.name.replace('\\', '/')
            val fileName = rawName.substringAfterLast('/')
            if (entry.isDirectory || rawName.startsWith("__MACOSX/") || fileName.startsWith(".")) {
                zip.closeEntry()
                continue
            }
            if (WhatsAppFormat.isStructuredMessages(fileName)) {
                // The table is read for the conversation it holds and never sealed as a file of the
                // chat — it is WhatsApp's own export of the same bubbles, column by column.
                val tableBytes = readEntryCapped(zip, MAX_TEXT_BYTES)
                zip.closeEntry()
                if (tableBytes == null) {
                    warnings += "$fileName is larger than the ${MAX_TEXT_BYTES / (1024 * 1024)} MB " +
                        "text limit and was skipped."
                    continue
                }
                val table = WhatsAppStructuredCsv
                    .parse(tableBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                if (table != null && table.usable) {
                    if (structured == null || table.messages > structured!!.messages) structured = table
                } else {
                    warnings += "$fileName is not a WhatsApp messages table — it has no sender and no " +
                        "content column, so none of its rows were read."
                }
                continue
            }
            if (WhatsAppFormat.isTranscriptCandidate(fileName)) {
                transcriptsSeen++
                val bytes = readEntryCapped(zip, MAX_TEXT_BYTES)
                zip.closeEntry()
                if (bytes == null) {
                    skipped++
                    warnings += "$fileName is larger than the ${MAX_TEXT_BYTES / (1024 * 1024)} MB " +
                        "text limit and was skipped."
                    continue
                }
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val counted = WhatsAppTranscript.messageCount(text)
                val better = counted > messages ||
                    (counted == messages && counted > 0 &&
                        WhatsAppFormat.transcriptRank(rawName) > WhatsAppFormat.transcriptRank(transcriptName))
                if (better) {
                    // The previous winner was not the chat after all; keep its bytes as an attachment
                    // rather than losing them, exactly as the old inline walk did.
                    transcriptBytes?.let { previous ->
                        if (onMedia(shortName(transcriptName), previous)) sealed++ else skipped++
                    }
                    transcript = text
                    transcriptBytes = bytes
                    transcriptName = rawName
                    messages = counted
                } else {
                    if (onMedia(fileName, bytes)) sealed++ else skipped++
                    mediaNames += fileName
                }
                onProgress("Reading chat transcript", 30)
                continue
            }
            if (WhatsAppFormat.isBookkeeping(fileName)) {
                bookkeeping++
                zip.closeEntry()
                continue
            }
            val entrySize = if (entry.size > 0) entry.size else -1L
            if (entrySize > MAX_MEDIA_BYTES) {
                skipped++
                warnings += oversize(fileName)
                zip.closeEntry()
                continue
            }
            if (totalMediaBytes >= MAX_TOTAL_MEDIA_BYTES) {
                skipped++
                if (!totalCapWarned) {
                    totalCapWarned = true
                    warnings += "Media stopped at the ${MAX_TOTAL_MEDIA_BYTES / (1024 * 1024)} MB " +
                        "export limit; the rest was left in the file."
                }
                zip.closeEntry()
                continue
            }
            val bytes = readEntryCapped(zip, MAX_MEDIA_BYTES)
            zip.closeEntry()
            if (bytes == null) {
                skipped++
                warnings += oversize(fileName)
                continue
            }
            if (bytes.isEmpty()) continue
            totalMediaBytes += bytes.size
            mediaNames += fileName
            if (onMedia(fileName, bytes)) sealed++ else skipped++
            onProgress("Sealing media", (30 + entries * 3).coerceIn(30, 92))
        }

        Found(
            title = WhatsAppFormat.chatTitle(archiveName, transcriptName),
            transcript = transcript,
            messages = messages,
            sealed = sealed,
            skipped = skipped,
            transcriptsSeen = transcriptsSeen,
            warnings = warnings,
            transcriptName = shortName(transcriptName),
            structured = structured,
            mediaNames = mediaNames,
            bookkeepingSeen = bookkeeping
        )
    }

    private fun shortName(path: String): String = path.substringAfterLast('/')

    private fun oversize(fileName: String): String =
        "$fileName is larger than the ${MAX_MEDIA_BYTES / (1024 * 1024)} MB media limit and was skipped."

    /** Reads one entry fully, draining it; null when it exceeds [limit] so the caller can skip cleanly. */
    private fun readEntryCapped(stream: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var oversize = false
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            if (oversize) continue
            total += read
            if (total > limit) {
                oversize = true
                out.reset()
            } else {
                out.write(buffer, 0, read)
            }
        }
        return if (oversize) null else out.toByteArray()
    }
}
