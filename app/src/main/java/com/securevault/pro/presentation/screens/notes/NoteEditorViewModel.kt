package com.securevault.pro.presentation.screens.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.SecureNote
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val saving: Boolean = false,
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
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value, error = null) }
    fun setContent(value: String) = _state.update { it.copy(content = value, sealed = false) }
    fun setColor(value: String) = _state.update { it.copy(color = value) }
    fun setPinned(value: Boolean) = _state.update { it.copy(isPinned = value) }
    fun setTags(value: String) = _state.update { it.copy(tagsInput = value) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun addAttachment(name: String, mimeType: String, bytes: ByteArray) {
        if (_state.value.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (_state.value.attachments.size >= 10) {
            _state.update { it.copy(error = "A note can hold up to 10 attachments.") }
            return
        }
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            val result = repository.fileVault.saveFile(
                bytes = bytes,
                displayName = name,
                mimeType = mimeType,
                category = repository.fileVault.guessCategory(name),
                noteId = draftId
            )
            result.onSuccess { file ->
                _state.update {
                    it.copy(
                        uploading = false,
                        attachments = it.attachments + NoteAttachment(
                            id = file.id,
                            name = file.name,
                            mimeType = file.mimeType,
                            size = file.size
                        )
                    )
                }
                repository.refreshFileCount()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        uploading = false,
                        error = failure.message ?: "The attachment could not be encrypted."
                    )
                }
            }
        }
    }

    fun removeAttachment(attachment: NoteAttachment) {
        if (_state.value.decoy) return
        viewModelScope.launch {
            repository.fileVault.deleteFile(attachment.id)
            _state.update {
                it.copy(attachments = it.attachments.filterNot { a -> a.id == attachment.id })
            }
            repository.refreshFileCount()
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
        val note = SecureNote(
            id = draftId,
            title = current.title.trim(),
            content = current.content,
            isPinned = current.isPinned,
            color = current.color,
            tags = current.tagsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            attachments = current.attachments,
            sealed = current.sealed
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ok = repository.saveNote(note)
            _state.update {
                it.copy(
                    saving = false,
                    saved = ok,
                    error = if (ok) null else "Unable to save this note."
                )
            }
        }
    }

    fun delete() {
        val id = editorId ?: return
        if (_state.value.decoy) return
        viewModelScope.launch {
            repository.deleteNote(id)
            _state.update { it.copy(deleted = true) }
        }
    }
}
