package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.ImapBodyStructure
import com.securevault.pro.data.email.MailTimings
import com.securevault.pro.data.email.MimeHeaderParser
import com.securevault.pro.data.email.MimeHeaders
import java.nio.charset.Charset

/**
 * Reading what a FETCH handed back.
 *
 * The protocol layer stores a message's parts under the section names the *server* used, which vary
 * in case, in whether they say `BODY` or `BODY.PEEK`, and in whether they carry the partial-fetch
 * suffix (`BODY[TEXT]<0.480>`). [MailSections] is the tolerant lookup that hides that, [MailPartDecoder]
 * turns a part's bytes into text whatever its transfer encoding claims, and [MailText] is the
 * presentation maths: a snippet, a size, a filename that is safe to write into the app's cache.
 *
 * All of it is pure and JVM-testable, and none of it allocates more than [MailTimings] allows: the
 * snippet and preview helpers cap their input rather than copying a 25 MB body into a String.
 */
object MailSections {

    /**
     * The bytes of the section whose *bracket content* matches [want].
     *
     * Both sides are normalised through [keyOf] before they are compared. This matters: a server
     * echoes the item name it answered under, so a request written as `BODY[TEXT]<0.480>` comes back
     * as `BODY.PEEK[TEXT]<0.480>`, and a request for part `1` may be reported as `BODY[1]`,
     * `BODY.PEEK[1]<0.480>` or `BINARY[1]`. Comparing the raw names would find no match, and a
     * message whose bytes are in the map would still be shown as having no text.
     *
     * `TEXT`, `BODY[TEXT]` and `BODY.PEEK[TEXT]<0.480>` therefore all match a request for `TEXT`; a
     * dotted part number matches itself (`1.2`); and the whole-message form keeps its own brackets,
     * so `BODY[]` matches `BODY[]` and nothing else. Longest key wins so
     * `HEADER.FIELDS (SUBJECT)` is never taken for `HEADER`.
     */
    fun pick(sections: Map<String, ByteArray>, want: String): ByteArray? {
        val target = keyOf(want)
        if (target.isEmpty()) return null
        val match = sections.keys
            .filter { keyOf(it) == target }
            .sortedByDescending { it.length }
            .firstOrNull()
        return match?.let { sections[it] }
    }

    /** The cache path a streamed section was written to, under the same tolerant key rules. */
    fun pickPath(paths: Map<String, String>, want: String): String? {
        val target = keyOf(want)
        val match = paths.keys
            .filter { keyOf(it) == target }
            .sortedByDescending { it.length }
            .firstOrNull()
        return match?.let { paths[it] }
    }

    /** `BODY.PEEK[HEADER.FIELDS (FROM)]` → `HEADER.FIELDS (FROM)`; `text` → `TEXT`. */
    fun keyOf(raw: String): String {
        val trimmed = raw.trim().uppercase()
        val bracketed = trimmed.substringAfter('[', "").substringBefore(']', "")
        val key = if (bracketed.isNotBlank()) bracketed else trimmed
        // A partial-fetch suffix belongs to the key's tail, never to its name.
        return key.substringBefore('<').trim().replace(Regex("\\s+"), " ")
    }

    /** True when the reply carries anything at all for [want] — used before a body is decoded. */
    fun has(sections: Map<String, ByteArray>, want: String): Boolean = pick(sections, want) != null
}

/**
 * RFC 2045 transfer encodings, for the parts that arrived as `BODY[…]` rather than `BINARY[…]`.
 *
 * `BINARY` would be the protocol's own decoder, but it is an optional extension and hosts that omit
 * it answer a `BINARY[1.2]` request with a tagged `BAD`; so the reader asks for BINARY when the
 * capability says it exists and falls back to `BODY[…]` plus this decoder. Unknown or missing
 * encodings are treated as raw bytes, which is what `7BIT`/`8BIT`/`BINARY` all really mean.
 */
object MailPartDecoder {

    fun decode(bytes: ByteArray, transferEncoding: String?, charset: Charset): String = when (
        transferEncoding?.trim()?.uppercase()
    ) {
        "BASE64" -> String(base64(bytes), charset)
        "QUOTED-PRINTABLE" -> String(quotedPrintable(bytes), charset)
        else -> String(bytes, charset)
    }

    /** Base64 that ignores the line breaks every mailer inserts and tolerates a missing pad. */
    fun base64(bytes: ByteArray): ByteArray {
        val cleaned = buildString(bytes.size) {
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (!c.isWhitespace()) append(c)
            }
        }
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return MailCredentials.decodeBase64(padded) ?: bytes
    }

    /**
     * Quoted-printable: `=3D` becomes `=`, a `=` at end of line is a soft break and disappears, and
     * anything malformed is left as written rather than dropped — losing a byte of the owner's text
     * is worse than a stray equals sign.
     */
    fun quotedPrintable(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '\r' || next == '\n') {
                    // Soft line break: skip the '=' and the break it hides.
                    i++
                    while (i < text.length && (text[i] == '\r' || text[i] == '\n')) i++
                    continue
                }
                if (i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + 3)
                    val value = runCatching { hex.toInt(16) }.getOrNull()
                    if (value != null) {
                        out.write(value)
                        i += 3
                        continue
                    }
                }
            }
            out.write(c.code)
            i++
        }
        return out.toByteArray()
    }
}

