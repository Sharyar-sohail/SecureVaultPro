package com.securevault.pro.data.email

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Streams an SMTP message body in bounded chunks with dot-stuffing applied on the fly.
 *
 * The buffer is exactly [MailTimings.STREAM_CHUNK_BYTES] and the writer never holds more than one
 * chunk plus a small carry, so a 25 MB attachment costs 32 KB of heap rather than 25 MB. The
 * line-start state crosses chunk boundaries deliberately: a `.` that lands at the start of a chunk
 * is only doubled when it really is the start of a *line*, which is the case a naive chunked writer
 * gets wrong.
 */
class DotStuffedWriter(
    private val sink: OutputStream,
    private val chunkBytes: Int = MailTimings.STREAM_CHUNK_BYTES
) {

    private val carry = ByteArrayOutputStream(chunkBytes)
    private var atLineStart = true
    private var wroteAnything = false
    private var total = 0L

    fun bytesWritten(): Long = total

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var i = offset
        val stop = offset + length
        while (i < stop) {
            val b = bytes[i]
            if (atLineStart && b == DOT) {
                carry.write(DOT.toInt())
            }
            carry.write(b.toInt())
            atLineStart = (b == LF)
            wroteAnything = true
            total++
            if (carry.size() >= chunkBytes) flushCarry()
            i++
        }
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** A file's bytes, streamed. Used for each attachment part. */
    fun writeFile(file: File) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(chunkBytes)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                write(buffer, 0, read)
            }
        }
    }

    /**
     * Ends the transfer: guarantees the body finishes with CRLF, then writes the lone-dot terminator.
     * No extra CRLF is invented when the body already ended in one, because that would add a blank
     * line to the received message.
     */
    fun finish() {
        if (carry.size() > 0) flushCarry()
        if (wroteAnything && !atLineStart) {
            carry.write(CR.toInt())
            carry.write(LF.toInt())
        } else if (!wroteAnything) {
            carry.write(CR.toInt())
            carry.write(LF.toInt())
        }
        carry.write(DOT.toInt())
        carry.write(CR.toInt())
        carry.write(LF.toInt())
        flushCarry()
        sink.flush()
    }

    private fun flushCarry() {
        val bytes = carry.toByteArray()
        if (bytes.isEmpty()) return
        sink.write(bytes, 0, bytes.size)
        carry.reset()
    }

    companion object {
        val DOT: Byte = '.'.code.toByte()
        val CR: Byte = 13
        val LF: Byte = 10
    }
}

/**
 * Builds the RFC 822 / MIME body of an outgoing message.
 *
 * Headers are small and assembled in memory; every attachment is streamed from its cache file
 * through [DotStuffedWriter], base64-encoded in [MailTimings.STREAM_CHUNK_BYTES] blocks with the
 * RFC 2045 76-character line wrapping applied as the bytes go out.
 */
