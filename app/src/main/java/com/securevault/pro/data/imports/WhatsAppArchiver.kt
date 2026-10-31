package com.securevault.pro.data.imports

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.SecureNote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** What one tap put into the vault. */
data class ChatArchive(
    val chat: String,
    val noteId: String,
    val messages: Int,
    val sealed: List<VaultFile>,
    val alreadyInVault: Int,
    val missing: List<WhatsAppTranscript.Reference>,
    val bytes: Long,
    val warnings: List<String>
)

/**
 * Copies a WhatsApp conversation into the vault the way the owner described it: tap the chat and
 * the transcript plus every file that belongs to it land together, with a proof block naming
 * where they came from and what each one hashes to.
 *
 * WhatsApp's own export is the source of truth for the words. Its media sits in separate type
 * folders — and inside the .zip when the owner chose "attach media" — so the filenames the
 * transcript carries are used to join the two: [WhatsAppArchive] decides which file on the phone
 * each line meant, and this class seals exactly those, linked to the note by `noteId` so opening
 * the chat opens its files. Nothing is uploaded, and nothing is guessed — a file the join cannot
 * place is listed in the note as missing instead of being filled in with a near match.
 */
@Singleton
class WhatsAppArchiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileVault: FileVaultRepository,
    private val repository: VaultRepository,
    private val whatsAppStreaming: WhatsAppStreamingImporter
) {

    /** Staging area for one file at a time, so a 400 MB video never has to fit in memory. */
    private val cacheDir: File get() = File(context.cacheDir, "wa-archive").apply { mkdirs() }

    /**
     * Archives a whole conversation: reads [sourceDocId] (a `.txt` or the `.zip` WhatsApp builds
     * around it), joins every file the transcript names against [folderMedia] and the archive's
     * own entries, seals the matches, and writes the transcript plus its proof block to one note.
     */
    suspend fun archive(
        treeUri: Uri,
        chat: String,
        sourceDocId: String,
        sourceName: String,
        sourceSize: Long,
        sourceModifiedAt: Long,
        sourcePath: String,
        folderMedia: List<WhatsAppArchive.MediaDoc>,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<ChatArchive> = withContext(Dispatchers.IO) {
        runCatching {
            val title = chat.ifBlank { WhatsAppTree.displayName(sourceName) }
            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceDocId)
            // readSource throws the specific reason it failed; a generic message here used to hide
            // "the transcript is too large" and "this archive holds no transcript" behind one line.
            val source = readSource(sourceUri, sourceName, onProgress)
            val parsed = WhatsAppTranscript.parse(source.transcript)
            if (parsed.messageCount == 0 && parsed.references.isEmpty()) {
                throw IllegalArgumentException(
                    "No WhatsApp messages were found inside $sourceName — it is not a chat export."
                )
            }
            // The same unit the picker and the share inbox would create for this conversation: one
            // chat, one note, one media container, whichever way it was imported.
            val noteId = WhatsAppChatUnit.idFor(title, sourceName)
            val plan = WhatsAppArchive.plan(parsed.references, source.zipMedia + folderMedia)
            val outcome = seal(noteId, plan.matches, treeUri, sourceUri, title, onProgress)
            val manifest = WhatsAppArchive.manifest(
                chat = title,
                sourceName = sourceName,
                sourcePath = sourcePath,
                sourceBytes = sourceSize,
                sourceModifiedAt = sourceModifiedAt,
                parsed = parsed,
                plan = plan.withMeasuredSizes(outcome.measured),
                hashes = outcome.hashes,
                provenance = deviceProvenance()
            )
            saveNote(
                noteId = noteId,
                title = title,
                content = chatBody(title, parsed, source.transcript, manifest),
                attachments = outcome.sealed.map {
                    NoteAttachment(id = it.id, name = it.name, mimeType = it.mimeType, size = it.size)
                }
            )
            // A note is not a conversation: the chat list and its viewer read the message tables,
            // so the same parsed transcript is written there too, under the same id the media was
            // just sealed into. Without this the import opened a note and the chat list stayed empty.
            val mirrorFailure = if (parsed.messageCount > 0) {
                runCatching {
                    whatsAppStreaming.importParsed(
                        request = WhatsAppStreamingImporter.Request(
                            chatId = noteId,
                            title = title,
                            sourceName = sourceName
                        ),
                        messages = WhatsAppTranscript.messages(source.transcript),
                        participants = parsed.participants,
                        transcriptSha = parsed.sha256,
                        firstStamp = parsed.firstStamp,
                        lastStamp = parsed.lastStamp,
                        mediaSealed = outcome.sealed.size
                    ).exceptionOrNull()
                }.getOrElse { it }
            } else {
                null
            }
            if (mirrorFailure is CancellationException) throw mirrorFailure
            val mirrorWarning = mirrorFailure?.let {
                "The conversation list could not be updated: ${it.message ?: "unknown reason"}."
            }
            ChatArchive(
                chat = title,
                noteId = noteId,
                messages = parsed.messageCount,
                sealed = outcome.sealed,
                alreadyInVault = outcome.reused,
                missing = plan.missing,
                bytes = outcome.sealed.sumOf { it.size },
                warnings = (source.warnings + outcome.warnings + listOfNotNull(mirrorWarning))
                    .filter { it.isNotBlank() }
                    .take(8)
            )
        }
    }

    /**
     * Seals files the owner picked out of one conversation without re-reading a transcript —
     * the "select anything inside this chat and keep it" case. They are linked to the same note
     * as the chat, which is created when the chat has never been archived.
     */
    suspend fun sealFiles(
        treeUri: Uri,
        chat: String,
        docs: List<WhatsAppArchive.MediaDoc>,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<ChatArchive> = withContext(Dispatchers.IO) {
        runCatching {
            val title = chat.ifBlank { "WhatsApp" }
            val noteId = WhatsAppChatUnit.idFor(title, "")
            val matches = docs.map { doc ->
                WhatsAppArchive.Match(
                    reference = WhatsAppTranscript.Reference(
                        fileName = doc.name,
                        kind = WhatsAppTranscript.kindOf(doc.name),
                        line = 0,
                        attached = true
                    ),
                    doc = doc,
                    how = "chosen"
                )
            }
            val outcome = seal(noteId, matches, treeUri, Uri.EMPTY, title, onProgress)
            val plan = WhatsAppArchive.Plan(matches, emptyList())
            val manifest = WhatsAppArchive.manifest(
                chat = title,
                sourceName = "${matches.size} file(s) chosen from WhatsApp",
                sourcePath = "WhatsApp Media",
                sourceBytes = plan.bytes,
                sourceModifiedAt = docs.maxOfOrNull { it.modifiedAt } ?: 0L,
                parsed = WhatsAppTranscript.Parsed(
                    messageCount = 0,
                    participants = emptyList(),
                    firstStamp = "",
                    lastStamp = "",
                    references = matches.map { it.reference },
                    omittedMedia = 0,
                    sha256 = ""
                ),
                plan = plan.withMeasuredSizes(outcome.measured),
                hashes = outcome.hashes,
                provenance = deviceProvenance()
            )
            val previous = runCatching { repository.getNote(noteId) }.getOrNull()
            val body = buildString {
                if (previous != null && previous.content.isNotBlank()) {
                    append(previous.content.trimEnd())
                    appendLine()
                    appendLine()
                    appendLine("— Files chosen later, from the same conversation —")
                } else {
                    appendLine("WhatsApp files · $title")
                    appendLine("${matches.size} file(s) taken from WhatsApp on this device")
                    appendLine()
                }
                append(manifest)
            }
            saveNote(
                noteId = noteId,
                title = title,
                content = body,
                attachments = (previous?.attachments.orEmpty()) +
                    outcome.sealed.take(MAX_ATTACHMENT_META).map {
                        NoteAttachment(id = it.id, name = it.name, mimeType = it.mimeType, size = it.size)
                    }
            )
            ChatArchive(
                chat = title,
                noteId = noteId,
                messages = 0,
                sealed = outcome.sealed,
                alreadyInVault = outcome.reused,
                missing = emptyList(),
                bytes = outcome.sealed.sumOf { it.size },
                warnings = outcome.warnings.filter { it.isNotBlank() }.take(8)
            )
        }
    }

    private class Outcome(
        val sealed: List<VaultFile>,
        val hashes: Map<String, String>,
        val measured: Map<String, Long>,
        val reused: Int,
        val warnings: List<String>
    )

    /**
     * Stages, hashes and seals each matched file. Files already sealed under this note are
     * counted rather than copied again, so tapping "archive" twice on a long chat does not fill
     * the vault with duplicates.
     */
    private suspend fun seal(
        noteId: String,
        matches: List<WhatsAppArchive.Match>,
        treeUri: Uri,
        sourceUri: Uri,
        title: String,
        onProgress: (String, Int) -> Unit
    ): Outcome {
        val already = runCatching { fileVault.listFiles() }
            .getOrDefault(emptyList())
            .filter { it.noteId == noteId }
            .map { it.name.lowercase() }
            .toHashSet()
        val sealed = mutableListOf<VaultFile>()
        val hashes = HashMap<String, String>(matches.size)
        val measured = HashMap<String, Long>(matches.size)
        val warnings = mutableListOf<String>()
        var reused = 0
        var failed = 0

        matches.forEachIndexed { index, match ->
            coroutineContext.ensureActive()
            onProgress(
                "Archiving $title",
                (6 + index * 88L / matches.size.coerceAtLeast(1)).toInt().coerceIn(6, 94)
            )
            if (already.contains(match.doc.name.lowercase())) {
                reused++
                hashes[match.doc.relativePath] = ALREADY_SEALED
                return@forEachIndexed
            }
            val staged = File(cacheDir, "stage-$index.tmp")
            try {
                val digest = if (match.doc.relativePath.startsWith(ZIP_PREFIX)) {
                    copyZipEntry(sourceUri, match.doc.relativePath.removePrefix(ZIP_PREFIX), staged)
                } else {
                    copyDocument(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, match.doc.relativePath),
                        staged
                    )
                }
                if (staged.length() == 0L) throw IllegalStateException("came back empty")
                measured[match.doc.relativePath] = staged.length()
                hashes[match.doc.relativePath] = digest
                val saved = fileVault.saveStream(
                    source = staged,
                    displayName = match.doc.name,
                    mimeType = fileVault.guessMime(match.doc.name),
                    category = fileVault.guessCategory(match.doc.name),
                    // The chat's own module on disk: this file's bytes sit in chats/<chat id>/,
                    // never beside another conversation's media.
                    noteId = noteId,
                    folder = WhatsAppChatUnit.containerOf(noteId)
                ).getOrNull()
                if (saved == null) warnings += "${match.doc.name} could not be sealed." else sealed += saved
            } catch (error: Throwable) {
                failed++
                warnings += "${match.doc.name}: ${error.message ?: "could not be read"}"
            } finally {
                staged.delete()
            }
        }
        if (failed > 0) warnings += "$failed file(s) could not be sealed."
        return Outcome(sealed, hashes, measured, reused, warnings)
    }

    private suspend fun saveNote(noteId: String, title: String, content: String, attachments: List<NoteAttachment>) {
        val existing = runCatching { repository.getNote(noteId) }.getOrNull()
        val note = SecureNote(
            id = noteId,
            title = title.take(160),
            content = content,
            isPinned = existing?.isPinned ?: false,
            color = existing?.color ?: "MINT",
            tags = ((existing?.tags ?: emptyList()) + ARCHIVE_TAGS).distinct(),
            attachments = attachments.take(MAX_ATTACHMENT_META),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        if (!repository.saveNote(note)) throw IllegalStateException("The decoy vault is read-only.")
    }

    private class SourceRead(
        val transcript: String,
        val zipMedia: List<WhatsAppArchive.MediaDoc>,
        val warnings: List<String>
    )

    /**
     * One pass over the export: the transcript text, plus — for a .zip — a name-and-size index of
     * the media inside it, so the planner can favour the file WhatsApp bundled over a same-named
     * photo in the media folder without holding a byte of it in memory.
     */
    private suspend fun readSource(
        uri: Uri,
        sourceName: String,
        onProgress: (String, Int) -> Unit
    ): SourceRead {
        val raw = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw IllegalArgumentException(
                "Secure Wallet lost permission to read $sourceName. Re-pick the folder and try again."
            )
        } ?: throw IllegalArgumentException("Secure Wallet could not open $sourceName for reading.")
        return raw.use { stream ->
            val push = PushbackInputStream(stream, 4)
            val head = ByteArray(4)
            val read = push.read(head)
            if (read > 0) push.unread(head, 0, read)
            if (read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                onProgress("Reading the chat export", 4)
                try {
                    readZip(push, sourceName)
                } catch (failure: java.util.zip.ZipException) {
                    throw IllegalArgumentException(
                        "$sourceName is a damaged archive (${failure.message ?: "the zip cannot be read"}). " +
                            "WhatsApp cut it short — export the chat again."
                    )
                } catch (failure: java.io.EOFException) {
                    throw IllegalArgumentException("$sourceName stops part-way through — the file is truncated.")
                }
            } else {
                onProgress("Reading the transcript", 4)
                val text = readCapped(push, MAX_TRANSCRIPT_BYTES)
                    .toString(Charsets.UTF_8).removePrefix("﻿")
                if (text.isBlank()) throw IllegalArgumentException("$sourceName is empty.")
                SourceRead(text, emptyList(), emptyList())
            }
        }
    }

    private fun readZip(stream: InputStream, sourceName: String): SourceRead {
        var transcript = ""
        var transcriptHits = 0
        var transcriptTooLarge = false
        val media = mutableListOf<WhatsAppArchive.MediaDoc>()
        val warnings = mutableListOf<String>()
        var entries = 0
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) {
                    warnings += "Stopped reading after $MAX_ENTRIES files in $sourceName."
                    break
                }
                val rawName = entry.name.replace('\\', '/')
                val fileName = rawName.substringAfterLast('/')
                if (entry.isDirectory || rawName.startsWith("__MACOSX/") || fileName.startsWith(".")) {
                    zip.closeEntry()
                    continue
                }
                // The transcript is found by its content, not its name: WhatsApp writes it as
                // "Ali Raza _chat.txt", "9234567890@c.us _chat.txt", "_CHAT.txt",
                // "business_chat-….txt" and more, and the file the export really contains is
                // whichever .txt carries the most message timestamps.
                if (WhatsAppTranscript.isTranscriptCandidate(fileName)) {
                    val bytes = readEntryCapped(zip, MAX_TRANSCRIPT_BYTES)
                    if (bytes == null) {
                        transcriptTooLarge = true
                    } else {
                        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
                        val hits = WhatsAppTranscript.messageCount(text)
                        if (hits > transcriptHits) {
                            transcriptHits = hits
                            transcript = text
                        }
                    }
                } else {
                    media += WhatsAppArchive.MediaDoc(
                        name = fileName,
                        relativePath = ZIP_PREFIX + rawName,
                        size = if (entry.size > 0) entry.size else 0L,
                        modifiedAt = if (entry.time > 0) entry.time else 0L
                    )
                }
                zip.closeEntry()
            }
        }
        if (transcript.isBlank()) {
            throw IllegalArgumentException(
                if (transcriptTooLarge) {
                    "The transcript inside $sourceName is larger than " +
                        "${MAX_TRANSCRIPT_BYTES / (1024 * 1024)} MB."
                } else {
                    "That archive holds no chat transcript inside it."
                }
            )
        }
        return SourceRead(transcript, media, warnings)
    }

    /** Re-opens the export and streams the requested entry to disk, hashing it as it goes. */
    private fun copyZipEntry(sourceUri: Uri, entryPath: String, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("the export could not be reopened")
        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.replace('\\', '/') == entryPath) {
                        FileOutputStream(dest).use { out ->
                            val buffer = ByteArray(STREAM_BUFFER)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                written += read
                                requireWithinLimit(written, dest)
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                            }
                        }
                        return hex(digest)
                    }
                    zip.closeEntry()
                }
            }
        }
        dest.delete()
        throw IllegalArgumentException("is no longer inside the export")
    }

    private fun copyDocument(uri: Uri, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("the file could not be opened")
        input.use { stream ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(STREAM_BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    written += read
                    requireWithinLimit(written, dest)
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
        }
        return hex(digest)
    }

    private fun requireWithinLimit(written: Long, dest: File) {
        if (written <= MAX_MEDIA_BYTES) return
        dest.delete()
        throw IllegalArgumentException("larger than the ${MAX_MEDIA_BYTES / (1024 * 1024)} MB archive limit")
    }

    private fun deviceProvenance(): String =
        "Secure Wallet Pro · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    private fun chatBody(
        title: String,
        parsed: WhatsAppTranscript.Parsed,
        transcript: String,
        manifest: String
    ): String = buildString {
        appendLine("WhatsApp archive · $title")
        appendLine(
            "${parsed.messageCount} messages · ${parsed.references.size} files referenced · " +
                "sealed on this device"
        )
        if (parsed.participants.isNotEmpty()) {
            appendLine("With: ${parsed.participants.take(12).joinToString(", ")}")
        }
        if (parsed.firstStamp.isNotBlank()) {
            appendLine("Span: ${parsed.firstStamp} → ${parsed.lastStamp}")
        }
        appendLine()
        appendLine(WhatsAppTranscript.CONVERSATION_MARKER)
        append(WhatsAppTranscript.stripBidi(transcript.replace("\r\n", "\n")).trim())
        appendLine()
        appendLine()
        append(manifest)
    }

    private fun readCapped(stream: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(STREAM_BUFFER)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw IllegalArgumentException("The transcript is larger than ${limit / (1024 * 1024)} MB.")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Reads one entry, draining it fully; null when it exceeds [limit], so the next entry stays aligned. */
    private fun readEntryCapped(stream: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(STREAM_BUFFER)
        var total = 0L
        var oversize = false
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            if (oversize) continue
            total += read
            if (total > limit) {
                oversize = true
                out.reset()
            } else {
                out.write(buffer, 0, read)
            }
        }
        return if (oversize) null else out.toByteArray()
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    private companion object {
        /** Every archive note carries these, so the Notes module can find the chats. */
        val ARCHIVE_TAGS = listOf("WhatsApp", "Archived")

        /** Says so in the proof block when a file was sealed by an earlier archive of this chat. */
        const val ALREADY_SEALED = "sealed in an earlier archive"

        /** Marks a file that lives inside the .zip rather than in a media folder. */
        const val ZIP_PREFIX = "zip:"

        const val MAX_TRANSCRIPT_BYTES = 25L * 1024L * 1024L
        const val MAX_MEDIA_BYTES = 500L * 1024L * 1024L
        const val MAX_ENTRIES = 4_000
        const val MAX_ATTACHMENT_META = 300
        const val STREAM_BUFFER = 128 * 1024
    }
}

/**
 * A plan whose file sizes are the ones actually handled: a .zip entry often reports no size until
 * it has been read, and a proof block must not quote a zero the archive never wrote.
 */
private fun WhatsAppArchive.Plan.withMeasuredSizes(measured: Map<String, Long>): WhatsAppArchive.Plan {
    if (measured.isEmpty()) return this
    val resized = matches.map { match ->
        val size = measured[match.doc.relativePath]
        if (size != null && size != match.doc.size) match.copy(doc = match.doc.copy(size = size)) else match
    }
    return copy(matches = resized)
}
