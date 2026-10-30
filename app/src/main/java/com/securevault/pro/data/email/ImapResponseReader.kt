package com.securevault.pro.data.email

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One piece of a response as it came off the wire.
 *
 * The split is the whole point of the type: a `{nnn}` literal is *bytes*, not text, and it may be
 * 25 MB of attachment. Keeping it apart from the surrounding line text is what lets the FETCH
 * parser stream it to the app cache instead of turning it into a String.
 */
sealed class WirePiece {

    /** Response text with the literal markers removed from it; never contains `{n}` itself. */
    data class Text(val value: String) : WirePiece()

    /** The `{nnn}` marker, kept so a parser can check the byte count it was promised. */
    data class Marker(val declaredBytes: Long) : WirePiece()

    /** Literal bytes that were read in full because they are small (metadata, section text). */
    data class Literal(val bytes: ByteArray) : WirePiece() {
        /** UTF-8 view of the literal; invalid bytes become U+FFFD, the read never throws. */
        fun asText(): String = String(bytes, Charsets.UTF_8)

        override fun equals(other: Any?): Boolean = other is Literal && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * A literal that was streamed somewhere else (the cache) instead of kept. Only the size and the
     * destination survive, which is exactly what a 25 MB attachment should leave behind in memory.
     */
    data class Streamed(val declaredBytes: Long, val destinationPath: String, val streaming: Boolean = true) : WirePiece()
}

/** What kind of line a response unit is, in the RFC 3501 sense. */
enum class ImapReplyKind {

    /** `* …` — server data, any number of them before the tagged answer. */
    UNTAGGED,

    /** `+ …` — a continuation, the server waiting for a client response (SASL, IDLE). */
    CONTINUATION,

    /** `<tag> OK|NO|BAD|PREAUTH …` — the answer to the command that carried that tag. */
    COMPLETED,

