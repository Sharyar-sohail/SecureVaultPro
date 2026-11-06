package com.securevault.pro.data.email

/**
 * Turns tokenised responses into [ImapEnvelope], [ImapBodyStructure] and [ImapFetchItem] values.
 *
 * Every function here takes tokens, never bytes and never a socket, which is what makes the whole
 * parse layer testable on the JVM. The rules followed are RFC 3501 section 7.4.2 (ENVELOPE),
 * 7.4.6 (BODYSTRUCTURE) and 7.6.2 (FETCH).
 */
object ImapResponseParser {

    /** `* 4 FETCH (UID 4 FLAGS (\Seen) ENVELOPE (…) BODYSTRUCTURE (…) BODY[TEXT] {120}…)`. */
    fun fetch(reply: ImapReply): ImapFetchItem? {
        val tokens = ImapTokenizer.tokenize(reply)
        return fetchFromTokens(tokens)
    }

    /** Same, from an already tokenised list — the entry point the tests use. */
    fun fetchFromTokens(tokens: List<ImapToken>): ImapFetchItem? {
        // An untagged FETCH begins with the sequence number and the literal `FETCH`; the item list
        // is the parenthesised group that follows.
        var head = 0
        if (tokens.getOrNull(head)?.asLongOrNull() != null) head++
        if (tokens.getOrNull(head)?.text()?.equals("FETCH", true) == true) head++
        val items = tokens.getOrNull(head)?.asListTokens() ?: return null
        var uid = 0L
        var sequence = tokens.getOrNull(0)?.asIntOrNull() ?: 0
        var flags: List<String> = emptyList()
        var internalDate = 0L
        var size = 0L
        var envelope: ImapEnvelope? = null
        var structure: ImapBodyStructure? = null
        val sections = LinkedHashMap<String, ByteArray>()
        val streamed = LinkedHashMap<String, Long>()
        val streamedPaths = LinkedHashMap<String, String>()
        var cursor = 0
        while (cursor < items.size) {
            val name = items[cursor].text().uppercase()
            // Every FETCH item is exactly one name followed by one value token, so the pair step
            // is unconditional; a truncated item simply reads a null value and is ignored.
            val value = items.getOrNull(cursor + 1)
            cursor += 2
            when {
                name == "UID" -> uid = value.asLongOrNull() ?: uid
                name == "SEQ" || name == "SEQNO" -> sequence = value.asIntOrNull() ?: sequence
                name == "FLAGS" -> flags = value.asListTokens().map { it.text() }
                name == "INTERNALDATE" -> internalDate = ImapDateFormat.parse(value.text())
                name == "RFC822.SIZE" -> size = value.asLongOrNull() ?: size
                name == "ENVELOPE" -> envelope = envelope(value)
                name == "BODYSTRUCTURE" -> structure = bodyStructure(value, "1")
                name.startsWith("BODY") || name.startsWith("BINARY") -> {
                    val section = sectionKeyOf(name)
                    when (value) {
                        is ImapToken.LiteralBytes -> sections[section] = value.bytes
                        is ImapToken.Quoted -> sections[section] = value.text().toByteArray(Charsets.UTF_8)
                        is ImapToken.StreamedLiteral -> {
                            streamed[section] = value.size
                            streamedPaths[section] = value.path
                        }
                        // A section the server answered with NIL or with nothing at all is empty.
                        else -> sections[section] = ByteArray(0)
                    }
                }
                else -> {
                    // `MODSEQ`, `X-GM-MSGID` and friends: read past the pair, keep the rest.
                }
            }
        }
        return ImapFetchItem(
            uid = uid,
            sequence = sequence,
            flags = flags,
            internalDate = internalDate,
            rfc822Size = if (size > 0L) size else sections.values.sumOf { it.size.toLong() },
            envelope = envelope,
            bodyStructure = structure,
            sections = sections,
            streamedSections = streamed,
            streamedPaths = streamedPaths
        )
    }

