package com.securevault.pro.presentation.screens.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.backup.CloudOperation
import com.securevault.pro.data.backup.CloudSyncState
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.imports.ChatArchive
import com.securevault.pro.data.imports.SharedInbox
import com.securevault.pro.data.imports.StoredWhatsAppChat
import com.securevault.pro.data.imports.WhatsAppAccess
import com.securevault.pro.data.imports.WhatsAppArchive
import com.securevault.pro.data.imports.WhatsAppArchiver
import com.securevault.pro.data.imports.WhatsAppChat
import com.securevault.pro.data.imports.WhatsAppChatLink
import com.securevault.pro.data.imports.WhatsAppChatReader
import com.securevault.pro.data.imports.WhatsAppChatUnit
import com.securevault.pro.data.imports.WhatsAppPick
import com.securevault.pro.data.imports.WhatsAppStreamingImporter
import com.securevault.pro.data.imports.WhatsAppTranscript
import com.securevault.pro.data.imports.WhatsAppTree
import com.securevault.pro.data.remote.google.AutoSyncManager
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.DriveStorageQuota
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.queryDocumentSize
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class FileUploadState(
    val active: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val percent: Int = 0,
    val cloud: Boolean = false,
    val failed: Boolean = false
)

data class DriveStorageState(
    val quota: DriveStorageQuota? = null,
    val vaultBytes: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null
)

/** One importable file WhatsApp was found to own. [docId] addresses it inside the granted tree. */
data class WhatsAppDoc(
    val docId: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long
)

/** The archiver's file handle: the same facts, without the view-model type reaching into the data layer. */
private fun WhatsAppDoc.toArchiveDoc() = WhatsAppArchive.MediaDoc(name, docId, size, modifiedAt)

/**
 * A conversation with at least one export on this handset. WhatsApp re-exports into
 * "…(1).zip", so one chat legitimately owns several files and the newest is the one to read.
 */
data class WhatsAppChatRow(
    val key: String,
    val title: String,
    val docs: List<WhatsAppDoc>
) {
    val bytes: Long get() = docs.sumOf { it.size }
    val latest: WhatsAppDoc get() = docs.maxByOrNull { it.modifiedAt } ?: docs.first()
}

/** A type folder under `WhatsApp Media` — Images, Videos, Voice Notes, PDFs — and its files. */
data class WhatsAppMediaRow(val folder: String, val docs: List<WhatsAppDoc>) {
    val bytes: Long get() = docs.sumOf { it.size }
}

/** What the walk of the granted folder found, ready to be listed without another scan. */
data class WhatsAppHubState(
    val granted: Boolean = false,
    val folder: String = "",
    val app: String = "WhatsApp",
    val scanning: Boolean = false,
    val chats: List<WhatsAppChatRow> = emptyList(),
    val media: List<WhatsAppMediaRow> = emptyList(),
    val unreadable: Int = 0,
    val capped: Boolean = false,
    val error: String? = null
) {
    val chatFiles: Int get() = chats.sumOf { it.docs.size }
    val mediaFiles: Int get() = media.sumOf { it.docs.size }
    val foundAnything: Boolean get() = chats.isNotEmpty() || media.isNotEmpty()
}

/** The four one-tap imports the folder hub answers. */
enum class WhatsAppImport {
    /** Every transcript found in `WhatsApp Exports`, newest re-export of each included. */
    AllChats,

    /** One conversation, named by the row the user tapped — text plus whatever media its export carries. */
    OneChat,

    /** The files of one `WhatsApp Media` type folder, chosen by the user. */
    ChatFiles,

    /** All media folders too. */
    AllMedia,

    /** Chats and media together. */
    Everything
}

