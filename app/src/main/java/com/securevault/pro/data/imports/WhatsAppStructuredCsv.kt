package com.securevault.pro.data.imports

/**
 * WhatsApp's structured messages table, read back into the conversation it describes.
 *
 * Since the 2024/2025 exports, "Export chat" can put a `_structured_messages_1.csv` beside the
 * transcript: one row per bubble, with the timestamp, the sender, the message type, the text and
 * the attachment in *separate columns*. That is a better source than prose — nothing has to be
 * guessed out of a sentence — but it is not the shape the vault parses, and on some builds it is
 * the **only** shape delivered (a Business export can carry the CSV and a transcript that failed to
 * write). So it is converted here, and the conversion is deliberately conservative:
 *
 *  - a row only becomes a message when it carries a timestamp, because the vault counts, draws and
 *    re-exports bubbles by their stamp; a row without one is kept as prose under the bubble above
 *    and reported in [Structured.undated], never silently counted;
 *  - the attachment column becomes WhatsApp's own `(file attached)` marker, so the existing
 *    reference index — and with it the media join — works on CSV-derived text exactly as on text;
 *  - `<Media omitted>` and empty rows are kept verbatim rather than dropped, and a header that
 *    names no sender and no content column is rejected as what it is: some other spreadsheet.
 *
 * Pure JVM, RFC 4180 quoting (embedded commas, doubled quotes, CRLF), BOM tolerant.
 */
object WhatsAppStructuredCsv {

    /** One parsed conversation table. */
    data class Structured(
        val transcript: String,
        val rows: Int,
        val messages: Int,
        val undated: Int,
        val attachments: List<String>
    ) {
        val usable: Boolean get() = transcript.isNotBlank()
    }

    /** `_structured_messages.csv`, `_structured_messages_1.csv`, `structured messages (2).csv`. */
    fun isStructuredMessagesFile(name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (!fileName.endsWith(".csv")) return false
        val stem = fileName.removeSuffix(".csv")
        return stem.contains("structured") && stem.contains("message") ||
            stem == "messages" || stem.matches(Regex("messages_?\\d*"))
    }

    private val TIMESTAMP_KEYS = listOf("timestamp", "time", "date")
    private val SENDER_KEYS = listOf("sender", "name", "from", "who")
    private val CONTENT_KEYS = listOf("content", "message", "text", "body")
    private val ATTACHMENT_KEYS = listOf("attachment", "media", "file", "image", "video")

    /**
     * Reads the table. Null when the bytes hold no header the vault can recognise — the caller then
     * says so out loud instead of importing a spreadsheet as a chat.
     */
    fun parse(text: String): Structured? {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (clean.isBlank()) return null
        val rows = readRows(clean)
        if (rows.isEmpty()) return null
        val header = rows.first().map { it.trim().lowercase() }
        val stampAt = indexOfAny(header, TIMESTAMP_KEYS)
        val senderAt = indexOfAny(header, SENDER_KEYS)
        val contentAt = indexOfAny(header, CONTENT_KEYS)
        val attachAt = indexOfAny(header, ATTACHMENT_KEYS)
        if (senderAt < 0 && contentAt < 0) return null
        val body = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }

        val out = StringBuilder()
        val attachments = LinkedHashSet<String>()
        var stamped = 0
        var undated = 0
        body.forEach { row ->
            val stamp = stampAt.takeIf { it >= 0 && it < row.size }?.let { normalizeStamp(row[it]) }.orEmpty()
            val sender = senderAt.takeIf { it >= 0 && it < row.size }?.let { row[it].trim() }.orEmpty()
            val content = contentAt.takeIf { it >= 0 && it < row.size }?.let { row[it].trim() }.orEmpty()
            val attachment = attachAt.takeIf { it >= 0 && it < row.size }?.let { row[it].trim() }.orEmpty()
            if (attachment.isNotBlank() && !isOmitted(attachment)) {
                attachments.add(attachment.substringAfterLast('/').substringAfterLast('\\'))
            }
            val line = when {
                stamp.isNotBlank() -> {
                    stamped++
                    val speaker = if (sender.isBlank()) "" else "$sender: "
                    val prose = content.trim()
                    // WhatsApp's own marker shape, so the existing attachment index and the media
                    // join work on CSV-derived text exactly as they do on an exported transcript.
                    val media = when {
                        attachment.isBlank() || isOmitted(attachment) -> ""
                        prose.isBlank() -> "$attachment (file attached)"
                        prose.contains(attachment, ignoreCase = true) -> ""
                        else -> "$prose $attachment (file attached)"
                    }
                    "$stamp - $speaker$prose$media"
                }

                else -> {
                    // No timestamp means the vault cannot place this bubble in time; keeping the
                    // words as an unstamped line preserves them without inventing a count.
                    undated++
                    if (sender.isBlank()) content else "$sender: $content"
                }
            }
            if (line.isNotBlank()) out.appendLine(line.trim())
        }
        if (out.isBlank()) return null
        return Structured(
            transcript = out.toString().trim(),
            rows = body.size,
            messages = stamped,
            undated = undated,
            attachments = attachments.toList()
        )
    }

    /** `2024-08-15T10:23:45Z` and `8/15/24, 10:23 AM` both reach the stamp dialects the parser knows. */
    private fun normalizeStamp(raw: String): String {
        val value = raw.trim().removeSuffix("Z")
        if (value.isBlank()) return ""
        val iso = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}(?::\\d{2})?).*$").find(value)
        return if (iso != null) "${iso.groupValues[1]}, ${iso.groupValues[2]}" else value
    }

    private fun isOmitted(value: String): Boolean =
        value.isBlank() || value.equals("nan", ignoreCase = true) || value == "<Media omitted>" ||
            value.contains("omitt", ignoreCase = true) && value.startsWith("<")

    /** A header cell compared on its own: `Message Type` is not the `Message` column. */
    private fun indexOfAny(header: List<String>, keys: List<String>): Int {
        keys.forEach { key ->
            val exact = header.indexOfFirst { it.replace(" ", "_").trim('"') == key }
            if (exact >= 0) return exact
        }
        keys.forEach { key ->
            val close = header.indexOfFirst { cell ->
                cell.split('_', ' ').any { it == key } || (cell.length <= key.length + 12 && cell.contains(key))
            }
            if (close >= 0) return close
        }
        return -1
    }

    /**
     * RFC 4180 reader: quoted fields may hold commas, newlines and doubled quotes, because a chat
     * message holds all three and WhatsApp quotes every cell.
     */
    private fun readRows(text: String): List<List<String>> {
        val rows = ArrayList<ArrayList<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted -> {
                    if (ch == '"') {
                        if (index + 1 < text.length && text[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else quoted = false
                    } else field.append(ch)
                }

                ch == '"' -> quoted = true
                ch == ',' -> {
                    row.add(field.toString())
                    field = StringBuilder()
                }

                ch == '\n' -> {
                    row.add(field.toString())
                    field = StringBuilder()
                    rows.add(row)
                    row = ArrayList()
                }

                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