    /** `BODY[HEADER.FIELDS  (FROM SUBJECT)]` → one stable key, inner whitespace normalised. */
    private fun sectionKeyOf(upperItemName: String): String =
        upperItemName.replace(Regex("\\s+"), " ").trim()

    /** The ten ENVELOPE fields, in order, with NIL everywhere the server sent NIL. */
    fun envelope(token: ImapToken?): ImapEnvelope? {
        val items = token?.asListTokens() ?: return null
        if (items.isEmpty()) return null
        fun textAt(i: Int): String = items.getOrNull(i).let { if (it.isNil()) "" else it.text() }
        fun addressesAt(i: Int): List<ImapAddress> = addressList(items.getOrNull(i))
        return ImapEnvelope(
            sentAt = textAt(0),
            subject = textAt(1),
            from = addressesAt(2),
            sender = addressesAt(3),
            replyTo = addressesAt(4),
            to = addressesAt(5),
            cc = addressesAt(6),
            bcc = addressesAt(7),
            inReplyTo = textAt(8),
            messageId = textAt(9)
        )
    }

    /**
     * An ENVELOPE address list. RFC 9051 §7.4.2 fixes the field order as
     * `(host-name route mailbox-name personal-name)`, so index 0 is the domain and index 3 is the
     * display name; index 1 (`route`) is legacy source-routing that no current server fills and is
     * read only to keep the positions honest.
     *
     * Reading the positions in any other order loses the domain, and every sender in the inbox shows
     * as a bare mailbox name that cannot be replied to.
     */
    fun addressList(token: ImapToken?): List<ImapAddress> {
        val group = token?.asListTokens() ?: return emptyList()
        return group.mapNotNull { entry ->
            val fields = entry.asListTokens()
            if (fields.isEmpty() && entry !is ImapToken.ListTok) return@mapNotNull null
            // A group marker — `(NIL NIL "undisclosed-recipients" NIL)` — names nobody, so skip it.
            val mailbox = if (fields.getOrNull(2).isNil()) "" else fields.getOrNull(2).text().trim()
            val host = if (fields.getOrNull(0).isNil()) "" else fields.getOrNull(0).text().trim()
            if (mailbox.isEmpty() && host.isEmpty()) return@mapNotNull null
            val personal = if (fields.getOrNull(3).isNil()) "" else fields.getOrNull(3).text()
            ImapAddress(
                host = host,
                mailbox = mailbox,
                personalName = personal,
                displayName = personal
            )
        }
    }

