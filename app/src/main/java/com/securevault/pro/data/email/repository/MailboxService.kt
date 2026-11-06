package com.securevault.pro.data.email.repository

import android.content.Context
import com.securevault.pro.data.email.EncodedWords
import com.securevault.pro.data.email.ImapBodyStructure
import com.securevault.pro.data.email.ImapEngine
import com.securevault.pro.data.email.ImapFetchItem
import com.securevault.pro.data.email.ImapFolderListing
import com.securevault.pro.data.email.ImapResponseReader
import com.securevault.pro.data.email.MailAddresses
import com.securevault.pro.data.email.MailAttachment
import com.securevault.pro.data.email.MailDraft
import com.securevault.pro.data.email.MailFolder
import com.securevault.pro.data.email.MailMessage
import com.securevault.pro.data.email.MailServerConfig
import com.securevault.pro.data.email.MailTimings
import com.securevault.pro.data.email.MailValidationResult
import com.securevault.pro.data.email.MimeTypes
import com.securevault.pro.data.email.SmtpMessageBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the mail screens can ask a server for.
 *
 * One connection per call, always closed on the way out, always off the main thread, always inside a
 * [MailStep] budget, and always answered with a [MailOutcome]. That shape is deliberate: a pooled
 * IMAP session is the quickest way to build a mail client that freezes a screen an hour later, and
 * this module exists because a stalled server must never be able to hang the vault. A call that opens
 * a socket pays one handshake; the message list then batches its FETCHes inside that one connection,
 * which is where round trips actually matter.
 *
 * Failure is decided once, here. A credential that cannot be minted, a folder that is not there, a
 * password this device cannot decrypt — each throws [MailFlowException] carrying a sentence the owner
 * reads unchanged, so a half-finished operation is never reported as an empty list.
 *
 * A read that fails on a broken connection is retried once, on a fresh connection, because mobile
 * radio handovers drop sockets constantly and the owner has no way to tell that from a server that
 * refuses them. Writes are never retried: a send or a delete that timed out may already have reached
 * the server, and repeating it would do the thing twice. A Stop press is never retried either — a
 * cancellation is the owner's decision, not a failure.
 */
interface MailboxService {

    /** The bounded two-server probe the setup screen runs before anything is saved. */
    suspend fun validate(
        record: MailAccountRecord,
        typedSecret: String? = null
    ): MailOutcome<MailValidationResult>

    /** `LIST "" "*"`, with the role folders' counts filled in when the server will say. */
    suspend fun listFolders(record: MailAccountRecord): MailOutcome<List<MailFolder>>

    /** The newest [limit] messages of one folder, newest first, snippets included. */
    suspend fun listMessages(
        record: MailAccountRecord,
        folderWireName: String,
        limit: Int = DEFAULT_LIST_SIZE
    ): MailOutcome<List<MailMessage>>

    /** One message: headers, readable body, and the handles its parts can be fetched with. */
    suspend fun readMessage(
        record: MailAccountRecord,
        folderWireName: String,
        uid: Long
    ): MailOutcome<MailOpenedMessage>

    /** One attachment's bytes into the app cache. Streams; a 25 MB part costs one chunk of heap. */
    suspend fun downloadPart(handle: MailPartHandle): MailOutcome<MailAttachment>

    /** `UID SEARCH` over one folder. The query is quoted, so a stray `*` cannot widen it. */
    suspend fun searchMessages(
        record: MailAccountRecord,
        folderWireName: String,
        query: String,
        limit: Int = DEFAULT_LIST_SIZE
    ): MailOutcome<List<MailMessage>>

    /** Send one draft; reports who accepted it and, separately, who did not. */
    suspend fun send(
        record: MailAccountRecord,
        draft: MailDraft,
        threading: MailThreading = MailThreading(),
        typedSecret: String? = null
    ): MailOutcome<MailSendResult>

