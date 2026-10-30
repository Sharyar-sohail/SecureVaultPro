package com.securevault.pro.data.imports

import java.security.MessageDigest
import java.util.UUID

/**
 * One conversation = one vault unit.
 *
 * The owner's complaint about the WhatsApp clone was that every chat shared the same address: the
 * media of two conversations could be filed under one note id, so opening "Ali" could show part of
 * "Family". This object is the single place that decides *who a chat is* and *where its files
 * live*, and it is pure JVM so the rule is pinned by a test instead of by a handset.
 *
 * Two rules, both deliberate:
 *  - the id is keyed on the conversation **name** WhatsApp exported, not on the transcript bytes,
 *    because a re-export adds messages to the end and must update the same unit rather than fork a
 *    second copy of the chat;
 *  - when the name says nothing (a file called `chat.txt`, `_WAChatSource.txt`, a bare running
 *    number) the *source file* breaks the tie instead, so two nameless exports from two different
 *    files still never share a container.
 */
object WhatsAppChatUnit {

    /** The folder inside the phone vault that holds every chat's own media container. */
    const val CONTAINER_ROOT = "chats"

    /** Titles that identify no conversation at all — WhatsApp's own fallbacks and empty names. */
    private val GENERIC = setOf("", "whatsapp chat", "whatsapp", "chat", "chat export", "whatsapp export")

    /** Characters that make a segment unsafe as a path token. */
    private val SAFE_SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** FNV-1a 32-bit parameters, mirrored verbatim by the web companion's `chatIconSlot`. */
    private const val FNV_OFFSET = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193

    /**
     * The stable id of the conversation. Same chat, any export path (picker, share inbox, folder
     * hub) → same id; different chats → different ids.
     */
    fun idFor(chatTitle: String, sourceName: String = ""): String {
        val normalized = normalizeTitle(chatTitle)
        val key = if (normalized in GENERIC) {
            "wa-chat|anon|$normalized|${digest(sourceName)}"
        } else {
            "wa-chat|$normalized"
        }
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }

    /**
     * The vault-relative directory that belongs to this chat alone. Every media file the
     * conversation carries is sealed inside it, so no two chats ever share one folder path, and
     * deleting a conversation's files can never touch another's.
     */
    fun containerOf(chatId: String): String = "$CONTAINER_ROOT/${safeSegment(chatId)}"

    /**
     * The chat a container path belongs to, or null when the path is not one of this module's
     * directories. The id round-trips through [containerOf] because a UUID is already a safe
     * segment; a path that went through the hashing fallback cannot name its chat and returns
     * null rather than a guess.
     */
    fun idFromContainer(path: String?): String? {
        if (path.isNullOrBlank() || !path.startsWith("$CONTAINER_ROOT/")) return null
        val segment = path.removePrefix("$CONTAINER_ROOT/").substringBefore('/')
        return segment.takeIf { it.matches(SAFE_SEGMENT) }
    }

    /**
     * The slot of the themed icon board this chat is drawn with. Chats take turns over the board so
     * each module gets its own glyph; the choice is a pure function of the id, so the icon a chat
     * wore yesterday is the icon it wears after a re-export.
     *
     * FNV-1a over 32-bit wrapping integers rather than a digest, on purpose: the web companion has
     * to pick the same slot for the same chat and JavaScript has no synchronous SHA-256. One
     * multiply and one xor, written the same way in both places, is what keeps a phone and a
     * browser showing one conversation the same mark. The vectors in [ChatGlyphBoard]'s test pin it.
     */
    fun iconSlot(chatId: String, slots: Int): Int {
        if (slots <= 0) return 0
        var hash = FNV_OFFSET
        for (char in chatId) {
            hash = hash xor char.code
            hash *= FNV_PRIME
        }
        return ((hash.toLong() and 0xFFFFFFFFL) % slots.toLong()).toInt()
    }

    /** A path segment that can never escape the container root, whatever a caller passed as id. */
    private fun safeSegment(chatId: String): String {
        val trimmed = chatId.trim()
        return if (trimmed.matches(SAFE_SEGMENT)) trimmed else digest(trimmed).take(32)
    }

    /** The chat name in the shape the id is keyed on: case- and punctuation-insensitive. */
    fun normalizeTitle(title: String): String = WhatsAppTranscript.stripBidi(title)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** True when two names mean the same conversation to the owner. */
    fun isSameChat(first: String, second: String): Boolean =
        normalizeTitle(first) == normalizeTitle(second)

    private fun digest(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(value.hashCode().toString(16))
}
