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
     * `TEXT`, `BODY[TEXT]` and `BODY.PEEK[TEXT]<0.480>` all match a request for `TEXT`; a dotted
     * part number matches itself (`1.2`), and a header request matches `HEADER` and any
     * `HEADER.FIELDS (…)`. Longest key wins so `HEADER.FIELDS (SUBJECT)` is never taken for `HEADER`.
     */
    fun pick(sections: Map<String, ByteArray>, want: String): ByteArray? {
        val target = keyOf(want)
        if (target.isEmpty()) return null
        val match = sections.keys
            .filter { it == target || it.startsWith("$target<") || it.startsWith("$target ") }
            .sortedByDescending { it.length }
            .firstOrNull()
        return match?.let { sections[it] }
    }

    /** The cache path a streamed section was written to, under the same tolerant key rules. */
    fun pickPath(paths: Map<String, String>, want: String): String? {
        val target = keyOf(want)
        val match = paths.keys
            .filter { it == target || it.startsWith("$target<") || it.startsWith("$target ") }
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
     * Not a renderer: script and style contents are dropped whole, block ends become line breaks,
     * `<br>` becomes one, entities are unescaped for the handful HTML inside an email actually uses,
     * and tags go. A reader screen showing stripped text is a plain, honest degradation of an IMAP
     * client that has no WebView in the vault.
     */
    fun stripHtml(html: String): String {
        val withoutBlocks = html
            .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|li|tr|h[1-6]|blockquote|pre)>"), "\n")
            .replace(Regex("(?is)<li[^>]*>"), "• ")
            .replace(Regex("(?is)<[^>]*>"), " ")
        val unescaped = unescapeEntities(withoutBlocks)
        return unescaped
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun unescapeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#8212;", "—")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")

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
