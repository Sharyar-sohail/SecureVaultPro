package com.securevault.pro.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.SecureNote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The size gate every intake path shares, kept away from Android so a test can stand a 251 MB
 * export in front of it without building one. Both [WhatsAppImporter.importFromUri] (the size the
 * provider declares) and [WhatsAppImporter.importFromFile] (the size of the staged copy) ask it
 * before a single byte is read, so the limit cannot be bypassed by choosing the other path.
 */
object WhatsAppImportLimits {

    /** The largest export Secure Wallet will read: bigger is a backup or a movie, not a chat. */
    const val MAX_EXPORT_BYTES = 250L * 1024L * 1024L

    /** The reason, in the owner's words, or null when the size is acceptable. */
    fun rejectFor(declaredBytes: Long): String? = when {
        declaredBytes < 0L -> null
        declaredBytes > MAX_EXPORT_BYTES ->
            "The export is larger than the ${MAX_EXPORT_BYTES / (1024 * 1024)} MB import limit. " +
                "Export the chat without media, or split it in WhatsApp and import the two halves."
        else -> null
    }

    /** True when [declaredBytes] may be read at all. */
    fun accepts(declaredBytes: Long): Boolean = rejectFor(declaredBytes) == null
}

/**
 * Counts what is actually consumed and refuses past the ceiling. Package-private to this file so
 * [WhatsAppImporter] is the only reader that can be told to read an enormous file.
 */
internal class ByteCappedStream(
    source: java.io.InputStream,
    private val limit: Long
) : java.io.FilterInputStream(source) {

    private var consumed = 0L

    override fun read(): Int = `in`.read().also { value ->
        if (value >= 0) charge(1L)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, length).also { value ->
            if (value > 0) charge(value.toLong())
        }

    override fun skip(requested: Long): Long {
        val moved = `in`.skip(requested)
        if (moved > 0) charge(moved)
        return moved
    }

    private fun charge(value: Long) {
        consumed += value
        if (consumed > limit) {
            throw IllegalArgumentException(
                WhatsAppImportLimits.rejectFor(consumed)
                    ?: "That export is larger than the import limit."
            )
        }
    }
}

data class WhatsAppImportResult(
    val chatTitle: String,
    /** The note the conversation was sealed into — the screen navigates there once the import lands. */
    val noteId: String,
    /**
     * The conversation's own vault unit id. It is the note id and the name of the directory this
     * chat's media was sealed into, so the UI can show one module per chat and the files of two
     * chats can never be listed as one pile.
     */
    val chatId: String = noteId,
    /** The chat's media container inside the file vault, e.g. `chats/<chatId>`. */
    val container: String = WhatsAppChatUnit.containerOf(noteId),
    val messages: Int,
    val mediaSaved: Int,
    val mediaSkipped: Int,
    /** Attachment names the transcript actually mentioned, whatever arrived or did not. */
    val mediaReferenced: Int = 0,
    /** How many of those [mediaReferenced] the export did not carry. Never counted as saved. */
    val mediaMissing: Int = 0,
    val warnings: List<String>
)

/**
 * Turns a WhatsApp "Export chat" file (.txt or .zip with media) into one
 * encrypted note per chat, and seals every media attachment into the file vault.
 * Nothing leaves the device during the import.
 *
 * Two entry points, one engine: [importFromUri] is a file the owner picked or that the share
 * sheet handed over while it was still readable, [importFromFile] is a copy the share queue
 * staged before the process died. Both sniff the bytes, because a WhatsApp export arrives with
 * whatever MIME type the OEM file manager happened to guess.
 */
