package com.securevault.pro.data.imports

import java.security.MessageDigest

/**
 * Reads a WhatsApp chat transcript as data, not as text.
 *
 * The export file is the only place WhatsApp writes who said what, and it hides two things
 * the vault needs: which media a conversation actually contains, and how big that
 * conversation is. Every attachment appears in the transcript under the *exact* filename the
 * phone saved it with — `IMG-20240815-WA0007.jpg (file attached)`, `(FILE ATTACHED: …)` in
 * newer builds — so the transcript is also the index that lets "open a chat, get its files"
 * work without asking the owner to browse a folder.
 *
 * Pure JVM: no Android types, no content providers, so the parsing rules are testable.
 */
object WhatsAppTranscript {

    /** What kind of attachment a name refers to — decides which media folder to search. */
    enum class MediaKind { Image, Video, Audio, Document }

    /**
     * One attachment mentioned by the transcript, in order of first appearance.
     * [attached] is true when WhatsApp wrote its own attachment marker on that line; a name
     * that only appears in prose ("send me the IMG-….jpg") is recorded but marked, so the
     * planner can put real attachments first instead of silently mixing the two.
     */
    data class Reference(val fileName: String, val kind: MediaKind, val line: Int, val attached: Boolean)

    data class Parsed(
        val messageCount: Int,
        val participants: List<String>,
        val firstStamp: String,
        val lastStamp: String,
        val references: List<Reference>,
        val omittedMedia: Int,
        val sha256: String
    ) {
        val empty: Boolean get() = messageCount == 0 && references.isEmpty()
    }

    /**
     * One bubble of the conversation, kept in the shape WhatsApp wrote it: the day and clock
     * exactly as exported, the sender when the export names one, the prose with WhatsApp's own
     * attachment markers taken out, and the files that message carried.
     */
    data class ChatMessage(
        val day: String,
        val clock: String,
        val sender: String?,
        val body: String,
        val files: List<String>,
        /** WhatsApp's line about the chat itself — "Missed voice call", the encryption notice. */
        val notice: Boolean = false,
        /** One of WhatsApp's "the message is gone" lines, which is a notice with its own wording. */
        val deleted: Boolean = false
    )

    /** The seam between the vault's own header lines and the exported conversation below them. */
    const val CONVERSATION_MARKER = "— Conversation —"

    /** The seam where the archive's proof block starts — written by [WhatsAppArchive.manifest]. */
    const val PROOF_MARKER = "— Archive proof —"

    /**
     * WhatsApp's own bookkeeping inside an export zip (`_MISSING.txt`, `_WEBSITES_listing.txt`,
     * `_MEDIAS REMOVED.txt`). It lists what could not be included and never holds the
     * conversation, so it must not win the transcript probe.
     */
    fun isTranscriptManifest(name: String): Boolean =
        MANIFEST_NAME.matches(name)

    private val MANIFEST_NAME = Regex("(?i)^_?(missing|websites.listing|medias?[\\s_-]*removed)\\.txt$")

    /**
     * Whether a file inside an export archive can hold the conversation at all: a `.txt` that is
     * not one of WhatsApp's own bookkeeping manifests. Name decides *eligibility* only — which
     * eligible file really carries the chat is decided by its bytes, in [bestTranscript].
     */
    fun isTranscriptCandidate(name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        return fileName.lowercase().endsWith(".txt") && !isTranscriptManifest(fileName)
    }

    /** How many bubbles these bytes carry — the measure the probe ranks archive `.txt` files by. */
    fun messageCount(text: String): Int = messages(text).size

    /** One `.txt` read out of an export archive, with the conversation its bytes hold. */
    data class TranscriptCandidate(val name: String, val text: String) {
        val messages: Int get() = messageCount(text)
    }

    /**
     * The content probe, in one pure call: of the eligible `.txt` files in an archive, the one
     * carrying the most messages is the chat, the others are attachments the owner kept by
     * mistake, and a file with no timestamps in it is not a chat at all. WhatsApp names the
     * transcript after the chat, and that name is not stable across versions, regions or
     * Business/iOS — so the name never decides.
     */
    fun bestTranscript(candidates: List<TranscriptCandidate>): TranscriptCandidate? =
        candidates.filter { isTranscriptCandidate(it.name) }
            .maxByOrNull { it.messages }
            ?.takeIf { it.messages > 0 }

