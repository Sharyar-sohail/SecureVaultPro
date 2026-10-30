package com.securevault.pro.data.email

/**
 * The pieces a FETCH response describes. All of them are pure values built by [ImapResponseParser]
 * from tokens, so every rule here is exercisable without a socket.
 */

/** One address from an ENVELOPE list: the four fields RFC 3501 defines, nothing invented. */
data class ImapAddress(
    val host: String,
    val mailbox: String,
    val personalName: String,
    val displayName: String
) {
    /** `Sharyar <s@example.com>`, or just the address when there is no name. */
    fun display(): String = when {
        displayName.isNotBlank() && host.isNotBlank() -> "$displayName <$mailbox@$host>"
        displayName.isNotBlank() -> "$displayName <$mailbox>"
        host.isNotBlank() -> "$mailbox@$host"
        else -> mailbox
    }

    val email: String
        get() = when {
            mailbox.isBlank() -> ""
            host.isBlank() -> mailbox
            else -> "$mailbox@$host"
        }
}

/** The ENVELOPE item: the header block as the server already parsed it. */
data class ImapEnvelope(
    val sentAt: String = "",
    val subject: String = "",
    val from: List<ImapAddress> = emptyList(),
    val sender: List<ImapAddress> = emptyList(),
    val replyTo: List<ImapAddress> = emptyList(),
    val to: List<ImapAddress> = emptyList(),
    val cc: List<ImapAddress> = emptyList(),
    val bcc: List<ImapAddress> = emptyList(),
    val inReplyTo: String = "",
    val messageId: String = ""
) {
    /** Best available originator: `sender` when set, else `from`, as the spec advises. */
    fun originator(): ImapAddress? = sender.firstOrNull() ?: from.firstOrNull()

    fun recipients(): List<ImapAddress> = to + cc + bcc

    fun dateEpochMillis(): Long = ImapDateFormat.parse(sentAt)
}

/**
 * A BODYSTRUCTURE / BODY node. A multipart node has [parts] and no leaf fields; a leaf node is the
 * reverse. `message/rfc822` carries both a nested [envelope] and a nested [bodyStructure].
 */
data class ImapBodyStructure(
    val section: String,
    val isMultiPart: Boolean,
    val mediaType: String = "TEXT",
    val mediaSubtype: String = "PLAIN",
    val parameters: Map<String, String> = emptyMap(),
    val contentId: String = "",
    val description: String = "",
    val encoding: String = "7BIT",
    val octetSize: Long = 0L,
    val dispositionType: String = "",
    val dispositionParameters: Map<String, String> = emptyMap(),
    val language: String = "",
    val location: String = "",
    val parts: List<ImapBodyStructure> = emptyList(),
    val envelope: ImapEnvelope? = null,
    val bodyStructure: ImapBodyStructure? = null
) {
    val fullType: String get() = "$mediaType/$mediaSubtype".uppercase()

    /** A leaf that should be saved as a file rather than shown: an attachment or a non-text part. */
    fun looksLikeAttachment(): Boolean =
        !isMultiPart && (
            dispositionType.equals("attachment", ignoreCase = true) ||
                fileName().isNotBlank() ||
                (!fullType.startsWith("TEXT") && !fullType.startsWith("MESSAGE/") &&
                    !fullType.startsWith("MULTIPART/") && !isInlineText())
            )

    /** The name the sender chose, from either place RFC 2183 allows it. */
    fun fileName(): String {
        val dispositionName = dispositionParameters["filename"].orEmpty().ifBlank {
            dispositionParameters["name"].orEmpty()
        }
        if (dispositionName.isNotBlank()) return Rfc2231.unquote(dispositionName)
        val typeName = parameters["name"].orEmpty()
        if (typeName.isNotBlank()) return Rfc2231.unquote(typeName)
        if (fullType.startsWith("IMAGE/") || fullType.startsWith("APPLICATION/")) return contentId
        return ""
    }

    /** True for a text part the reader walks rather than saves. */
    fun isInlineText(): Boolean {
        if (isMultiPart) return false
        val type = fullType
        val inline = dispositionType.isBlank() || dispositionType.equals("inline", ignoreCase = true)
        return inline && (type == "TEXT/PLAIN" || type == "TEXT/HTML")
    }
}

/** One message's FETCH answer, keyed by the section names the server used. */
data class ImapFetchItem(
    val uid: Long = 0L,
    val sequence: Int = 0,
    val flags: List<String> = emptyList(),
    val internalDate: Long = 0L,
    val rfc822Size: Long = 0L,
    val envelope: ImapEnvelope? = null,
    val bodyStructure: ImapBodyStructure? = null,
    /** Literal bodies by section name, e.g. `BODY[HEADER]`, `BODY[TEXT]`, `BODY[]`. */
    val sections: Map<String, ByteArray> = emptyMap(),
    /** Sections that were streamed to the cache instead of kept, with their destination path. */
    val streamedSections: Map<String, Long> = emptyMap(),
    val streamedPaths: Map<String, String> = emptyMap()
) {
    fun hasFlag(flag: String): Boolean = flags.any { it.equals(flag, ignoreCase = true) }
    val seen: Boolean get() = hasFlag("\\Seen")
    val flagged: Boolean get() = hasFlag("\\Flagged")
    val deleted: Boolean get() = hasFlag("\\Deleted")
}

/** The IMAP date formats a server may hand back, parsed without a locale dependency. */
object ImapDateFormat {

    /** `Thu, 29 Oct 2026 12:34:56 +0500` and the day-of-month padding variants. */
    fun parse(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        val withoutDay = value.substringAfter(' ', value).trim()
        val candidate = if (withoutDay.contains(',') && withoutDay.take(4).all(Char::isDigit)) value else withoutDay
        val tokens = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Expected: [day] Mon year time zone  — the weekday is dropped when a server includes one.
        val parts = if (tokens.size >= 5 && tokens[0].endsWith(",")) tokens.drop(1) else tokens
        if (parts.size < 4) return 0L
        val day = parts[0].trimStart('0').toIntOrNull() ?: return 0L
        val month = MONTHS[parts[1].take(3).lowercase()] ?: return 0L
        val year = parts[2].toIntOrNull() ?: return 0L
        val time = parts[3].split(":").map { it.toIntOrNull() ?: 0 }
        if (time.size < 3) return 0L
        val offset = parseOffset(parts.getOrNull(4).orEmpty())
        val millis = utcMillis(year, month, day, time[0], time[1], time[2]) - offset * 60_000L
        return if (millis < 0L) 0L else millis
    }

    private fun parseOffset(raw: String): Int {
        val value = raw.trim()
        if (value.length < 3) return 0
        val sign = if (value.first() == '-') -1 else 1
        val digits = value.drop(1)
        val hours = digits.take(2).toIntOrNull() ?: return 0
        val minutes = digits.drop(2).toIntOrNull() ?: 0
        return sign * (hours * 60 + minutes)
    }

    /** Days-since-epoch arithmetic kept in-house so no `SimpleDateFormat` locale can shift a parse. */
    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val days = daysFromCivil(year, month, day)
        return ((days * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L)
    }

    /** Howard Hinnant's civil-from-days algorithm: exact for every date a mail server can name. */
    private fun daysFromCivil(yIn: Int, m: Int, d: Int): Long {
        var y = yIn.toLong()
        y -= if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )
}
