package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported WhatsApp conversation, as a real row rather than as one big note blob.
 *
 * The chat used to exist only as a note whose body was the whole transcript, which meant "open the
 * chat" had to decrypt and re-parse megabytes of text before a single bubble could be drawn, and a
 * conversation too large for that path could not be imported at all. This table is the index of the
 * conversation: how big it is, who speaks in it, which container its media lives in, and how far an
 * interrupted import got.
 *
 * The at-rest rule matches the notes module exactly — [title] and [contactName] are plain columns
 * (they are what the list is ordered and grouped by, and `secure_notes.title` is already plain), and
 * everything somebody *said* lives in `whatsapp_messages`, where the body, the sender and the
 * attachment names are sealed before they reach the row.
 *
 * [chatId] is not invented here: it is [com.securevault.pro.data.imports.WhatsAppChatUnit.idFor],
 * the same id the note mirror and the `chats/<chatId>` media container already use, so a chat
 * imported by an older build resolves to the same row and the same folder.
 */
@Entity(tableName = "whatsapp_chats")
data class WhatsAppChatEntity(
    @PrimaryKey val chatId: String,
    /** What WhatsApp called the chat in the exported file name. */
    val title: String,
    /** The contact the owner recognises this chat by — the name the list is grouped under. */
    val contactName: String,
    /** The export file the row came from, kept so a re-import can tell the owner what changed. */
    val sourceName: String = "",
    /** The chat's own media container inside the file vault: `chats/<chatId>`. */
    val container: String,
    /** Sealed JSON list of the speakers the transcript itself names, most frequent first. */
    val participantsJson: String = "[]",
    /** Rows committed for this chat — the count the list shows and the resume watermark. */
    val messageCount: Int = 0,
    /** Media files of this chat sealed in [container]. */
    val mediaCount: Int = 0,
    val firstStamp: String = "",
    val lastStamp: String = "",
    /** SHA-256 of the transcript bytes of the last completed import; the resume key. */
    val transcriptSha: String = "",
    /** The legacy note mirror of this conversation — its id, which is the same as [chatId]. */
    val noteId: String = "",
    /** `READY`, `IMPORTING` or `INTERRUPTED`; only a READY chat is ever shown as complete. */
    val importState: String = IMPORT_STATE_READY,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastImportedAt: Long = 0L,
    /** False when the transcript was too large to mirror into a note; the rows are the only copy. */
    val mirroredToNote: Boolean = true
) {
    companion object {
        const val IMPORT_STATE_READY = "READY"
        const val IMPORT_STATE_IMPORTING = "IMPORTING"
        const val IMPORT_STATE_INTERRUPTED = "INTERRUPTED"
    }
}