    /**
     * The conversation inside an archived note, without the archive's header and without its
     * proof block. The viewer renders exactly this, so its bubble count is the count the import
     * reported: leaving the manifest attached would glue a page of bookkeeping onto the last
     * message — and a date-first line in it would be drawn as a message that never happened.
     */
    fun transcriptOf(content: String): String =
        content.substringAfter(CONVERSATION_MARKER, content)
            .substringBefore(PROOF_MARKER)
            .trim()

    /**
     * The name of the conversation, read out of the name WhatsApp gave the file. It carries the
     * chat on some builds and nothing but a number on others, so the bookkeeping tokens come off
     * case-insensitively: Android writes `… _chat.txt`, iOS writes `… _CHAT.txt`, Business writes
     * `business_chat-92345…@c.us.txt`, and `_WAChatSource.txt` is WhatsApp's own credits file.
     * A bare number becomes a dialable label rather than a meaningless string of digits.
     */
    fun titleFrom(name: String): String {
        var value = name.substringAfterLast('/').substringAfterLast('\\')
        value = value.removeSuffix(".zip").removeSuffix(".ZIP").removeSuffix(".txt").removeSuffix(".TXT")
        // The scaffolding `_` WhatsApp puts in front of `_chat.txt`, `_media` and the desktop
        // export's `_WhatsApp Chat - Ali.txt` is not part of the conversation name.
        value = value.replace(Regex("^_+"), "")
        value = value.replace("WhatsApp Chat with ", "", ignoreCase = true)
        value = value.replace(Regex("(?i)^whatsapp chat\\s*[-–]?\\s*"), "")
        value = value.replace(Regex("(?i)^business[_\\s-]*"), "")
        value = value.substringBefore("@s.whatsapp.net").substringBefore("@g.us").substringBefore("@c.us")
        value = value.replace(Regex("(?i)(^|[\\s_-])chat$"), "")
        value = value.replace(Regex("(?i)(^|[\\s_-])wachatsource$"), "")
        value = value.replace(Regex("(?i)^chat\\s*[-_]\\s*"), "")
        value = value.trim('-', '_', ' ', '.')
        if (value.matches(Regex("^\\d{7,15}$"))) value = "Chat · +$value"
        return value.ifBlank { "WhatsApp chat" }
    }

    /**
     * Whether a note is a conversation worth drawing as bubbles. The archive always writes the
     * marker, and a hand-pasted transcript is recognised by its stamped lines, so both paths
     * reach the chat view without needing a tag.
     */
    fun isChat(content: String): Boolean {
        if (content.contains(CONVERSATION_MARKER) || content.startsWith("WhatsApp transcript ·")) return true
        return content.lineSequence().take(40).count { STAMP.containsMatchIn(it) } >= 3
    }

