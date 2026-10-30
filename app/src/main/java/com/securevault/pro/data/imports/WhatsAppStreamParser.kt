package com.securevault.pro.data.imports

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import kotlin.math.min

/**
 * Reads a WhatsApp transcript a line at a time, off any stream, without ever holding the file.
 *
 * The importer used to turn the whole export into one `String` before parsing it. That is the
 * shortest route to a working feature and the shortest route to an `OutOfMemoryError` on the owner's
 * phone: years of one chat export as several hundred megabytes of text, a "without media" export of
 * a big group can pass a gigabyte, and Android gives this process a few hundred megabytes of heap —
 * and the old code held *two copies* of the bytes on the way to the decoder. The owner saw the
 * import fail with a size complaint, or saw nothing at all.
 *
 * What is bounded here, and by how much:
 *  - [READ_CHUNK_CHARS] UTF-16 characters per read (16 KiB ≈ 32 KiB of heap) — the only buffer that
 *    grows with anything;
 *  - [MAX_LINE_CHARS] characters of one assembled line (256 KiB); a longer line is truncated,
 *    counted and abandoned rather than buffered without limit — a WhatsApp message line is a
 *    message, and a "message" that runs to tens of megabytes is a binary file renamed `.txt`;
 *  - [MAX_PARTICIPANTS] distinct speaker names and [MAX_REFERENCES] attachment names: the only two
 *    lists kept, both of strings the size of a name rather than of the chat;
 *  - one bubble in flight per [Listener.onMessage] call. Batching those into the database is
 *    [WhatsAppStreamingImporter]'s job.
 *
 * The digest is taken over the **bytes** handed to the decoder, through [CountedDigestInputStream], so it
 * identifies the transcript itself and not this parser's opinion of it: re-picking the same file
 * after a cancellation is the same digest, and that is what lets a resume start where the last run
 * stopped instead of duplicating every bubble.
 */
object WhatsAppStreamParser {

    /** 16 KiB of characters, about 32 KiB of heap — the buffer that reads the file. */
    const val READ_CHUNK_CHARS = 16 * 1024

    /** One line may not exceed 256 KiB of characters; past that it is truncated and counted. */
    const val MAX_LINE_CHARS = 256 * 1024

    /** Speaker names kept for grouping; past this the list is marked incomplete, not wrong. */
    const val MAX_PARTICIPANTS = 200

    /** Attachment names kept so media can be paired with the bubble that mentions it. */
    const val MAX_REFERENCES = 20_000

    /** What a parse produced. Nothing in it grows with the size of the file. */
    data class Report(
        val messages: Int,
        val participants: List<String>,
        val firstStamp: String,
        val lastStamp: String,
        val references: List<WhatsAppTranscript.Reference>,
        val bytes: Long,
        val sha256: String,
        val truncatedLines: Int,
        val participantsCapped: Boolean,
        val referencesCapped: Boolean
    )

    /** What a streaming read needs from the caller. Called on the caller's thread. */
    interface Listener {
        /** A finished bubble, in transcript order, with continuation lines already folded in. */
        fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int)

