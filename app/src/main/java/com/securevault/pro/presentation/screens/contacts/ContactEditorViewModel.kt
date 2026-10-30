package com.securevault.pro.presentation.screens.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.SecureContact
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ContactEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val decoy: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val category: String = "Personal",
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false
) {
    val canSave: Boolean get() = !saving && !decoy && name.isNotBlank()
}

@HiltViewModel
class ContactEditorViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val editorId: String? = savedStateHandle.get<String>("contactId")?.takeIf { it.isNotBlank() }
    private val draftId: String = editorId ?: UUID.randomUUID().toString()
    private val _state = MutableStateFlow(
        ContactEditorUiState(
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
            val contact = editorId?.let { repository.getContact(it) }
            if (contact == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    name = contact.name,
                    phone = contact.phone,
                    email = contact.email,
                    address = contact.address,
                    notes = contact.notes,
                    category = contact.category
                )
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun setPhone(value: String) = _state.update { it.copy(phone = value) }
    fun setEmail(value: String) = _state.update { it.copy(email = value) }
    fun setAddress(value: String) = _state.update { it.copy(address = value) }
    fun setNotes(value: String) = _state.update { it.copy(notes = value) }
    fun setCategory(value: String) = _state.update { it.copy(category = value) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (current.name.isBlank()) {
            _state.update { it.copy(error = "A contact needs a name.") }
            return
        }
        val contact = SecureContact(
            id = draftId,
            name = current.name.trim(),
            phone = current.phone.trim(),
            email = current.email.trim(),
            address = current.address.trim(),
            notes = current.notes,
            category = current.category
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ok = repository.saveContact(contact)
            _state.update {
                it.copy(
                    saving = false,
                    saved = ok,
                    error = if (ok) null else "Unable to save this contact."
                )
            }
        }
    }

    fun delete() {
        val id = editorId ?: return
        if (_state.value.decoy) return
        viewModelScope.launch {
            repository.deleteContact(id)
            _state.update { it.copy(deleted = true) }
        }
    }
}
