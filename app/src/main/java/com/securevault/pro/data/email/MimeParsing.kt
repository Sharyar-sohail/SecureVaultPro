package com.securevault.pro.data.email

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * MIME header field parsing: folding, parameters, RFC 2047 encoded words and RFC 2231 continuations.
 *
 * All of it is pure `String`/`ByteArray` in, value out, so the unit tests cover the messy cases real
 * mailers produce without a socket anywhere in sight.
 */

/** Header fields of one MIME part, in arrival order, names kept as the sender wrote them. */
class MimeHeaders(val entries: List<Pair<String, String>>) {

    /** The first value of [name], case-insensitively, or empty when the field is absent. */
    fun first(name: String): String =
        entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second.orEmpty()

    fun values(name: String): List<String> =
        entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun has(name: String): Boolean = values(name).isNotEmpty()

    /** `Content-Type` decoded to `text/plain` plus its parameters, with RFC 2231 honoured. */
    fun contentType(): Pair<String, Map<String, String>> =
        MimeTokens.parseContentType(first("Content-Type"))

    fun contentDisposition(): Pair<String, Map<String, String>> =
        MimeTokens.parseContentType(first("Content-Disposition"))

    val charset: Charset
        get() = charsetOf(contentType().second["charset"])

    fun mediaType(): String = contentType().first.lowercase(Locale.US).ifBlank { "text/plain" }

    /** The RFC 2047-decoded view of a header, which is what a subject line needs. */
    fun decoded(name: String): String = EncodedWords.decode(first(name))

    companion object {
        fun charsetOf(raw: String?): Charset {
            val value = raw?.trim()?.removeSurrounding("\"")?.ifBlank { null } ?: return StandardCharsets.UTF_8
            return runCatching {
                Charset.forName(value.replace('_', '-').uppercase(Locale.US).let {
                    when (it) {
                        "LATIN1", "ISO88591" -> "ISO-8859-1"
                        "US", "ASCII", "ANSI_X3.4-1968" -> "US-ASCII"
                        "GB2312" -> "GB2312"
                        else -> it
                    }
                })
            }.getOrDefault(StandardCharsets.UTF_8)
        }
    }

    /** The names the module reads, kept in one list so a review can see the whole surface. */
    val interesting: List<String> = listOf(
        "Content-Type", "Content-Transfer-Encoding", "Content-Disposition", "Content-ID",
        "Content-Description", "Content-Language", "Content-Location"
    )
}

/** The header block of a part: fields, folding, and the blank line that ends it. */
object MimeHeaderParser {

    /**
     * Parses the leading header block of [bytes].
     *
     * Folding (RFC 5322 obs-fold: a continuation line starts with space or tab) is joined with a
     * single space, because a folded `Subject:` across three lines is ordinary mail, not an edge case.
     */
    fun parse(bytes: ByteArray): MimeHeaders {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        return parseText(text)
    }

    fun parseText(text: String): MimeHeaders {
        val out = ArrayList<Pair<String, String>>()
        var name: String? = null
        val value = StringBuilder()
        var afterColon = false
        for (line in text.split("\r\n", "\n")) {
            if (line.isEmpty()) break
            val continuation = line.firstOrNull() == ' ' || line.firstOrNull() == '\t'
            if (continuation && name != null) {
                value.append(' ').append(line.trim())
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                // A body line reached without a blank separator: the header block is over.
                if (name != null) {
                    out += name to value.toString().trim()
                    name = null
                }
                break
            }
            if (name != null && afterColon) out += name to value.toString().trim()
            name = line.substring(0, colon).trim()
            value.setLength(0)
            value.append(line.substring(colon + 1))
            afterColon = true
        }
        if (name != null) out += name to value.toString().trim()
        return MimeHeaders(out)
    }
}

/** MIME/SMTP token helpers: quoting, parameter lists, and the `type; key=value` split. */
object MimeTokens {

    /** `text/html; charset="utf-8"` → `text/html` plus the lower-cased parameter map. */
    fun parseContentType(raw: String): Pair<String, Map<String, String>> {
        val value = raw.trim()
        if (value.isEmpty()) return "" to emptyMap()
        val pieces = splitParameters(value)
        val type = pieces.first().substringBefore(';').trim().lowercase(Locale.US)
        val parameters = LinkedHashMap<String, String>()
        val extended = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (piece in pieces.drop(1)) {
            val key = piece.substringBefore('=').trim().lowercase(Locale.US)
            val body = piece.substringAfter('=', "").trim()
            if (key.isEmpty()) continue
            val star = key.indexOf('*')
            if (star >= 0) {
                // An RFC 2231 family member: `filename`, `filename*`, `filename*0*`, `filename*1`.
                extended.getOrPut(key.substring(0, star)) { LinkedHashMap() }[key] = body
            } else {
                parameters[key] = unquote(body)
            }
        }
        extended.forEach { (base, family) ->
            val decoded = Rfc2231.decode(family)
            if (decoded.isNotBlank() || !parameters.containsKey(base)) parameters[base] = decoded
        }
        return type to parameters
    }

    /** Splits on semicolons that are not inside a quoted string. */
    fun splitParameters(raw: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (character in raw) {
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '"' -> {
                    quote = character
                    current.append(character)
                }
                character == ';' -> {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    fun unquote(value: String): String = if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    } else {
        value
    }

    fun escapeParameter(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** A display name for an outgoing header, quoted only when it needs to be. */
    fun escapeDisplayName(value: String): String {
        val trimmed = value.trim()
        val needsQuotes = trimmed.any { ",;:<>@[]()\\\"".contains(it) } || trimmed.startsWith(" ")
        return if (needsQuotes) "\"${escapeParameter(trimmed)}\"" else trimmed
    }
}

/**
 * RFC 2231 parameter encoding: `filename*0*=utf-8''%C3%25…`, `filename*1=…`, and the plain
 * `filename*=utf-8''…` single-shot form.
 */
object Rfc2231 {

    /**
     * Decodes the collected family of one parameter. Keys arrive as `name*`, `name*0*`, `name*1`;
     * the language tag (`utf-8''`) is used for the percent-decode and then dropped.
     */
    fun decode(family: Map<String, String>): String {
        if (family.isEmpty()) return ""
        val single = family.entries.firstOrNull() ?: return ""
        val ordered = family.toSortedMap(
            compareBy({ it.substringBeforeLast('*') }, { it.substringAfterLast('*', "").toIntOrNull() ?: 0 })
        )
        val builder = StringBuilder()
        var charset = StandardCharsets.UTF_8
        var sawExtended = false
        ordered.forEach { (key, rawValue) ->
            var value = unquote(rawValue)
            if (key.endsWith("*")) {
                sawExtended = true
                val languageSplit = value.indexOf("''")
                if (languageSplit >= 0) {
                    charset = MimeHeaders.charsetOf(value.substring(0, languageSplit))
                    value = value.substring(languageSplit + 2)
                }
            }
            builder.append(value)
        }
        val joined = builder.toString()
        return if (sawExtended) percentDecode(joined, charset) else unquote(joined).ifBlank { single.value }
    }

    /** Percent-decoding of a single RFC 2231 value; a stray `%` is kept as a literal, never thrown. */
    fun percentDecode(value: String, charset: Charset = StandardCharsets.UTF_8): String {
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.code)
            i++
        }
        return String(out.toByteArray(), charset)
    }

    fun unquote(value: String): String = MimeTokens.unquote(value)
}