    /**
     * `\Seen` written for real — every list and reader fetch uses `BODY.PEEK`, so nothing is marked
     * read by accident.
     */
    suspend fun markSeen(
        record: MailAccountRecord,
        folderWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean>

    /**
     * `\Flagged` written or removed for real.
     *
     * Clearing a star sends `-FLAGS (\Flagged)`, which takes exactly one flag away and cannot touch
     * any other. [currentFlags] is therefore not needed and is ignored; it stays in the signature
     * because the screens already call this with it.
     */
    suspend fun setFlagged(
        record: MailAccountRecord,
        folderWireName: String,
        uid: Long,
        flagged: Boolean,
        currentFlags: List<String> = emptyList()
    ): MailOutcome<Boolean>

    /** `UID COPY`, then the selected messages only are marked deleted and removed. */
    suspend fun moveMessages(
        record: MailAccountRecord,
        fromWireName: String,
        toWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean>

    /**
     * `\Deleted` + `UID EXPUNGE` for the messages named.
     *
     * This is the only destruction in the module and it takes one deliberate button press: nothing
     * here ages, expires, auto-trashes or reclaims anything on a clock, and a message left in a folder
     * stays exactly where it is until the owner moves or deletes it.
     */
    suspend fun deleteMessages(
        record: MailAccountRecord,
        folderWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean>

    /** Where fetched bodies and attachments are written: the vault's own cache directory. */
    fun cacheDirectory(): File

    companion object {
        /** Rows a folder screen asks for in one pass; beyond that the owner refreshes. */
        const val DEFAULT_LIST_SIZE = 50
    }
}

/** One message as the reader screen needs it: the row, readable text, and its parts. */
data class MailOpenedMessage(
    val message: MailMessage,
    val body: String,
    val bodyWasHtml: Boolean,
    val parts: List<MailPartHandle>,
    val security: String,
    val truncated: Boolean
) {
    val attachments: List<MailAttachment> get() = parts.map { it.attachment }
    val attachmentCount: Int get() = parts.count { it.attachment.inline.not() }
}

/**
 * What a part needs to be fetched again: which folder, which UID, which MIME section, and how that
 * section was transfer-encoded. [attachment] is the row the screen lists.
 */
data class MailPartHandle(
    val accountId: String,
    val folderWireName: String,
    val uid: Long,
    val section: String,
    val transferEncoding: String,
    val attachment: MailAttachment
) {
    val fileName: String get() = attachment.fileName
    val sizeBytes: Long get() = attachment.sizeBytes
    val saved: Boolean get() = attachment.cachePath.isNotBlank() && File(attachment.cachePath).isFile
}

/** What a send did. A partial refusal is reported, never folded into a generic failure. */
data class MailSendResult(
    val accepted: List<String>,
    val refused: List<String>,
    val bytesAnnounced: Long,
    val serverSaid: String
) {
    val partiallyAccepted: Boolean get() = refused.isNotEmpty() && accepted.isNotEmpty()
    val message: String
        get() = when {
            refused.isEmpty() -> "Sent to ${accepted.size} recipient${if (accepted.size == 1) "" else "s"}."
            accepted.isNotEmpty() ->
                "Sent, but the server refused ${refused.size} of them: ${refused.joinToString("; ")}"
            else -> "The server accepted none of the recipients."
        }
}

@Singleton
class DefaultMailboxService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: MailAccountStore,
    private val gmail: GmailTokenProvider
) : MailboxService {

    private val cacheRoot: File by lazy { File(context.cacheDir, "mail").apply { mkdirs() } }

    override fun cacheDirectory(): File = cacheRoot

    // ------------------------------------------------------------------ setup probe

    override suspend fun validate(
        record: MailAccountRecord,
        typedSecret: String?
    ): MailOutcome<MailValidationResult> {
        val config = record.account.config
        val problems = config.validate()
        if (problems.isNotEmpty()) return MailOutcome.problem(problems.joinToString(" "))
        val credential = resolveCredential(record, typedSecret)
        return mailCall(MailStep.VALIDATE) {
            withContext(Dispatchers.IO) {
                var listing: List<ImapFolderListing> = emptyList()
                val incoming = runCatching {
                    val session = MailSessions.openImap(config.incoming, credential, scratchDir(record.id, "imap"))
                    try {
                        listing = session.engine.list()
                        "${config.incoming.describe()} · ${session.security}"
                    } finally {
                        session.close()
                    }
                }
                val outgoing = runCatching {
                    val session = MailSessions.openSmtp(config.outgoing, credential, ehloNameFor(config, record))
                    try {
                        "${config.outgoing.describe()} · ${session.security}"
                    } finally {
                        session.close()
                    }
                }
                MailValidationResult(
                    ok = incoming.isSuccess && outgoing.isSuccess,
                    incomingReport = incoming.getOrElse { MailFailures.describe(it, MailStep.VALIDATE) },
                    outgoingReport = outgoing.getOrElse { MailFailures.describe(it, MailStep.VALIDATE) },
                    folderCount = listing.count { MailFolderNames.isSelectable(it.flags) },
                    reason = listOfNotNull(incoming.exceptionOrNull(), outgoing.exceptionOrNull())
                        .firstOrNull()
                        ?.let { MailFailures.describe(it, MailStep.VALIDATE) }
                        .orEmpty()
                )
            }
        }
    }

    // ------------------------------------------------------------------ folders

    override suspend fun listFolders(record: MailAccountRecord): MailOutcome<List<MailFolder>> =
        mailCall(MailStep.FOLDERS) {
            withContext(Dispatchers.IO) {
                val session = openImap(record, null)
                try {
                    session.engine.list()
                        .map { MailFolderNames.toMailFolder(record.id, it) }
                        .filter { MailFolderNames.isSelectable(it.flags) }
                        .map { folder -> withCounts(session.engine, folder) }
                        .sortedWith(MailFolderNames.comparator())
                } finally {
                    session.close()
                }
            }
        }

    /** `EXAMINE` rather than `SELECT`, so reading a count never marks anything as read. */
    private fun withCounts(engine: ImapEngine, folder: MailFolder): MailFolder {
        val role = MailFolderNames.roleOf(folder.wireName, folder.name, folder.flags)
        if (role == MailFolderRole.OTHER) return folder
        return runCatching {
            val status = engine.select(folder.wireName, readOnly = true)
            folder.copy(total = status.exists, unseen = status.unseen)
        }.getOrDefault(folder)
    }

    // ------------------------------------------------------------------ message list

    override suspend fun listMessages(
        record: MailAccountRecord,
        folderWireName: String,
        limit: Int
    ): MailOutcome<List<MailMessage>> = listCall(record, folderWireName, limit, criteria = "ALL")

    override suspend fun searchMessages(
        record: MailAccountRecord,
        folderWireName: String,
        query: String,
        limit: Int
    ): MailOutcome<List<MailMessage>> {
        val term = query.trim()
        if (term.isEmpty()) return MailOutcome.ready(emptyList())
        // One quoted string: a `*`, a `)` or a newline typed by the owner cannot reshape the search.
        val safe = term.replace("\r", " ").replace("\n", " ").take(120)
        return listCall(record, folderWireName, limit, criteria = "TEXT ${ImapEngine.quote(safe)}")
    }

    private suspend fun listCall(
        record: MailAccountRecord,
        folderWireName: String,
        limit: Int,
        criteria: String
    ): MailOutcome<List<MailMessage>> = mailCall(MailStep.METADATA) {
        withContext(Dispatchers.IO) {
            val session = openImap(record, null)
            try {
                val engine = session.engine
                engine.select(folderWireName)
                val found = engine.uidSearch(criteria)
                if (found.isEmpty()) return@withContext emptyList()
                val wanted = found.takeLast(limit.coerceIn(1, 500))
                val rows = ArrayList<MailMessage>(wanted.size)
                // Newest UIDs first, in [MailTimings.METADATA_UID_BATCH] groups: a folder of 30 000
                // messages must not become one 30 000-UID FETCH.
                wanted.reversed()
                    .chunked(MailTimings.METADATA_UID_BATCH)
                    .forEach { batch -> rows += fetchRows(engine, record, folderWireName, batch.sorted()) }
                rows.distinctBy { it.uid }.sortedByDescending { it.receivedAt }
            } finally {
                session.close()
            }
        }
    }

    private fun fetchRows(
        engine: ImapEngine,
        record: MailAccountRecord,
        folderWireName: String,
        uids: List<Long>
    ): List<MailMessage> {
        if (uids.isEmpty()) return emptyList()
        val items = "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE " +
            "BODY[TEXT]<0.${MailTimings.SNIPPET_CHARS}>"
        return engine.uidFetch(uids.joinToString(","), items, silent = true)
            .mapNotNull { item -> rowOf(record, folderWireName, item) }
    }

    private fun rowOf(record: MailAccountRecord, folderWireName: String, item: ImapFetchItem): MailMessage? {
        val uid = item.uid.takeIf { it > 0L } ?: item.sequence.toLong()
        if (uid <= 0L) return null
        val envelope = item.envelope
        val leaves = MailParts.leaves(item.bodyStructure)
        val attachments = MailParts.attachments(leaves)
        val textPart = MailParts.preferredTextPart(leaves)
        val raw = MailSections.pick(item.sections, "TEXT")
        val decoded = if (raw == null || raw.isEmpty()) "" else MailPartDecoder.decode(
            raw,
            textPart?.encoding ?: "7BIT",
            textPart?.let(MailParts::charsetOf) ?: Charsets.UTF_8
        )
        val flat = if (textPart?.fullType == "TEXT/HTML") MailText.stripHtml(decoded) else decoded
        val originator = envelope?.originator()
        return MailMessage(
            id = MailMessageIds.of(record.id, folderWireName, uid),
            accountId = record.id,
            folderWireName = folderWireName,
            uid = uid,
            subject = EncodedWords.decode(envelope?.subject.orEmpty()).ifBlank { "(no subject)" },
            fromName = originator?.displayName.orEmpty(),
            fromAddress = originator?.email.orEmpty(),
            toSummary = envelope?.to?.joinToString(", ") { it.display() }.orEmpty(),
            receivedAt = item.internalDate
                .takeIf { it > 0L }
                ?: envelope?.dateEpochMillis().orZero(),
            sizeOctets = item.rfc822Size,
            seen = item.seen,
            flagged = item.flagged,
            hasAttachments = attachments.isNotEmpty(),
            attachmentCount = attachments.size,
            snippet = MailText.snippet(flat),
            messageId = envelope?.messageId.orEmpty(),
            inReplyTo = envelope?.inReplyTo.orEmpty(),
            fetchedAt = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------------ reader

    override suspend fun readMessage(
        record: MailAccountRecord,
        folderWireName: String,
        uid: Long
    ): MailOutcome<MailOpenedMessage> = mailCall(MailStep.OPEN) {
        withContext(Dispatchers.IO) {
            val session = openImap(record, null)
            try {
                val engine = session.engine
                engine.select(folderWireName)
                val meta = engine.uidFetch(
                    uid.toString(),
                    "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE",
                    silent = true
                ).firstOrNull() ?: throw MailFlowException(
                    "That message is no longer in ${MailFolderNames.displayOf(folderWireName)} on the " +
                        "server — it may have been moved in another mail app.",
                    MailFailureKind.PROTOCOL
                )
                val leaves = MailParts.leaves(meta.bodyStructure)
                val textPart = MailParts.preferredTextPart(leaves)
                val section = textPart?.section?.takeIf { it.isNotBlank() } ?: "TEXT"
                val fetched = engine.uidFetch(
                    uid.toString(),
                    "BODY[$section]",
                    silent = true,
                    sinkFactory = bigPartSink(uid)
                ).firstOrNull()
                val body = partBodyText(meta, fetched, textPart, section)
                val wasHtml = textPart?.fullType == "TEXT/HTML"
                val readable = MailText.preview(if (wasHtml) MailText.stripHtml(body) else body)
                MailOpenedMessage(
                    message = rowOf(record, folderWireName, meta) ?: missingRow(record, folderWireName, meta),
                    body = readable,
                    bodyWasHtml = wasHtml,
                    parts = MailParts.attachments(leaves).mapIndexed { index, part ->
                        handleOf(record.id, folderWireName, uid, index, part)
                    },
                    security = session.security,
                    truncated = body.length > MailTimings.BODY_PREVIEW_CHARS
                )
            } finally {
                session.close()
            }
        }
    }

    /** Sections over the streamed threshold go to a named cache file instead of the heap. */
    private fun bigPartSink(uid: Long): (section: String, declaredBytes: Long) -> ImapResponseReader.LiteralSink? =
        { section, declaredBytes ->
            if (declaredBytes < MailTimings.STREAMED_LITERAL_THRESHOLD_BYTES) null
            else FileSink(File(cacheRoot, "body-$uid-${MailText.safeFileName(section, "part")}.bin"))
        }

    /**
     * A part's text: from memory when it is small, from the cache file when the reader streamed it.
     *
     * A streamed body is read back only as far as [MailTimings.BODY_PREVIEW_CHARS] allows, so a 20 MB
     * plain-text message cannot be turned into a 20 MB String on the way to a composable.
     */
    private fun partBodyText(
        meta: ImapFetchItem,
        fetched: ImapFetchItem?,
        part: ImapBodyStructure?,
        section: String
    ): String {
        val charset = part?.let(MailParts::charsetOf) ?: Charsets.UTF_8
        val encoding = part?.encoding ?: "7BIT"
        val inline = fetched?.let { MailSections.pick(it.sections, section) }
            ?: MailSections.pick(meta.sections, section)
        if (inline != null && inline.isNotEmpty()) return MailPartDecoder.decode(inline, encoding, charset)
        val path = fetched?.let { MailSections.pickPath(it.streamedPaths, section) }
            ?: MailSections.pickPath(meta.streamedPaths, section)
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return ""
        val bytes = runCatching { readCapped(file, MailTimings.BODY_PREVIEW_CHARS.toLong() * 4L) }
            .getOrNull() ?: throw MailFlowException(
                "The message arrived but its text could not be read back from this device's cache. " +
                    "Close it and open it again.",
                MailFailureKind.UNKNOWN
            )
        return MailPartDecoder.decode(bytes, encoding, charset)
    }

    private fun readCapped(file: File, capBytes: Long): ByteArray {
        val cap = min(capBytes, file.length()).toInt().coerceAtLeast(0)
        val bytes = ByteArray(cap)
        var read = 0
        FileInputStream(file).use { input ->
            while (read < cap) {
                val step = input.read(bytes, read, cap - read)
                if (step <= 0) break
                read += step
            }
        }
        return if (read == cap) bytes else bytes.copyOf(read)
    }

    private fun missingRow(record: MailAccountRecord, folderWireName: String, item: ImapFetchItem): MailMessage =
        MailMessage(
            id = MailMessageIds.of(record.id, folderWireName, item.uid),
            accountId = record.id,
            folderWireName = folderWireName,
            uid = item.uid,
            subject = EncodedWords.decode(item.envelope?.subject.orEmpty()).ifBlank { "(no subject)" },
            fromName = item.envelope?.originator()?.displayName.orEmpty(),
            fromAddress = item.envelope?.originator()?.email.orEmpty(),
            toSummary = "",
            receivedAt = item.internalDate.orZero(),
            sizeOctets = item.rfc822Size,
            seen = item.seen,
            flagged = item.flagged,
            hasAttachments = false,
            attachmentCount = 0,
            snippet = "",
            fetchedAt = System.currentTimeMillis()
        )

    // ------------------------------------------------------------------ attachments

    override suspend fun downloadPart(handle: MailPartHandle): MailOutcome<MailAttachment> =
        mailCall(MailStep.DOWNLOAD) {
            withContext(Dispatchers.IO) {
                val record = store.find(handle.accountId) ?: throw MailFlowException(
                    "That mailbox is no longer configured on this device.",
                    MailFailureKind.CONFIG
                )
                val session = openImap(record, null)
                try {
                    val engine = session.engine
                    engine.select(handle.folderWireName)
                    val target = attachmentFile(record.id, handle.uid, handle.fileName)
                    val binary = session.supportsBinary
                    engine.uidFetch(
                        handle.uid.toString(),
                        if (binary) "BINARY[${handle.section}]" else "BODY[${handle.section}]",
                        silent = true,
                        sinkFactory = { _, _ -> FileSink(target) }
                    )
                    if (!target.isFile || target.length() <= 0L) throw MailFlowException(
                        "The server sent nothing for ${handle.fileName}, so nothing was saved.",
                        MailFailureKind.PROTOCOL
                    )
                    // `BINARY` arrives decoded; `BODY` arrives transfer-encoded and is decoded on disk.
                    val saved = if (binary) target else decodeOnDisk(target, handle.transferEncoding)
                    handle.attachment.copy(cachePath = saved.absolutePath, sizeBytes = saved.length())
                } finally {
                    session.close()
                }
            }
        }

    /**
     * Decode a downloaded part **on disk**.
     *
     * Base64 goes through `java.util.Base64`'s wrapping decoder, the only shape that turns a 25 MB
     * attachment into its real bytes without holding either in memory. Quoted-printable is decoded in
     * memory only inside the protocol layer's own inline cap; anything else — `7BIT`, `8BIT`,
     * `BINARY`, an absent header — already is raw bytes and is left exactly as written.
     */
    private fun decodeOnDisk(source: File, encoding: String): File {
        val normalised = encoding.trim().uppercase().replace("_", "-")
        if (normalised == "BASE64") {
            val decoded = File(source.parentFile, source.nameWithoutExtension + ".decoded")
            val ok = runCatching {
                FileInputStream(source).use { input ->
                    Base64.getDecoder().wrap(input).use { decoder ->
                        FileOutputStream(decoded).use { output ->
                            decoder.copyTo(output, MailTimings.STREAM_CHUNK_BYTES)
                        }
                    }
                }
                decoded.length() > 0L
            }.getOrDefault(false)
            if (!ok) {
                runCatching { decoded.delete() }
                return source
            }
            runCatching { source.delete() }
            return decoded
        }
        if (normalised == "QUOTED-PRINTABLE" && source.length() <= MailTimings.MAX_INLINE_LITERAL_BYTES) {
            val bytes = runCatching { MailPartDecoder.quotedPrintable(source.readBytes()) }.getOrNull()
                ?: return source
            runCatching { source.writeBytes(bytes) }
        }
        return source
    }

    private fun handleOf(
        accountId: String,
        folderWireName: String,
        uid: Long,
        index: Int,
        part: ImapBodyStructure
    ): MailPartHandle {
        val declared = part.fileName()
        val fallback = "part-${part.section.ifBlank { (index + 1).toString() }}"
        val name = MailText.safeFileName(declared, fallback)
        val mime = if (part.mediaType.isBlank() || part.mediaSubtype.isBlank()) {
            MimeTypes.forFileName(name)
        } else {
            "${part.mediaType.lowercase()}/${part.mediaSubtype.lowercase()}"
        }
        val messageId = MailMessageIds.of(accountId, folderWireName, uid)
        return MailPartHandle(
            accountId = accountId,
            folderWireName = folderWireName,
            uid = uid,
            section = part.section.ifBlank { (index + 1).toString() },
            transferEncoding = part.encoding,
            attachment = MailAttachment(
                id = "$messageId#$index",
                messageId = messageId,
                partIndex = index,
                fileName = name,
                mimeType = mime,
                sizeBytes = part.octetSize,
                cachePath = "",
                inline = part.dispositionType.equals("inline", ignoreCase = true) ||
                    (part.dispositionType.isBlank() && part.contentId.isNotBlank())
            )
        )
    }

    private fun attachmentFile(accountId: String, uid: Long, fileName: String): File {
        val dir = File(File(cacheRoot, "attachments"), "$accountId-$uid").apply { mkdirs() }
        return File(dir, MailText.safeFileName(fileName, "attachment-$uid"))
    }

    // ------------------------------------------------------------------ sending

    override suspend fun send(
        record: MailAccountRecord,
        draft: MailDraft,
        threading: MailThreading,
        typedSecret: String?
    ): MailOutcome<MailSendResult> {
        val recipients = MailAddresses.envelopeRecipients(draft.to, draft.cc)
        if (recipients.isEmpty()) {
            return MailOutcome.problem("Add at least one recipient before sending.")
        }
        val problems = record.account.config.validate()
        if (problems.isNotEmpty()) return MailOutcome.problem(problems.joinToString(" "))
        val credential = resolveCredential(record, typedSecret)
        return mailCall(MailStep.SEND) {
            withContext(Dispatchers.IO) {
                val builder = SmtpMessageBuilder(draft)
                val session = MailSessions.openSmtp(
                    record.account.config.outgoing,
                    credential,
                    ehloNameFor(record.account.config, record)
                )
                try {
                    val from = MailAddresses.addressOf(draft.fromAddress).ifBlank { record.address }
                    session.engine.mailFrom(from, builder.approximateSize())
                    val refused = session.engine.rcptTo(recipients)
                    val said = session.engine.dataFromSource(MailMessageSource(draft, threading, builder))
                    MailSendResult(
                        accepted = recipients.filterNot { it in refused },
                        refused = refused,
                        bytesAnnounced = builder.approximateSize(),
                        serverSaid = said.take(160)
                    )
                } finally {
                    session.close()
                }
            }
        }
    }

    // ------------------------------------------------------------------ flags and moves

    override suspend fun markSeen(
        record: MailAccountRecord,
        folderWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean> = writeCall(record, folderWireName) { engine ->
        uids.isEmpty() || engine.uidStore(uids, listOf("\\Seen"))
    }

    override suspend fun setFlagged(
        record: MailAccountRecord,
        folderWireName: String,
        uid: Long,
        flagged: Boolean,
        currentFlags: List<String>
    ): MailOutcome<Boolean> = writeCall(record, folderWireName) { engine ->
        if (flagged) return@writeCall engine.uidStore(listOf(uid), listOf("\\Flagged"))
        val kept = currentFlags.filterNot { it.equals("\\Flagged", true) || it.isBlank() }
        if (kept.isNotEmpty()) return@writeCall engine.uidStore(listOf(uid), kept, replace = true)
        // Nothing else is set on this message, and `uidStore` refuses an empty list rather than
        // sending `FLAGS ()`. Saying so beats silently leaving a star on the wrong message.
        throw MailFlowException(
            "That star could not be cleared: the message carries no other flag to write back, and " +
                "this app does not yet send an empty flag set.",
            MailFailureKind.PROTOCOL
        )
    }

    override suspend fun moveMessages(
        record: MailAccountRecord,
        fromWireName: String,
        toWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean> = writeCall(record, fromWireName) { engine ->
        if (uids.isEmpty()) return@writeCall true
        if (!engine.uidCopy(uids, toWireName)) throw MailFlowException(
            "The server would not copy the message to ${MailFolderNames.displayOf(toWireName)}, so " +
                "it stayed exactly where it was.",
            MailFailureKind.PROTOCOL
        )
        engine.uidDelete(uids, expunge = true)
    }

    override suspend fun deleteMessages(
        record: MailAccountRecord,
        folderWireName: String,
        uids: List<Long>
    ): MailOutcome<Boolean> = writeCall(record, folderWireName) { engine ->
        uids.isEmpty() || engine.uidDelete(uids, expunge = true)
    }

    private suspend fun writeCall(
        record: MailAccountRecord,
        folderWireName: String,
        block: (ImapEngine) -> Boolean
    ): MailOutcome<Boolean> = mailCall(MailStep.FLAG) {
        withContext(Dispatchers.IO) {
            val session = openImap(record, null)
            try {
                session.engine.select(folderWireName)
                block(session.engine)
            } finally {
                session.close()
            }
        }
    }

    // ------------------------------------------------------------------ credentials

    /**
     * The credential for one connection, minted or unsealed and never written anywhere.
     *
     * A Gmail token is requested here and nowhere else, bounded by [MailStep.TOKEN]; a password is
     * unsealed by the store, used by one `LOGIN`/`AUTH` and dropped when this returns. [typedSecret]
     * exists for the setup screen, which holds the owner's typed password before anything is stored.
     */
    private suspend fun resolveCredential(
        record: MailAccountRecord,
        typedSecret: String?
    ): MailCredential {
        if (record.usesOAuth) {
            val token = withContext(Dispatchers.IO) {
                MailDeadline.call(MailStep.TOKEN) { gmail.accessToken(record.address) }
            }
            return when (token) {
                is MailOutcome.Ready -> MailCredential.Xoauth2(record.loginName, token.value)
                is MailOutcome.Problem -> throw MailFlowException(token.message, token.kind)
            }
        }
        val secret = typedSecret ?: when (val opened = store.unlockSecret(record.id)) {
            is MailOutcome.Ready -> opened.value
            is MailOutcome.Problem -> throw MailFlowException(opened.message, opened.kind)
        }
        return MailCredential.Password(record.loginName, secret)
    }

    private suspend fun openImap(record: MailAccountRecord, typedSecret: String?): ImapSession =
        MailSessions.openImap(
            record.account.config.incoming,
            resolveCredential(record, typedSecret),
            scratchDir(record.id, "imap")
        )

    private fun scratchDir(accountId: String, kind: String): File =
        File(File(cacheRoot, kind), accountId).apply { mkdirs() }

    private fun ehloNameFor(config: MailServerConfig, record: MailAccountRecord): String =
        MailAddresses.domainOf(record.address)
            .ifBlank { config.outgoing.host }
            .ifBlank { "localhost" }
}

/**
 * A literal too big for memory, written to one file as it arrives.
 *
 * [ImapResponseReader.LiteralSink] hands over the same reused buffer every chunk, so this writes
 * straight through instead of copying; [path] is what the parser reports back in `WirePiece.Streamed`
 * and in `ImapFetchItem.streamedPaths`. The stream is closed on the first [path] call, because that
 * is the reader's own "this literal is finished" signal.
 */
class FileSink(private val target: File) : ImapResponseReader.LiteralSink {

    private var opened: FileOutputStream? = null

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        runCatching { target.parentFile?.mkdirs() }
        val stream = opened ?: FileOutputStream(target).also { opened = it }
        runCatching { stream.write(bytes, offset, length) }
    }

    override fun path(): String {
        runCatching { opened?.flush() }
        runCatching { opened?.close() }
        opened = null
        return target.absolutePath
    }
}

/** Message identity across accounts and folders: the same UID in two folders is not the same row. */
object MailMessageIds {
    fun of(accountId: String, folderWireName: String, uid: Long): String = "$accountId|$folderWireName|$uid"
    fun accountOf(id: String): String = id.substringBefore('|', "")
    fun folderOf(id: String): String = id.substringAfter('|', "").substringBefore('|')
    fun uidOf(id: String): Long = id.substringAfterLast('|', "").toLongOrNull() ?: 0L
}

/** A date the server never supplied is "unknown", sorted last — never a negative timestamp. */
private fun Long?.orZero(): Long = this?.takeIf { it > 0L } ?: 0L