    /**
     * Recursive BODYSTRUCTURE. A node that opens with a list is a multipart whose last list member
     * is the subtype; otherwise it is a leaf of one to six fields plus extensions.
     */
    fun bodyStructure(token: ImapToken?, section: String): ImapBodyStructure? {
        val items = token?.asListTokens() ?: return null
        if (items.isEmpty()) return null
        if (items.first().isList()) {
            val parts = items.takeWhile { it.isList() }
                .mapIndexed { index, child -> bodyStructure(child, "$section.${index + 1}") ?: fallthrough(child, "$section.${index + 1}") }
            val subtypeToken = items.getOrNull(parts.size)
            return ImapBodyStructure(
                section = section,
                isMultiPart = true,
                mediaType = "MULTIPART",
                mediaSubtype = subtypeToken.text().ifBlank { "MIXED" },
                parts = parts,
                parameters = paramsOf(items.getOrNull(parts.size + 1))
            )
        }
        val mediaType = items.getOrNull(0).text().uppercase()
        val subtype = items.getOrNull(1).text().uppercase()
        val params = paramsOf(items.getOrNull(2))
        val contentId = if (items.getOrNull(3).isNil()) "" else items.getOrNull(3).text()
        val description = if (items.getOrNull(4).isNil()) "" else items.getOrNull(4).text()
        val encoding = if (items.getOrNull(5).isNil()) "7BIT" else items.getOrNull(5).text().uppercase()
        val octets = items.getOrNull(6).asLongOrNull() ?: 0L
        var index = 7
        var textLines: Long = 0
        if (mediaType == "TEXT" && items.getOrNull(index)?.asLongOrNull() != null) {
            textLines = items[index].asLongOrNull() ?: 0L
            index++
        }
        var envelope: ImapEnvelope? = null
        var nested: ImapBodyStructure? = null
        if (mediaType == "MESSAGE" && subtype == "RFC822") {
            envelope = envelope(items.getOrNull(index))
            if (envelope != null) index++
            nested = bodyStructure(items.getOrNull(index), "$section.1")
            if (nested != null) index++
        } else if (mediaType == "MULTIPART") {
            return ImapBodyStructure(
                section = section,
                isMultiPart = true,
                mediaType = "MULTIPART",
                mediaSubtype = subtype.ifBlank { "MIXED" },
                parameters = params
            )
        }
        var dispositionType = ""
        var dispositionParams: Map<String, String> = emptyMap()
        items.getOrNull(index)?.let { holder ->
            val fields = holder.asListTokens()
            if (fields.isNotEmpty()) {
                dispositionType = fields.getOrNull(0).text()
                dispositionParams = paramsOf(fields.getOrNull(1))
            }
        }
        index++
        val language = if (items.getOrNull(index).isNil()) "" else items.getOrNull(index).text()
        index++
        val location = if (items.getOrNull(index).isNil()) "" else items.getOrNull(index).text()
        return ImapBodyStructure(
            section = section,
            isMultiPart = false,
            mediaType = mediaType,
            mediaSubtype = subtype,
            parameters = params + if (textLines > 0L) mapOf("lines" to textLines.toString()) else emptyMap(),
            contentId = contentId,
            description = description,
            encoding = encoding,
            octetSize = octets,
            dispositionType = dispositionType,
            dispositionParameters = dispositionParams,
            language = language,
            location = location,
            envelope = envelope,
            bodyStructure = nested
        )
    }

    private fun fallthrough(token: ImapToken, section: String): ImapBodyStructure = ImapBodyStructure(
        section = section,
        isMultiPart = false,
        encoding = "7BIT"
    ).let { if (token is ImapToken.ListTok) it.copy(parts = token.items.mapIndexed { i, c -> bodyStructure(c, "$section.${i + 1}") ?: it }) else it }