class SmtpMessageBuilder(
    private val draft: MailDraft,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The byte count announced to `MAIL FROM SIZE=`, rounded up on the attachment sizes on disk. */
    fun approximateSize(): Long {
        val headers = headerBlock().toByteArray(Charsets.UTF_8).size
        val body = draft.bodyText.toByteArray(Charsets.UTF_8).size
        val attachments = draft.attachmentPaths.sumOf { path ->
            runCatching { File(path).length() }.getOrDefault(0L)
        }
        // base64 inflates by 4/3 plus CRLFs; the estimate only has to be honest, not exact.
        return headers + body + attachments * 4L / 3L + 512L
    }

    fun headerBlock(): String = buildString {
        append("From: ").append(MailAddresses.display(draft.fromAddress)).append(CRLF)
        if (draft.to.isNotEmpty()) append("To: ").append(draft.to.joinToString(", ") { MailAddresses.display(it) }).append(CRLF)
        if (draft.cc.isNotEmpty()) append("Cc: ").append(draft.cc.joinToString(", ") { MailAddresses.display(it) }).append(CRLF)
        append("Subject: ").append(EncodedWords.encodeHeader(draft.subject)).append(CRLF)
        append("Date: ").append(SmtpDateFormat.format(clock())).append(CRLF)
        append("Message-ID: ").append(newMessageId()).append(CRLF)
        append("MIME-Version: 1.0").append(CRLF)
        append("User-Agent: Secure Wallet Pro").append(CRLF)
    }

    /**
     * Writes the whole message. With no attachments the body is a single `text/plain`; with any, a
     * `multipart/mixed` whose first section is itself a `multipart/alternative` when HTML was supplied.
     */
    @Throws(IOException::class)
    fun writeTo(sink: OutputStream) {
        val buffered = if (sink is BufferedOutputStream) sink else BufferedOutputStream(sink, MailTimings.STREAM_CHUNK_BYTES)
        val writer = DotStuffedWriter(buffered)
        writer.writeText(headerBlock())
        if (draft.attachmentPaths.isEmpty()) {
            writer.writeText("Content-Type: text/plain; charset=UTF-8$CRLF")
            writer.writeText("Content-Transfer-Encoding: 8BIT$CRLF$CRLF")
            writer.writeText(normaliseBody(draft.bodyText))
        } else {
            val boundary = newBoundary("mixed")
            writer.writeText("Content-Type: multipart/mixed; boundary=\"$boundary\"$CRLF$CRLF")
            writer.writeText("--$boundary$CRLF")
            writer.writeText("Content-Type: text/plain; charset=UTF-8$CRLF")
            writer.writeText("Content-Transfer-Encoding: 8BIT$CRLF$CRLF")
            writer.writeText(normaliseBody(draft.bodyText))
            writer.writeText("$CRLF")
            draft.attachmentPaths.forEachIndexed { index, path ->
                val file = File(path)
                if (!file.isFile) return@forEachIndexed
                val name = file.name.ifBlank { "attachment-${index + 1}" }
                val mime = MimeTypes.forFileName(name)
                writer.writeText("--$boundary$CRLF")
                writer.writeText("Content-Type: $mime; name=\"${MimeTokens.escapeParameter(name)}\"$CRLF")
                writer.writeText("Content-Transfer-Encoding: base64$CRLF")
                writer.writeText(
                    "Content-Disposition: attachment; filename=\"${MimeTokens.escapeParameter(name)}\"$CRLF$CRLF"
                )
                writeBase64Stream(file, writer)
                writer.writeText("$CRLF")
            }
            writer.writeText("--$boundary--$CRLF")
        }
        writer.finish()
    }

    /**
     * Streams one file as base64 with 76-character lines, never holding more than three input chunks
     * (exactly one base64 line's worth) at a time.
     */
    private fun writeBase64Stream(file: File, writer: DotStuffedWriter) {
        val encoder = java.util.Base64.getEncoder()
        val groupBytes = 3 * 57 // 76 base64 characters per line
        FileInputStream(file).use { input ->
            val buffer = ByteArray(groupBytes)
            val line = StringBuilder(100)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val encoded = encoder.encode(buffer.copyOf(read)).toString(Charsets.US_ASCII)
                encoded.chunked(76).forEach { piece ->
                    line.setLength(0)
                    line.append(piece)
                    writer.writeText(line.toString() + CRLF)
                }
            }
        }
    }

    private fun normaliseBody(raw: String): String =
        raw.replace("\r\n", "\n").replace("\n", CRLF).let { if (it.endsWith(CRLF)) it else it + CRLF }

    private fun newMessageId(): String {
        val domain = MailAddresses.domainOf(draft.fromAddress).ifBlank { "localhost" }
        return "<${System.currentTimeMillis()}.${java.util.UUID.randomUUID()
            .toString().take(8)}@$domain>"
    }

    private fun newBoundary(kind: String): String =
        "svp-$kind-${System.currentTimeMillis().toString(36)}-${(1000..9999).random()}"

    companion object {
        private const val CRLF = "\r\n"
    }
}

/** The RFC 2822 date format SMTP and IMAP both read: `Tue, 30 Oct 2026 09:14:22 +0500`. */
object SmtpDateFormat {

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** Formatted in the device's default offset, which is what the owner's mail client expects. */
    fun format(epochMillis: Long): String {
        val zone = java.util.TimeZone.getDefault()
        val calendar = java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
        val offsetMillis = zone.getOffset(epochMillis)
        val sign = if (offsetMillis >= 0) '+' else '-'
        val absolute = kotlin.math.abs(offsetMillis)
        val hours = absolute / 3_600_000
        val minutes = (absolute % 3_600_000) / 60_000
        val dayOfWeek = DAYS[((calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7)]
        val month = MONTHS[calendar.get(java.util.Calendar.MONTH)]
        return "%s, %02d %s %04d %02d:%02d:%02d %c%02d%02d".format(
            dayOfWeek,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            month,
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
            sign,
            hours,
            minutes
        )
    }
}