        /** Bytes of the transcript consumed so far, so progress can move without a second pass. */
        fun onBytes(totalBytes: Long) {}
    }

    /** Raised out of [read] when the caller asked to stop, so the importer can roll the batch back. */
    class Stopped(message: String) : IllegalStateException(message)

    /**
     * Reads [source] and emits the conversation into [listener].
     *
     * [shouldStop] is asked once per line, so the owner's Stop button lands within a line of being
     * pressed rather than after the next megabyte.
     */
    fun read(
        source: InputStream,
        listener: Listener,
        shouldStop: () -> Boolean = { false }
    ): Report {
        val sha = MessageDigest.getInstance("SHA-256")
        // java's DigestInputStream hides its byte counter (getNumRead is protected), and progress
        // needs the exact byte count as the file streams past, so the counting lives here.
        val counted = CountedDigestInputStream(BufferedInputStream(source, READ_CHUNK_CHARS / 2), sha)
        val reader = InputStreamReader(counted, Charsets.UTF_8)
        try {
            val chunk = CharArray(READ_CHUNK_CHARS)
            val line = StringBuilder(min(READ_CHUNK_CHARS, 8 * 1024))
            val state = LineState()
            var lineIndex = 0
            var reportedBytes = 0L
            var overflowing = false
            var stopped = false

            while (!stopped) {
                val read = reader.read(chunk)
                if (read <= 0) break
                var index = if (state.atStart) skipBom(chunk, read) else 0
                state.atStart = false
                while (index < read) {
                    val char = chunk[index]
                    index++
                    if (char == '\n') {
                        if (overflowing) {
                            state.truncatedLines++
                            overflowing = false
                        }
                        if (!state.consume(line.toString(), listener, lineIndex, shouldStop)) {
                            stopped = true
                            break
                        }
                        lineIndex++
                        line.setLength(0)
                        continue
                    }
                    if (char == '\r') continue
                    if (line.length >= MAX_LINE_CHARS) {
                        overflowing = true
                        continue
                    }
                    line.append(char)
                }
                if (counted.totalBytesRead > reportedBytes) {
                    reportedBytes = counted.totalBytesRead
                    listener.onBytes(reportedBytes)
                }
            }
            if (stopped) throw Stopped("The import was stopped before the conversation finished reading.")
            if (overflowing) state.truncatedLines++
            if (line.isNotEmpty()) state.consume(line.toString(), listener, lineIndex, shouldStop)
            state.finish(listener)
            return state.report(
                bytes = counted.totalBytesRead,
                sha256 = sha.digest().joinToString("") { "%02x".format(it) },
                truncatedLines = state.truncatedLines
            )
        } finally {
            runCatching { reader.close() }
        }
    }

    /** The BOM some handsets write is not part of the first message. */
    private fun skipBom(buffer: CharArray, length: Int): Int =
        if (length > 0 && buffer[0].code == 0xFEFF) 1 else 0

    /** The running totals of one read, kept out of [read] so the loop stays a loop. */
    private class LineState {
        var atStart = true
        var sawStamp = false
        var truncatedLines = 0
        var emitted = 0
        var firstStamp = ""
        var lastStamp = ""
        var participantsCapped = false
        var referencesCapped = false
        val counts = LinkedHashMap<String, Int>(16)
        val references = LinkedHashMap<String, WhatsAppTranscript.Reference>(64)
        var pending: WhatsAppTranscript.ChatMessage? = null
        var pendingBody = StringBuilder()
        var pendingFiles = mutableListOf<String>()

        /**
         * One line, folded into the message above it or started as a new one. Answers false when the
         * caller asked to stop, which is how the read unwinds.
         */
        fun consume(
            raw: String,
            listener: Listener,
            index: Int,
            shouldStop: () -> Boolean
        ): Boolean {
            if (shouldStop()) return false
            val line = WhatsAppTranscript.stripBidi(raw).trim()
            if (line.isEmpty()) {
                if (pending != null && pendingBody.isNotEmpty()) pendingBody.append('\n')
                return true
            }
            val stamped = WhatsAppTranscript.stampedMessage(line, index)
            if (stamped == null) {
                // Unstamped before the first message is the export's own header — the `app.txt` and
                // `_hash` lines, WhatsApp's credits — and belongs to no bubble. Unstamped after one
                // is a wrapped message and joins the bubble above it.
                if (pending != null) {
                    if (pendingBody.isNotEmpty()) pendingBody.append('\n')
                    val extra = WhatsAppTranscript.attachmentsIn(line, index)
                    if (extra.isEmpty()) {
                        pendingBody.append(line)
                    } else {
                        extra.forEach { name -> if (name !in pendingFiles) pendingFiles += name }
                        pendingBody.append(WhatsAppTranscript.bodyWithoutAttachments(line, extra).ifBlank { line })
                    }
                }
                return true
            }
            sawStamp = true
            pending?.let { flush(it, listener) }
            stamped.sender?.let { name ->
                if (counts.containsKey(name) || counts.size < MAX_PARTICIPANTS) {
                    counts[name] = (counts[name] ?: 0) + 1
                } else {
                    participantsCapped = true
                }
            }
            val stamp = "${stamped.day} ${stamped.clock}".trim()
            if (firstStamp.isBlank()) firstStamp = stamp
            lastStamp = stamp
            WhatsAppTranscript.referencesIn(line, index).forEach { reference ->
                val key = reference.fileName.lowercase()
                if (references.containsKey(key) || references.size < MAX_REFERENCES) {
                    val existing = references[key]
                    // The stronger fact wins: a name mentioned in prose and later attached is an
                    // attachment, exactly as [WhatsAppTranscript.parse] decides it.
                    if (existing == null || (reference.attached && !existing.attached)) references[key] = reference
                } else {
                    referencesCapped = true
                }
            }
            pending = stamped
            pendingFiles = stamped.files.toMutableList()
            pendingBody = StringBuilder(stamped.body)
            return true
        }

        private fun flush(message: WhatsAppTranscript.ChatMessage, listener: Listener) {
            listener.onMessage(message.copy(body = pendingBody.toString().trim(), files = pendingFiles), emitted)
            emitted++
        }

        /** The last bubble of the file, which has no following stamp to close it. */
        fun finish(listener: Listener) {
            pending?.let { flush(it, listener) }
        }

        fun report(bytes: Long, sha256: String, truncatedLines: Int): Report = Report(
            messages = emitted,
            participants = counts.entries.sortedByDescending { it.value }.map { it.key },
            firstStamp = firstStamp,
            lastStamp = lastStamp,
            references = references.values.toList(),
            bytes = bytes,
            sha256 = sha256,
            truncatedLines = truncatedLines,
            participantsCapped = participantsCapped,
            referencesCapped = referencesCapped
        )
    }
}

/**
 * Streams bytes through a SHA-256 digest while counting them.
 *
 * The JDK's `DigestInputStream` counts too, but its counter is protected, so it cannot be read from
 * here. The count is what drives `onBytes`, and progress reported in characters instead of bytes
 * would move at the wrong rate on a UTF-8 export with non-Latin text.
 */
internal class CountedDigestInputStream(
    private val source: InputStream,
    private val digest: MessageDigest
) : InputStream() {

    var totalBytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) {
            digest.update(value.toByte())
            totalBytesRead++
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = source.read(buffer, offset, length)
        if (count > 0) {
            digest.update(buffer, offset, count)
            totalBytesRead += count
        }
        return count
    }

    override fun available(): Int = source.available()

    override fun close() {
        source.close()
    }
}
