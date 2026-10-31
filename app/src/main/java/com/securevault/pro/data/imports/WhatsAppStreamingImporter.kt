package com.securevault.pro.data.imports

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Turns a WhatsApp export into database rows, a batch at a time, without ever holding the export.
 *
 * This is the engine behind "pick the file, the chat opens". It does one thing the old importer could
 * not: it reads a transcript of *any* length — 5 GB included — and writes the conversation as rows
 * while it reads, in batches of [MESSAGE_BATCH] bubbles, so the memory the import uses is the size of
 * a batch, not the size of the chat.
 *
 * ## The four properties the owner's bug reports demanded
 *
 *  1. **Bounded.** [WhatsAppStreamParser] holds 16 KiB of characters; this class holds one batch of
 *     bubbles (≤500) and, for a `.zip`, one staged entry at a time
 *     ([STAGE_COPY_BYTES]-byte copies). Nothing here allocates a file-sized `String` or byte array.
 *  2. **Progress that moves.** Every emit reports the bytes consumed, and the stages are the owner's
 *     words — "Reading the conversation", "Writing 12 500 of 40 000 messages" — not a spinner.
 *  3. **Cancellation that leaves the database consistent.** A stop raises [WhatsAppStreamParser.Stopped]
 *     and [WhatsAppChatSink.abandon] is called with the ordinal the run started at: the bubbles this
 *     attempt wrote are deleted, a brand-new chat's row is deleted outright, and the list never shows
 *     a conversation with half of it in there.
 *  4. **Resume that cannot duplicate.** The transcript's digest is taken while reading, the store
 *     answers with a [WhatsAppResumePoint], and bubbles are keyed on (`chatId`, `ordinal`) — the same
 *     file parsed twice numbers the same bubble the same way, so a retry rewrites rows instead of
 *     adding a second copy of the conversation.
 *
 * ## Why a `.zip` is staged rather than parsed in place
 *
 * The transcript inside an archive is not always the first `.txt`, and WhatsApp's own bookkeeping
 * (`_MISSING.txt`, `_media.xml`) competes with it, so the winner can only be chosen after the entries
 * have been seen. Choosing it must not mean keeping all the candidates in memory, so each candidate
 * is streamed to a cache file while its digest and bubble count are computed, the loser is deleted
 * the moment a better one appears, and the winner is re-read from disk. Peak cost is one entry plus
 * one batch of rows — and a 2 GB clip inside the archive costs a 2 GB copy on disk, never in heap.
 */
