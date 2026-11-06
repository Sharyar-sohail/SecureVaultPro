package com.securevault.pro.presentation.screens.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.SecureNote
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class NoteEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val decoy: Boolean = false,
    val title: String = "",
    val content: String = "",
    val color: String = "DEFAULT",
    val isPinned: Boolean = false,
    val tagsInput: String = "",
    val attachments: List<NoteAttachment> = emptyList(),
    val sealed: Boolean = false,
    val uploading: Boolean = false,
    /** 0..1 across staging and sealing of the attachment in flight. */
    val attachmentProgress: Float = 0f,
    val saving: Boolean = false,
    /** A background save the owner never asked for; it must never pop the editor. */
    val autosaving: Boolean = false,
    /** There are edits newer than the last write to the vault. */
    val dirty: Boolean = false,
    /** Wall clock of the last accepted write; 0 until something has been stored. */
    val savedAt: Long = 0L,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false
) {
    val canSave: Boolean
        get() = !saving && !decoy && (title.isNotBlank() || content.isNotBlank() || attachments.isNotEmpty())
}

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val editorId: String? = savedStateHandle.get<String>("noteId")?.takeIf { it.isNotBlank() }
    private val draftId: String = editorId ?: UUID.randomUUID().toString()
    private val _state = MutableStateFlow(
        NoteEditorUiState(
            isNew = editorId == null,
            decoy = sessionManager.isDecoy()
        )
    )
    val state = _state.asStateFlow()

    /**
     * Notes save themselves, the way Keep and Apple Notes do, so a note that was typed and
     * abandoned is not silently lost. The write waits [AUTOSAVE_DELAY_MS] after the last
     * keystroke and is skipped entirely when the text matches what is already in the vault,
     * so no sealing work happens while the keyboard is moving.
     */
    private var autosaveJob: Job? = null

    /** The field values the vault actually holds right now. */
    private var persistedSnapshot: String? = null

    /**
     * `viewModelScope` is already cancelled by the time `onCleared` runs, so the final flush of a
     * note edited seconds before the owner pressed back needs a scope the VM does not own. It
     * launches at most one short Room write and is not reused after that.
     */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val note = editorId?.let { repository.getNote(it) }
            if (note == null) {
                _state.update { it.copy(loading = false, isNew = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    title = note.title,
                    content = note.content,
                    color = note.color,
                    isPinned = note.isPinned,
                    tagsInput = note.tags.joinToString(", "),
                    attachments = note.attachments,
                    sealed = note.sealed
                )
            }
            persistedSnapshot = snapshot(_state.value)
        }
    }

    fun setTitle(value: String) {
        _state.update { it.copy(title = value, error = null) }
        markDirty()
    }

    fun setContent(value: String) {
        _state.update { it.copy(content = value, sealed = false) }
        markDirty()
    }

    fun setColor(value: String) {
        _state.update { it.copy(color = value) }
        markDirty()
    }

    fun setPinned(value: Boolean) {
        _state.update { it.copy(isPinned = value) }
        markDirty()
    }

    fun setTags(value: String) {
        _state.update { it.copy(tagsInput = value) }
        markDirty()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Attachments over this size are staged to cache and sealed by streaming; anything smaller
     * keeps the in-memory path. The vault itself refuses a byte array past 100 MB, so the old
     * habit of reading the whole pick into RAM capped note files far below what the Files
     * module already handles and doubled the memory cost on a mid-range phone.
     */
    fun addAttachment(name: String, mimeType: String, bytes: ByteArray) {
        if (_state.value.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
            _state.update { it.copy(error = "A note can hold up to $MAX_ATTACHMENTS attachments.") }
            return
        }
        _state.update { it.copy(uploading = true, attachmentProgress = 0.5f, error = null) }
        viewModelScope.launch {
            val result = repository.fileVault.saveFile(
                bytes = bytes,
                displayName = name,
                mimeType = mimeType,
                category = repository.fileVault.guessCategory(name),
                noteId = draftId
            )
            result.onSuccess { file ->
                acceptAttachment(file.id, file.name, file.mimeType, file.size)
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        uploading = false,
                        attachmentProgress = 0f,
                        error = failure.message ?: "The attachment could not be encrypted."
                    )
                }
            }
        }
    }

    /**
     * Seals a file that has already been copied into this app's cache. The bytes never enter
     * memory, so a note can carry the same 2 GB ceiling the Files module already offers.
     * The staged file is the app's own scratch copy and is deleted once it has been sealed.
     */
    fun addStagedAttachment(name: String, mimeType: String, staged: File) {
        if (_state.value.decoy) {
            staged.delete()
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
            staged.delete()
            _state.update { it.copy(error = "A note can hold up to $MAX_ATTACHMENTS attachments.") }
            return
        }
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                repository.fileVault.saveStream(
                    source = staged,
                    displayName = name,
                    mimeType = mimeType,
                    category = repository.fileVault.guessCategory(name),
                    noteId = draftId
                )
            }
            staged.delete()
            result.fold(
                onSuccess = { saved ->
                    saved.onSuccess { file ->
                        acceptAttachment(file.id, file.name, file.mimeType, file.size)
                    }.onFailure { failure ->
                        _state.update {
                            it.copy(
                                uploading = false,
                                attachmentProgress = 0f,
                                error = failure.message ?: "The attachment could not be encrypted."
                            )
                        }
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            uploading = false,
                            attachmentProgress = 0f,
                            error = failure.message ?: "The attachment could not be encrypted."
                        )
                    }
                }
            )
        }
    }

    /** Progress of the staging copy, which owns the first half of the bar. */
    fun setStagingProgress(fraction: Float) {
        _state.update { it.copy(uploading = true, attachmentProgress = fraction.coerceIn(0f, 1f) * 0.5f) }
    }

    private fun acceptAttachment(id: String, displayName: String, mimeType: String, size: Long) {
        _state.update {
            it.copy(
                uploading = false,
                attachmentProgress = 1f,
                attachments = it.attachments + NoteAttachment(
                    id = id,
                    name = displayName,
                    mimeType = mimeType,
                    size = size
                )
            )
        }
        viewModelScope.launch {
            repository.refreshFileCount()
        }
        markDirty()
    }

    fun removeAttachment(attachment: NoteAttachment) {
        if (_state.value.decoy) return
        viewModelScope.launch {
            repository.fileVault.deleteFile(attachment.id)
            _state.update {
                it.copy(attachments = it.attachments.filterNot { a -> a.id == attachment.id })
            }
            repository.refreshFileCount()
            markDirty()
        }
    }

    fun exportAttachment(id: String, onReady: (File?) -> Unit) {
        viewModelScope.launch {
            onReady(repository.fileVault.exportToCache(id))
        }
    }

    /** The decrypted file behind an attachment, for callers that can wait — bubble thumbnails. */
    suspend fun attachmentFile(id: String): File? = repository.fileVault.exportToCache(id)

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (current.title.isBlank() && current.content.isBlank() && current.attachments.isEmpty()) {
            _state.update { it.copy(error = "Add a title, some content or an attachment.") }
            return
        }
        persist(explicit = true, current)
    }

    private fun persist(explicit: Boolean, current: NoteEditorUiState) {
        autosaveJob?.cancel()
        val note = buildNote(current)
        _state.update {
            if (explicit) it.copy(saving = true, error = null)
            else it.copy(autosaving = true, error = null)
        }
        viewModelScope.launch {
            val ok = repository.saveNote(note)
            val stamp = System.currentTimeMillis()
            if (ok) persistedSnapshot = snapshot(_state.value)
            _state.update {
                if (explicit) {
                    it.copy(
                        saving = false,
                        saved = ok,
                        dirty = if (ok) false else it.dirty,
                        savedAt = if (ok) stamp else it.savedAt,
                        isNew = if (ok) false else it.isNew,
                        error = if (ok) null else "Unable to save this note."
                    )
                } else {
                    it.copy(
                        autosaving = false,
                        // A failed background write leaves the note dirty, so the next pause
                        // retries it and the owner still has the chance to save by hand.
                        dirty = !ok,
                        savedAt = if (ok) stamp else it.savedAt,
                        isNew = if (ok) false else it.isNew,
                        error = if (ok) null else "Autosave failed — save the note again."
                    )
                }
            }
        }
    }

    private fun buildNote(current: NoteEditorUiState): SecureNote = SecureNote(
        id = draftId,
        title = current.title.trim(),
        content = current.content,
        isPinned = current.isPinned,
        color = current.color,
        tags = current.tagsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
        attachments = current.attachments,
        sealed = current.sealed
    )

    private fun markDirty() {
        val current = _state.value
        // A sealed note's body only exists on another device, so it is never rewritten behind the
        // owner's back; the explicit button is the one writer there.
        if (current.decoy || current.sealed) return
        autosaveJob?.cancel()
        if (snapshot(current) == persistedSnapshot) {
            if (current.dirty) _state.update { it.copy(dirty = false) }
            return
        }
        if (current.title.isBlank() && current.content.isBlank() && current.attachments.isEmpty()) {
            return
        }
        _state.update { it.copy(dirty = true) }
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val pending = _state.value
            if (pending.saving || pending.autosaving) return@launch
            if (snapshot(pending) == persistedSnapshot) {
                _state.update { it.copy(dirty = false) }
                return@launch
            }
            persist(explicit = false, pending)
        }
    }

    private fun snapshot(s: NoteEditorUiState): String = listOf(
        s.title.trim(),
        s.content,
        s.color,
        s.isPinned.toString(),
        s.tagsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(","),
        s.attachments.joinToString(",") { it.id }
    ).joinToString("\u0001")

    /** Called when the owner leaves the editor: the last pause of typing must not be dropped. */
    fun flushPendingChanges() {
        val current = _state.value
        autosaveJob?.cancel()
        if (current.decoy || current.sealed || current.saving || current.autosaving) return
        if (!current.dirty || snapshot(current) == persistedSnapshot) return
        if (current.title.isBlank() && current.content.isBlank() && current.attachments.isEmpty()) return
        val note = buildNote(current)
        flushScope.launch { repository.saveNote(note) }
    }

    override fun onCleared() {
        autosaveJob?.cancel()
        val current = _state.value
        if (current.dirty && snapshot(current) != persistedSnapshot) {
            flushScope.launch { repository.saveNote(buildNote(current)) }
        }
        super.onCleared()
    }

    fun delete() {
        val current = _state.value
        if (current.decoy) return
        autosaveJob?.cancel()
        viewModelScope.launch {
            // A note that was never persisted has no row for deleteNote() to walk, so its
            // sealed files are released here rather than left orphaned in the file vault.
            if (current.isNew) {
                current.attachments.forEach { repository.fileVault.deleteFile(it.id) }
                repository.refreshFileCount()
            }
            repository.deleteNote(draftId)
            _state.update { it.copy(deleted = true) }
        }
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 1_200L
        const val MAX_ATTACHMENTS = 10
        /** Picks at or below this size keep the in-memory path; anything larger is streamed. */
        const val IN_MEMORY_ATTACHMENT_BYTES = 32L * 1024L * 1024L
    }
}
