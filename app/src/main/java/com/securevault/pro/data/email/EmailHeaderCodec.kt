package com.securevault.pro.data.email

import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC 2047 encoded words, in both directions, plus the extension-to-MIME table attachments need.
 *
 * The two belong in one file because they are the same kind of thing: small pure functions the
 * message codecs reached for and that the mail agent never wrote. Both are the parts of MIME that
 * real mailers get wrong often enough that a naive implementation loses data — a subject in Arabic
 * arriving as `=?UTF-8?B?...?=` across three words, or a filename with a space and a `;` in it.
 *
 * Decoding never throws. A malformed encoded word is returned verbatim, because a header the owner
 * can read with `=?…?=` in it beats a crash on an inbox whose provider escapes differently, and the
 * alternative — guessing an encoding from the bytes — turns a subject into mojibake silently.
 */
object EncodedWords {

    private const val MAX_WORD_CHARS = 75

    /**
     * Every `=?charset?B|Q?text?=` in [raw], decoded; anything else is left as written.
     *
     * Whitespace *between* two adjacent encoded words is discarded rather than preserved, which is
     * what RFC 2047 section 6.2 requires and what makes a wrapped subject rejoin into words instead
     * of `Hello  world`. Whitespace outside encoded words is kept.
     */
    fun decode(raw: String): String {
        if (raw.isEmpty() || !raw.contains("=?")) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        var closedWordImmediately = false
        while (index < raw.length) {
            val start = raw.indexOf("=?", index)
            if (start < 0) {
                out.append(raw, index, raw.length)
                break
            }
            val word = matchWord(raw, start)
            if (word == null) {
                // Not an encoded word after all: copy the "?=" -free text up to here and move on,
                // but never re-scan the same two characters.
                val next = if (start + 2 <= raw.length) start + 2 else raw.length
                out.append(raw, index, next)
                index = next
                closedWordImmediately = false
                continue
            }
            if (closedWordImmediately && out.isNotEmpty()) {
                trimTrailingSpaces(out)
            }
            val body = raw.substring(word.payloadStart, word.payloadEnd)
            out.append(decodePayload(word.charset, word.encoding, body))
            index = word.next
            closedWordImmediately = true
        }
        return out.toString()
    }

    /**
     * A `Subject:` line safe to put on the wire.
     *
     * ASCII that needs no escaping is passed through untouched — that keeps ordinary subjects
     * readable in a packet capture and in every client's log. Anything with a non-ASCII or a control
     * character is sent as UTF-8 base64 encoded words, split at word boundaries so a long subject
     * still fits inside the 75-character limit a strict server rejects.
     */
    fun encodeHeader(value: String): String {
        if (value.isEmpty()) return value
        if (value.all { it.code in 33..126 } && !value.contains("=?")) return value
        val pieces = StringBuilder()
        splitForEncoding(value).forEachIndexed { position, chunk ->
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(chunk.toByteArray(Charsets.UTF_8))
                .replace("=", "")
            // Re-padding the stripped tail is cheaper than carrying a padded encoder that would put
            // `?` in the wrong place; the alphabet has no `?` so the word delimiters stay intact.
            val padded = padBase64(encoded)
            if (position > 0) pieces.append(' ')
            pieces.append("=?UTF-8?B?").append(padded).append("?=")
        }
        return pieces.toString()
    }

    // ---------------------------------------------------------------------- decoding

    private class Word(val payloadStart: Int, val payloadEnd: Int, val next: Int, val charset: String, val encoding: Char)

    /** Walks `=?charset?enc?payload?=` from [start]; null when the shape or the alphabet is wrong. */
    private fun matchWord(raw: String, start: Int): Word? {
        var cursor = start + 2
        if (cursor >= raw.length) return null
        if (raw[cursor] != '?') return null
        cursor++
        val charsetStart = cursor
        while (cursor < raw.length && raw[cursor] != '?') cursor++
        if (cursor >= raw.length || cursor == charsetStart) return null
        val charset = raw.substring(charsetStart, cursor)
        cursor++
        val encoding = if (cursor < raw.length) raw[cursor].uppercaseChar() else ' '
        if (encoding != 'B' && encoding != 'Q') return null
        cursor++
        if (cursor >= raw.length || raw[cursor] != '?') return null
        cursor++
        val payloadStart = cursor
        val terminator = raw.indexOf("?=", payloadStart)
        if (terminator < 0) return null
        if (terminator == payloadStart) return null
        for (position in payloadStart until terminator) {
            if (!isEncodedWordCharacter(raw[position])) return null
        }
        return Word(payloadStart, terminator, terminator + 2, charset, encoding)
    }

    /** The RFC 2047 `tokenchars` minus the `?` that terminates a payload, plus space for Q's underscores. */
    private fun isEncodedWordCharacter(character: Char): Boolean =
        character == ' ' || character == '_' || (
            character.code > 32 && character.code < 127 &&
                !"?=(),<>@[]\\\";:. ".contains(character) && character != '\u007f'
            )

    private fun decodePayload(charsetName: String, encoding: Char, payload: String): String {
        val charset = charsetOf(charsetName)
        val bytes = when (encoding) {
            'B' -> base64Bytes(payload)
            else -> qDecode(payload)
        }
        if (bytes == null) return "?=$charsetName?$encoding?$payload?="
        return String(bytes, charset)
    }