data class FilesUiState(
    val files: List<VaultFile> = emptyList(),
    val query: String = "",
    val category: String = "ALL",
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    val decoy: Boolean = false,
    val upload: FileUploadState = FileUploadState(),
    val drive: DriveStorageState = DriveStorageState(),
    val error: String? = null
) {
    val uploading: Boolean get() = upload.active
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val cloudSyncManager: CloudSyncManager,
    private val autoSyncManager: AutoSyncManager,
    private val whatsAppArchiver: WhatsAppArchiver,
    private val whatsAppStreaming: WhatsAppStreamingImporter,
    private val whatsAppChats: WhatsAppChatReader,
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val files = MutableStateFlow<List<VaultFile>>(emptyList())
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow("ALL")
    private val decoy = MutableStateFlow(sessionManager.isDecoy())
    private val upload = MutableStateFlow(FileUploadState())
    private val driveQuota = MutableStateFlow(DriveStorageState())
    private val error = MutableStateFlow<String?>(null)
    private val categories = listOf("ALL", "Docs", "Media", "Audio", "Scans")

    private val whatsAppHub = MutableStateFlow(WhatsAppHubState())

    /** The long job the Stop button cancels: an import, a hub archive, or a staged export. */
    private var runningJob: Job? = null

    /**
     * The note a finished import wrote, offered to the screen exactly once. Leaving the owner on
     * the picker after a chat they just sealed reads as "nothing happened" — the conversation is
     * the proof that it did.
     */
    private val openNoteId = MutableStateFlow<String?>(null)
    val openNote: StateFlow<String?> get() = openNoteId

    /** Called by the screen once it has navigated, so returning to Files does not jump again. */
    fun noteOpened() {
        openNoteId.value = null
    }

    /**
     * The conversation a finished streaming import wrote, offered to the screen exactly once.
     * A chat that lives in the conversation tables has no note to open, so it opens its own viewer.
     */
    private val openChatId = MutableStateFlow<String?>(null)
    val openChat: StateFlow<String?> get() = openChatId

    fun chatOpened() {
        openChatId.value = null
    }

    /** Every conversation the vault's own tables hold, newest import first in the list. */
    val storedChats: StateFlow<List<StoredWhatsAppChat>> = whatsAppChats.observeChats()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The scanned WhatsApp folder, listed in the dialog so the user taps a chat instead of hunting for it. */
    val whatsAppState: StateFlow<WhatsAppHubState> get() = whatsAppHub

    private var whatsAppScanning = false

    private data class LocalExtras(
        val decoy: Boolean,
        val upload: FileUploadState,
        val error: String?,
        val cloud: CloudSyncState,
        val drive: DriveStorageState
    )

    val state = combine(
        files,
        query,
        category,
        combine(
            decoy,
            upload,
            error,
            cloudSyncManager.state,
            driveQuota
        ) { d, u, e, c, drive -> LocalExtras(d, u, e, c, drive) }
    ) { allFiles, search, selected, extras ->
        val decoyMode = extras.decoy
        val source = if (decoyMode) decoyFiles() else allFiles
        val q = search.trim().lowercase()
        val counts = buildMap {
            put("ALL", source.size)
            categories.filter { it != "ALL" }.forEach { key ->
                put(key, source.count { it.category.equals(key, ignoreCase = true) })
            }
        }
        val filtered = source
            .asSequence()
            .filter { selected == "ALL" || it.category.equals(selected, ignoreCase = true) }
            .filter { q.isBlank() || it.name.lowercase().contains(q) || it.mimeType.lowercase().contains(q) }
            .sortedByDescending { it.uploadedAt }
            .toList()
        FilesUiState(
            files = filtered,
            query = search,
            category = selected,
            total = source.size,
            counts = counts,
            decoy = decoyMode,
            upload = liveUpload(extras.upload, extras.cloud),
            drive = extras.drive.copy(
                vaultBytes = source.fold(0L) { acc, file -> acc + file.size }
            ),
            error = extras.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FilesUiState()
    )

    init {
        refresh()
        refreshDriveStorage()
        // A share that arrived while the app was dead is only known once the queue is read back
        // from disk; the in-memory list alone would show an empty banner.
        SharedInbox.reload(context)
        viewModelScope.launch {
            // The queue can only be sealed inside the real vault, so it is sealed the moment the
            // owner unlocks instead of waiting for them to find a button.
            sessionManager.mode.collect { mode ->
                if (mode == SessionMode.REAL && SharedInbox.pending.value.isNotEmpty() && !upload.value.active) {
                    importPendingShares()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            files.value = repository.fileVault.listFiles()
            repository.refreshFileCount()
        }
    }

    fun refreshDriveStorage() {
        if (!cloudSyncManager.canAccessDrive()) {
            driveQuota.value = DriveStorageState(
                error = "Connect Google Drive to see the storage available in your account."
            )
            return
        }
        if (driveQuota.value.loading) return
        driveQuota.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = cloudSyncManager.driveStorageQuota()
            driveQuota.value = DriveStorageState(
                quota = result.getOrNull(),
                loading = false,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategory(value: String) {
        category.value = if (value in categories) value else "ALL"
    }

    fun clearError() {
        error.value = null
    }

    fun importFromUri(uri: Uri) {
        if (decoy.value) {
            error.value = "The decoy vault is read-only."
            return
        }
        if (upload.value.active) {
            // Never swallow a pick: the file was chosen, so silence reads as a broken button.
            error.value = "A transfer is already running. Wait for it to finish, then pick the file again."
            return
        }
        runningJob = viewModelScope.launch {
            val previousError = autoSyncManager.state.value.lastError
            upload.value = FileUploadState(
                active = true,
                title = "Reading file",
                detail = "Opening the selected document…",
                percent = 2
            )
            try {
                val name = queryDisplayName(context, uri, "vault_file")
                val declared = queryDocumentSize(context, uri)
                val mime = context.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val entry = withTimeout(GENERIC_IMPORT_TIMEOUT_MS) {
                    val staged = stageToCacheFile(uri, name, declared) { fraction ->
                        upload.update { current ->
                            current.copy(
                                title = "Reading file",
                                detail = name,
                                percent = (2 + fraction * 34).toInt().coerceIn(2, 36)
                            )
                        }
                    }
                    upload.value = FileUploadState(
                        active = true,
                        title = "Encrypting file",
                        detail = "AES-256-GCM · sealing into the local vault",
                        percent = 48
                    )
                    try {
                        val saved = repository.fileVault.saveStream(
                            source = staged,
                            displayName = name,
                            mimeType = mime,
                            category = repository.fileVault.guessCategory(name)
                        )
                        saved.exceptionOrNull()?.let { throw it }
                        saved.getOrThrow()
                    } finally {
                        staged.delete()
                    }
                }
                files.value = repository.fileVault.listFiles()
                repository.refreshFileCount()
                refreshDriveStorage()
                val uploadedAt = System.currentTimeMillis()
                val mirrored = entry.size <= FileVaultRepository.CLOUD_MIRROR_MAX_BYTES
                val drive = cloudSyncManager.canAccessDrive() && mirrored
                upload.value = FileUploadState(
                    active = true,
                    title = when {
                        !mirrored -> "Stored securely on this device"
                        drive -> "Uploading file to Google Drive…"
                        else -> "Stored securely on this device"
                    },
                    detail = when {
                        !mirrored -> {
                            "Files above ${
                                FileVaultRepository.CLOUD_MIRROR_MAX_BYTES / (1024 * 1024)
                            } MB stay in the on-device vault and are not embedded in the Drive mirror."
                        }
                        drive -> "File is uploading to the encrypted cloud vault"
                        else -> "Sign in to Google Drive to mirror files across devices"
                    },
                    percent = if (drive) 62 else 100,
                    cloud = drive
                )
                if (drive) {
                    awaitCloudSync(uploadedAt, previousError)
                } else {
                    delay(1_400)
                    upload.value = FileUploadState()
                }
            } catch (timedOut: TimeoutCancellationException) {
                // Before the cancellation branch on purpose: a timeout is a CancellationException,
                // and rethrowing it here would strand the overlay at "Reading file" forever.
                val message = "That file is still not sealed after ten minutes — the storage behind it " +
                    "is asleep or the transfer stalled. Try again with the file saved on this phone."
                error.value = message
                upload.value = FileUploadState(
                    active = true,
                    title = "Upload stopped",
                    detail = message,
                    percent = 100,
                    failed = true
                )
                delay(2_200)
                upload.value = FileUploadState()
            } catch (cancelled: CancellationException) {
                upload.value = FileUploadState()
                throw cancelled
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "The file could not be uploaded."
                error.value = message
                upload.value = FileUploadState(
                    active = true,
                    title = "Upload failed",
                    detail = message,
                    percent = 100,
                    failed = true
                )
                delay(1_800)
                upload.value = FileUploadState()
            }
        }
    }

    /**
     * Chats the share sheet handed over while the vault was closed. They stay in app storage
     * until this screen exists, because nothing can be sealed into an encrypted vault that is
     * locked — and the queue survives the process being killed mid-share.
     */
    val pendingShares: StateFlow<List<SharedInbox.Pending>> = SharedInbox.pending

    /** The last import's result in the owner's words: "1 chat · 312 messages · 14 photos". */
    private val _importSummary = MutableStateFlow<String?>(null)
    val importSummary: StateFlow<String?> = _importSummary.asStateFlow()

    fun clearImportSummary() {
        _importSummary.value = null
    }

    /** Seals every queued share now, whether it arrived before or after the vault opened. */
    fun importPendingShares() {
        val queued = pendingShares.value
        if (queued.isEmpty()) return
        if (sessionManager.mode.value != SessionMode.REAL) {
            // A real chat must never be written into the decoy vault, where it would be visible
            // to whoever is shown that space. The queue waits for the owner's real unlock.
            error.value = "Open your real vault to seal the chats waiting in the share inbox."
            return
        }
        if (upload.value.active) {
            error.value = "A transfer is already running. Wait for it to finish, then tap Seal shared chats again."
            return
        }
        SharedInbox.reload(context)
        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            drainInbox(SharedInbox.pending.value)
        }
    }

    private suspend fun drainInbox(queued: List<SharedInbox.Pending>) {
        var chats = 0
        var messages = 0
        var media = 0
        val complaints = mutableListOf<String>()
        queued.forEachIndexed { index, entry ->
            upload.value = FileUploadState(
                active = true,
                title = "Sealing the shared chat",
                detail = "${index + 1} of ${queued.size} · ${entry.name}",
                percent = ((index + 1) * 90L / queued.size).toInt().coerceIn(2, 96)
            )
            val staged = entry.staged?.let(::File)
            val outcome = try {
                when {
                    staged != null && staged.isFile -> importStagedGuarded(staged)
                    entry.uri != null -> importGuarded(Uri.parse(entry.uri))
                    else -> Result.failure(IllegalArgumentException(SharedInboxLostHint))
                }
            } catch (cancelled: CancellationException) {
                upload.value = FileUploadState()
                throw cancelled
            }
            // The row leaves the queue either way: a share that failed twice has to be re-sent,
            // and leaving it behind would nag the owner forever with a file that no longer exists.
            SharedInbox.complete(context, entry.id)
            outcome.fold(
                onSuccess = {
                    chats++
                    messages += it.messages
                    media += it.mediaSealed
                    complaints += it.warnings
                    openChatId.value = it.chatId
                },
                onFailure = { complaints += "${entry.name}: ${it.message ?: "could not be read"}" }
            )
        }
        files.value = repository.fileVault.listFiles()
        repository.refreshFileCount()
        upload.value = FileUploadState()
        _importSummary.value = if (chats > 0) {
            buildString {
                append("$chats chat${if (chats == 1) "" else "s"} imported · $messages messages")
                if (media > 0) append(" · $media photo${if (media == 1) "" else "s"} & file${if (media == 1) "" else "s"}")
            }
        } else {
            "Nothing from that share could be imported"
        }
        if (complaints.isNotEmpty()) error.value = summarize(complaints.toList())
    }

    private fun publishStage(stage: String, percent: Int) {
        upload.update {
            it.copy(
                title = "Sealing the shared chat",
                detail = stage,
                percent = percent.coerceIn(2, 99)
            )
        }
    }

    fun dismissPendingShares() {
        SharedInbox.clear(context)
        error.value = null
    }

    fun dismissPendingShare(id: String) {
        SharedInbox.clear(context, id)
    }

    /** A share whose copy never landed and whose URI expired — the only honest answer is to re-send. */
    private val SharedInboxLostHint =
        "That shared chat could no longer be read. Android drops the permission after a while, so " +
            "open the chat in WhatsApp → ⋮ → More → Export chat and share it to Secure Wallet again."

    private val ImportTimeoutHint =
        "That export is still unread after several minutes — the file is too large or the storage " +
            "behind it is asleep. Export the chat without media and try again."

    /**
     * One import, bounded by a deadline, through the streaming engine.
     *
     * The legacy importer is kept for the folder hub's archive path, but every file the owner picks
     * or shares goes through [WhatsAppStreamingImporter]: it reads a transcript of any length and
     * writes the conversation into the vault's tables one batch at a time, so neither the words nor
     * the archive's media are ever held in memory as one blob.
     */
    private suspend fun importGuarded(
        uri: Uri,
        onStage: (String, Int) -> Unit = { stage, percent -> publishStage(stage, percent) }
    ): Result<WhatsAppStreamingImporter.Outcome> {
        val name = queryDisplayName(context, uri, "whatsapp_export")
        return streamGuarded(name, { context.contentResolver.openInputStream(uri) }, onStage)
    }

    /** The same engine for a share whose bytes are already in app storage. */
    private suspend fun importStagedGuarded(file: File): Result<WhatsAppStreamingImporter.Outcome> {
        val name = file.name
        return streamGuarded(name, { java.io.FileInputStream(file) }) { stage, percent ->
            publishStage(stage, percent)
        }
    }

    private suspend fun streamGuarded(
        displayName: String,
        source: () -> java.io.InputStream?,
        onStage: (String, Int) -> Unit
    ): Result<WhatsAppStreamingImporter.Outcome> {
        val isArchive = displayName.lowercase().endsWith(".zip")
        val title = WhatsAppTranscript.titleFrom(if (isArchive) displayName.removeSuffix(".zip") else displayName)
        val request = WhatsAppStreamingImporter.Request(
            chatId = WhatsAppChatUnit.idFor(title, displayName),
            title = title,
            sourceName = displayName
        )
        val outcome = try {
            withTimeout(GENERIC_IMPORT_TIMEOUT_MS) {
                if (isArchive) {
                    whatsAppStreaming.importArchive(
                        request = request,
                        source = {
                            source() ?: throw IllegalStateException("The selected file could not be opened")
                        },
                        onProgress = onStage
                    )
                } else {
                    whatsAppStreaming.importTranscript(
                        request = request,
                        source = {
                            source() ?: throw IllegalStateException("The selected file could not be opened")
                        },
                        onProgress = onStage
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            return Result.failure(IllegalArgumentException(ImportTimeoutHint))
        }
        val thrown = outcome.exceptionOrNull()
        if (thrown is CancellationException) throw thrown
        return outcome
    }

    /** Stops a run that has clearly stalled: an escape is mandatory on every long path. */
    fun cancelRunningOperation() {
        runningJob?.cancel()
        runningJob = null
        upload.value = FileUploadState()
        error.value = "Stopped. Nothing half-written was kept — start the import again when ready."
    }

    fun importWhatsApp(uri: Uri) {
        if (decoy.value) {
            error.value = "The decoy vault is read-only."
            return
        }
        if (upload.value.active) {
            // Never swallow a pick: the file was chosen, so silence reads as a broken button.
            error.value = "A transfer is already running. Wait for it to finish, then pick the file again."
            return
        }
        runningJob = viewModelScope.launch {
            upload.value = FileUploadState(
                active = true,
                title = "Importing WhatsApp export",
                detail = "Reading the selected file",
                percent = 2
            )
            val result = try {
                importGuarded(uri) { stage, percent ->
                    upload.update {
                        it.copy(
                            title = "Importing WhatsApp export",
                            detail = stage,
                            percent = percent.coerceIn(2, 99)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                // Only a stop or a screen teardown reaches here; the deadline is turned into a
                // failed result inside importGuarded, so the owner always gets words on screen.
                upload.value = FileUploadState()
                throw cancelled
            }
            result.fold(
                onSuccess = { imported ->
                    files.value = repository.fileVault.listFiles()
                    repository.refreshFileCount()
                    val detail = buildString {
                        append("${imported.messages} messages")
                        if (imported.mediaSealed > 0) append(" · ${imported.mediaSealed} media sealed")
                        if (imported.mediaSkipped > 0) append(" · ${imported.mediaSkipped} skipped")
                    }
                    _importSummary.value = buildString {
                        append("1 chat imported · ${imported.messages} messages")
                        if (imported.mediaSealed > 0) {
                            append(" · ${imported.mediaSealed} photo${if (imported.mediaSealed == 1) "" else "s"} & file${if (imported.mediaSealed == 1) "" else "s"}")
                        }
                    }
                    upload.value = FileUploadState(
                        active = true,
                        title = "Chat sealed · ${imported.title}",
                        detail = detail,
                        percent = 100
                    )
                    if (imported.warnings.isNotEmpty()) {
                        // Every complaint, not just the first: a chat that skipped three videos
                        // and hit the total cap has three separate things the owner must know.
                        error.value = summarize(imported.warnings)
                    }
                    // The conversation is the point of the import, so the import ends there
                    // instead of leaving the owner on the picker with a toast they have to find.
                    openChatId.value = imported.chatId
                    upload.value = FileUploadState()
                },
                onFailure = { throwable ->
                    val message = throwable.message ?: "The WhatsApp export could not be imported."
                    error.value = message
                    upload.value = FileUploadState(
                        active = true,
                        title = "Import failed",
                        detail = message,
                        percent = 100,
                        failed = true
                    )
                    delay(2_000)
                    upload.value = FileUploadState()
                }
            )
        }
    }

    /**
     * The hub behind the three things the manual picker could never do: archive every exported
     * chat at once, archive one named chat with its files, and keep a folder of WhatsApp's files.
     * It reads the granted folder rather than asking the user to select files, so a tap means
     * "all of them".
     */
    fun importFromWhatsAppHub(mode: WhatsAppImport, selector: String = "") {
        if (decoy.value) {
            error.value = "The decoy vault is read-only."
            return
        }
        if (upload.value.active) {
            // Never swallow a pick: the file was chosen, so silence reads as a broken button.
            error.value = "A transfer is already running. Wait for it to finish, then pick the file again."
            return
        }
        val grant = WhatsAppAccess.current(context)
        if (grant == null) {
            error.value = "Secure Wallet needs the WhatsApp folder once — tap “Allow WhatsApp folder” below."
            refreshWhatsAppHub()
            return
        }
        val hub = whatsAppHub.value
        val chatRows = when (mode) {
            WhatsAppImport.AllChats, WhatsAppImport.Everything -> hub.chats
            WhatsAppImport.OneChat -> hub.chats.filter {
                it.key == WhatsAppTree.searchKey(selector) || WhatsAppTree.matchesChat(it.title, selector)
            }

            WhatsAppImport.ChatFiles, WhatsAppImport.AllMedia -> emptyList()
        }
        val mediaRows = when (mode) {
            WhatsAppImport.ChatFiles -> hub.media.filter { WhatsAppTree.folderMatchesQuery(it.folder, selector) }
            WhatsAppImport.Everything -> hub.media
            WhatsAppImport.AllMedia -> hub.media
            WhatsAppImport.OneChat, WhatsAppImport.AllChats -> emptyList()
        }
        if (chatRows.isEmpty() && mediaRows.isEmpty()) {
            error.value = if (mode == WhatsAppImport.OneChat || mode == WhatsAppImport.ChatFiles) {
                "Nothing in ${hub.folder} matches “$selector”. WhatsApp only ever writes a folder " +
                    "when you export: open that chat → ⋮ → More → Export chat → Without media, then " +
                    "refresh this list. The live conversation itself sits in WhatsApp's private " +
                    "storage, which no app but WhatsApp can read."
            } else {
                "No exported chats or media files in ${hub.folder} yet. In WhatsApp open a chat → " +
                    "⋮ → More → Export chat → Without media → Share → Secure Wallet Pro, which needs " +
                    "no folder at all — or export it there, tap the refresh arrow, and this list " +
                    "fills in."
            }
            return
        }
        runningJob = viewModelScope.launch {
            archiveFromHub(
                heading = when (mode) {
                    WhatsAppImport.AllChats -> "Archiving every chat"
                    WhatsAppImport.OneChat -> "Archiving the chat"
                    WhatsAppImport.ChatFiles -> "Archiving WhatsApp files"
                    WhatsAppImport.AllMedia -> "Archiving WhatsApp media"
                    WhatsAppImport.Everything -> "Archiving all of WhatsApp"
                },
                chatRows = chatRows,
                mediaRows = mediaRows,
                treeUri = grant.treeUri,
                folderMedia = hub.media.flatMap { it.docs }.map { it.toArchiveDoc() }
            )
        }
    }

    /**
     * The chat-centric path behind the folder hub: one tap hands over whole conversations — the
     * words, plus every file the transcript names that this phone still holds — sealed into one
     * note each, and the media folders the user chose on their own.
     *
     * Only the newest export of a chat is read. WhatsApp re-exports the same conversation into
     * "… (1).zip" with the older messages at the front, so archiving each copy would write the
     * same note three times and seal the same photos again.
     */
    private suspend fun archiveFromHub(
        heading: String,
        chatRows: List<WhatsAppChatRow>,
        mediaRows: List<WhatsAppMediaRow>,
        treeUri: Uri,
        folderMedia: List<WhatsAppArchive.MediaDoc>
    ) {
        val startedAt = System.currentTimeMillis()
        val previousError = autoSyncManager.state.value.lastError
        val total = chatRows.size + mediaRows.size
        val chatResults = mutableListOf<ChatArchive>()
        val mediaResults = mutableListOf<ChatArchive>()
        val complaints = mutableListOf<String>()
        var step = 0

        chatRows.forEach { row ->
            val base = stepBase(step, total)
            val export = row.latest
            val stage = "${step + 1} of $total · ${row.title}"
            upload.value = FileUploadState(active = true, title = heading, detail = stage, percent = base)
            bounded(row.title, HUB_ITEM_TIMEOUT_MS) {
                whatsAppArchiver.archive(
                    treeUri = treeUri,
                    chat = row.title,
                    sourceDocId = export.docId,
                    sourceName = export.name,
                    sourceSize = export.size,
                    sourceModifiedAt = export.modifiedAt,
                    sourcePath = export.docId.removePrefix("primary:"),
                    folderMedia = folderMedia,
                    onProgress = { inside, percent ->
                        upload.update { it.copy(detail = "$stage · $inside", percent = blend(base, percent)) }
                    }
                )
            }.fold(
                onSuccess = {
                    chatResults += it
                    complaints += it.warnings
                },
                onFailure = { complaints += "${row.title}: ${it.message ?: "could not be archived"}" }
            )
            step++
        }

        mediaRows.forEach { folder ->
            val base = stepBase(step, total)
            val title = mediaNoteTitle(folder.folder)
            val stage = "${step + 1} of $total · $title"
            upload.value = FileUploadState(active = true, title = heading, detail = stage, percent = base)
            bounded(title, HUB_ITEM_TIMEOUT_MS) {
                whatsAppArchiver.sealFiles(
                    treeUri = treeUri,
                    chat = title,
                    docs = folder.docs.map { it.toArchiveDoc() },
                    onProgress = { inside, percent ->
                        upload.update { it.copy(detail = "$stage · $inside", percent = blend(base, percent)) }
                    }
                )
            }.fold(
                onSuccess = {
                    mediaResults += it
                    complaints += it.warnings
                },
                onFailure = { complaints += "$title: ${it.message ?: "could not be sealed"}" }
            )
            step++
        }

        files.value = repository.fileVault.listFiles()
        repository.refreshFileCount()
        refreshDriveStorage()
        val sealed = (chatResults + mediaResults).sumOf { it.sealed.size }
        val reused = (chatResults + mediaResults).sumOf { it.alreadyInVault }
        val messages = chatResults.sumOf { it.messages }
        val missing = chatResults.sumOf { it.missing.size }
        val summary = listOfNotNull(
            if (chatResults.isNotEmpty()) {
                "${chatResults.size} chat${if (chatResults.size == 1) "" else "s"} · $messages messages"
            } else {
                null
            },
            if (mediaResults.isNotEmpty()) {
                "${mediaResults.size} folder${if (mediaResults.size == 1) "" else "s"} of files"
            } else {
                null
            },
            if (sealed > 0) "$sealed file${if (sealed == 1) "" else "s"} sealed" else null,
            if (reused > 0) "$reused already in the vault" else null,
            if (missing > 0) "$missing named but not on this phone" else null
        ).joinToString(" · ").ifBlank { "Nothing could be archived" }
        if (chatResults.isEmpty() && mediaResults.isEmpty()) {
            val message = complaints.firstOrNull() ?: "The WhatsApp folder gave up its files."
            error.value = message
            upload.value = FileUploadState(
                active = true,
                title = "Nothing archived",
                detail = message,
                percent = 100,
                failed = true
            )
            delay(2_600)
            upload.value = FileUploadState()
            return
        }
        upload.value = FileUploadState(
            active = true,
            title = if (chatResults.isEmpty()) "WhatsApp files sealed" else "Chat archive sealed",
            detail = summary,
            percent = 100
        )
        _importSummary.value = summary
        if (chatResults.size == 1) openNoteId.value = chatResults.single().noteId
        val mirrored = repository.fileVault.listFiles()
            .filter { it.createdAt >= startedAt }
            .any { it.size <= FileVaultRepository.CLOUD_MIRROR_MAX_BYTES }
        if (mirrored && cloudSyncManager.canAccessDrive()) {
            awaitCloudSync(startedAt, previousError)
        } else {
            delay(2_400)
            upload.value = FileUploadState()
        }
        if (complaints.isNotEmpty()) error.value = summarize(complaints)
    }

    /**
     * All of a run's complaints in the space one banner has: the first few in their own words,
     * the rest counted, so a partial import never looks like a clean one.
     */
    private fun summarize(warnings: List<String>): String = when {
        warnings.size <= 2 -> warnings.joinToString(" ")
        else -> warnings.take(2).joinToString(" ") + " (+${warnings.size - 2} more)"
    }

    /** Where the current item's own 0–100 sits inside the slice of the bar belonging to it. */
    private fun stepBase(index: Int, total: Int): Int =
        (2 + index * 88L / total.coerceAtLeast(1)).toInt().coerceIn(2, 94)

    /**
     * One unit of a batch, bounded by a deadline. Both the importer and the archiver wrap their
     * bodies in `runCatching`, so the cancellation a timeout raises comes back as a failed result
     * instead of flying out — it is put back on the throwing path here, or a stopped run would
     * invent a reason and then quietly start the next hundred files.
     */
    private suspend fun <T> bounded(
        item: String,
        timeoutMs: Long,
        work: suspend () -> Result<T>
    ): Result<T> = try {
        val outcome = withTimeout(timeoutMs) { work() }
        val failure = outcome.exceptionOrNull()
        if (failure is CancellationException) throw failure
        outcome
    } catch (timedOut: TimeoutCancellationException) {
        Result.failure(
            IllegalArgumentException(
                "$item took longer than ${timeoutMs / 60_000} minute(s) and was stopped. Export that " +
                    "chat without media, or copy the file into Downloads and use \"Choose the chat file\"."
            )
        )
    }

    private fun blend(base: Int, percent: Int): Int =
        (base + (96 - base) * percent.coerceIn(0, 100) / 100).coerceIn(base, 96)

    /** "Images" → "WhatsApp Images"; the type folders are not conversations, so they keep their own note. */
    private fun mediaNoteTitle(folder: String): String =
        if (folder.startsWith("WhatsApp", ignoreCase = true)) folder else "WhatsApp ${folder.ifBlank { "Media" }}"

    /** Grants the folder and reads it straight away, so the first tap already shows the chats. */
    fun attachWhatsAppFolder(treeUri: Uri?) {
        if (treeUri == null) return
        if (WhatsAppAccess.remember(context, treeUri) == null) {
            whatsAppHub.value = WhatsAppHubState(error = "That is not a folder Secure Wallet can read.")
            return
        }
        refreshWhatsAppHub()
    }

    fun forgetWhatsAppFolder() {
        WhatsAppAccess.forget(context)
        whatsAppHub.value = WhatsAppHubState()
    }

    /**
     * Reads the granted folder again. Safe to call on every dialog open: the permission is
     * checked rather than trusted, because the system can revoke it and a stale grant would
     * otherwise list chats that then fail one by one.
     */
    fun refreshWhatsAppHub() {
        if (whatsAppScanning) return
        val grant = WhatsAppAccess.current(context)
        if (grant == null) {
            val wasGranted = whatsAppHub.value.granted
            whatsAppHub.value = WhatsAppHubState(
                error = if (wasGranted) {
                    "Secure Wallet lost access to the WhatsApp folder. Allow it again."
                } else {
                    "Allow the WhatsApp folder once and every chat on this phone becomes importable."
                }
            )
            return
        }
        whatsAppScanning = true
        whatsAppHub.value = WhatsAppHubState(
            granted = true,
            folder = grant.label,
            app = WhatsAppChat.folderName(grant.packageId),
            scanning = true
        )
        runningJob = viewModelScope.launch {
            // Bounded, because a walk of a sleeping SD card or a cloud-backed provider never
            // finishes — and an empty spinner is the dead end this project refuses to ship.
            val found = try {
                withTimeout(HUB_SCAN_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { readWhatsAppTreeSafely(grant.treeUri) }
                }
            } catch (timedOut: TimeoutCancellationException) {
                null
            } catch (cancelled: CancellationException) {
                whatsAppScanning = false
                whatsAppHub.value = WhatsAppHubState(
                    granted = true,
                    folder = grant.label,
                    app = WhatsAppChat.folderName(grant.packageId),
                    error = "The folder scan was stopped."
                )
                throw cancelled
            }
            whatsAppScanning = false
            val identity = WhatsAppHubState(
                granted = true,
                folder = grant.label,
                app = WhatsAppChat.folderName(grant.packageId)
            )
            whatsAppHub.value = when {
                found == null -> identity.copy(
                    error = "That folder took too long to list and the scan was stopped. SD cards, " +
                        "USB drives and cloud folders do this — copy the chat export into " +
                        "Downloads on this phone and use \"Choose the chat file\" instead, which " +
                        "needs no folder at all."
                )

                found.refused -> identity.copy(
                    error = "That folder refused to be listed. Grant the whole internal storage " +
                        "folder instead — some OEM file managers hide app folders on their own."
                )

                found.scan.chats.isEmpty() && found.scan.media.isEmpty() -> identity.copy(
                    // Not a blank list: the reason, and the tap that fixes it.
                    error = "Nothing importable in ${grant.label} yet. This folder only ever holds " +
                        "what WhatsApp has written there, and chats are only written by " +
                        "⋮ → More → Export chat — the live conversation lives in WhatsApp's own " +
                        "private storage, which no app but WhatsApp can read. Export one chat, " +
                        "then scan again.",
                    unreadable = found.scan.unreadable,
                    capped = found.scan.capped
                )

                else -> identity.copy(
                    chats = found.scan.chats,
                    media = found.scan.media,
                    unreadable = found.scan.unreadable,
                    capped = found.scan.capped
                )
            }
        }
    }

    /** A walk that failed is reported, never thrown: a folder that cannot be read is not a crash. */
    private data class HubRead(val scan: WhatsAppScan, val refused: Boolean)

    private suspend fun readWhatsAppTreeSafely(treeUri: Uri): HubRead {
        val result = runCatching { readWhatsAppTree(treeUri) }
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
        return result.getOrNull()?.let { HubRead(it, false) } ?: HubRead(emptyScan(), true)
    }

    private fun emptyScan() = WhatsAppScan(emptyList(), emptyList(), 0, false)

    private class WhatsAppScan(
        val chats: List<WhatsAppChatRow>,
        val media: List<WhatsAppMediaRow>,
        val unreadable: Int,
        val capped: Boolean
    )

    /**
     * Breadth-first over the granted folder, keeping only what WhatsApp itself produced.
     *
     * Both caps exist because the walk runs inside a tap: a handset with 40 000 photos must not
     * hang the dialog, and the folders past the fourth level are not WhatsApp's layout anyway —
     * at that depth the user has granted something like the whole of internal storage.
     */
    private suspend fun readWhatsAppTree(treeUri: Uri): WhatsAppScan {
        val authority = treeUri.authority ?: return WhatsAppScan(emptyList(), emptyList(), 0, false)
        val rootDoc = DocumentsContract.getTreeDocumentId(treeUri)
        val exports = linkedMapOf<String, MutableList<WhatsAppDoc>>()
        val media = linkedMapOf<String, MutableList<WhatsAppDoc>>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootDoc to "")
        var folders = 0
        var filesSeen = 0
        var unreadable = 0
        var capped = false
        while (queue.isNotEmpty()) {
            // A blocking cursor read is not a suspension point, so the deadline is checked here
            // or a folder that never answers would outrun every timeout wrapped around it.
            coroutineContext[Job]?.ensureActive()
            if (folders >= WHATSAPP_FOLDER_CAP || filesSeen >= WHATSAPP_FILE_CAP) {
                capped = true
                break
            }
            val (docId, relative) = queue.removeFirst()
            folders++
            for (child in whatsAppChildren(authority, docId)) {
                val path = if (relative.isBlank()) child.name else "$relative/${child.name}"
                when (WhatsAppTree.classify(path, child.isDirectory)) {
                    WhatsAppTree.Kind.RootFolder,
                    WhatsAppTree.Kind.MediaFolder,
                    WhatsAppTree.Kind.ExportsFolder ->
                        if (path.count { it == '/' } < WhatsAppTree.MAX_DEPTH - 1) {
                            queue.add(child.docId to path)
                        }

                    WhatsAppTree.Kind.ChatExport -> {
                        filesSeen++
                        exports.getOrPut(WhatsAppTree.chatKey(child.name)) { mutableListOf() }.add(child.toDoc())
                    }

                    WhatsAppTree.Kind.MediaFile -> {
                        filesSeen++
                        media.getOrPut(whatsAppMediaGroup(path)) { mutableListOf() }.add(child.toDoc())
                    }

                    WhatsAppTree.Kind.Unreadable -> unreadable++
                    WhatsAppTree.Kind.Skip -> Unit
                }
            }
        }
        return WhatsAppScan(
            chats = exports.entries
                .map { (key, docs) ->
                    WhatsAppChatRow(
                        key = key,
                        title = WhatsAppTree.displayName(docs.maxBy { it.modifiedAt }.name),
                        docs = docs.sortedByDescending { it.modifiedAt }
                    )
                }
                .sortedBy { it.title.lowercase() },
            media = media.entries
                .map { (folder, docs) -> WhatsAppMediaRow(folder, docs.sortedByDescending { it.modifiedAt }) }
                .sortedByDescending { it.docs.size },
            unreadable = unreadable,
            capped = capped
        )
    }

    /** One directory's children. Any failure is an empty list: a folder that cannot be read is not a crash. */
    private fun whatsAppChildren(authority: String, parentDocId: String): List<WhatsAppChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocId)
        return runCatching {
            val rows = context.contentResolver.query(
                childrenUri,
                WHATSAPP_CHILD_PROJECTION,
                null,
                null,
                null
            )
            rows?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        val rawName = cursor.getString(1) ?: docId.substringAfterLast('/')
                        if (rawName.isBlank() || rawName == "." || rawName == "..") continue
                        add(
                            WhatsAppChild(
                                docId = docId,
                                name = rawName,
                                isDirectory = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                                size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                                modifiedAt = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** "WhatsApp Media/Voice Notes/PTT.aac" → "Voice Notes"; the type folder is what the user recognises. */
    private fun whatsAppMediaGroup(relativePath: String): String {
        val parts = relativePath.split('/')
        val index = parts.indexOf(WhatsAppTree.MEDIA_DIR)
        return if (index >= 0 && index + 1 < parts.size - 1) parts[index + 1] else WhatsAppTree.MEDIA_DIR
    }

    /**
     * One pass over a list of found files: exports go to the chat importer, everything else is
     * sealed as a vault file, and the overlay names the current item because a 400 MB video must
     * not look like a frozen app. The same engine serves the folder hub and the manual picker.
     */
    private suspend fun runWhatsAppBatch(jobs: List<WhatsAppJob>, heading: String) {
        val uploadedAt = System.currentTimeMillis()
        val previousError = autoSyncManager.state.value.lastError
        var chats = 0
        var messages = 0
        var sealedFiles = 0
        var unreadable = 0
        var chatNotes = listOf<String>()
        val complaints = mutableListOf<String>()
        // The transcript goes first, so each file picked beside it can be sealed into that chat's
        // own module while the batch runs instead of landing as an unlinked vault entry. Only a
        // selection with exactly one chat is linked: with several, a photo cannot be attributed.
        val ordered = jobs.sortedBy { if (it.pick == WhatsAppPick.Conversation) 0 else 1 }
        val singleChat = ordered.count { it.pick == WhatsAppPick.Conversation } == 1
        var chatTarget: Pair<String, String>? = null
        val alreadySealed = mutableMapOf<String, VaultFile>()
        val linked = mutableListOf<NoteAttachment>()
        ordered.forEachIndexed { index, job ->
            upload.value = FileUploadState(
                active = true,
                title = heading,
                detail = "${index + 1} of ${ordered.size} · ${job.name}",
                percent = ((index + 1) * 92L / ordered.size).toInt().coerceIn(2, 96)
            )
            when (job.pick) {
                WhatsAppPick.Ignored -> unreadable += 1
                WhatsAppPick.Conversation -> importGuarded(job.uri).fold(
                    onSuccess = {
                        chats += 1
                        messages += it.messages
                        sealedFiles += it.mediaSealed
                        chatNotes = chatNotes + it.chatId
                        complaints += it.warnings
                        if (singleChat) {
                            chatTarget = it.chatId to it.container
                            // A re-import of the same chat reuses the files already sealed into
                            // its module instead of copying every photo a second time.
                            alreadySealed.putAll(
                                repository.fileVault.listFiles()
                                    .filter { file -> file.noteId == it.chatId }
                                    .associateBy { file -> file.name.lowercase() }
                            )
                        }
                    },
                    onFailure = { complaints += "${job.name}: ${it.message ?: "unreadable export"}" }
                )

                WhatsAppPick.Media -> bounded(job.name, GENERIC_IMPORT_TIMEOUT_MS) {
                    val target = chatTarget
                    val reuse = target?.let { alreadySealed[job.name.lowercase()] }
                    when {
                        reuse != null -> Result.success(reuse)
                        target != null -> sealMedia(
                            uri = job.uri,
                            name = job.name,
                            noteId = target.first,
                            folder = target.second
                        )
                        else -> sealMedia(job.uri, job.name)
                    }
                }.fold(
                    onSuccess = { file ->
                        sealedFiles += 1
                        if (chatTarget != null) {
                            linked += NoteAttachment(
                                id = file.id,
                                name = file.name,
                                mimeType = file.mimeType,
                                size = file.size
                            )
                        }
                    },
                    onFailure = { complaints += "${job.name}: ${it.message ?: "could not be sealed"}" }
                )
            }
        }
        // The files are in the vault; linking their names to the conversation is what makes the
        // chat view draw them inside the bubbles that mentioned them.
        chatTarget?.let { target -> attachPickedFiles(target.first, linked) }
        files.value = repository.fileVault.listFiles()
        repository.refreshFileCount()
        refreshDriveStorage()
        val summary = buildString {
            if (chats > 0) append("$chats chat${if (chats == 1) "" else "s"} · $messages messages")
            if (sealedFiles > 0) {
                if (isNotEmpty()) append(" · ")
                append("$sealedFiles file${if (sealedFiles == 1) "" else "s"} sealed")
            }
            if (isEmpty()) append("Nothing importable in that selection")
            if (unreadable > 0) append(" · $unreadable backup database${if (unreadable == 1) "" else "s"} skipped")
        }
        if (chats == 0 && sealedFiles == 0) {
            error.value = if (unreadable > 0 && complaints.isEmpty()) {
                "Those are WhatsApp's encrypted message databases — no app but WhatsApp can open " +
                    "them. Export the chat inside WhatsApp instead."
            } else {
                complaints.firstOrNull() ?: "None of the selected files could be imported."
            }
            upload.value = FileUploadState(
                active = true,
                title = "Nothing imported",
                detail = error.value.orEmpty(),
                percent = 100,
                failed = true
            )
            delay(2_600)
            upload.value = FileUploadState()
            return
        }
        upload.value = FileUploadState(
            active = true,
            title = "WhatsApp import sealed",
            detail = summary,
            percent = 100
        )
        _importSummary.value = summary
        // One conversation in the selection means the owner wanted that conversation: open it.
        // Several means they wanted the whole set, where the Files list is the useful place to be.
        if (chatNotes.size == 1) openChatId.value = chatNotes.single()
        val mirrored = repository.fileVault.listFiles()
            .filter { it.createdAt >= uploadedAt }
            .any { it.size <= FileVaultRepository.CLOUD_MIRROR_MAX_BYTES }
        if (mirrored && cloudSyncManager.canAccessDrive()) {
            awaitCloudSync(uploadedAt, previousError)
        } else {
            delay(2_400)
            upload.value = FileUploadState()
        }
        if (complaints.isNotEmpty()) error.value = summarize(complaints)
    }

    /** A batch taken from the file picker instead of the folder walk: the picker cannot say which
     * folder a file came from, so the name and MIME type decide. Kept because it works even when
     * the user refuses the folder grant.
     */
    fun importWhatsAppPicks(uris: List<Uri>) {
        if (decoy.value) {
            error.value = "The decoy vault is read-only."
            return
        }
        if (uris.isEmpty()) return
        if (upload.value.active) {
            // Never swallow a pick: the files were chosen, so silence reads as a broken button.
            error.value = "A transfer is already running. Wait for it to finish, then pick them again."
            return
        }
        val jobs = uris.map { uri ->
            val name = queryDisplayName(context, uri, "whatsapp_item")
            WhatsAppJob(uri, name, WhatsAppChat.route(name, context.contentResolver.getType(uri)))
        }
        runningJob = viewModelScope.launch { runWhatsAppBatch(jobs, "Importing from WhatsApp") }
    }

    /**
     * One picked media document, staged then encrypted into the vault — no cloud waiting here.
     *
     * When [noteId] is named, the file is sealed into that chat's own module and linked to it, so
     * a photo picked beside a `.txt` export is the photo its bubble shows instead of an unlinked
     * entry in the Files list.
     */
    private suspend fun sealMedia(
        uri: Uri,
        name: String,
        noteId: String? = null,
        folder: String? = null
    ): Result<VaultFile> {
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return runCatching {
            val staged = stageToCacheFile(uri, name, queryDocumentSize(context, uri)) { }
            try {
                val saved = repository.fileVault.saveStream(
                    source = staged,
                    displayName = name,
                    mimeType = mime,
                    category = repository.fileVault.guessCategory(name),
                    noteId = noteId,
                    folder = folder
                )
                saved.exceptionOrNull()?.let { throw it }
                saved.getOrThrow()
            } finally {
                staged.delete()
            }
        }
    }

    /**
     * Writes the files picked beside a transcript onto that conversation's note. The chat view
     * matches a bubble's attachment name against the note's attachments, so this merge is the
     * difference between a real photo and "media not included in this export".
     *
     * [WhatsAppChatLink.merge] keeps one entry per file name, so picking the same chat again
     * updates the same metadata instead of growing the list.
     */
    private suspend fun attachPickedFiles(chatId: String, sealed: List<NoteAttachment>) {
        if (sealed.isEmpty()) return
        val note = runCatching { repository.getNote(chatId) }.getOrNull() ?: return
        val merged = WhatsAppChatLink.merge(note.attachments, sealed)
        if (merged.map { it.id } == note.attachments.map { it.id }) return
        runCatching { repository.saveNote(note.copy(attachments = merged)) }
    }

    private fun liveUpload(local: FileUploadState, cloud: CloudSyncState): FileUploadState {
        if (!local.active || local.failed || local.percent < 60) return local
        if (!cloud.active || cloud.operation != CloudOperation.BACKUP) return local
        val boosted = (62 + cloud.percent * 0.34f).toInt().coerceIn(62, 96)
        return local.copy(
            title = "Uploading file to Google Drive…",
            detail = cloud.detail.ifBlank { local.detail },
            percent = maxOf(local.percent, boosted),
            cloud = true
        )
    }

    private suspend fun awaitCloudSync(uploadedAt: Long, previousError: String?) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val sync = autoSyncManager.state.value
            val cloud = cloudSyncManager.state.value
            if (cloud.active && cloud.operation == CloudOperation.BACKUP) {
                val boosted = (62 + cloud.percent * 0.34f).toInt().coerceIn(62, 96)
                upload.update { it.copy(percent = maxOf(it.percent, boosted)) }
            }
            if (!sync.running && !sync.pending && sync.lastSyncedAt >= uploadedAt) {
                upload.value = FileUploadState(
                    active = true,
                    title = "Uploaded to Google Drive",
                    detail = "The encrypted cloud mirror is up to date",
                    percent = 100,
                    cloud = true
                )
                delay(1_000)
                upload.value = FileUploadState()
                return
            }
            if (!sync.running && sync.lastError != null && sync.lastError != previousError) {
                upload.value = FileUploadState(
                    active = true,
                    title = "Saved on this device",
                    detail = "Cloud sync will retry automatically",
                    percent = 100,
                    cloud = true,
                    failed = true
                )
                delay(2_200)
                upload.value = FileUploadState()
                return
            }
            delay(250)
        }
        upload.value = FileUploadState(
            active = true,
            title = "Saved securely",
            detail = "Cloud mirroring continues in the background",
            percent = 100,
            cloud = true
        )
        delay(1_600)
        upload.value = FileUploadState()
    }

    /**
     * Copies the picked document into app cache first. Vault files reach 2 GB, so the
     * bytes must never be buffered in memory on the way to the AES-GCM sealing step.
     */
    private suspend fun stageToCacheFile(
        uri: Uri,
        name: String,
        declared: Long,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val limit = FileVaultRepository.MAX_FILE_BYTES
        if (declared > limit) throw IllegalArgumentException("This file is larger than 2 GB")
        val staging = File(context.cacheDir, "vault_staging").apply { mkdirs() }
        staging.listFiles()?.forEach { it.delete() }
        val target = File(staging, "stage_${System.currentTimeMillis()}")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected file could not be opened")
        stream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                var lastPercent = -1
                while (true) {
                    // Every stop and every deadline lands here: reading is a blocking call, so
                    // without this check a stalled provider would run on after the owner left.
                    coroutineContext[Job]?.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) {
                        target.delete()
                        throw IllegalArgumentException("This file is larger than 2 GB")
                    }
                    output.write(buffer, 0, read)
                    val fraction = if (declared > 0) {
                        (total.toFloat() / declared).coerceIn(0f, 1f)
                    } else {
                        (total.toFloat() / limit).coerceIn(0f, 0.9f)
                    }
                    val percent = (fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(fraction)
                    }
                }
                output.flush()
            }
        }
        if (target.length() == 0L) {
            target.delete()
            throw IllegalArgumentException("That file is empty. ($name)")
        }
        onProgress(1f)
        target
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.fileVault.deleteFile(id)
            files.value = repository.fileVault.listFiles()
            repository.refreshFileCount()
        }
    }

    fun export(id: String, onReady: (File?) -> Unit) {
        viewModelScope.launch {
            onReady(repository.fileVault.exportToCache(id))
        }
    }

    private fun decoyFiles(): List<VaultFile> = emptyList()

    /** A row of the SAF children cursor for one directory of the granted tree. */
    private data class WhatsAppChild(
        val docId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long
    ) {
        fun toDoc() = WhatsAppDoc(docId, name, size, modifiedAt)
    }

    /** One file queued for the batch, with the importer it belongs to already decided. */
    private data class WhatsAppJob(val uri: Uri, val name: String, val pick: WhatsAppPick)

    private companion object {
        val WHATSAPP_CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        /** A phone with 40 000 photos must not freeze the dialog while it is being counted. */
        const val WHATSAPP_FILE_CAP = 4_000
        const val WHATSAPP_FOLDER_CAP = 200

        /**
         * How long one import is allowed to run before the screen says so out loud. A chat export
         * is read off flash in seconds; minutes means a sleeping SD card or a cloud provider
         * fetching bytes, and neither deserves an owner staring at a frozen progress bar.
         */

        /** The folder walk is a list, not a transfer: it either answers or gets stopped. */
        const val HUB_SCAN_TIMEOUT_MS = 45_000L

        /** Two gigabytes over a slow content provider is slow on purpose, so this bound is generous. */
        const val GENERIC_IMPORT_TIMEOUT_MS = 10L * 60L * 1000L

        /** One chat or one folder of a multi-chat run: a stalled entry must not stall the rest. */
        const val HUB_ITEM_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
