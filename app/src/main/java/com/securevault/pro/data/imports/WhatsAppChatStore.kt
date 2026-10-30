package com.securevault.pro.data.imports

import com.securevault.pro.data.local.dao.WhatsAppChatDao
import com.securevault.pro.data.local.entity.WhatsAppChatEntity
import com.securevault.pro.data.local.entity.WhatsAppChatMessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room half of the streaming importer: batches of bubbles become rows, sealed on the way in.
 *
 * Three rules live here, and each one is the reason a bug could not happen:
 *
 *  - **The chat row is written before the first bubble.** A run the process kills halfway leaves a
 *    row in `IMPORTING`, which every read filter hides, so the list never shows a conversation that
 *    is missing its middle.
 *  - **A changed transcript starts over; an unchanged one resumes.** The SHA-256 of the transcript
 *    is the resume key: identical bytes mean the rows already written are this conversation, so the
 *    run starts after them; different bytes mean the old rows belong to another export and are
 *    cleared before the new ones land.
 *  - **Every field somebody said is sealed.** Sender, body, attachment names and the participant
 *    list go through [ChatFieldCipher] before they reach a column; the title, the contact name and
 *    the timestamps stay plain so the list can sort and group without unsealing a single bubble.
 */
@Singleton
class WhatsAppChatStore @Inject constructor(
    private val dao: WhatsAppChatDao,
    private val cipher: ChatFieldCipher
) : WhatsAppChatSink {

    override suspend fun prepare(draft: WhatsAppChatDraft): WhatsAppResumePoint {
        val existing = dao.chat(draft.chatId)
        val sameTranscript = existing != null &&
            existing.transcriptSha.isNotBlank() &&
            draft.transcriptSha.isNotBlank() &&
            existing.transcriptSha == draft.transcriptSha
        val isFresh = !sameTranscript
        if (isFresh && existing != null) {
            // The stored bubbles belong to a different export of this chat. Keeping them would
            // interleave two transcripts under one id, so they leave before the new ones arrive.
            dao.clearMessages(draft.chatId)
        }
        val startOrdinal = if (isFresh) 0 else dao.nextOrdinal(draft.chatId)
        dao.upsertChat(
            WhatsAppChatEntity(
                chatId = draft.chatId,
                title = draft.title,
                contactName = draft.contactName,
                sourceName = draft.sourceName,
                container = draft.container,
                participantsJson = cipher.seal(WhatsAppStringListJson.encode(draft.participants)),
                // Nothing is counted until the run commits: a failed import must not raise the
                // list's message total for a conversation it did not finish writing.
                messageCount = 0,
                mediaCount = 0,
                firstStamp = "",
                lastStamp = "",
                transcriptSha = draft.transcriptSha,
                noteId = draft.noteId,
                importState = WhatsAppChatEntity.IMPORT_STATE_IMPORTING,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: draft.createdAt,
                updatedAt = draft.updatedAt,
                lastImportedAt = existing?.lastImportedAt ?: 0L,
                mirroredToNote = existing?.mirroredToNote ?: draft.mirroredToNote
            )
        )
        return WhatsAppResumePoint(
            startOrdinal = startOrdinal,
            isFresh = isFresh,
            existedBefore = existing != null
        )
    }

    override suspend fun append(chatId: String, batch: List<WhatsAppMessageDraft>) {
        if (batch.isEmpty()) return
        dao.insertMessages(
            batch.map { draft ->
                WhatsAppChatMessageEntity(
                    chatId = chatId,
                    ordinal = draft.ordinal,
                    day = draft.day,
                    clock = draft.clock,
                    sender = sealText(draft.sender),
                    body = sealText(draft.body),
                    notice = draft.notice,
                    deleted = draft.deleted,
                    attachmentsJson = sealList(draft.attachments),
                    mediaCount = draft.attachments.size
                )
            }
        )
    }

    override suspend fun finish(commit: WhatsAppChatCommit, mirror: WhatsAppChatDraft) {
        val existing = dao.chat(commit.chatId)
        dao.upsertChat(
            WhatsAppChatEntity(
                chatId = commit.chatId,
                title = mirror.title,
                contactName = mirror.contactName,
                sourceName = mirror.sourceName,
                container = mirror.container,
                participantsJson = cipher.seal(WhatsAppStringListJson.encode(commit.participants)),
                messageCount = commit.messages,
                mediaCount = commit.media,
                firstStamp = commit.firstStamp,
                lastStamp = commit.lastStamp,
                transcriptSha = mirror.transcriptSha,
                noteId = mirror.noteId,
                importState = WhatsAppChatEntity.IMPORT_STATE_READY,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: mirror.createdAt,
                updatedAt = mirror.updatedAt,
                lastImportedAt = mirror.lastImportedAt,
                mirroredToNote = mirror.mirroredToNote
            )
        )
    }

    override suspend fun abandon(chatId: String, fromOrdinal: Int, existedBefore: Boolean) {
        dao.truncateFrom(chatId, fromOrdinal)
        if (!existedBefore) {
            // The row itself was created by this very run, so it is not a conversation the owner
            // ever had: it leaves, and the list never learns it existed.
            dao.deleteChat(chatId)
            return
        }
        val current = dao.chat(chatId) ?: return
        val kept = dao.messageCount(chatId)
        dao.upsertChat(
            current.copy(
                messageCount = kept,
                importState = WhatsAppChatEntity.IMPORT_STATE_INTERRUPTED,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Blank stays blank — a GCM envelope around "" is noise, and an empty sender is meaningful. */
    private fun sealText(value: String): String = if (value.isBlank()) value else cipher.seal(value)

    private fun sealList(values: List<String>): String =
        if (values.isEmpty()) "[]" else cipher.seal(WhatsAppStringListJson.encode(values))
}

/** One conversation as the viewer reads it, already unsealed. */
data class StoredWhatsAppChat(
    val chatId: String,
    val title: String,
    val contactName: String,
    val participants: List<String>,
    val messageCount: Int,
    val mediaCount: Int,
    val firstStamp: String,
    val lastStamp: String,
    val updatedAt: Long
)

/** One bubble as the viewer reads it, already unsealed. */
data class StoredWhatsAppBubble(
    val ordinal: Int,
    val day: String,
    val clock: String,
    val sender: String,
    val body: String,
    val notice: Boolean,
    val deleted: Boolean,
    val attachments: List<String>
) {
    /** The parser's own model, so the existing chat surface draws a row-backed bubble the same way. */
    fun asMessage(): WhatsAppTranscript.ChatMessage = WhatsAppTranscript.ChatMessage(
        day = day,
        clock = clock,
        sender = sender.takeIf { it.isNotBlank() },
        body = body,
        files = attachments,
        notice = notice,
        deleted = deleted
    )
}

/**
 * The read side of the conversation tables: the list the Files screen shows and the bubbles the
 * viewer pages through. Unsealing happens here, off the main thread, one page at a time — a three
 * hundred thousand message chat opens its first page instead of decrypting all of itself.
 */
@Singleton
class WhatsAppChatReader @Inject constructor(
    private val dao: WhatsAppChatDao,
    private val cipher: ChatFieldCipher
) {

    private val unseal: (String) -> String = { sealed ->
        if (sealed.isBlank()) sealed else cipher.open(sealed)
    }

    fun observeChats(): Flow<List<StoredWhatsAppChat>> =
        dao.observeChats().map { rows -> rows.map(::chatOf) }

    suspend fun chats(): List<StoredWhatsAppChat> = dao.chats().map(::chatOf)

    suspend fun chat(chatId: String): StoredWhatsAppChat? {
        val row = dao.chat(chatId) ?: return null
        if (row.importState == WhatsAppChatEntity.IMPORT_STATE_IMPORTING) return null
        return chatOf(row)
    }

    suspend fun bubbles(chatId: String, limit: Int = PAGE_BUBBLES, offset: Int = 0): List<StoredWhatsAppBubble> =
        dao.messages(chatId, limit, offset).map(::bubbleOf)

    companion object {
        /** One screenful plus a little: the viewer loads this much, not the chat. */
        const val PAGE_BUBBLES = 300
    }

    private fun chatOf(row: WhatsAppChatEntity) = StoredWhatsAppChat(
        chatId = row.chatId,
        title = row.title,
        contactName = row.contactName,
        participants = WhatsAppStringListJson.decode(unseal(row.participantsJson)),
        messageCount = row.messageCount,
        mediaCount = row.mediaCount,
        firstStamp = row.firstStamp,
        lastStamp = row.lastStamp,
        updatedAt = row.updatedAt
    )

    private fun bubbleOf(row: WhatsAppChatMessageEntity) = StoredWhatsAppBubble(
        ordinal = row.ordinal,
        day = row.day,
        clock = row.clock,
        sender = unseal(row.sender),
        body = unseal(row.body),
        notice = row.notice,
        deleted = row.deleted,
        attachments = WhatsAppStringListJson.decode(unseal(row.attachmentsJson))
    )
}