    /** A send command (`C>` line) echoed back by a debugging server; ignored by the engine. */
    SEND_ECHO
}

/**
 * A complete response unit: the pieces between one CRLF and the next, with any literals resolved.
 *
 * [pieces] is ordered exactly as the bytes arrived, so [text] can rebuild a readable form for a
 * log line while [literal] still gives the parser the raw bytes without a copy of the whole body.
 */
data class ImapReply(
    val kind: ImapReplyKind,
    val tag: String?,
    val pieces: List<WirePiece>,
    val code: String = "",
    val comment: String = ""
) {
    /** The line's text with each literal replaced by `<literal N bytes>`, safe for a log. */
    val text: String
        get() = pieces.joinToString("") { piece ->
            when (piece) {
                is WirePiece.Text -> piece.value
                is WirePiece.Marker -> "<literal ${piece.declaredBytes}>"
                is WirePiece.Literal -> "<literal ${piece.bytes.size}>"
                is WirePiece.Streamed -> "<streamed ${piece.declaredBytes}>"
            }
        }

    /** The first literal's bytes, which is what a `UID FETCH … BODY[…]` reply carries. */
    fun firstLiteral(): ByteArray? = pieces.firstOrNull { it is WirePiece.Literal }
        ?.let { (it as WirePiece.Literal).bytes }

    fun firstStreamed(): WirePiece.Streamed? = pieces.firstOrNull { it is WirePiece.Streamed }
        as? WirePiece.Streamed

    val isTaggedAnswerTo: Boolean get() = kind == ImapReplyKind.COMPLETED
    val isBye: Boolean get() = kind == ImapReplyKind.UNTAGGED && text.startsWith("BYE", ignoreCase = true)

    companion object {
        /**
         * A literal-free reply built from one wire line. A `{nnn}` marker inside [rawLine] is left
         * as text: a line that really carries a literal arrives through [ImapResponseReader], which
         * pulls the bytes off the stream, or is assembled with [withLiteral].
         */
        fun of(rawLine: String): ImapReply {
            val (kind, tag, body) = split(rawLine)
            val code = if (kind == ImapReplyKind.COMPLETED) {
                body.substringBefore(' ').uppercase().takeIf { it in ImapResponseReader.TAGGED_CODES }.orEmpty()
            } else {
                ""
            }
            val comment = if (code.isNotEmpty()) body.substringAfter(' ', body) else body
            val pieces = if (body.isEmpty()) emptyList() else listOf(WirePiece.Text(body))
            return ImapReply(kind = kind, tag = tag, pieces = pieces, code = code, comment = comment)
        }

        /** An untagged status reply, `* 12 EXISTS` and friends. */
        fun untagged(body: String): ImapReply = of("* $body")

        /** The tagged answer to one command. */
        fun tagged(tag: String, code: String, comment: String = ""): ImapReply =
            of("$tag $code${if (comment.isEmpty()) "" else " $comment"}")

        /** A server continuation, `+ `<[base64]>. */
        fun continuation(body: String): ImapReply = of("+ $body")

        /** This reply with [bytes] appended as a `{n}` literal, the way the reader would have read it. */
        fun ImapReply.withLiteral(bytes: ByteArray): ImapReply {
            val declared = bytes.size.toLong()
            val pieces = this.pieces.toMutableList()
            if (pieces.isNotEmpty() && pieces.last() is WirePiece.Text) {
                val last = pieces.removeAt(pieces.size - 1) as WirePiece.Text
                pieces += WirePiece.Text("${last.value}{$declared}")
            } else {
                pieces += WirePiece.Text("{$declared}")
            }
            pieces += WirePiece.Marker(declared)
            pieces += WirePiece.Literal(bytes)
            return copy(pieces = pieces)
        }

        private fun split(rawLine: String): Triple<ImapReplyKind, String?, String> = when {
            rawLine == "*" || rawLine.startsWith("* ") ->
                Triple(ImapReplyKind.UNTAGGED, null, rawLine.removePrefix("* "))
            rawLine == "+" || rawLine.startsWith("+ ") ->
                Triple(ImapReplyKind.CONTINUATION, null, rawLine.removePrefix("+ "))
            rawLine.startsWith("C>") -> Triple(ImapReplyKind.SEND_ECHO, null, rawLine)
            else -> Triple(
                ImapReplyKind.COMPLETED,
                rawLine.substringBefore(' '),
                rawLine.substringAfter(' ', "")
            )
        }
    }
}

/**
 * Reads responses off a byte stream, honouring `{nnn}` literals properly.
 *
 * This is the class a regex cannot replace. The rules it implements, straight from RFC 3501:
 * - a response ends at CRLF, **except** when it ends with `{nnn}` immediately before that CRLF:
 *   then `nnn` raw bytes follow, and they may themselves contain CRLF, quotes and braces;
 * - after those bytes the response continues on the next line, so one logical reply is
 *   "text, literal, more text" and is returned as one [ImapReply];
 * - literals larger than [MailTimings.STREAMED_LITERAL_THRESHOLD_BYTES] are written to a file by
 *   [literalSink] and never kept in memory.
 *
 * The read-ahead buffer is bounded by [chunkBytes] and capped by [MailTimings.MAX_LINE_BYTES]; a
 * server that streams a line forever is a failure, not an out-of-memory crash.
 */
class ImapResponseReader(
    private val source: InputStream,
    private val chunkBytes: Int = MailTimings.STREAM_CHUNK_BYTES,
    private val maxInlineLiteralBytes: Long = MailTimings.MAX_INLINE_LITERAL_BYTES
) {

    /** Where a big literal goes instead of the heap. */
    interface LiteralSink {
        /** Called once per chunk; the array is reused, so an implementation must copy or write. */
        fun write(bytes: ByteArray, offset: Int, length: Int)

        /** Path reported back in [WirePiece.Streamed]; the file is already fully written by then. */
        fun path(): String
    }

    /**
     * A fixed destination for literals that must not be kept in memory, used when no per-reply
     * [sinkFactory] claimed the literal and the literal is over the size threshold.
     */
    var literalSink: LiteralSink? = null

    /**
     * Decides, per literal, whether the bytes should be streamed. Called with the text of the reply
     * read so far (which carries the `UID` and the section name), the section hint and the declared
     * size; returning null falls back to [literalSink].
     *
     * A factory that returns a sink *claims* the literal, so it is streamed whatever its size — that
     * is how the sync engine sends every message body to the cache rather than to the heap.
     *
     * The section hint is found by looking for the last `BODY[…]`/`BINARY[…]` item name in the text
     * read so far. That is a *naming* aid for the destination file, not a parser substitute: the
     * literal bytes themselves are never tokenized and never turned into a String.
     */
    var sinkFactory: ((replyText: String, section: String, declaredBytes: Long) -> LiteralSink?)? = null

    private val buffer = ByteArray(chunkBytes.coerceIn(1, MailTimings.MAX_LINE_BYTES))
    private var fill = 0
    private var cursor = 0
    private var eof = false
    private var byteTotal = 0L

    /** Bytes sitting in [buffer] that belong to the *next* reply — the invariant the STARTTLS upgrade checks. */
    val bufferedAhead: Int get() = if (eof) 0 else fill - cursor

    /** Total bytes consumed, for the streaming tests and the sync log. */
    fun bytesRead(): Long = byteTotal

    /**
     * Reads one logical reply, or null at a clean EOF.
     *
     * A `{nnn}` literal is streamed to a sink when [sinkFactory] claims it or when it is bigger than
     * the inline threshold and a [literalSink] is installed; otherwise it is kept as bytes, capped by
     * [maxInlineLiteralBytes].
     */
    @Throws(IOException::class)
    fun readReply(): ImapReply? {
        val pieces = mutableListOf<WirePiece>()
        var pendingDeclared: Long? = null
        var kind: ImapReplyKind = ImapReplyKind.UNTAGGED
        var tag: String? = null
        var code = ""
        var comment = ""
        var classified = false
        var sawFirstLine = false
        while (true) {
            if (pendingDeclared == null) {
                val line = readLine() ?: run {
                    if (!sawFirstLine) return null
                    return ImapReply(kind, tag, pieces, code, comment)
                }
                sawFirstLine = true
                if (line.isEmpty()) continue
                if (!classified) {
                    classified = true
                    val stripped = when {
                        line == "*" || line.startsWith("* ") -> {
                            kind = ImapReplyKind.UNTAGGED
                            line.removePrefix("* ")
                        }
                        line == "+" || line.startsWith("+ ") -> {
                            kind = ImapReplyKind.CONTINUATION
                            line.removePrefix("+ ")
                        }
                        line.startsWith("C>") -> {
                            kind = ImapReplyKind.SEND_ECHO
                            comment = line
                            line
                        }
                        else -> {
                            kind = ImapReplyKind.COMPLETED
                            tag = line.substringBefore(' ')
                            val rest = line.substringAfter(' ', "")
                            val candidate = rest.substringBefore(' ').uppercase()
                            if (candidate in TAGGED_CODES) {
                                code = candidate
                                comment = rest.substringAfter(' ', "")
                            }
                            rest
                        }
                    }
                    if (markerOf(stripped) == null) {
                        pieces += WirePiece.Text(stripped)
                        return ImapReply(kind, tag, pieces, code, comment)
                    }
                    pendingDeclared = markerOf(stripped)
                    pieces += WirePiece.Text(stripped.substringBeforeLast("{"))
                    pieces += WirePiece.Marker(pendingDeclared!!)
                } else {
                    val marker = markerOf(line)
                    if (marker == null) {
                        pieces += WirePiece.Text(line)
                        return ImapReply(kind, tag, pieces, code, comment)
                    }
                    pendingDeclared = marker
                    pieces += WirePiece.Text(line.substringBeforeLast("{"))
                    pieces += WirePiece.Marker(marker)
                }
            } else {
                val size = pendingDeclared
                pendingDeclared = null
                if (size <= 0L) continue
                val replyText = pieces.joinToString("") { piece ->
                    (piece as? WirePiece.Text)?.value ?: ""
                }
                val claimed = sinkFactory?.invoke(replyText, sectionHint(replyText), size)
                val sink = claimed ?: literalSink
                val streamed = sink != null && (claimed != null || size > MailTimings.STREAMED_LITERAL_THRESHOLD_BYTES)
                if (streamed) {
                    val written = readLiteralToSink(size, sink!!)
                    if (written != size) throw MailProtocolException(
                        "The server promised $size bytes of literal and sent $written."
                    )
                    pieces += WirePiece.Streamed(size, sink.path())
                } else {
                    if (size > maxInlineLiteralBytes) {
                        throw MailProtocolException(
                            "A ${size}-byte literal is too large to read as metadata; the server " +
                                "sent it where a small response was expected."
                        )
                    }
                    pieces += WirePiece.Literal(readLiteralBytes(size))
                }
            }
        }
    }

    /** The `{nnn}` at the very end of a line, or null when there is none. */
    private fun markerOf(line: String): Long? =
        LITERAL_AT_END.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** Reads and discards every untagged reply until the tagged answer to [tag] arrives. */
    fun drainUntilTag(tag: String, onUntagged: (ImapReply) -> Unit = { }): ImapReply {
        while (true) {
            val reply = readReply() ?: throw MailNetworkException(
                "The server closed the connection before answering."
            )
            when (reply.kind) {
                ImapReplyKind.COMPLETED -> {
                    if (reply.tag == tag) return reply
                }
                ImapReplyKind.CONTINUATION -> {
                    throw MailProtocolException("The server asked for more input during a command: ${reply.text}")
                }
                else -> onUntagged(reply)
            }
        }
    }

    /** Reads until the tagged answer, keeping every untagged reply. */
    fun readUntilTag(tag: String): TaggedExchange {
        val untagged = mutableListOf<ImapReply>()
        var continuation: ImapReply? = null
        var completed: ImapReply? = null
        while (completed == null) {
            val reply = readReply() ?: throw MailNetworkException("The server closed the connection.")
            when (reply.kind) {
                ImapReplyKind.COMPLETED -> if (reply.tag == tag) completed = reply
                ImapReplyKind.CONTINUATION -> continuation = reply
                else -> {
                    untagged += reply
                    continuation = null
                }
            }
        }
        return TaggedExchange(completed!!, untagged, continuation)
    }

    /** One command's worth of answers. */
    data class TaggedExchange(
        val completed: ImapReply,
        val untagged: List<ImapReply>,
        val continuation: ImapReply?
    ) {
        val ok: Boolean get() = completed.code == "OK" || completed.code == "PREAUTH"
        val failureReason: String
            get() = completed.comment.ifBlank { completed.code.ifBlank { "the server refused" } }
    }

    // ------------------------------------------------------------------ primitives

    /** One CRLF-terminated line, CR/LF stripped. Null at EOF when nothing is buffered. */
    @Throws(IOException::class)
    private fun readLine(): String? {
        val collected = ByteArrayOutputStream(256)
        var sawAnything = false
        while (true) {
            if (cursor >= fill) {
                if (!fillBuffer()) {
                    eof = true
                    return if (collected.size() == 0) {
                        if (sawAnything) "" else null
                    } else {
                        // A line without a terminating CRLF at EOF: still a real answer, keep it.
                        collected.toString(Charsets.UTF_8.name())
                    }
                }
            }
            var i = cursor
            var newlineAt = -1
            while (i < fill) {
                if (buffer[i] == LF_BYTE) {
                    newlineAt = i
                    break
                }
                i++
            }
            if (newlineAt < 0) {
                collected.write(buffer, cursor, fill - cursor)
                cursor = fill
                if (collected.size() > MailTimings.MAX_LINE_BYTES) {
                    throw MailProtocolException("The server sent a line longer than the reader allows.")
                }
                sawAnything = true
                continue
            }
            val take = newlineAt - cursor
            if (take > 0) collected.write(buffer, cursor, take)
            cursor = newlineAt + 1
            sawAnything = true
            val bytes = collected.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR_BYTE) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }

    @Throws(IOException::class)
    private fun fillBuffer(): Boolean {
        fill = 0
        cursor = 0
        val read = source.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        fill = read
        byteTotal += read
        return true
    }

    @Throws(IOException::class)
    private fun readLiteralBytes(size: Long): ByteArray {
        val out = ByteArrayOutputStream(size.coerceAtMost(MAX_PREALLOC).toInt())
        var remaining = size
        while (remaining > 0) {
            if (cursor >= fill && !fillBuffer()) {
                throw MailNetworkException("The connection ended in the middle of a literal.")
            }
            val available = minOf(remaining, (fill - cursor).toLong()).toInt()
            out.write(buffer, cursor, available)
            cursor += available
            remaining -= available
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    private fun readLiteralToSink(size: Long, sink: LiteralSink): Long {
        var remaining = size
        while (remaining > 0) {
            if (cursor >= fill && !fillBuffer()) {
                throw MailNetworkException("The connection ended in the middle of a streamed literal.")
            }
            val available = minOf(remaining, (fill - cursor).toLong()).toInt()
            sink.write(buffer, cursor, available)
            cursor += available
            remaining -= available
        }
        return size - remaining
    }

    /**
     * Reads replies until one of [kind] arrives, skipping untagged server data. Used by the SASL
     * exchange, where the answer to `AUTHENTICATE` is a `+` continuation rather than a tagged
     * completion. A tagged answer that shows up first is returned as-is so the caller can report the
     * real failure instead of waiting for a continuation that will never come.
     */
    @Throws(IOException::class)
    fun readReplyUntil(kind: ImapReplyKind): ImapReply? {
        while (true) {
            val reply = readReply() ?: return null
            if (reply.kind == kind || reply.kind == ImapReplyKind.COMPLETED) return reply
        }
    }

    /**
     * One reply with [factory] deciding where each of its literals goes. The factory is in effect for
     * this read only, so a metadata command can never inherit a FETCH body sink.
     */
    @Throws(IOException::class)
    fun readReplyWithSink(factory: (section: String, declaredBytes: Long) -> LiteralSink?): ImapReply? {
        val outer = sinkFactory
        sinkFactory = { _, section, size -> factory(section, size) }
        try {
            return readReply()
        } finally {
            sinkFactory = outer
        }
    }

    companion object {
        private val CR_BYTE: Byte = 13
        private val LF_BYTE: Byte = 10

        /** The four status codes a tagged answer may carry (RFC 3501 section 7). */
        internal val TAGGED_CODES = setOf("OK", "NO", "BAD", "PREAUTH")

        /** Never pre-allocate more than this for a literal; the read loop grows the buffer anyway. */
        private const val MAX_PREALLOC = 256L * 1024L

        /** `{123}` at the very end of a line — the only place a literal marker is legal. */
        private val LITERAL_AT_END = Regex("\\{(\\d+)}$")

        /** `BODY[HEADER.FIELDS (FROM)]` / `BINARY[1.2]` — the last section named in the reply text. */
        private val SECTION_PATTERN = Regex("(?i)(BODY(?:\\.PEEK)?|BINARY(?:\\.PEEK)?\\??)\\[([^]]*)]")

        internal fun sectionHint(text: String): String =
            SECTION_PATTERN.findAll(text).lastOrNull()?.let { "${it.value.uppercase()}" }?.trim() ?: ""
    }
}
