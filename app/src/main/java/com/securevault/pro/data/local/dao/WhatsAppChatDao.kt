package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.WhatsAppChatEntity
import com.securevault.pro.data.local.entity.WhatsAppChatMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * The rows behind "my imported WhatsApp chats".
 *
 * Three rules shape this interface, and each of them exists because of a defect that shipped
 * without it:
 *
 *  - **A half-imported chat is never listed.** Every read filter excludes `IMPORTING`, so a run the
 *    owner stopped or the process lost leaves nothing that looks like a conversation with two
 *    messages in it. The rows it did write stay put as `INTERRUPTED` and are the resume point.
 *  - **A retry cannot duplicate a bubble.** Messages are written with `REPLACE` against the
 *    (`chatId`, `ordinal`) primary key, and the ordinal is a deterministic position in the
 *    transcript, so re-running an import over the same file rewrites the same rows.
 *  - **A batch is one transaction.** [insertMessages] is annotated [Transaction] as well as
 *    [Insert]: the whole batch lands or none of it does, which is what makes the ordinal watermark
 *    on the chat row trustworthy.
 */
@Dao
interface WhatsAppChatDao {

    // ── conversations ───────────────────────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM whatsapp_chats WHERE importState <> 'IMPORTING' " +
            "ORDER BY contactName COLLATE NOCASE ASC, lastImportedAt DESC"
    )
    fun observeChats(): Flow<List<WhatsAppChatEntity>>

    @Query(
        "SELECT * FROM whatsapp_chats WHERE importState <> 'IMPORTING' " +
            "ORDER BY contactName COLLATE NOCASE ASC, lastImportedAt DESC"
    )
    suspend fun chats(): List<WhatsAppChatEntity>

    @Query("SELECT * FROM whatsapp_chats WHERE chatId = :chatId LIMIT 1")
    suspend fun chat(chatId: String): WhatsAppChatEntity?

    @Upsert
    suspend fun upsertChat(chat: WhatsAppChatEntity)

    /** A chat with no rows under it: the conversation and its bubbles leave together (FK cascade). */
    @Query("DELETE FROM whatsapp_chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    /**
     * Bubbles-only reset, used when a re-import of the same chat gets a transcript that no longer
     * matches what is stored. The chat row survives, so the conversation keeps its id, its icon and
     * its media container instead of forking a second copy of itself.
     */
    @Query("DELETE FROM whatsapp_messages WHERE chatId = :chatId")
    suspend fun clearMessages(chatId: String)

    // ── bubbles ─────────────────────────────────────────────────────────────────────────────

    /** One batch of the conversation, in one transaction, replacing any row already at that ordinal. */
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<WhatsAppChatMessageEntity>)

    /**
     * The conversation as the viewer wants it: in order, one page at a time, so opening a 300 000
     * message chat reads the first page instead of decrypting the whole thing.
     */
    @Query(
        "SELECT * FROM whatsapp_messages WHERE chatId = :chatId " +
            "ORDER BY ordinal ASC LIMIT :limit OFFSET :offset"
        )
    suspend fun messages(chatId: String, limit: Int, offset: Int): List<WhatsAppChatMessageEntity>

    @Query("SELECT * FROM whatsapp_messages WHERE chatId = :chatId ORDER BY ordinal ASC")
    suspend fun allMessages(chatId: String): List<WhatsAppChatMessageEntity>

    @Query("SELECT COUNT(*) FROM whatsapp_messages WHERE chatId = :chatId")
    suspend fun messageCount(chatId: String): Int

    /** The highest committed position plus one — where a resumed import starts numbering again. */
    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM whatsapp_messages WHERE chatId = :chatId")
    suspend fun nextOrdinal(chatId: String): Int

    /**
     * Everything at or after [from]. A cancelled run calls this with the watermark it started from,
     * so the rows it wrote before the stop are dropped and the chat is exactly as consistent as it
     * was before the import began.
     */
    @Query("DELETE FROM whatsapp_messages WHERE chatId = :chatId AND ordinal >= :from")
    suspend fun truncateFrom(chatId: String, from: Int)

    /** Used by the delete path: the container the chat's media lives in, before the row goes. */
    @Query("SELECT container FROM whatsapp_chats WHERE chatId = :chatId LIMIT 1")
    suspend fun containerOf(chatId: String): String?
}
