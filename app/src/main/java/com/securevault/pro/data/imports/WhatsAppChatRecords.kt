package com.securevault.pro.data.imports

/**
 * The rows an imported conversation is made of, kept away from Room and away from Android.
 *
 * The importer's job ends at these types: it parses a stream, decides which contact a chat belongs
 * to, and hands finished batches to a [WhatsAppChatSink]. Where the batches are stored — the vault's
 * Room database on a handset, a list in a test — is not the parser's business, and that separation
 * is the only reason the batching, the resume watermark and the rollback on a cancelled import can
 * be pinned by a JVM test instead of being discovered on an owner's phone.
 *
 * ## What is sealed before it reaches storage
 *
 * The vault's at-rest rule is the notes module's rule: a title is a plain column, everything
 * somebody *said* is encrypted (`secure_notes.content` goes through
 * [com.securevault.pro.security.CryptoManager] before it is written). The same split is kept here —
 * [WhatsAppChatDraft.title] and [WhatsAppChatDraft.contactName] are plain, while the sender, the
 * body and the attachment names of a [WhatsAppMessageDraft] are sealed by the [ChatFieldCipher] the
 * store supplies. [WhatsAppChatDraft.participants] is sealed too: it is made of the names in the
 * messages, and a list of who is in a chat is part of the chat.
 */

/** The seam to the device's key. Sealed so it can be replaced by a reversible fake under test. */
interface ChatFieldCipher {
    /** The ciphertext to store. Must never return its input for non-blank input. */
    fun seal(value: String): String

    /** The plaintext, or the stored value unchanged when it cannot be opened — never a crash. */
    fun open(sealedValue: String): String

    companion object {
        /** Stores whatever it is given, unencrypted. Only the test fakes and the decoy path use it. */
        val PLAIN = object : ChatFieldCipher {
            override fun seal(value: String): String = value
            override fun open(sealedValue: String): String = sealedValue
        }
    }
}

/**
 * A JSON array of strings, written and read without `org.json`.
 *
 * Android has `JSONArray`, plain JVM does not, and the codec that decides what the vault stores for
 * a message's attachments has to be the same code the test asserts on. It is ten lines of escaping
 * rather than a dependency.
 */
object WhatsAppStringListJson {

    fun encode(values: List<String>): String = buildString {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
        append(']')
    }

    /** Best effort: a value the vault cannot read decodes to an empty list rather than throwing. */
    fun decode(text: String): List<String> {
        val body = text.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (char != '"') {
                index++
                continue
            }
            index++
            current.setLength(0)
            while (index < body.length && body[index] != '"') {
                val value = body[index]
                if (value == '\\' && index + 1 < body.length) {
                    val next = body[index + 1]
                    current.append(
                        when (next) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = body.substring(index + 2, minOf(index + 6, body.length))
                                hex.toIntOrNull(16)?.toChar() ?: next
                            }
                            else -> next
                        }
                    )
                    index += if (next == 'u') 6 else 2
                } else {
                    current.append(value)
                    index++
                }
            }
            index++
            val value = current.toString()
            if (value.isNotBlank()) out += value
        }
        return out
    }
}

/** One conversation as the store keeps it. */
data class WhatsAppChatDraft(
    val chatId: String,
    val title: String,
    val contactName: String,
    val sourceName: String = "",
    val container: String = WhatsAppChatUnit.containerOf(chatId),
    val participants: List<String> = emptyList(),
    val messageCount: Int = 0,
    val mediaCount: Int = 0,
    val firstStamp: String = "",
    val lastStamp: String = "",
    val transcriptSha: String = "",
    val noteId: String = "",
    val importState: String = STATE_READY,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastImportedAt: Long = 0L,
    val mirroredToNote: Boolean = true
) {
    val isComplete: Boolean get() = importState == STATE_READY

    companion object {
        const val STATE_READY = "READY"
        const val STATE_IMPORTING = "IMPORTING"
        const val STATE_INTERRUPTED = "INTERRUPTED"
    }
}

/** One bubble of a conversation, before it is sealed and written. */
data class WhatsAppMessageDraft(
    val ordinal: Int,
    val day: String = "",
    val clock: String = "",
    val sender: String = "",
    val body: String = "",
    val notice: Boolean = false,
    val deleted: Boolean = false,
    val attachments: List<String> = emptyList()
)

/** Where a run starts writing, and whether anything already in the store has to be thrown away. */
data class WhatsAppResumePoint(
    /** The first ordinal this run owns. Rows below it are already committed and are left alone. */
    val startOrdinal: Int,
    /** True when the previous bubbles do not match this file, so they are cleared before writing. */
    val isFresh: Boolean,
    /** False when the conversation was not already in the store — a rollback deletes the row. */
    val existedBefore: Boolean
)

/** What the store answers after a run finishes, so the UI can report the truth rather than a hope. */
data class WhatsAppChatCommit(
    val chatId: String,
    val messages: Int,
    val media: Int,
    val participants: List<String>,
    val firstStamp: String,
    val lastStamp: String
)

/**
 * Everything the importer needs from storage. The Room implementation is
 * [WhatsAppChatStore]; a test implements it over a list.
 */
interface WhatsAppChatSink {

    /**
     * Decides the resume point for [draft] and marks the chat as being written.
     *
     * Called before the first byte of the conversation is parsed, so a run that dies halfway is a
     * run the store knows about — the chat row exists in [WhatsAppChatDraft.STATE_IMPORTING] and is
     * hidden from every list.
     */
    suspend fun prepare(draft: WhatsAppChatDraft): WhatsAppResumePoint

    /** One batch of bubbles, committed as one unit. */
    suspend fun append(chatId: String, batch: List<WhatsAppMessageDraft>)

    /** The conversation is whole: publish the counts and let the list show it. */
    suspend fun finish(commit: WhatsAppChatCommit, mirror: WhatsAppChatDraft)

    /**
     * The run was stopped or failed. [fromOrdinal] is the resume point the run started from: rows at
     * or above it belong to the abandoned attempt and are deleted. A chat that did not exist before
     * is deleted outright, so nothing half-written is left behind.
     */
    suspend fun abandon(chatId: String, fromOrdinal: Int, existedBefore: Boolean)
}
