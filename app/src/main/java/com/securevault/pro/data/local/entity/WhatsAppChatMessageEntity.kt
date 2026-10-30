package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One bubble of an imported WhatsApp conversation.
 *
 * `chatId + ordinal` is the primary key on purpose. The ordinal is the message's position in the
 * transcript, counted from zero, and the parse that produces it is deterministic — the same bytes
 * always number the same bubble the same way. That is what makes a retry after a cancellation
 * idempotent: re-writing ordinal 4 213 replaces the row instead of adding a second copy of the
 * message, so "resume" and "start again" can never leave duplicate bubbles.
 *
 * [sender], [body] and [attachmentsJson] hold sealed text, never plaintext — the vault encrypts
 * what somebody said at rest, the same way `secure_notes.content` is sealed. [day] and [clock] stay
 * plain because the viewer groups bubbles under a date header and the rows are read in ordinal
 * order; a timestamp is not the conversation.
 *
 * The child column of the foreign key needs its own index: Room does not create one, and without it
 * deleting a chat makes SQLite scan the whole message table for the cascade.
 */
@Entity(
    tableName = "whatsapp_messages",
    primaryKeys = ["chatId", "ordinal"],
    indices = [Index("chatId")],
    foreignKeys = [
        ForeignKey(
            entity = WhatsAppChatEntity::class,
            parentColumns = ["chatId"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WhatsAppChatMessageEntity(
    /** The conversation this bubble belongs to — [WhatsAppChatEntity.chatId]. */
    val chatId: String,
    /** Position in the transcript from zero; part of the primary key so a retry cannot duplicate. */
    val ordinal: Int,
    /** The date exactly as WhatsApp wrote it (`15/08/2024`, `Aug 15, 2024`, …). */
    val day: String = "",
    /** The clock exactly as WhatsApp wrote it (`10:23`, `10:23:45`, `10:24:11 AM`), or empty. */
    val clock: String = "",
    /** Sealed speaker name; empty for a system line with no sender. */
    val sender: String = "",
    /** Sealed message text with WhatsApp's attachment markers taken out. */
    val body: String = "",
    /** WhatsApp's own line about the chat — "Missed voice call", the encryption notice. */
    val notice: Boolean = false,
    /** WhatsApp's "this message was deleted" family, which is a notice with its own wording. */
    val deleted: Boolean = false,
    /** Sealed JSON array of the file names this bubble references, in the order WhatsApp wrote them. */
    val attachmentsJson: String = "[]",
    /** [attachmentsJson].size as a plain column so the list can show a media count without unsealing. */
    val mediaCount: Int = 0
)
