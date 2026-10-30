package com.securevault.pro.data.imports

import android.content.Context
import android.net.Uri
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault's side of an export: it takes the note a chat was sealed into, decrypts its media one
 * file at a time, and leaves a normal WhatsApp-shaped file in scratch storage for the system save
 * dialog or another app to take away.
 *
 * The chat itself never leaves encrypted storage unasked — nothing is written until the owner has
 * chosen a destination — and the staged copy lives in the cache directory, which the system is
 * free to clear and which this class wipes before every run.
 */
@Singleton
class WhatsAppExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileVault: FileVaultRepository
) {

    /** A finished export, ready to be handed to `CreateDocument` or to another app. */
    data class Ready(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val messages: Int,
        val mediaWritten: Int,
        val mediaSkipped: Int
    ) {
        val summary: String
            get() = buildString {
                append("$messages messages")
                if (mediaWritten > 0) append(" · $mediaWritten file${if (mediaWritten == 1) "" else "s"}")
                if (mediaSkipped > 0) append(" · $mediaSkipped skipped")
            }
    }

    companion object {
        private const val DIR_NAME = "whatsapp_export"
        private const val PART_LIMIT = 400

        /** A note whose bubbles were never imported from a WhatsApp export still exports as text. */
        const val NO_CONVERSATION =
            "This note holds no WhatsApp conversation, so there is nothing to write out."
    }

    /**
     * Builds the export in the cache directory. [includeMedia] decides between the two shapes
     * WhatsApp itself uses: a `.txt` of the words, or a `.zip` carrying the words plus the files.
     */
    suspend fun stage(
        title: String,
        content: String,
        attachments: List<NoteAttachment>,
        includeMedia: Boolean,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<Ready> = withContext(Dispatchers.IO) {
        runCatching {
            val conversation = WhatsAppTranscript.transcriptOf(content)
            val messages = WhatsAppTranscript.messageCount(conversation)
            if (conversation.isBlank() || messages == 0) {
                throw IllegalArgumentException(NO_CONVERSATION)
            }
            val clean = WhatsAppFormat.safeFileStem(title)
            val dir = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            if (!includeMedia || attachments.isEmpty()) {
                onProgress("Writing the transcript", 40)
                val name = WhatsAppFormat.exportedTranscriptName(clean)
                val file = WhatsAppExport.writeTranscript(File(dir, name), clean, conversation)
                onProgress("Transcript ready", 100)
                return@runCatching Ready(
                    file = file,
                    displayName = name,
                    mimeType = "text/plain",
                    messages = messages,
                    mediaWritten = 0,
                    mediaSkipped = 0
                )
            }
            val name = WhatsAppFormat.exportedArchiveName(clean)
            val target = File(dir, name)
            val wanted = attachments.take(PART_LIMIT)
            val media = wanted.map { attachment ->
                WhatsAppExport.Media(attachment.name) {
                    // Resolved lazily and one at a time: the file vault decrypts into scratch
                    // storage that the next decrypt clears, so a pre-resolved list would leave
                    // the archive holding one file repeated.
                    onProgress("Decrypting ${attachment.name}", 30)
                    fileVault.exportToCache(attachment.id)?.inputStream()
                }
            }
            val stats = WhatsAppExport.writeArchive(
                target = target,
                title = clean,
                conversation = conversation,
                media = media,
                onEntry = { entry -> onProgress("Packed $entry", 70) }
            )
            onProgress("Archive ready", 100)
            Ready(
                file = target,
                displayName = name,
                mimeType = "application/zip",
                messages = messages,
                mediaWritten = stats.entries - 1,
                mediaSkipped = stats.skipped.size + (attachments.size - wanted.size).coerceAtLeast(0)
            )
        }
    }

    /**
     * A copy in the one cache folder this app's FileProvider is allowed to serve (`secure_share/`).
     *
     * The export itself is built in `whatsapp_export/`, because the file vault wipes
     * `secure_share/` every time it decrypts a file and a zip is assembled one decrypted file at a
     * time. Sharing needs a `content://` URI rather than a path, so the finished export is copied
     * across only once every media file is already packed.
     */
    suspend fun shareable(ready: Ready): Result<Ready> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "secure_share").apply { mkdirs() }
            val target = File(dir, ready.displayName)
            ready.file.copyTo(target, overwrite = true)
            ready.copy(file = target)
        }
    }

    /** Streams the staged file into the document the owner just named in the save dialog. */
    suspend fun deliver(source: File, target: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IllegalArgumentException(
                    "Secure Wallet could not write to that location. Choose somewhere else — " +
                        "this phone's Downloads folder always works."
                )
            source.inputStream().use { input ->
                output.use { sink -> input.copyTo(sink, 128 * 1024) }
            }
            source.length()
        }
    }

    /** Scratch copies of a chat are plaintext while they sit here, so they are not left behind. */
    suspend fun discard() = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, DIR_NAME).listFiles()?.forEach { it.delete() } }
    }
}