/** The leaf parts of one MIME tree, in reading order, and their names. */
object MailParts {

    /**
     * Depth-capped walk of a BODYSTRUCTURE tree.
     *
     * [MailTimings.MAX_MIME_DEPTH] and [MailTimings.MAX_MIME_PARTS] are the ceilings a hostile or
     * broken structure cannot exceed, and a truncated tree still yields the parts that were read
     * instead of throwing away the whole message.
     */
    fun leaves(
        structure: ImapBodyStructure?,
        maxParts: Int = MailTimings.MAX_MIME_PARTS,
        maxDepth: Int = MailTimings.MAX_MIME_DEPTH
    ): List<ImapBodyStructure> {
        val out = ArrayList<ImapBodyStructure>()
        if (structure == null) return out
        walk(structure, 0, out, maxParts, maxDepth)
        return out
    }

    private fun walk(
        node: ImapBodyStructure,
        depth: Int,
        out: MutableList<ImapBodyStructure>,
        maxParts: Int,
        maxDepth: Int
    ) {
        if (out.size >= maxParts || depth > maxDepth) return
        if (node.isMultiPart) {
            node.parts.forEach { walk(it, depth + 1, out, maxParts, maxDepth) }
            return
        }
        out += node
    }

    /** The part that should be shown as the message: plain text first, else HTML, else the first text. */
    fun preferredTextPart(leaves: List<ImapBodyStructure>): ImapBodyStructure? =
        leaves.firstOrNull { it.fullType == "TEXT/PLAIN" && !it.looksLikeAttachment() }
            ?: leaves.firstOrNull { it.fullType == "TEXT/HTML" && !it.looksLikeAttachment() }
            ?: leaves.firstOrNull { it.fullType.startsWith("TEXT/") && !it.looksLikeAttachment() }

    /** The parts that should be offered as files rather than shown inline. */
    fun attachments(leaves: List<ImapBodyStructure>): List<ImapBodyStructure> =
        leaves.filter { it.looksLikeAttachment() }

    /** The charset a part declared, so a Windows-1256 body is not shown as question marks. */
    fun charsetOf(part: ImapBodyStructure): Charset =
        MimeHeaders.charsetOf(part.parameters["charset"])

    /** The header block of a raw part, for the cases where only the full message came back. */
    fun headersOf(bytes: ByteArray): MimeHeaders = MimeHeaderParser.parse(bytes)
}

/** Presentation maths for mail text. */
object MailText {

    /**
     * HTML to readable text.
     *
     * Not a renderer: comments and the contents of `script`/`style`/`head`/`title` are dropped whole,
     * block ends become line breaks, `<br>` becomes one, list items get a bullet, table cells get a
     * space so two columns do not merge into one word, entities are decoded once, and tags go.
     *
     * Decoding happens last and in a single pass on purpose. Unescaping `&amp;lt;` early turns it into
     * `&lt;`, which a second pass would then turn into `<`, so text the sender meant literally was
     * being rewritten; a body of `&lt;script&gt;` used to arrive on screen as `<script>`.
     *
     * A reader screen showing stripped text is a plain, honest degradation of a mail client that has
     * no WebView in the vault.
     */
    fun stripHtml(html: String): String {
        val withoutBlocks = html
            .replace(Regex("(?is)<!--[\\s\\S]*?-->"), " ")
            .replace(Regex("(?is)<(script|style|head|title)[^>]*>.*?</\\1\\s*>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|li|ul|ol|tr|table|h[1-6]|blockquote|pre|section|article)>"), "\n")
            .replace(Regex("(?is)<li[^>]*>"), "\n\u2022 ")
            .replace(Regex("(?is)<(p|div|ul|ol|tr|table|h[1-6]|blockquote|pre|section|article)[^>]*>"), "\n")
            .replace(Regex("(?is)</?(td|th)[^>]*>"), " ")
            .replace(Regex("(?is)<[^>]*>"), " ")
        return decodeEntities(withoutBlocks)
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .replace('\u00A0', ' ')
            .trim()
    }