@Singleton
class WhatsAppImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileVault: FileVaultRepository,
    private val repository: VaultRepository
) {
    companion object {
        private const val MAX_TEXT_BYTES = 25L * 1024L * 1024L
        private const val MAX_ATTACHMENT_META = 300

        private const val BACKUP_FILE_HINT =
            "That is an encrypted WhatsApp backup, not a chat export. No app but WhatsApp can read " +
                "it — the key never leaves the phone. Open the chat in WhatsApp or WhatsApp " +
                "Business, then use ⋮ → More → Export chat and pick the .txt or .zip it writes."

        /** Said when the bytes are not a transcript at all — the owner sees this, never a silent zero. */
        const val NO_TIMESTAMPS_HINT =
            "That .txt holds no WhatsApp timestamps, so it is not a chat export. In WhatsApp " +
                "open the chat → ⋮ → More → Export chat and pick the file it writes."

        /** Said when a share sheet hands over a photo or clip instead of the chat file. */
        const val NOT_A_CHAT_HINT =
            "That file is not a WhatsApp chat export, so there is no conversation inside it. " +
                "In WhatsApp open the chat → ⋮ → More → Export chat, then share the .txt or .zip " +
                "it writes to Secure Wallet Pro."
    }

    suspend fun importFromUri(
        uri: Uri,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<WhatsAppImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = displayName(uri)
            val declared = declaredSize(uri)
            WhatsAppImportLimits.rejectFor(declared)?.let { throw IllegalArgumentException(it) }
            val stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (error: SecurityException) {
                // A picker grant expires if the app is reinstalled or the owner revokes it; the
                // file is still there, and "permission denied" is the only answer they can act on.
                throw IllegalArgumentException(
                    "Secure Wallet lost permission to read that file. Pick it again and choose " +
                        "\"Allow\" on the dialog."
                )
            } ?: throw IllegalArgumentException(
                "The selected WhatsApp export could not be opened. It may have been moved or " +
                    "renamed since it was shared — pick it again."
            )
            stream.use { importStream(it, displayName, onProgress) }
        }
    }

    /**
     * A chat the share sheet handed over and the queue copied into app storage before the process
     * died. The copy is already ours, so no content permission is involved — this is the path that
     * makes a share arriving while the vault is locked still land after unlock.
     */
    suspend fun importFromFile(
        file: File,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<WhatsAppImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile || file.length() == 0L) {
                throw IllegalArgumentException("The shared chat was lost before it could be sealed — share it again.")
            }
            WhatsAppImportLimits.rejectFor(file.length())?.let { throw IllegalArgumentException(it) }
            file.inputStream().use { importStream(it, file.name, onProgress) }
        }
    }

    private suspend fun importStream(
        stream: InputStream,
        displayName: String,
        onProgress: (String, Int) -> Unit
    ): WhatsAppImportResult {
        // A WhatsApp *backup* (.db.crypt14) is encrypted with a key only WhatsApp
        // holds; feeding it to the transcript parser burns memory and looks like a
        // hang, so refuse it up front with the instruction that actually works.
        if (WhatsAppFormat.isEncryptedBackup(displayName)) {
            throw IllegalArgumentException(BACKUP_FILE_HINT)
        }
        onProgress("Opening WhatsApp export", 4)
        // The declared size is only a promise: a provider can answer zero or a lie. Counting the
        // bytes actually read is what makes the 250 MB cap real, and it bounds every path — a zip
        // whose entries unpack larger than their archive (a zip bomb) is stopped here too.
        val counted = ByteCappedStream(stream, WhatsAppImportLimits.MAX_EXPORT_BYTES)
        val pushback = PushbackInputStream(counted, 4)
        val header = ByteArray(4)
        val read = pushback.read(header)
        if (read > 0) pushback.unread(header, 0, read)
        val isZip = read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        return try {
            if (isZip) importZip(pushback, displayName, onProgress) else importText(pushback, displayName, onProgress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: java.util.zip.ZipException) {
            throw IllegalArgumentException(
                "$displayName is a damaged archive (${error.message ?: "the zip cannot be read"}). " +
                    "WhatsApp cut it short — export the chat again and re-pick the file."
            )
        } catch (error: java.io.EOFException) {
            throw IllegalArgumentException(
                "$displayName stops part-way through — the file was cut short. " +
                    "Export the chat again from WhatsApp."
            )
        } catch (error: java.io.IOException) {
            throw IllegalArgumentException(
                "That file could not be read where it is stored (${error.message ?: "input error"}). " +
                    "If it lives on an SD card or in cloud storage, save it to this phone first, " +
                    "or share it straight from WhatsApp into Secure Wallet."
            )
        }
    }

    private suspend fun importText(
        stream: InputStream,
        displayName: String,
        onProgress: (String, Int) -> Unit
    ): WhatsAppImportResult {
        // Probe the first block before allocating: a chat transcript is text, and a
        // stray binary file fails here instead of decoding 25 MB of noise.
        val head = ByteArray(4096)
        val headLength = fill(stream, head)
        if (head.take(headLength).any { it == 0.toByte() }) {
            throw IllegalArgumentException(BACKUP_FILE_HINT)
        }
        val body = readCapped(stream, MAX_TEXT_BYTES - headLength)
        val text = (head.copyOfRange(0, headLength) + body)
            .toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
        if (text.isBlank()) throw IllegalArgumentException("That chat export is empty.")
        val title = WhatsAppTranscript.titleFrom(displayName)
        val chatId = WhatsAppChatUnit.idFor(title, displayName)
        val parsed = WhatsAppTranscript.parse(text)
        val messages = parsed.messageCount
        // A .txt that parses to zero bubbles is not a conversation, and writing an empty chat
        // note over it is the silent failure the owner cannot diagnose — say what is missing.
        if (messages == 0) throw IllegalArgumentException(NO_TIMESTAMPS_HINT)
        // A plain .txt carries words only. Its attachment markers name files that are not inside
        // it, and saying so is the difference between "the import lost my photos" and "WhatsApp
        // never sent them" — the count the owner sees is the count the file really mentioned.
        val wanted = parsed.references.filter { it.attached }
        val warnings = if (wanted.isEmpty()) emptyList() else listOf(
            "This .txt carries ${wanted.size} file${if (wanted.size == 1) "" else "s"} the chat mentions " +
                "but no media. In WhatsApp export it again with \"Attach media\" to bring the files too."
        )
        onProgress("Sealing chat transcript", 62)
        val built = WhatsAppChatNote.build(
            chatId = chatId,
            title = title,
            transcript = text,
            messages = messages,
            mediaSaved = 0,
            attachments = emptyList()
        )
        if (!repository.saveNote(noteOf(built, emptyList()))) {
            throw IllegalStateException("The decoy vault is read-only.")
        }
        onProgress("Chat sealed", 100)
        return WhatsAppImportResult(
            chatTitle = built.title,
            noteId = built.id,
            chatId = built.id,
            container = WhatsAppChatUnit.containerOf(built.id),
            messages = messages,
            mediaSaved = 0,
            mediaSkipped = 0,
            mediaReferenced = wanted.size,
            mediaMissing = wanted.size,
            warnings = warnings
        )
    }

    private suspend fun importZip(
        stream: InputStream,
        displayName: String,
        onProgress: (String, Int) -> Unit
    ): WhatsAppImportResult {
        val baseTitle = WhatsAppTranscript.titleFrom(displayName.removeSuffix(".zip"))
        // One conversation, one unit, one container: decided before a byte of media is sealed, so
        // every file this archive carries lands inside this chat's own directory and the note that
        // lists them shares the same id. Two chats can never end up in one folder or one blob.
        val chatId = WhatsAppChatUnit.idFor(baseTitle, displayName)
        val container = WhatsAppChatUnit.containerOf(chatId)
        val attachments = mutableListOf<NoteAttachment>()
        val sealWarnings = mutableListOf<String>()
        var attachmentMetaFull = false

        val found = WhatsAppZipReader.read(
            source = stream,
            archiveName = displayName,
            onProgress = onProgress,
            onMedia = { name, bytes ->
                val meta = fileVault.saveFile(
                    bytes = bytes,
                    displayName = name,
                    mimeType = fileVault.guessMime(name),
                    category = fileVault.guessCategory(name),
                    noteId = chatId,
                    folder = container
                ).getOrNull()
                if (meta == null) {
                    sealWarnings += "$name could not be sealed."
                    false
                } else {
                    if (!attachmentMetaFull && attachments.size < MAX_ATTACHMENT_META) {
                        attachments += NoteAttachment(
                            id = meta.id,
                            name = meta.name,
                            mimeType = meta.mimeType,
                            size = meta.size
                        )
                    } else {
                        attachmentMetaFull = true
                    }
                    true
                }
            }
        )

        val conversation = WhatsAppTranscript.stripBidi(found.conversation.replace("\r\n", "\n")).trim()
        val messages = WhatsAppTranscript.messageCount(conversation)
        val warnings = mutableListOf<String>()
        warnings += found.warnings
        warnings += sealWarnings
        if (!found.hasConversation) {
            // The archive was readable but held no conversation: say which half is missing rather
            // than writing an empty chat note the owner will read as a broken import.
            if (found.transcriptsSeen > 0) warnings += NO_TIMESTAMPS_HINT else warnings += WhatsAppZipReader.NO_TRANSCRIPT_HINT
        } else if (found.messages == 0 && found.structured != null) {
            // The words came out of WhatsApp's messages table because the transcript did not parse.
            warnings += "No readable transcript was inside that archive, so the conversation was " +
                "rebuilt from WhatsApp's structured messages file."
        }
        val structuredUndated = found.structured?.undated ?: 0
        if (structuredUndated > 0) {
            warnings += "$structuredUndated row(s) in WhatsApp's messages table carried no timestamp; " +
                "their words were kept and they were not counted as messages."
        }

        // The honest media ledger: what the chat mentioned, what the archive actually carried, and
        // the gap between them named. Never a number lifted from the transcript's markers.
        val referenced = WhatsAppTranscript.parse(conversation).references.filter { it.attached }
        val plan = WhatsAppArchive.plan(
            references = referenced,
            docs = found.mediaNames.map { name -> WhatsAppArchive.MediaDoc(name, name, 0L, 0L) }
        )
        val missing = plan.missing
        if (missing.isNotEmpty()) {
            val named = missing.take(3).joinToString(", ") { it.fileName }
            val rest = if (missing.size > 3) " and ${missing.size - 3} more" else ""
            warnings += "$named$rest — ${missing.size} of the ${referenced.size} file(s) this chat " +
                "mentions were not inside the export."
        }

        val title = found.title.ifBlank { baseTitle }
        val built = WhatsAppChatNote.build(
            chatId = chatId,
            title = title,
            transcript = conversation,
            messages = messages,
            mediaSaved = found.sealed,
            attachments = attachments,
            container = container
        )
        if (conversation.isBlank() && found.sealed == 0) {
            // Nothing importable at all — the vault gains neither a chat nor a file, so the owner
            // must be told that out loud instead of being sent to an empty note.
            throw IllegalArgumentException(
                warnings.firstOrNull() ?: "That archive held nothing Secure Wallet could import."
            )
        }
        if (!repository.saveNote(noteOf(built, attachments))) {
            throw IllegalStateException("The decoy vault is read-only.")
        }
        if (found.sealed > attachments.size) {
            warnings += "${found.sealed - attachments.size} extra media files stay in the Files module."
        }
        onProgress("Import complete", 100)
        return WhatsAppImportResult(
            chatTitle = built.title,
            noteId = built.id,
            chatId = built.id,
            container = container,
            messages = messages,
            mediaSaved = found.sealed,
            mediaSkipped = found.skipped,
            mediaReferenced = referenced.size,
            mediaMissing = missing.size,
            warnings = warnings.take(8)
        )
    }

    /** The built note as the vault stores it: the id the chat's media is already linked to. */
    private fun noteOf(built: WhatsAppChatNote.Built, attachments: List<NoteAttachment>): SecureNote {
        val now = System.currentTimeMillis()
        return SecureNote(
            id = built.id,
            title = built.title,
            content = built.content,
            color = WhatsAppChatNote.COLOR,
            tags = WhatsAppChatNote.TAGS,
            attachments = attachments,
            createdAt = now,
            updatedAt = now
        )
    }

    /** Fills [target] as far as the stream allows and returns how many bytes landed. */
    private fun fill(stream: InputStream, target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val read = stream.read(target, total, target.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun readCapped(stream: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw IllegalArgumentException(
                    "That chat is larger than the ${limit / (1024 * 1024)} MB text limit. " +
                        "Export it without media, or split it into two exports."
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "WhatsApp export"
    }

    private fun declaredSize(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        }
        return 0L
    }
}
