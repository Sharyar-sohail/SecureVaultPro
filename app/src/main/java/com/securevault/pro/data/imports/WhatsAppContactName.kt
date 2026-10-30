package com.securevault.pro.data.imports

/**
 * Who a conversation belongs to, and how the owner finds it again.
 *
 * The owner's complaint this answers is "I imported four chats and the list shows me four files".
 * WhatsApp names an export after the chat, but only when the chat has a name to write: a group
 * exports as `…@g.us.txt`, a business thread as `business_chat-92345@c.us.txt`, and a handset with
 * contacts off keeps the running number — and the app's own re-export calls everything `chat.txt`.
 * Those titles are not how the owner thinks about the conversation. **The names inside the messages
 * are.** WhatsApp writes `Ali Raza: salaam` on every line even when the file name says nothing, so
 * the transcript itself names the participants, and [of] promotes the person who speaks to the title
 * of the chat.
 *
 * Pure JVM: the same rule decides the id, the list row and the icon, so a chat cannot be filed under
 * one name in the list and another in the vault.
 */
object WhatsAppContactName {

    /** Titles that name no one: WhatsApp's own fallbacks and anything the importer invented. */
    private val ANONYMOUS = setOf(
        "whatsapp chat", "whatsapp", "chat", "chat export", "whatsapp export", "whatsapp chats"
    )

    /** The word WhatsApp writes for the owner's own messages. Never the name of the other side. */
    private val SELF = setOf("you", "me", "my", "mine", "this device", "ya", "main")

    /** A running number turned into a label by [WhatsAppTranscript.titleFrom]. */
    private val NUMBER_TITLE = Regex("(?i)^chat\\s*·?\\s*\\+?[\\d\\s-]{6,}$")

    /** A bare JID or phone number that survived the title cleanup. */
    private val PHONE_ONLY = Regex("[+]?[\\d\\s-]{6,}")

    /**
     * The name to show for a conversation called [title] whose messages name [participants].
     *
     * A title that already names someone wins — WhatsApp's own name for the chat is what the owner
     * exported it as, and a group's name is in that title. Only a title that names nobody is
     * replaced by the loudest participant.
     */
    fun of(title: String, participants: List<String>): String {
        val clean = WhatsAppTranscript.stripBidi(title).trim()
        if (looksNamed(clean)) return clean
        val speaker = participants.firstOrNull { candidate ->
            val lower = WhatsAppChatUnit.normalizeTitle(candidate)
            lower.isNotBlank() && lower !in SELF && !PHONE_ONLY.matches(candidate.trim())
        }
        return speaker?.trim()?.takeIf { it.isNotBlank() } ?: clean.ifBlank { "WhatsApp chat" }
    }

    /** True when the title already tells the owner whose chat this is. */
    fun looksNamed(title: String): Boolean {
        val value = WhatsAppTranscript.stripBidi(title).trim()
        if (value.isBlank()) return false
        if (value.lowercase() in ANONYMOUS) return false
        if (NUMBER_TITLE.matches(value)) return false
        if (PHONE_ONLY.matches(value.replace(Regex("[A-Za-z]"), ""))) return false
        // A title that is only punctuation or a single letter says nothing either.
        return value.count { it.isLetterOrDigit() } >= 2
    }

    /** The section a chat sorts under: its first letter, or `#` when it does not start with one. */
    fun sectionOf(displayName: String): String {
        val first = WhatsAppTranscript.stripBidi(displayName).trim().firstOrNull { it.isLetterOrDigit() }
            ?: return HASH
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else HASH
    }

    const val HASH = "#"

    /**
     * Chats filed by contact, then by letter.
     *
     * Two exports of the same conversation are the same row already ([WhatsAppChatUnit] keys the id
     * on the name), so a contact with several chats in the vault — a personal thread and a business
     * thread that happen to share a name — still shows both, sorted by the newest import.
     */
    fun sections(chats: List<WhatsAppChatDraft>): List<WhatsAppContactSection> {
        if (chats.isEmpty()) return emptyList()
        val byContact = LinkedHashMap<String, MutableList<WhatsAppChatDraft>>()
        chats.forEach { chat ->
            val name = displayOf(chat).ifBlank { "WhatsApp chat" }
            byContact.getOrPut(name) { mutableListOf() }.add(chat)
        }
        val byLetter = LinkedHashMap<String, MutableList<WhatsAppContactGroup>>()
        byContact.entries
            .sortedWith(compareBy({ sectionOf(it.key) }, { it.key.lowercase() }))
            .forEach { (name, group) ->
                val ordered = group.sortedByDescending { it.lastImportedAt.takeIf { at -> at > 0L } ?: it.updatedAt }
                byLetter.getOrPut(sectionOf(name)) { mutableListOf() }
                    .add(WhatsAppContactGroup(name, ordered))
            }
        // `#` is where the numbers and the unnamed threads land; it belongs at the end, not between
        // two letters, and a section with nothing in it is never shown.
        val letters = byLetter.keys.filter { it != HASH }.sorted()
        val hash = byLetter[HASH]
        return letters.map { WhatsAppContactSection(it, byLetter.getValue(it).sortedBy { g -> g.contactName.lowercase() }) } +
            listOfNotNull(hash?.let { WhatsAppContactSection(HASH, it.sortedBy { g -> g.contactName.lowercase() }) })
    }

    /** The name a stored chat is listed under. Falls back to the title for rows written before this. */
    fun displayOf(chat: WhatsAppChatDraft): String =
        chat.contactName.ifBlank { of(chat.title, chat.participants) }
}

/** One contact's chats, as one block of the list. */
data class WhatsAppContactGroup(val contactName: String, val chats: List<WhatsAppChatDraft>) {
    /** The newest conversation of this contact — what the row previews. */
    val newest: WhatsAppChatDraft get() = chats.first()
    val messages: Int get() = chats.sumOf { it.messageCount }
    val media: Int get() = chats.sumOf { it.mediaCount }

    /** The icon the group is drawn with: the newest chat's own glyph, so a group of one looks like one. */
    val glyphChatId: String get() = newest.chatId
}

/** Contacts that share a first letter. */
data class WhatsAppContactSection(val letter: String, val groups: List<WhatsAppContactGroup>) {
    val chats: Int get() = groups.sumOf { it.chats.size }
}
