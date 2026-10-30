package com.securevault.pro.data.email

/**
 * The IMAP response tokenizer.
 *
 * Literals are not text, so they do not sit inside the string that gets tokenized: [flatten]
 * replaces every `{nnn}` marker with the one-character sentinel [LITERAL_SLOT] and parks the real
 * value in a side list; the tokenizer emits that value as a whole token when it reaches the slot.
 * A 25 MB body therefore never becomes a String anywhere in the pipeline — the side list holds
 * either its bytes (metadata-sized literals only) or a [ImapToken.StreamedLiteral] pointing at the
 * cache file it was streamed to.
 */
sealed class ImapToken {

    /** An atom: `UID`, `12`, `NIL`, `\Seen`, `BODY[HEADER.FIELDS (FROM TO)]`. */
    data class Atom(val value: String) : ImapToken()

    /** A `"quoted string"`, already unescaped. */
    data class Quoted(val value: String) : ImapToken()

    /** A `{nnn}` literal small enough to keep. */
    data class LiteralBytes(val bytes: ByteArray) : ImapToken() {
        fun asUtf8(): String = String(bytes, Charsets.UTF_8)
        override fun equals(other: Any?): Boolean = other is LiteralBytes && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A `{nnn}` literal that was streamed to [path]; the bytes are on disk, not here. */
    data class StreamedLiteral(val size: Long, val path: String) : ImapToken()

    /** A parenthesised list, arbitrarily nested. */
    data class ListTok(val items: List<ImapToken>) : ImapToken()

    companion object {
        val NIL = Atom("NIL")
    }
}

/** True when the token is absent or the atom `NIL`. */
fun ImapToken?.isNil(): Boolean = this == null || (this is ImapToken.Atom && value.equals("NIL", true))

/** The text of a token: atoms and quoted as-is, literal bytes decoded UTF-8, never throws. */
fun ImapToken?.text(): String = when (this) {
    is ImapToken.Atom -> value
    is ImapToken.Quoted -> value
    is ImapToken.LiteralBytes -> asUtf8()
    is ImapToken.StreamedLiteral -> ""
    is ImapToken.ListTok -> items.joinToString(" ") { it.text() }
    null -> ""
}

fun ImapToken?.asLongOrNull(): Long? = text().trim().toLongOrNull()

fun ImapToken?.asIntOrNull(): Int? = text().trim().toIntOrNull()

fun ImapToken?.asListTokens(): List<ImapToken> = (this as? ImapToken.ListTok)?.items ?: emptyList()

fun ImapToken?.isList(): Boolean = this is ImapToken.ListTok

object ImapTokenizer {

    /** The sentinel standing for one literal slot in the flattened text. */
    const val LITERAL_SLOT: Char = '\u0001'

    /** Response text plus the ordered literal tokens its slots refer to. */
    data class Flattened(val text: String, val literals: List<ImapToken>)

    fun flatten(pieces: List<WirePiece>): Flattened {
        val builder = StringBuilder()
        val pulled = ArrayList<ImapToken>()
        for (piece in pieces) {
            when (piece) {
                is WirePiece.Text -> builder.append(piece.value)
                is WirePiece.Marker -> builder.append(LITERAL_SLOT)
                is WirePiece.Literal -> pulled += ImapToken.LiteralBytes(piece.bytes)
                is WirePiece.Streamed ->
                    pulled += ImapToken.StreamedLiteral(piece.declaredBytes, piece.destinationPath)
            }
        }
        return Flattened(builder.toString(), pulled)
    }

    fun tokenize(text: String): List<ImapToken> = tokenize(Flattened(text, emptyList()))

    fun tokenize(reply: ImapReply): List<ImapToken> = tokenize(flatten(reply.pieces))

    fun tokenize(pieces: List<WirePiece>): List<ImapToken> = tokenize(flatten(pieces))

    /** Top level: everything up to the end of the text. */
    fun tokenize(flat: Flattened): List<ImapToken> {
        val cursor = LiteralCursor(flat.literals)
        return readSequence(flat.text, 0, cursor).first
    }

    /**
     * Reads tokens until the matching `)` (or the end at top level) and returns them with the index
     * just past their terminator. Nested lists recurse through the same [cursor], so the literal
     * slots stay in wire order no matter how deep the tree goes.
     */
    private fun readSequence(
        text: String,
        from: Int,
        cursor: LiteralCursor
    ): Pair<List<ImapToken>, Int> {
        val out = ArrayList<ImapToken>()
        var i = from
        while (i < text.length) {
            when (val c = text[i]) {
                ' ', '\t', '\r', '\n' -> i++
                '(' -> {
                    val (inner, next) = readSequence(text, i + 1, cursor)
                    out += ImapToken.ListTok(inner)
                    i = next
                }
                '"' -> {
                    val (value, next) = readQuoted(text, i + 1)
                    out += ImapToken.Quoted(value)
                    i = next
                }
                LITERAL_SLOT -> {
                    out += cursor.next()
                    i++
                }
                ')' -> return out to (i + 1)
                else -> {
                    val (value, next) = readAtom(text, i)
                    if (value.isEmpty()) return out to (i + 1)
                    out += ImapToken.Atom(value)
                    i = next
                }
            }
        }
        return out to i
    }

    /** `"…"` with the two backslash escapes the grammar allows. */
    private fun readQuoted(text: String, from: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return builder.toString() to (i + 1)
                c == '\\' && i + 1 < text.length -> {
                    builder.append(text[i + 1])
                    i += 2
                }
                else -> {
                    builder.append(c)
                    i++
                }
            }
        }
        // An unterminated quote is a damaged response; keep what was read rather than throwing.
        return builder.toString() to i
    }

    /**
     * An atom, with section specs kept whole: `BODY[HEADER.FIELDS (FROM TO)]` is one item name, so
     * everything between a `[` and its matching `]` — spaces and parentheses included — belongs to
     * the token. That is how servers really emit FETCH item names, and it is the only way to tell
     * `BODY[TEXT]` from `BODY` plus a stray bracket.
     */
    private fun readAtom(text: String, from: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var i = from
        var brackets = 0
        while (i < text.length) {
            val c = text[i]
            if (brackets > 0) {
                builder.append(c)
                i++
                if (c == ']') brackets--
                continue
            }
            if (c == '[') {
                brackets++
                builder.append(c)
                i++
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '(' || c == ')' ||
                c == '"' || c == LITERAL_SLOT
            ) {
                return builder.toString() to i
            }
            builder.append(c)
            i++
        }
        return builder.toString() to i
    }

    /** The shared, forward-only walk over the parked literals. */
    private class LiteralCursor(private val literals: List<ImapToken>) {
        private var index = 0
        fun next(): ImapToken = literals.getOrElse(index++) { ImapToken.LiteralBytes(ByteArray(0)) }
    }
}