    /** A `(key value key value …)` parameter list flattened to a map, keys lower-cased. */
    fun paramsOf(token: ImapToken?): Map<String, String> {
        val items = token?.asListTokens() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < items.size) {
            out[items[i].text().lowercase()] = items[i + 1].text()
            i += 2
        }
        return out
    }

    /** `* LIST (\HasNoChildren) "/" "INBOX"` → the wire name, the separator and the flags. */
    fun list(reply: ImapReply): ImapFolderListing? {
        val tokens = ImapTokenizer.tokenize(reply)
        if (tokens.size < 4 || tokens[0].text().uppercase() != "LIST") return null
        val flags = tokens[1].asListTokens().map { it.text() }
        val separator = if (tokens[2].isNil()) "" else tokens[2].text()
        val name = tokens[3].text()
        return ImapFolderListing(flags = flags, separator = separator, wireName = name)
    }

    /** `* LSUB …` answers with the same shape and a possibly different hierarchy. */
    fun lsub(reply: ImapReply): ImapFolderListing? = list(reply)

    /** A decoded `* N EXISTS` / `* N RECENT` pair. */
    fun mailboxSize(reply: ImapReply): Pair<Int, Int>? {
        val tokens = ImapTokenizer.tokenize(reply)
        if (tokens.size < 2) return null
        val count = tokens[0].asIntOrNull() ?: return null
        return when (tokens[1].text().uppercase()) {
            "EXISTS" -> count to -1
            "RECENT" -> -1 to count
            else -> null
        }
    }

    /** `UID SEARCH` / `SEARCH` answer: the sequence or UID set the server matched. */
    fun search(reply: ImapReply): List<Long> =
        ImapTokenizer.tokenize(reply)
            .dropWhile { it.text().uppercase() == "SEARCH" }
            .mapNotNull { it.asLongOrNull() }

    /** `* CAPABILITY IMAP4rev1 …` */
    fun capability(reply: ImapReply): Set<String> =
        ImapTokenizer.tokenize(reply)
            .dropWhile { it.text().uppercase() == "CAPABILITY" }
            .map { it.text() }
            .toSet()

    /**
     * The status a SELECT/EXAMINE answer carries: the untagged `* 12 EXISTS` / `* 3 RECENT` lines and
     * the tagged `OK [UIDVALIDITY 3857529045] [UIDNEXT 4392]`.
     *
     * Two shapes have to be read, because the protocol uses both. A status line puts the number
     * *before* its word (`* 12 EXISTS`), while bracketed response data puts it *after*
     * (`[UIDNEXT 4392]`). A forward-only scan finds no number in front of `EXISTS`, which made every
     * folder report zero messages and hid the whole inbox.
     */
    fun selectStatus(reply: ImapReply): MailboxStatus {
        val tokens = ImapTokenizer.tokenize(reply)
        var exists = 0
        var recent = 0
        var unseen = 0
        var uidValidity = 0L
        var uidNext = 0L
        for (index in tokens.indices) {
            val raw = tokens[index].text().trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("[")) {
                // `[UIDVALIDITY 3857529045]`, `[UNSEEN 4]`: label first, number second.
                val body = raw.removePrefix("[").removeSuffix("]").trim()
                val label = body.substringBefore(' ').uppercase()
                val value = body.substringAfter(' ', "").trim().toLongOrNull()
                if (value == null) continue
                when (label) {
                    "UIDVALIDITY" -> uidValidity = value
                    "UIDNEXT" -> uidNext = value
                    "UNSEEN" -> unseen = value.toInt()
                    "EXISTS" -> exists = value.toInt()
                    "RECENT" -> recent = value.toInt()
                }
                continue
            }
            val label = raw.uppercase()
            if (label !in STATUS_WORDS) continue
            val value = tokens.getOrNull(index - 1).asLongOrNull()
                ?: tokens.getOrNull(index + 1).asLongOrNull()
                ?: continue
            when (label) {
                "EXISTS" -> exists = value.toInt()
                "RECENT" -> recent = value.toInt()
                "UNSEEN" -> unseen = value.toInt()
                "UIDVALIDITY" -> uidValidity = value
                "UIDNEXT" -> uidNext = value
            }
        }
        return MailboxStatus(exists, recent, unseen, uidValidity, uidNext)
    }

    private val STATUS_WORDS = setOf("EXISTS", "RECENT", "UNSEEN", "UIDVALIDITY", "UIDNEXT")

    /** Flags a mailbox advertises: `* FLAGS (\Answered \Flagged \Deleted \Seen \Draft)`. */
    fun flags(reply: ImapReply): List<String> =
        ImapTokenizer.tokenize(reply)
            .dropWhile { it.text().uppercase() != "FLAGS" }
            .drop(1)
            .firstOrNull()
            ?.asListTokens()
            ?.map { it.text() }
            ?: emptyList()
}

/** One row of a LIST answer, before it is joined with the account's own naming rules. */
data class ImapFolderListing(
    val flags: List<String>,
    val separator: String,
    val wireName: String
)

/**
 * The counts a SELECT/EXAMINE reported. [uidValidity] changes only when the server has thrown the
 * folder's UIDs away and renumbered them, so it is the one number that decides whether any UID held
 * on to a message still means the same message. [uidNext] is the UID the *next* arriving mail will
 * carry: the anchor a client without a local cache uses to fetch only what it has never seen.
 */
data class MailboxStatus(
    val exists: Int,
    val recent: Int,
    val unseen: Int,
    val uidValidity: Long,
    val uidNext: Long = 0L
)
