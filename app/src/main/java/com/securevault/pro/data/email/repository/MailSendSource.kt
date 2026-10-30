package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.DotStuffedWriter
import com.securevault.pro.data.email.MailDraft
import com.securevault.pro.data.email.MailTimings
import com.securevault.pro.data.email.MessageBodySource
import com.securevault.pro.data.email.MimeTokens
import com.securevault.pro.data.email.MimeTypes
import com.securevault.pro.data.email.SmtpMessageBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Base64

/**
 * The bytes of one outgoing message, handed to `SmtpEngine.dataFromSource` **un-stuffed**.
 *
 * Why this class exists instead of a call to `SmtpMessageBuilder.writeTo`: that method already wraps
 * its sink in a [DotStuffedWriter] and writes the terminating lone dot, while
 * [MessageBodySource.writeThrough] is documented the other way round — "sources must not pre-stuff",
 * because the engine owns the stuffing and the terminator. Feeding one into the other would double
 * every leading dot and end the transfer early, so the send path assembles its raw bytes here.
 * [MailMessageSource] reuses the protocol layer's real contracts — [SmtpMessageBuilder.headerBlock]
 * for the header set and [SmtpMessageBuilder.approximateSize] for the `SIZE=` hint — and adds the one
 * thing `MailDraft` cannot carry yet: threading. `agent_notes/email-ui.md` asks the protocol owner to
 * expose `writeRawTo(sink: DotStuffedWriter)` so this file's body assembly can be deleted.
 *
 * Memory is bounded by [MailTimings.STREAM_CHUNK_BYTES]: an attachment is base64-encoded in
 * 57-triple blocks (76 output characters, RFC 2045's line length) and streamed, so a 25 MB PDF costs
 * the same on the send path as it does on the receive path.
 */

/** Reply threading, which [MailDraft] has no field for; empty values write no header. */
data class MailThreading(
    val inReplyTo: String = "",
    val references: String = ""
) {
    fun headerBlock(): String = buildString {
        if (inReplyTo.isNotBlank()) append("In-Reply-To: ").append(angle(inReplyTo)).append("\r\n")
        if (references.isNotBlank()) append("References: ").append(references.trim()).append("\r\n")
    }

    private fun angle(value: String): String = if (value.startsWith("<")) value else "<$value>"

    companion object {
        /** The threading for an answer to [Message-id], keeping whatever chain it carried. */
        fun replyTo(messageId: String, references: String = ""): MailThreading =
            MailThreading(inReplyTo = messageId, references = listOf(messageId, references)
                .filter { it.isNotBlank() }.joinToString(" "))
    }
}

class MailMessageSource(
    private val draft: MailDraft,
    private val threading: MailThreading = MailThreading(),
    private val builder: SmtpMessageBuilder = SmtpMessageBuilder(draft)
) : MessageBodySource {

    /** Only the attachments that really exist on disk are offered; a vanished cache file is skipped. */
    private val files: List<File> = draft.attachmentPaths.map(::File).filter { it.isFile }

    override fun contentLength(): Long =
        builder.approximateSize() + threading.headerBlock().toByteArray(Charsets.UTF_8).size

    override fun writeThrough(sink: DotStuffedWriter) {
        sink.writeText(builder.headerBlock())
        sink.writeText(threading.headerBlock())
        if (files.isEmpty()) {
            sink.writeText("Content-Type: text/plain; charset=UTF-8$CRLF")
            sink.writeText("Content-Transfer-Encoding: 8BIT$CRLF$CRLF")
            sink.writeText(bodyBytes())
            return
        }
        val boundary = newBoundary()
        sink.writeText("Content-Type: multipart/mixed; boundary=\"$boundary\"$CRLF$CRLF")
        sink.writeText("--$boundary$CRLF")
        sink.writeText("Content-Type: text/plain; charset=UTF-8$CRLF")
        sink.writeText("Content-Transfer-Encoding: 8BIT$CRLF$CRLF")
        sink.writeText(bodyBytes())
        sink.writeText(CRLF)
        files.forEachIndexed { index, file ->
            val name = file.name.ifBlank { "attachment-${index + 1}" }
            val mime = MimeTypes.forFileName(name)
            val escaped = MimeTokens.escapeParameter(name)
            sink.writeText("--$boundary$CRLF")
            sink.writeText("Content-Type: $mime; name=\"$escaped\"$CRLF")
            sink.writeText("Content-Transfer-Encoding: base64$CRLF")
            sink.writeText("Content-Disposition: attachment; filename=\"$escaped\"$CRLF$CRLF")
            writeBase64(file, sink)
            sink.writeText(CRLF)
        }
        sink.writeText("--$boundary--$CRLF")
    }

    /** The body with RFC 5322 line endings, always closed by a break so the terminator cannot glue on. */
    private fun bodyBytes(): String =
        draft.bodyText.replace("\r\n", "\n").replace("\n", CRLF).let {
            if (it.endsWith(CRLF)) it else it + CRLF
        }

    private fun writeBase64(file: File, sink: DotStuffedWriter) {
        val encoder = Base64.getEncoder()
        // 57 input bytes are exactly one 76-character RFC 2045 line, so nothing is ever re-buffered.
        val groupBytes = 3 * 57
        val buffer = ByteArray(groupBytes)
        val crlf = byteArrayOf(CR, LF)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val encoded = encoder.encode(buffer.copyOf(read)).toString(Charsets.US_ASCII)
                encoded.chunked(76).forEach { piece ->
                    sink.write(piece.toByteArray(Charsets.US_ASCII))
                    sink.write(crlf, 0, crlf.size)
                }
            }
        }
    }

    private fun newBoundary(): String =
        "svp-mixed-${System.currentTimeMillis().toString(36)}-${(1000..9999).random()}"

    companion object {
        private const val CRLF = "\r\n"
        private const val CR: Byte = 13
        private const val LF: Byte = 10

        /** The draft for a reply: the original quoted, with the recipient taken from its sender. */
        fun draftReply(
            from: String,
            toAddress: String,
            originalSubject: String,
            originalSnippet: String
        ): MailDraft = MailDraft(
            fromAddress = from,
            to = listOf(toAddress),
            subject = if (originalSubject.startsWith("Re:", true)) originalSubject else "Re: $originalSubject",
            bodyText = buildString {
                append("\n\n")
                originalSnippet.lineSequence().take(24).forEach { append("> ").append(it).append('\n') }
            }
        )
    }
}
