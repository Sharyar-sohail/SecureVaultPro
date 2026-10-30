package com.securevault.pro.presentation.screens.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.imports.WhatsAppExporter
import com.securevault.pro.data.imports.WhatsAppTranscript
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.SecureNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** One conversation already sealed in the vault, listed so the owner can pick which to write back out. */
data class VaultChatRow(
    val noteId: String,
    val title: String,
    val messages: Int,
    val files: Int,
    val updatedAt: Long
)

/** Where a staged export goes: into a file the owner names, or straight into another app. */
enum class ChatExportDestination { Save, Share }

/** The staged export, in the shape a screen needs to hand it to a chooser. */
data class ChatShareTarget(
    val path: String,
    val displayName: String,
    val mimeType: String
)

data class ChatExportState(
    /** Bumped by every finished stage, so the save dialog opens exactly once per request. */
    val requestId: Int = 0,
    val busy: Boolean = false,
    val stage: String = "",
    val destination: ChatExportDestination = ChatExportDestination.Save,
    val ready: WhatsAppExporter.Ready? = null,
    val share: ChatShareTarget? = null,
    val result: String? = null,
    val error: String? = null
)

/**
 * The way back out.
 *
 * A chat that was imported must be readable again outside the vault — as a plain `.txt`
 * transcript or a `.zip` with its media — and the only honest way to offer that is the system's
 * own save dialog, because a vault file can never be written into app-private storage and called
 * a backup. Nothing is decrypted until the owner has asked for a specific chat, and the plaintext
 * copy that staging produces lives in cache and is deleted as soon as it has been written or sent.
 */