    /** Base64 that tolerates a missing pad and the URL alphabet; null when the payload is not base64 at all. */
    private fun base64Bytes(payload: String): ByteArray? {
        val cleaned = payload.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        return runCatching {
            java.util.Base64.getUrlDecoder().decode(padBase64(cleaned.replace('-', '/').replace('_', '+')))
        }.getOrNull() ?: runCatching { java.util.Base64.getDecoder().decode(padBase64(cleaned)) }.getOrNull()
    }

    /** `=XX` hex escapes, `_` as space; the payload is pre-split on `_` so a literal `_` survives. */
    private fun qDecode(payload: String): ByteArray? {
        val out = ArrayList<Byte>(payload.length)
        var index = 0
        while (index < payload.length) {
            val character = payload[index]
            when {
                character == '_' -> {
                    out += ' '.code.toByte()
                    index++
                }
                character == '=' -> {
                    if (index + 2 >= payload.length) return null
                    val high = Character.digit(payload[index + 1], 16)
                    val low = Character.digit(payload[index + 2], 16)
                    if (high < 0 || low < 0) return null
                    out += ((high shl 4) or low).toByte()
                    index += 3
                }
                character.code > 126 || character.code < 32 -> index++
                else -> {
                    out += character.code.toByte()
                    index++
                }
            }
        }
        return out.toByteArray()
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        if (remainder == 0) return value
        return value + "=".repeat(4 - remainder)
    }

    private val charsetCache = ConcurrentHashMap<String, Charset>()

    private fun charsetOf(name: String): Charset {
        val key = name.trim().removeSurrounding("\"")
        if (key.isEmpty()) return Charsets.UTF_8
        return charsetCache.getOrPut(key) {
            runCatching {
                Charset.forName(key.replace('_', '-').uppercase(Locale.US).let {
                    when (it) {
                        "LATIN1", "ISO88591", "ISO_8859_1" -> "ISO-8859-1"
                        "US", "ASCII", "ANSI_X3.4-1968" -> "US-ASCII"
                        else -> it
                    }
                })
            }.getOrDefault(Charsets.UTF_8)
        }
    }

    private fun trimTrailingSpaces(out: StringBuilder) {
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
    }

    /**
     * Chunks the input so each encoded word stays under [MAX_WORD_CHARS] once base64-expanded
     * (`4/3 * bytes + 12` for the delimiters), preferring a space boundary and never cutting a
     * surrogate pair in half.
     */
    private fun splitForEncoding(value: String): List<String> {
        val maxRawBytes = ((MAX_WORD_CHARS - 12) * 3) / 4
        val pieces = mutableListOf<String>()
        var cursor = 0
        while (cursor < value.length) {
            var end = cursor
            var bytes = 0
            var lastSpace = -1
            while (end < value.length) {
                val character = value[end]
                if (character == ' ') lastSpace = end
                val width = utf8Width(character)
                if (bytes + width > maxRawBytes) break
                bytes += width
                end++
            }
            if (end >= value.length) {
                pieces += value.substring(cursor)
                break
            }
            val cut = if (lastSpace > cursor) lastSpace else end
            pieces += value.substring(cursor, cut)
            cursor = if (lastSpace > cursor) cut + 1 else cut
        }
        return pieces.filter { it.isNotEmpty() }.ifEmpty { listOf(value) }
    }

    private fun utf8Width(character: Char): Int = when {
        character.code < 0x80 -> 1
        character.code < 0x800 -> 2
        Character.isHighSurrogate(character) || Character.isLowSurrogate(character) -> 1
        else -> 3
    }
}

/**
 * Media types for an attachment name, from the extensions a phone actually produces.
 *
 * A wrong type is not cosmetic: the receiving client is told to open the bytes with it, so a
 * `.pdf` that arrives as `application/octet-stream` downloads instead of opening and a `.jpg` that
 * arrives as `text/plain` is rendered as garbage. Unknown extensions return the honest
 * `application/octet-stream` rather than a guess from the filename prefix.
 */
object MimeTypes {

    private val byExtension: Map<String, String> = mapOf(
        // documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "rtf" to "application/rtf",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        // images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
        // audio and video
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "amr" to "audio/amr",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        // archives and binaries
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "apk" to "application/vnd.android.package-archive",
        "vcf" to "text/vcard",
        "ics" to "text/calendar",
        "epub" to "application/epub+zip",
        "bin" to "application/octet-stream"
    )

    /** The type for [fileName]; `application/octet-stream` when the extension is unknown or absent. */
    fun forFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension.isEmpty() || extension == fileName.lowercase(Locale.US)) return "application/octet-stream"
        return byExtension[extension] ?: "application/octet-stream"
    }

    /** True when a receiver can be expected to show it inline rather than offer a download. */
    fun isInlineFriendly(mimeType: String): Boolean =
        mimeType.startsWith("image/") || mimeType == "application/pdf" || mimeType.startsWith("text/")

    fun extensionFor(mimeType: String): String =
        byExtension.entries.firstOrNull { it.value == mimeType.lowercase(Locale.US) }?.key.orEmpty()
}