    /**
     * The transcript read as messages rather than as statistics — what the chat view draws.
     * Unstamped lines belong to the message above them, because WhatsApp wraps long messages
     * over several lines without repeating the stamp.
     *
     * Kept as the whole-text entry point; the streaming importer uses [stampedMessage] line by line
     * with the same continuation rule, so a chat parsed from a 5 GB file and a chat parsed from a
     * string cannot disagree about where one message ends.
     */
    fun messages(text: String): List<ChatMessage> {
        val lines = stripBidi(text.replace("\r\n", "\n")).split('\n')
        val conversation = ArrayList<ChatMessage>(lines.size)
        var current: ChatMessage? = null
        var body = StringBuilder()
        var openFiles = mutableListOf<String>()

        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) {
                if (current != null && body.isNotEmpty()) body.append('\n')
                return@forEachIndexed
            }
            val stamped = stampedMessage(line, index)
            if (stamped == null) {
                if (current != null) {
                    if (body.isNotEmpty()) body.append('\n')
                    val extra = attachmentsOf(line, index)
                    if (extra.isEmpty()) {
                        body.append(line)
                    } else {
                        extra.map { it.fileName }.forEach { name -> if (name !in openFiles) openFiles += name }
                        body.append(displayBody(line, extra.map { it.fileName }).ifBlank { line })
                    }
                }
                return@forEachIndexed
            }
            current?.let { conversation += it.copy(body = body.toString().trim(), files = openFiles) }
            current = stamped
            openFiles = stamped.files.toMutableList()
            body = StringBuilder(stamped.body)
        }
        current?.let { conversation += it.copy(body = body.toString().trim(), files = openFiles) }
        return conversation
    }

    /**
     * One line as a message, or null when the line does not open with a WhatsApp timestamp and so
     * belongs to the message above it.
     *
     * This is the seam the streaming parser reads through: the stamp dialects, the sender rule, the
     * attachment markers and the notice wording are decided here and nowhere else, so a line cannot
     * parse one way in the viewer and another way on the way into the database.
     */
    fun stampedMessage(line: String, index: Int): ChatMessage? {
        val value = line.trim()
        if (value.isEmpty()) return null
        val stamp = STAMP.find(value) ?: return null
        val stamped = stampText(stamp.value)
        var day = stamped
        var clock = ""
        CLOCK_TAIL.find(stamped)?.let {
            day = it.groupValues[1].trim()
            clock = it.groupValues[2].trim()
        }
        val afterStamp = value.substring(stamp.range.last + 1)
        val sender = senderOf(afterStamp)
        val content = if (sender == null) afterStamp else afterStamp.substring(sender.length + 1)
        val files = attachmentsOf(content, index).map { it.fileName }
        val deleted = isDeletedMessage(content)
        return ChatMessage(
            day = day,
            clock = clock,
            sender = sender,
            body = displayBody(content, files),
            files = ArrayList(files),
            notice = isNotice(content, deleted),
            deleted = deleted
        )
    }

    /** True when a line opens a message rather than wrapping the one above it. */
    fun isStamped(line: String): Boolean = stampedMessage(line, 0) != null

    /**
     * The names a line references as files, in the order it references them. The public seam the
     * streaming reader uses so a wrapped message and a stamped one are searched for attachments by
     * the same rules — the marker dialects and the prose-vs-attachment decision live here and nowhere
     * else.
     */
    fun attachmentsIn(line: String, index: Int): List<String> = attachmentsOf(line, index).map { it.fileName }

    /** [attachmentsIn] with WhatsApp's own marker decision kept: prose mentions stay unmarked. */
    fun referencesIn(line: String, index: Int): List<Reference> = attachmentsOf(line, index)

    /** The message text with every attachment marker and referenced name taken out. */
    fun bodyWithoutAttachments(text: String, files: List<String>): String = displayBody(text, files)

    private val CLOCK_TAIL =
        Regex("^(.*?)[\\s,]+(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[APap][Mm])?)$")

    /** WhatsApp's notice lines describe the chat; they are centred, not bubbled. */
    private fun isNotice(afterSender: String, deleted: Boolean = isDeletedMessage(afterSender)): Boolean {
        val value = afterSender.trim().trim('*').trim()
        if (value.isEmpty()) return false
        if (OMITTED.containsMatchIn(value)) return false
        if (deleted) return true
        return NOTICE.matches(value) || value.lowercase() in SYSTEM_LINES
    }

    /**
     * WhatsApp's "the message is gone" family. The wording depends on who deleted it and on the
     * version, and two of them are not sentences a reader would call a message: `~ May have deleted
     * this message` is what an export writes when the *other* side removed it, and the leading tilde
     * is WhatsApp's own marker rather than text the sender typed. Left unhandled it became a speech
     * bubble from nobody, which is the "import shows my chat full of strange messages" report.
     */
    private val DELETED = Regex(
        "(?i)^(this message was deleted|you deleted this message|deleted message|" +
            "may have deleted this message|this message was deleted by a sender|" +
            "you deleted this message for everyone|message was deleted)\\s*~?$"
    )

    /** True for the deleted-message wording, with or without the tilde WhatsApp wraps it in. */
    fun isDeletedMessage(value: String): Boolean {
        val flat = stripBidi(value).trim().trim('*', '~', ' ', '\t').trim()
        return flat.isNotEmpty() && DELETED.matches(flat.removeSuffix("~").trim())
    }

    private val NOTICE = Regex(
        "(?i)(messages and calls are end-to-end encrypted.*|created group|joined using this invite link|" +
            "missed (voice|video) call|missed call|voice call|video call|deleted message|" +
            "this message was deleted|you deleted this message|may have deleted this message|left|" +
            "now owns this phone number|you (added|removed) .*|security code changed.*|" +
            "turned on (snap mode|disappearing messages)|\\d+ (participants|members))"
    )

    /**
     * The message as a reader sees it. WhatsApp's attachment markers and the filenames they
     * point at are the media bubble's job, so both come out of the caption — what is left is
     * what the person actually typed.
     */
    private fun displayBody(afterSender: String, files: List<String>): String {
        var value = ATTACHED_PAREN.replace(afterSender, " ")
        value = ATTACHED_BRACKET.replace(value, " ")
        value = ATTACHED_BRACKET_PAREN.replace(value, " ")
        files.forEach { name -> value = value.replace(name, " ", ignoreCase = true) }
        value = ATTACHED_SUFFIX.replace(value, " ")
        value = value.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
        return if (value.equals("attached", ignoreCase = true)) "" else value
    }

    /**
     * The line-opening timestamp. WhatsApp writes it differently per locale and version —
     * `15/08/2024, 10:23 - `, `[15/08/2024, 10:23:45] `, `2024-08-15, 10:23 - `, `15 Aug 2024,
     * 10:24:11 - ` and, on exports from iPhones set to English, the month-first
     * `Aug 15, 2024, 10:24:11 AM`. The clock is optional: Business and some desktop exports write
     * the date alone (`15/08/2024 - Ali: Salaam`), and a bubble is still a bubble when the hour
     * was never exported. Only a line that opens with one of these is a message; everything else
     * is a wrapped line or a footer.
     */
    private val STAMP = Regex(
        "^\\s*\\[?(" +
            "\\d{1,2}\\s+[A-Za-z]{3,9}\\.?\\s+\\d{2,4}" +        // 15 Aug 2024
            "|[A-Za-z]{3,9}\\.?\\s+\\d{1,2},?\\s+\\d{4}" +       // Aug 15, 2024
            "|\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}" +            // 2024-08-15
            "|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}" +          // 15/08/2024
            ")" +
            "(?:\\s*(?:,|\\s+at\\s+)\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[APap][Mm])?)?" +
            "\\]?\\s*[-–—:]?\\s*"
    )

    private val WA_SEQUENCED = Regex(
        "(?i)\\b(IMG|VID|PTT|AUD|STK|TX|DOC|TPS|BLC)-\\d{8}-WA\\d{4}\\.\\w{2,5}"
    )

    /** iOS-style and voice/document names: "WhatsApp Image 2024-08-15 at 10.23.45.jpeg". */
    private val WA_SPACED = Regex(
        "(?i)\\b((?:WhatsApp|Business WhatsApp|Voice Message|Voice call|Video call|Document|Audio Message|Status Me)" +
            "[\\w \\-.]*?)\\s(\\d{4}-\\d{2}-\\d{2})(?:\\s?at\\s?(\\d{2}[.:]\\d{2}[.:]\\d{2}))?\\.([a-zA-Z0-9]{2,6})"
    )

    /**
     * WhatsApp's own attachment marker. The filename it points at is read from the text in
     * front of it rather than from a fixed-width token, because document names carry spaces
     * ("Report FINAL.pdf") and message bodies carry words ("here is the Report FINAL.pdf").
     */
    private val ATTACHED_SUFFIX = Regex("(?i)\\(file attached\\)")

    /** `(FILE ATTACHED: IMG-20240815-WA0007.jpg)` — the newer WhatsApp wording. */
    private val ATTACHED_PAREN = Regex("(?i)\\(FILE ATTACHED:\\s*(.+?)\\)")

    /**
     * iOS writes the marker the other way round, with the name inside the brackets:
     * `[attached: 0000000003-IMG_2078.jpeg]`, and some builds prefix it with the media-attachment
     * token (`[MMMMediaAttachment: …]`, `[MedDiaAttachment: …]`). The name inside is the name the
     * phone wrote the file with, so it is the one that has to reach the archive index.
     */
    private val ATTACHED_BRACKET = Regex("(?i)\\[\\s*(?:(?:M{2,4}[A-Za-z]*MediaAttachment)|attached)\\s*:\\s*([^\\]\\r\\n]+)\\]")

    /** `(attached: Invoice.pdf)` — the same marker with round brackets, seen on older iOS exports. */
    private val ATTACHED_BRACKET_PAREN = Regex("(?i)\\(\\s*attached\\s*:\\s*([^)\\r\\n]+)\\)")

    /** `<Media omitted>`, `<Média ignorada>`, `<Archivo multimedia omitido>` … */
    private val OMITTED = Regex("(?i)<[^>]{0,60}(?:omitt?ed|ignorad|omit|не отправлено|не було надіслано)[^>]{0,60}>")

    private val SYSTEM_LINES = setOf(
        "missed voice call", "missed video call", "deleted message", "this message was deleted",
        "you deleted this message", "created group", "joined using", "left", "now owns this phone number"
    )

    fun parse(text: String): Parsed {
        val clean = stripBidi(text.replace("\r\n", "\n"))
        val lines = clean.split('\n')
        var messages = 0
        var omitted = 0
        var firstStamp = ""
        var lastStamp = ""
        val senders = LinkedHashMap<String, Int>()
        val references = LinkedHashMap<String, Reference>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val stampMatch = STAMP.find(line)
            if (stampMatch == null) return@forEachIndexed
            val stamp = stampText(stampMatch.value)
            if (firstStamp.isBlank()) firstStamp = stamp
            lastStamp = stamp
            messages++

            val afterStamp = line.substring(stampMatch.range.last + 1)
            val sender = senderOf(afterStamp)
            sender?.let { senders[it] = (senders[it] ?: 0) + 1 }
            if (OMITTED.containsMatchIn(line)) omitted++

            // Scan the body, not the whole line: "Ali: Report FINAL.pdf (file attached)" must
            // yield "Report FINAL.pdf", never a name with the sender glued to it.
            val body = if (sender == null) afterStamp else afterStamp.substring(sender.length + 1)
            for (reference in attachmentsOf(body, index)) {
                val key = reference.fileName.lowercase()
                val existing = references[key]
                // A file first mentioned in prose and later attached is an attachment: the
                // stronger fact wins, and the longer spelling beats a truncated tail.
                val replaces = existing == null ||
                    (reference.attached && !existing.attached) ||
                    (reference.attached == existing.attached && reference.fileName.length > existing.fileName.length)
                if (replaces) references[key] = reference
            }
        }

        val ranked = senders.entries.sortedByDescending { it.value }.map { it.key }
        return Parsed(
            messageCount = messages,
            participants = ranked.take(20),
            firstStamp = firstStamp,
            lastStamp = lastStamp,
            references = references.values.toList(),
            omittedMedia = omitted,
            sha256 = sha256Of(clean)
        )
    }

    /**
     * The timestamp as a reader of the chat would write it — date and clock, without the
     * brackets or the dash WhatsApp puts between the time and the sender. The archive's span is
     * quoted back to the owner, so it has to match what WhatsApp showed them.
     */
    private fun stampText(matched: String): String = matched.trim().trim { it in "[]–—-: " }

    /**
     * A message line is `Name: body`; a 1:1 chat line is just `body`, and system lines have
     * no sender. Anything that looks like a sentence rather than a short label is skipped,
     * because the first colon in a message body is not a name separator.
     */
    private fun senderOf(afterStamp: String): String? {
        val colon = afterStamp.indexOf(':')
        if (colon <= 0 || colon > 80) return null
        val candidate = afterStamp.substring(0, colon).trim()
        if (candidate.isEmpty()) return null
        if (candidate.length > 60) return null
        if (candidate.contains('\n')) return null
        if (candidate.lowercase() in SYSTEM_LINES) return null
        // Timestamps inside the body ("see you at 10:30") must not become senders.
        if (candidate.matches(Regex("\\d{1,2}:?\\d{0,2}\\s*[APap]?[Mm]?"))) return null
        return candidate
    }

    private fun attachmentsOf(body: String, index: Int): List<Reference> {
        val marked = ATTACHED_PAREN.containsMatchIn(body) || ATTACHED_SUFFIX.containsMatchIn(body) ||
            ATTACHED_BRACKET.containsMatchIn(body) || ATTACHED_BRACKET_PAREN.containsMatchIn(body)
        val found = LinkedHashSet<Reference>()
        for (marker in listOf(ATTACHED_PAREN, ATTACHED_BRACKET, ATTACHED_BRACKET_PAREN)) {
            marker.findAll(body).forEach { match ->
                val name = match.groupValues[1].trim().substringAfterLast('/').substringAfterLast('\\')
                if (name.isNotBlank()) found.add(Reference(name, kindOf(name), index, true))
            }
        }
        var cursor = 0
        ATTACHED_SUFFIX.findAll(body).forEach { match ->
            // The name is the file-like tail of the text just before WhatsApp's own marker;
            // "see the Invoice Dec.pdf (file attached)" names one file, not four.
            val name = fileTail(body.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            if (name.isNotBlank()) found.add(Reference(name, kindOf(name), index, true))
        }
        WA_SEQUENCED.findAll(body).forEach { match ->
            found.add(Reference(match.value, kindOf(match.value), index, marked))
        }
        WA_SPACED.findAll(body).forEach { match ->
            val label = match.groupValues[1].trim()
            val day = match.groupValues[2]
            val clock = match.groupValues[3]
            val extension = match.groupValues[4]
            val name = if (clock.isBlank()) "$label $day.$extension" else "$label $day at $clock.$extension"
            found.add(Reference(name, kindOf(name), index, marked))
        }
        return keepLongest(found.toList())
    }

    /**
     * A filename walked backwards from its extension. Stops at prose ("the", "please"), at a
     * label or sender that ends in a colon, and at 120 characters, so the tail is a name rather
     * than a sentence that happens to finish with one.
     */
    private fun fileTail(before: String): String {
        if (before.isBlank()) return ""
        val tokens = before.trim().split(Whitespace)
        if (!EXTENSION_AT_END.containsMatchIn(tokens.last())) return ""
        var start = tokens.size - 1
        var i = tokens.size - 2
        while (i >= 0) {
            val token = tokens[i]
            if (token.lowercase() in PROSE_WORDS || token.endsWith(":")) break
            if (!NAME_PART.matches(token)) break
            if (tokens.subList(i, tokens.size).joinToString(" ").length > MAX_NAME_CHARS) break
            start = i
            i--
        }
        return tokens.subList(start, tokens.size).joinToString(" ")
    }

    /**
     * WhatsApp gives the same file two spellings on one line — the full
     * "WhatsApp Image 2024-08-15 at 10.23.45.jpeg" and, from the marker scan, its tail
     * "10.23.45.jpeg". The longer spelling is the one the phone actually stored, so the shorter
     * tail of an existing name is dropped instead of becoming a phantom missing file.
     */
    private fun keepLongest(refs: List<Reference>): List<Reference> {
        val names = refs.map { it.fileName.lowercase() }
        return refs.filter { reference ->
            val lower = reference.fileName.lowercase()
            names.none { it != lower && it.endsWith(" $lower") }
        }
    }

    private const val MAX_NAME_CHARS = 120
    private val Whitespace = Regex("\\s+")
    private val EXTENSION_AT_END = Regex("\\.[A-Za-z0-9]{2,5}$")
    private val NAME_PART = Regex("^[\\p{L}.\\d][\\p{L}\\p{N}._\\-]*$")
    private val PROSE_WORDS = setOf(
        "a", "an", "and", "as", "at", "by", "for", "from", "here", "i", "in", "is", "it", "me",
        "my", "of", "on", "or", "please", "see", "send", "sending", "sent", "that", "the",
        "this", "to", "u", "we", "with", "you", "your"
    )

    /** Extension wins, then the WhatsApp name prefix and the words a voice note is saved under. */
    fun kindOf(fileName: String): MediaKind {
        val lower = fileName.lowercase()
        val extension = lower.substringAfterLast('.', "")
        return when {
            extension in IMAGE_EXT -> MediaKind.Image
            extension in VIDEO_EXT -> MediaKind.Video
            extension in AUDIO_EXT -> MediaKind.Audio
            lower.startsWith("img-") || lower.contains("whatsapp image") ||
                lower.contains("whatsapp sticker") || lower.contains("status me") -> MediaKind.Image
            lower.startsWith("vid-") || lower.contains("whatsapp video") -> MediaKind.Video
            lower.startsWith("ptt-") || lower.startsWith("aud-") ||
                VOICE_WORD.containsMatchIn(lower) -> MediaKind.Audio
            else -> MediaKind.Document
        }
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "3gp", "mov", "avi", "mkv", "webm")
    private val AUDIO_EXT = setOf("opus", "aac", "m4a", "mp3", "amr", "wav", "ogg", "caf")

    /** Whole words only: "Invoice.pdf" ends with the letters of "voice" but is not a voice note. */
    private val VOICE_WORD = Regex("\\b(voice|voicemail|audio|recording)\\b")

    /** WhatsApp's bidi control characters break alignment and confuse hashing. */
    fun stripBidi(text: String): String =
        text.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")

    fun sha256Of(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * The note a conversation is kept in, and the address of its own media container. Keyed on the
     * *name* WhatsApp exported rather than the transcript bytes, so a re-export that added twenty
     * messages updates the same note and the same folder instead of forking a second copy of the
     * chat — and so the picker path, the share-inbox path and the folder-hub path all agree on one
     * id for one conversation. A name that says nothing falls back to the source file, so two
     * nameless exports never share a container. See [WhatsAppChatUnit].
     */
    fun noteIdFor(chatTitle: String, sourceName: String): String =
        WhatsAppChatUnit.idFor(chatTitle, sourceName)

    /**
     * The date a media name carries, as `yyyyMMdd`. WhatsApp writes the receive date into every
     * media filename, so it is the one field that survives a rename and can be lined up against
     * the file's own last-modified time. Empty when the name has no unambiguous date.
     */
    fun dayOf(fileName: String): String {
        val match = DAY.find(fileName.replace('_', '-')) ?: return ""
        val year = match.groupValues[1]
        val month = match.groupValues[2]
        val day = match.groupValues[3]
        if (month.toIntOrNull() !in 1..12 || day.toIntOrNull() !in 1..31) return ""
        return "$year$month$day"
    }

    /** Seconds since midnight from a name like "…at 10.23.45…", or -1. */
    fun timeOf(fileName: String): Int {
        val match = TIME.find(fileName) ?: return -1
        val hour = match.groupValues[1].toIntOrNull() ?: return -1
        val minute = match.groupValues[2].toIntOrNull() ?: return -1
        val second = match.groupValues[3].toIntOrNull() ?: 0
        if (hour > 23 || minute > 59 || second > 59) return -1
        return hour * 3600 + minute * 60 + second
    }

    private val DAY = Regex("(\\d{4})-?(\\d{2})-?(\\d{2})")
    private val TIME = Regex("(\\d{2})[.:](\\d{2})[.:](\\d{2})")

    /**
     * The two names WhatsApp gives the *same* photo across versions — `IMG-20240815-WA0007.jpg`
     * in the transcript, `WhatsApp Image 2024-08-15 at 10.23.45.jpeg` on some devices — reduced
     * to one comparable key: the date, the sequence number and a type letter.
     */
    fun equivalenceKey(fileName: String): String {
        val lower = stripBidi(fileName).lowercase()
        val digits = lower.replace(Regex("[^a-z0-9]"), "")
        val waSequence = Regex("wa(\\d{4})").find(lower)?.groupValues?.getOrNull(1)
        val day = dayOf(fileName)
        val kind = when (kindOf(fileName)) {
            MediaKind.Image -> "img"
            MediaKind.Video -> "vid"
            MediaKind.Audio -> "aud"
            MediaKind.Document -> "doc"
        }
        val sequence = waSequence ?: Regex("(\\d{3,4})$").find(digits)?.groupValues?.getOrNull(1) ?: ""
        return if (day.isEmpty() && sequence.isEmpty()) {
            "$kind|${fileName.substringAfterLast('/').lowercase()}"
        } else {
            "$kind|$day|$sequence"
        }
    }
}