@HiltViewModel
class WhatsAppExportViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val exporter: WhatsAppExporter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The chat this screen was opened on, when there is one — the note editor's export buttons use it. */
    val routedNoteId: String? = savedStateHandle.get<String>("noteId")?.takeIf { it.isNotBlank() }

    private val _chats = MutableStateFlow<List<VaultChatRow>>(emptyList())

    /** Conversations in this vault, newest first — the list "Export a chat" opens. */
    val chats: StateFlow<List<VaultChatRow>> = _chats.asStateFlow()

    private val _state = MutableStateFlow(ChatExportState())
    val state: StateFlow<ChatExportState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            // Parsing a transcript is not free and the vault can hold dozens of chats, so the
            // counting happens off the main thread rather than while the sheet animates in.
            repository.observeNotes().collect { notes ->
                _chats.value = withContext(Dispatchers.Default) { notes.mapNotNull(::chatRow) }
            }
        }
    }

    /**
     * Builds the file for [noteId]. [includeMedia] decides the shape: a `.txt` of the words, or a
     * `.zip` with every attachment the chat was sealed with. The result is staged, not written —
     * the screen hands it to the save dialog or the share sheet and reports the outcome here.
     */
    fun beginExport(noteId: String?, includeMedia: Boolean, destination: ChatExportDestination) {
        if (_state.value.busy) {
            _state.value = _state.value.copy(error = "One export is already running. Wait for it to finish.")
            return
        }
        if (noteId.isNullOrBlank()) {
            _state.value = _state.value.copy(
                error = "Open a chat in this vault first — there is no conversation here to write out."
            )
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, stage = "Opening the chat", result = null, error = null) }
            val note = repository.getNote(noteId)
            if (note == null) {
                failWith("That chat is no longer in the vault.")
                return@launch
            }
            val outcome = try {
                withTimeout(STAGE_TIMEOUT_MS) {
                    exporter.stage(
                        title = note.title,
                        content = note.content,
                        attachments = note.attachments,
                        includeMedia = includeMedia,
                        onProgress = { stage, _ -> _state.update { it.copy(stage = stage) } }
                    )
                }
            } catch (timedOut: TimeoutCancellationException) {
                Result.failure(IllegalStateException(STAGE_TIMEOUT_HINT))
            } catch (cancelled: CancellationException) {
                _state.value = ChatExportState()
                throw cancelled
            }
            outcome.fold(
                onSuccess = { ready ->
                    if (destination == ChatExportDestination.Share) {
                        publish(ready)
                    } else {
                        _state.update {
                            it.copy(
                                busy = false,
                                stage = "",
                                destination = destination,
                                ready = ready,
                                requestId = it.requestId + 1
                            )
                        }
                    }
                },
                onFailure = { failWith(it.message ?: "The chat could not be written out.") }
            )
        }
    }

    /**
     * The save dialog's answer. A file the owner never chose is not written anywhere, and the
     * staged plaintext copy is deleted either way — an abandoned export must not sit in cache.
     */
    fun finishExport(uri: Uri?) {
        val ready = _state.value.ready
        if (uri == null || ready == null) {
            viewModelScope.launch { exporter.discard() }
            _state.value = ChatExportState()
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, stage = "Writing ${ready.displayName}") }
            val written = try {
                withTimeout(WRITE_TIMEOUT_MS) { exporter.deliver(ready.file, uri) }
            } catch (timedOut: TimeoutCancellationException) {
                Result.failure(IllegalStateException(WRITE_TIMEOUT_HINT))
            } catch (cancelled: CancellationException) {
                exporter.discard()
                _state.value = ChatExportState()
                throw cancelled
            }
            exporter.discard()
            written.fold(
                onSuccess = {
                    val message = "Exported ${ready.displayName} · ${ready.summary}"
                    _state.value = ChatExportState(result = message)
                    delay(RESULT_LINGER_MS)
                    if (_state.value.result == message) _state.value = ChatExportState()
                },
                onFailure = { failWith(it.message ?: "The chat could not be written to that location.") }
            )
        }
    }

    /** Called by the screen once it has raised the chooser, so it is never raised twice. */
    fun shareHandled() {
        _state.update { it.copy(share = null) }
    }

    /** Backing out of a save dialog the owner no longer wants. */
    fun cancelExport() {
        job?.cancel()
        job = null
        viewModelScope.launch {
            exporter.discard()
            _state.value = ChatExportState(result = STOPPED)
            delay(RESULT_LINGER_MS)
            if (_state.value.result == STOPPED) _state.value = ChatExportState()
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** The staged file copied into the folder the FileProvider is allowed to serve. */
    private suspend fun publish(ready: WhatsAppExporter.Ready) {
        _state.update { it.copy(stage = "Handing the chat to the share sheet") }
        exporter.shareable(ready).fold(
            onSuccess = { copy ->
                _state.value = ChatExportState(
                    destination = ChatExportDestination.Share,
                    share = ChatShareTarget(copy.file.absolutePath, copy.displayName, copy.mimeType),
                    result = null
                )
            },
            onFailure = { failWith(it.message ?: "The chat could not be prepared for another app.") }
        )
    }

    private fun failWith(message: String) {
        _state.update { it.copy(busy = false, stage = "", ready = null, share = null, error = message) }
    }

    /** Only real conversations belong in this list: a note with no bubbles has nothing to export. */
    private fun chatRow(note: SecureNote): VaultChatRow? {
        val conversation = WhatsAppTranscript.transcriptOf(note.content)
        val messages = WhatsAppTranscript.messageCount(conversation)
        val isChat = note.tags.any { it.equals("WhatsApp", ignoreCase = true) } ||
            WhatsAppTranscript.isChat(note.content)
        if (!isChat || conversation.isBlank()) return null
        return VaultChatRow(
            noteId = note.id,
            title = note.title.ifBlank { "WhatsApp chat" },
            messages = messages,
            files = note.attachments.size,
            updatedAt = note.updatedAt
        )
    }

    private companion object {
        /** Decrypting a chat's media is the slow half; past this the owner gets told, not waited on. */
        const val STAGE_TIMEOUT_MS = 3L * 60L * 1000L

        const val WRITE_TIMEOUT_MS = 3L * 60L * 1000L

        const val RESULT_LINGER_MS = 6_000L

        const val STOPPED = "Export stopped — nothing was written."

        const val STAGE_TIMEOUT_HINT =
            "That chat is still being unpacked after three minutes — it is either enormous or the " +
                "storage behind it is asleep. Try exporting it without media."

        const val WRITE_TIMEOUT_HINT =
            "Writing to that location stopped answering, so the export was abandoned. Choose this " +
                "phone's Downloads folder instead — it always accepts the file."
    }
}