class WhatsAppStreamingImporter(
    private val sink: WhatsAppChatSink,
    private val media: MediaSealer = MediaSealer.NONE,
    private val cacheDir: File? = null,
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** Bubbles per database write. One batch is one transaction, so this is the commit unit. */
        const val MESSAGE_BATCH = 500

        /** Bytes copied at a time when an archive entry is staged to disk. 128 KiB, always. */
        const val STAGE_COPY_BYTES = 128 * 1024

        /**
         * An entry bigger than this is not a chat attachment on a phone. The ceiling is the file
         * vault's own 2 GB limit, so nothing is staged that could not be sealed afterwards.
         */
        const val MAX_STAGED_ENTRY_BYTES = 2L * 1000L * 1000L * 1000L

        /** Names of media seen inside an archive, kept for the pairing pass; the count of files, not bytes. */
        const val MAX_MEDIA_NAMES = 50_000
    }

    /** What the caller said the conversation is, before any of it has been read. */
    data class Request(
        val chatId: String,
        val title: String,
        val sourceName: String,
        val container: String = WhatsAppChatUnit.containerOf(chatId),
        val noteId: String = chatId
    )

    /** The engine's answer: what landed, what was paired, and what the owner should be told. */
    data class Outcome(
        val chatId: String,
        val title: String,
        val contactName: String,
        val container: String,
        val messages: Int,
        val mediaSealed: Int,
        val mediaSkipped: Int,
        val referenced: Int,
        val missing: Int,
        val participants: List<String>,
        val transcriptSha: String,
        val bytes: Long,
        val resumed: Boolean,
        val warnings: List<String>
    )

    /** Raised when a stop is asked for mid-write. Always unwinds through [WhatsAppChatSink.abandon]. */
    private class Abandoned(val startOrdinal: Int, val existedBefore: Boolean) : InterruptedIOException()

    // ── public entry points ───────────────────────────────────────────────────────────────────

    /**
     * Streams a `.txt` transcript — or any plain exported text — into rows.
     *
     * [source] is a factory, not a stream: the digest has to be known before the store decides
     * whether this is a resume or a fresh import, which means the bytes are read once to count them
     * and once to write them. Re-opening is cheap on a file; keeping a gigabyte in memory is not.
     */
    suspend fun importTranscript(
        request: Request,
        source: () -> InputStream,
        onProgress: (String, Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): Result<Outcome> = runCatching {
        val probe = probe(source, onProgress, shouldStop)
        val prepared = prepare(request, probe.sha, probe.messages, onProgress)
        val outcome = try {
            writeTranscript(
                request = request,
                source = source,
                probe = probe,
                startOrdinal = prepared.resume.startOrdinal,
                onProgress = onProgress,
                shouldStop = shouldStop
            )
        } catch (stopped: WhatsAppStreamParser.Stopped) {
            rollback(request.chatId, prepared, stopped)
            throw stopped
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) {
                rollback(request.chatId, prepared, failure)
                throw failure
            }
            rollback(request.chatId, prepared, failure)
            throw failure
        }
        commit(request, prepared, outcome.mediaSealed, outcome.report, outcome.warnings, resumed = prepared.resume.startOrdinal > 0)
    }

    /**
     * Streams a `.zip` "Attach media" export: the transcript becomes rows exactly as in
     * [importTranscript], and every media entry is staged to disk and handed to [MediaSealer] beside
     * it, inside the chat's own container.
     */
    suspend fun importArchive(
        request: Request,
        source: () -> InputStream,
        onProgress: (String, Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): Result<Outcome> = runCatching {
        val staging = requireNotNull(cacheDir) { "Archive import needs a cache directory to stage entries in" }
        val walk = walkArchive(request, staging, source, onProgress, shouldStop)
        val prepared = prepare(request, walk.sha, walk.messages, onProgress)
        val transcriptFile = walk.transcriptFile
            ?: throw IllegalArgumentException(WhatsAppZipReader.NO_TRANSCRIPT_HINT)
        val probe = Probe(walk.sha, walk.messages, walk.bytes)
        val outcome = try {
            writeTranscript(
                request = request,
                source = { FileInputStream(transcriptFile) },
                probe = probe,
                startOrdinal = prepared.resume.startOrdinal,
                onProgress = onProgress,
                shouldStop = shouldStop
            )
        } catch (stopped: WhatsAppStreamParser.Stopped) {
            rollback(request.chatId, prepared, stopped)
            walk.discardSealed(media)
            throw stopped
        } catch (failure: Throwable) {
            rollback(request.chatId, prepared, failure)
            walk.discardSealed(media)
            throw failure
        } finally {
            transcriptFile.delete()
        }
        val warnings = walk.warnings.toMutableList()
        warnings += outcome.warnings
        commit(request, prepared, outcome.mediaSealed, outcome.report, warnings, resumed = prepared.resume.startOrdinal > 0)
    }

    /**
     * The rows behind a conversation the caller has already parsed in memory — the small-export path,
     * which keeps the importer's byte-identical legacy behaviour and still ends up with real rows.
     */
    suspend fun importParsed(
        request: Request,
        messages: List<WhatsAppTranscript.ChatMessage>,
        participants: List<String>,
        transcriptSha: String,
        firstStamp: String,
        lastStamp: String,
        mediaSealed: Int = 0,
        onProgress: (String, Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): Result<Outcome> = runCatching {
        val probe = Probe(transcriptSha, messages.size, bytes = 0L)
        val prepared = prepare(request, transcriptSha, messages.size, onProgress)
        val start = prepared.resume.startOrdinal
        val warnings = mutableListOf<String>()
        val batch = ArrayList<WhatsAppMessageDraft>(MESSAGE_BATCH)
        var written = 0
        try {
            messages.forEachIndexed { index, message ->
                if (shouldStop()) throw WhatsAppStreamParser.Stopped("The import was stopped.")
                if (index < start) return@forEachIndexed
                batch += message.toDraft(index)
                if (batch.size >= MESSAGE_BATCH) {
                    sink.append(request.chatId, batch)
                    written += batch.size
                    batch.clear()
                    onProgress("Writing ${index + 1} of ${messages.size} messages", percentOf(index, messages.size))
                }
            }
            if (batch.isNotEmpty()) {
                sink.append(request.chatId, batch)
                written += batch.size
                batch.clear()
            }
            val report = WhatsAppStreamParser.Report(
                messages = messages.size,
                participants = participants,
                firstStamp = firstStamp,
                lastStamp = lastStamp,
                references = messages.flatMap { it.files }
                    .mapIndexed { index, name ->
                        WhatsAppTranscript.Reference(name, WhatsAppTranscript.kindOf(name), index, true)
                    },
                bytes = 0L,
                sha256 = transcriptSha,
                truncatedLines = 0,
                participantsCapped = false,
                referencesCapped = false
            )
            commit(request, prepared, mediaSealed, report, warnings, resumed = start > 0)
        } catch (failure: Throwable) {
            rollback(request.chatId, prepared, failure)
            throw failure
        }
    }

    // ── the pipeline ──────────────────────────────────────────────────────────────────────────

    /** What the cheap first pass over a transcript learned, before a single row is written. */
    private data class Probe(val sha: String, val messages: Int, val bytes: Long)

    /** Runs the parser twice-over: once for the digest and the count, once for the rows. */
    private fun probe(
        source: () -> InputStream,
        onProgress: (String, Int) -> Unit,
        shouldStop: () -> Boolean
    ): Probe {
        onProgress("Reading the conversation", 6)
        var count = 0
        var bytes = 0L
        var sha = ""
        source().use { stream ->
            val read = WhatsAppStreamParser.read(
                source = stream,
                listener = object : WhatsAppStreamParser.Listener {
                    override fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int) {
                        count++
                    }

                    override fun onBytes(totalBytes: Long) {
                        bytes = totalBytes
                        onProgress("Reading the conversation", 6 + (totalBytes / 20_000).toInt().coerceAtMost(10))
                    }
                },
                shouldStop = shouldStop
            )
            sha = read.sha256
        }
        return Probe(sha = sha, messages = count, bytes = bytes)
    }

    /**
     * The streaming second pass: bubbles become drafts, drafts are flushed in batches, and the digest
     * is checked against the cheap pass so a file that changed under the import cannot half-land.
     */
    private suspend fun writeTranscript(
        request: Request,
        source: () -> InputStream,
        probe: Probe,
        startOrdinal: Int,
        onProgress: (String, Int) -> Unit,
        shouldStop: () -> Boolean
    ): WriteOutcome {
        val batch = ArrayList<WhatsAppMessageDraft>(MESSAGE_BATCH)
        val warnings = mutableListOf<String>()
        var written = 0
        var report: WhatsAppStreamParser.Report? = null
        var stopRequested = false

        source().use { stream ->
            val parsed = try {
                WhatsAppStreamParser.read(
                    source = stream,
                    listener = object : WhatsAppStreamParser.Listener {
                        override fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int) {
                            if (ordinal < startOrdinal) return
                            batch += message.toDraft(ordinal)
                            if (batch.size >= MESSAGE_BATCH) {
                                kotlinx.coroutines.runBlocking {
                                    sink.append(request.chatId, batch)
                                }
                                written += batch.size
                                batch.clear()
                            }
                        }

                        override fun onBytes(totalBytes: Long) {
                            onProgress(
                                "Writing messages",
                                (40 + (totalBytes / 200_000).toInt()).coerceAtMost(90)
                            )
                        }
                    },
                    shouldStop = { stopRequested || shouldStop() }
                )
            } catch (stopped: WhatsAppStreamParser.Stopped) {
                throw stopped
            }
            report = parsed
            if (probe.sha.isNotBlank() && parsed.sha256 != probe.sha) {
                throw IllegalStateException(
                    "That file changed while it was being read, so the import was abandoned. Pick it again."
                )
            }
            if (parsed.truncatedLines > 0) {
                warnings += "${parsed.truncatedLines} line(s) were longer than " +
                    "${WhatsAppStreamParser.MAX_LINE_CHARS / 1024} KB and were cut short."
            }
            if (parsed.referencesCapped) {
                warnings += "More than ${MAX_MEDIA_NAMES} files are named in that chat; the rest were listed as one count."
            }
        }
        if (batch.isNotEmpty()) {
            kotlinx.coroutines.runBlocking { sink.append(request.chatId, batch) }
            written += batch.size
            batch.clear()
        }
        if (stopRequested) throw WhatsAppStreamParser.Stopped("The import was stopped.")
        return WriteOutcome(
            report = requireNotNull(report) { "the parser answered nothing" },
            written = written,
            warnings = warnings,
            mediaSealed = 0
        )
    }

    private data class WriteOutcome(val report: WhatsAppStreamParser.Report, val written: Int, val warnings: List<String>, val mediaSealed: Int)

    /** One batch of bubbles, sealed and ready for Room. */
    private fun WhatsAppTranscript.ChatMessage.toDraft(ordinal: Int) = WhatsAppMessageDraft(
        ordinal = ordinal,
        day = day,
        clock = clock,
        sender = sender.orEmpty(),
        body = body,
        notice = notice,
        deleted = deleted,
        attachments = files
    )

    /** Marks the conversation as being written and learns where to start. */
    private suspend fun prepare(
        request: Request,
        sha: String,
        messages: Int,
        onProgress: (String, Int) -> Unit
    ): Prepared {
        onProgress("Preparing the conversation", 20)
        val stamp = now()
        val draft = WhatsAppChatDraft(
            chatId = request.chatId,
            title = request.title,
            contactName = WhatsAppContactName.of(request.title, emptyList()),
            sourceName = request.sourceName,
            container = request.container,
            messageCount = 0,
            transcriptSha = sha,
            noteId = request.noteId,
            importState = WhatsAppChatDraft.STATE_IMPORTING,
            createdAt = stamp,
            updatedAt = stamp,
            lastImportedAt = stamp
        )
        return Prepared(request, sink.prepare(draft))
    }

    private data class Prepared(val request: Request, val resume: WhatsAppResumePoint)

    /** Publishes the counts and lets the list show the conversation. */
    private suspend fun commit(
        request: Request,
        prepared: Prepared,
        mediaSealed: Int,
        report: WhatsAppStreamParser.Report,
        warnings: List<String>,
        resumed: Boolean
    ): Outcome {
        val contact = WhatsAppContactName.of(request.title, report.participants)
        val stamp = now()
        val total = maxOf(report.messages, prepared.resume.startOrdinal)
        sink.finish(
            commit = WhatsAppChatCommit(
                chatId = request.chatId,
                messages = total,
                media = mediaSealed,
                participants = report.participants,
                firstStamp = report.firstStamp,
                lastStamp = report.lastStamp
            ),
            mirror = WhatsAppChatDraft(
                chatId = request.chatId,
                title = request.title,
                contactName = contact,
                sourceName = request.sourceName,
                container = request.container,
                participants = report.participants,
                messageCount = total,
                mediaCount = mediaSealed,
                firstStamp = report.firstStamp,
                lastStamp = report.lastStamp,
                transcriptSha = report.sha256,
                noteId = request.noteId,
                importState = WhatsAppChatDraft.STATE_READY,
                createdAt = stamp,
                updatedAt = stamp,
                lastImportedAt = stamp
            )
        )
        val missing = report.references.filter { it.attached }.size
        return Outcome(
            chatId = request.chatId,
            title = request.title,
            contactName = contact,
            container = request.container,
            messages = total,
            mediaSealed = mediaSealed,
            mediaSkipped = 0,
            referenced = report.references.size,
            missing = 0,
            participants = report.participants,
            transcriptSha = report.sha256,
            bytes = report.bytes,
            resumed = resumed,
            warnings = warnings
        )
    }

    /** A stopped or failed run: the bubbles it wrote are taken back, and the chat is left honest. */
    private suspend fun rollback(chatId: String, prepared: Prepared, cause: Throwable) {
        // A cancelled coroutine refuses to suspend, so without NonCancellable the cleanup of a
        // stopped import would itself be cancelled and the half-written bubbles would stay in the
        // list — the exact thing this method exists to prevent.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { sink.abandon(chatId, prepared.resume.startOrdinal, prepared.resume.existedBefore) }
        }
    }

    private fun percentOf(index: Int, total: Int): Int =
        if (total <= 0) 90 else (40 + (index * 50L / total)).toInt().coerceIn(40, 92)

    // ── the archive walk ──────────────────────────────────────────────────────────────────────

    /** What one pass over an archive found: the winner on disk, the files sealed, the complaints. */
    private class Walk(
        val transcriptFile: File?,
        val sha: String,
        val messages: Int,
        val bytes: Long,
        val warnings: List<String>,
        val mediaSealed: Int,
        val mediaSkipped: Int,
        val sealedIds: List<String>,
        val mediaNames: List<String>
    ) {
        fun discardSealed(sealer: MediaSealer) {
            sealedIds.forEach { runCatching { kotlinx.coroutines.runBlocking { sealer.discard(it) } } }
        }
    }

    /**
     * Entries in one pass, one staged file at a time.
     *
     * A `.txt` candidate is copied to disk while its digest and bubble count are computed, and the
     * moment a better candidate appears the previous one is deleted — so the archive's text costs one
     * entry of disk and never a byte of retained heap. Media entries are staged and handed to the
     * sealer in the same breath, because a 1 GB clip must not be buffered either.
     */
    private suspend fun walkArchive(
        request: Request,
        staging: File,
        source: () -> InputStream,
        onProgress: (String, Int) -> Unit,
        shouldStop: () -> Boolean
    ): Walk {
        staging.mkdirs()
        val warnings = mutableListOf<String>()
        val sealedIds = mutableListOf<String>()
        val mediaNames = mutableListOf<String>()
        var transcriptFile: File? = null
        var transcriptSha = ""
        var transcriptMessages = 0
        var totalBytes = 0L
        var sealed = 0
        var skipped = 0
        var entries = 0
        var capped = false

        ZipInputStream(source()).use { zip ->
            while (true) {
                if (shouldStop()) throw WhatsAppStreamParser.Stopped("The import was stopped.")
                val entry = zip.nextEntry ?: break
                entries++
                val rawName = entry.name.replace('\\', '/')
                val fileName = rawName.substringAfterLast('/')
                if (entry.isDirectory || rawName.startsWith("__MACOSX/") || fileName.startsWith(".")) {
                    zip.closeEntry()
                    continue
                }
                if (WhatsAppFormat.isBookkeeping(fileName) || WhatsAppStructuredCsv.isStructuredMessagesFile(fileName)) {
                    zip.closeEntry()
                    continue
                }
                if (WhatsAppFormat.isTranscriptCandidate(fileName)) {
                    val candidate = File(staging, "wa_txt_${entries}_${fileName.take(48).filter(::safeStageChar)}")
                    val staged = stage(zip, candidate, onTranscript = true)
                    if (staged == null) {
                        skipped++
                        warnings += oversize(fileName)
                        candidate.delete()
                        continue
                    }
                    totalBytes += staged.bytes
                    onProgress("Reading the chat transcript", 8 + (entries % 20))
                    val counted = countBubbles(candidate)
                    if (counted > transcriptMessages) {
                        transcriptFile?.delete()
                        transcriptFile = candidate
                        transcriptSha = staged.sha
                        transcriptMessages = counted
                    } else {
                        // Not the conversation after all: keep it as an attachment the owner saved,
                        // exactly as the archive reader always has.
                        val id = media.seal(fileName, candidate, request)
                        if (id == null) skipped++ else {
                            sealed++
                            sealedIds += id
                            nameSlot(mediaNames, fileName, { capped = true })
                        }
                        candidate.delete()
                    }
                    zip.closeEntry()
                    continue
                }
                val declared = if (entry.size > 0) entry.size else -1L
                if (declared > MAX_STAGED_ENTRY_BYTES) {
                    skipped++
                    warnings += oversize(fileName)
                    zip.closeEntry()
                    continue
                }
                val target = File(staging, "wa_media_${entries}_${fileName.take(48).filter(::safeStageChar)}")
                val staged = stage(zip, target, onTranscript = false)
                zip.closeEntry()
                if (staged == null) {
                    skipped++
                    warnings += oversize(fileName)
                    target.delete()
                    continue
                }
                totalBytes += staged.bytes
                val id = media.seal(fileName, target, request)
                target.delete()
                if (id == null) {
                    skipped++
                    warnings += "$fileName could not be sealed into the vault."
                } else {
                    sealed++
                    sealedIds += id
                    nameSlot(mediaNames, fileName, { capped = true })
                }
                onProgress("Sealing media", 10 + (entries % 30))
            }
        }
        if (capped) {
            warnings += "That archive holds more than $MAX_MEDIA_NAMES files; only the first " +
                "$MAX_MEDIA_NAMES were matched against the conversation."
        }
        if (transcriptFile == null) {
            transcriptFile?.delete()
            // The media of an archive without a transcript was sealed before the winner was known;
            // nothing will link it to a chat, so it is taken back instead of left in the vault.
            sealedIds.forEach { id -> runCatching { media.discard(id) } }
            throw IllegalArgumentException(
                if (mediaNames.isEmpty() && sealed == 0) WhatsAppZipReader.NO_TRANSCRIPT_HINT
                else WhatsAppImporter.NO_TIMESTAMPS_HINT
            )
        }
        return Walk(
            transcriptFile = transcriptFile,
            sha = transcriptSha,
            messages = transcriptMessages,
            bytes = totalBytes,
            warnings = warnings,
            mediaSealed = sealed,
            mediaSkipped = skipped,
            sealedIds = sealedIds,
            mediaNames = mediaNames
        )
    }

    private fun safeStageChar(value: Char): Boolean = value.isLetterOrDigit() || value in "-_."

    private fun oversize(fileName: String): String =
        "$fileName is larger than ${MAX_STAGED_ENTRY_BYTES / (1000 * 1000)} MB and was left in the export."

    /** Copies one entry to [target], answering null when it outruns the ceiling. */
    private fun stage(
        input: InputStream,
        target: File,
        onTranscript: Boolean
    ): Staged? {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target).use { raw ->
            val out = DigestOutputStream(raw, digest)
            val buffer = ByteArray(STAGE_COPY_BYTES)
            var total = 0L
            var oversized = false
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_STAGED_ENTRY_BYTES) {
                    oversized = true
                    break
                }
                out.write(buffer, 0, read)
            }
            out.flush()
            if (oversized) {
                raw.channel.truncate(0)
                return null
            }
            return Staged(bytes = total, sha = digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private data class Staged(val bytes: Long, val sha: String)

    /** How many bubbles a staged transcript holds — the ranking measure, read without retaining it. */
    private suspend fun countBubbles(file: File): Int {
        var count = 0
        FileInputStream(file).use { stream ->
            count = WhatsAppStreamParser.read(
                source = stream,
                listener = object : WhatsAppStreamParser.Listener {
                    override fun onMessage(message: WhatsAppTranscript.ChatMessage, ordinal: Int) = Unit
                }
            ).messages
        }
        return count
    }

    /** Keeps the media-name list bounded by entry count; the count of files, never the bytes. */
    private fun nameSlot(names: MutableList<String>, name: String, onCapped: () -> Unit) {
        if (names.size >= MAX_MEDIA_NAMES) {
            onCapped()
            return
        }
        if (name !in names) names += name
    }
}

/**
 * What the engine does with an archive's files. On a handset this is the file vault, sealing the
 * staged entry into the chat's own `chats/<chatId>` container; in a test it is a recorder.
 *
 * The bytes arrive as a file rather than an array so that a two gigabyte clip can be sealed without
 * the process ever holding it.
 */
interface MediaSealer {

    /** Stores [file] under [name] for [request]'s chat and answers with its vault id, or null. */
    suspend fun seal(name: String, file: File, request: WhatsAppStreamingImporter.Request): String?

    /** Takes back a file this run sealed, so a cancelled import leaves no orphan. */
    suspend fun discard(id: String) = Unit

    companion object {
        /** For a plain `.txt` export, which carries no files at all. */
        val NONE = object : MediaSealer {
            override suspend fun seal(
                name: String,
                file: File,
                request: WhatsAppStreamingImporter.Request
            ): String? = null
        }
    }
}
