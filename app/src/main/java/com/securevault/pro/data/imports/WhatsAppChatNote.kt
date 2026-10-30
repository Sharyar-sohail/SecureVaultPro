package com.securevault.pro.data.imports

import com.securevault.pro.domain.model.NoteAttachment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The note a conversation is kept in, built away from Android.
 *
 * This was a private method of [WhatsAppImporter], which meant the one claim that matters most —
 * *what the vault writes is what a later export writes out and a re-import reads back* — could only
 * be tested on a handset. It lives here now, so the round trip is a JVM test on the production
 * bytes: build the note, cut the conversation back out of it, export it, read the export, compare.
 *
 * The header lines and the conversation are separated by [WhatsAppTranscript.CONVERSATION_MARKER]
 * and that seam is the contract: everything the vault adds (counts, the import date, the file list)
 * sits above it and is never counted as a message; everything below it is the transcript exactly as
 * WhatsApp wrote it, which is what makes the export open in any text editor and re-import clean.
 */
object WhatsAppChatNote {

    /** The tags every imported conversation carries — how the Notes module and the chat view find it. */
    val TAGS = listOf("WhatsApp", "Imported")

    /** The note colour the importer gives a chat. */
    const val COLOR = "MINT"

    /** The most attachment names ever listed in the header; the rest are in the file vault. */
    private const val HEADER_FILES = 10

    /** What the importer hands to the vault for one conversation. */
    data class Built(
        val id: String,
        val title: String,
        val content: String,
        val attachments: List<NoteAttachment>
    )

    /**
     * Builds the record of [title]. [container] is the chat's own media directory, so the note names
     * the module its files live in; [importedAt] is a parameter rather than a clock call so the
     * builder is deterministic under test.
     */
    fun build(
        chatId: String,
        title: String,
        transcript: String,
        messages: Int,
        mediaSaved: Int,
        attachments: List<NoteAttachment>,
        container: String = WhatsAppChatUnit.containerOf(chatId),
        importedAt: Long = System.currentTimeMillis()
    ): Built = Built(
        id = chatId,
        title = title.take(160),
        content = contentOf(
            title = title,
            transcript = transcript,
            messages = messages,
            mediaSaved = mediaSaved,
            attachments = attachments,
            container = container,
            importedAt = importedAt
        ),
        attachments = attachments
    )

    /** The header + marker + transcript, stripped of the bidi marks WhatsApp injects. */
    fun contentOf(
        title: String,
        transcript: String,
        messages: Int,
        mediaSaved: Int,
        attachments: List<NoteAttachment>,
        container: String,
        importedAt: Long
    ): String = buildString {
        appendLine("WhatsApp transcript · ${title.take(160)}")
        appendLine(
            "Imported ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(importedAt))} · " +
                "$messages messages · encrypted on device"
        )
        if (mediaSaved > 0) {
            appendLine("Attachments sealed in the File vault: $mediaSaved")
            appendLine("This chat keeps its own module: $container")
            attachments.take(HEADER_FILES).forEach { appendLine("• ${it.name}") }
            if (attachments.size > HEADER_FILES) {
                appendLine("• ${attachments.size - HEADER_FILES} more in the Files module")
            }
        }
        val clean = WhatsAppTranscript.stripBidi(transcript.replace("\r\n", "\n")).trim()
        if (clean.isNotBlank()) {
            appendLine()
            appendLine(WhatsAppTranscript.CONVERSATION_MARKER)
            append(clean)
        }
    }
}