    /**
     * One pass over the text, decoding named, decimal (`&#709;`) and hex (`&#x20AC;`) entities.
     *
     * Known names are handled for the set mail actually uses; a numeric reference becomes the code
     * point it states unless it is a control character or outside Unicode, in which case it is left
     * exactly as written. An unknown name (`&fjell;`) is left as written too, because guessing is how
     * a client eats a word the owner needs.
     */
    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            // An entity is & + up to 12 name/body characters + ';'. Longer means it is not one.
            val end = value.indexOf(';', i + 1)
            if (end <= i || end - i > NAMED_ENTITY_MAX + 2) {
                out.append(c)
                i++
                continue
            }
            val body = value.substring(i + 1, end)
            val decoded = entityValue(body)
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private const val NAMED_ENTITY_MAX = 10

    private fun entityValue(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
            val digits = if (hex) body.substring(2) else body.substring(1)
            if (digits.isEmpty() || !digits.all { it.isDigit() || (hex && it in 'a'..'f') || (hex && it in 'A'..'F') }) {
                return null
            }
            val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            if (code < 0 || code > 0x10FFFF) return null
            // C0/C1 controls and surrogates are not text; keep the source instead of injecting them.
            if (code < 0x20 && code != 0x09 && code != 0x0A && code != 0x0D) return null
            if (code in 0xD800..0xDFFF) return null
            if (code in 0x7F..0x9F) return null
            return String(Character.toChars(code))
        }
        return NAMED_ENTITIES[body.lowercase(java.util.Locale.ROOT)]
    }

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "shy" to "",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "bull" to "\u2022",
        "middot" to "\u00B7",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "sect" to "\u00A7",
        "para" to "\u00B6",
        "euro" to "\u20AC",
        "pound" to "\u00A3",
        "yen" to "\u00A5",
        "cent" to "\u00A2",
        "curren" to "\u00A4",
        "larr" to "\u2190",
        "rarr" to "\u2192",
        "uarr" to "\u2191",
        "darr" to "\u2193",
        "dagger" to "\u2020",
        "permil" to "\u2030",
        "prime" to "\u2032",
        "rsquo" to "\u2019",
        "lsquo" to "\u2018",
        "rdquo" to "\u201D",
        "ldquo" to "\u201C",
    )

    /**
     * The one-line preview for a list row: whitespace collapsed, capped at [maxChars], with an
     * ellipsis that only appears when text was actually cut.
     */
    fun snippet(raw: String, maxChars: Int = MailTimings.SNIPPET_CHARS): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        return flat.take(maxChars).trimEnd() + "…"
    }

    /** Text for the reader screen, capped so a 20 MB body cannot be handed to a composable. */
    fun preview(raw: String, maxChars: Int = MailTimings.BODY_PREVIEW_CHARS): String {
        if (raw.length <= maxChars) return raw
        return raw.take(maxChars) + "\n\n… " +
            (raw.length - maxChars).toString() + " more characters in this message."
    }

    /** `1.4 MB` / `22 KB` / `412 B` — the only size format the attachment rows use. */
    fun readableSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1) return "$bytes B"
        if (kb < 1024) return "${round(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${round(mb)} MB"
        return "${round(mb / 1024.0)} GB"
    }

    private fun round(value: Double): String =
        if (value < 10) String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
        else value.toLong().toString()

    /**
     * A filename that is safe to create inside the cache directory.
     *
     * An attachment name arrives from a server and a server is not trusted: `../../x`, absolute
     * Windows paths, `NUL.pdf`, control characters and a 4 KB name all get reduced here to a plain
     * basename of at most 120 characters, with the extension preserved when it is still printable.
     * Nothing about this is cosmetic — writing a raw server name is how a mail client escapes its
     * sandbox.
     */
    fun safeFileName(raw: String, fallback: String = "attachment"): String {
        val base = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}\u0000-\u001F\u007F]"), "")
            .trim('.', ' ', ':')
            .replace(Regex("[^A-Za-z0-9 ._\\-()\\[\\]{}#&@+=',;]"), "_")
        if (base.isEmpty()) return fallback
        val dot = base.lastIndexOf('.')
        val name = if (dot > 0) base.substring(0, dot).take(96) else base.take(96)
        val extension = if (dot > 0) base.substring(dot).take(16) else ""
        val candidate = (name + extension).ifBlank { fallback }
        return if (candidate.equals(".", ignoreCase = false) || candidate.all { it == '.' }) fallback else candidate
    }

    /** Two-letter monogram for the sender column, from a name or an address. */
    fun monogramOf(name: String, address: String): String {
        val source = name.ifBlank { address.substringBefore('@', address) }.trim()
        if (source.isEmpty()) return "?"
        val pieces = source.split(Regex("\\s+")).filter { it.isNotBlank() }
        val first = pieces.firstOrNull()?.firstOrNull()?.uppercaseChar() ?: return "?"
        val second = pieces.getOrNull(1)?.firstOrNull()?.uppercaseChar()
        return if (second != null) "$first$second" else first.toString()
    }
}
