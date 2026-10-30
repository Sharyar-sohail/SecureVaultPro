package com.securevault.pro.presentation.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.contacts.DeviceContactsImporter
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.ContactCategories
import com.securevault.pro.domain.model.SecureContact
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ContactImportStatus { IDLE, READING, IMPORTING, DONE, ERROR }

data class ContactImportState(
    val status: ContactImportStatus = ContactImportStatus.IDLE,
    val message: String = "",
    val progress: Float = 0f,
    val imported: Int = 0,
    val merged: Int = 0,
    val skipped: Int = 0
)

data class ContactsUiState(
    val contacts: List<SecureContact> = emptyList(),
    val query: String = "",
    val category: String = "ALL",
    val total: Int = 0,
    val categoryCounts: Map<String, Int> = emptyMap(),
    val decoy: Boolean = false,
    val loading: Boolean = false,
    val importState: ContactImportState = ContactImportState()
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val importer: DeviceContactsImporter,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow("ALL")
    private val importState = MutableStateFlow(ContactImportState())

    val state = combine(
        repository.observeContacts(),
        query,
        category,
        sessionManager.mode.map { it == SessionMode.DECOY }.distinctUntilChanged(),
        importState
    ) { contacts, search, selected, decoy, import ->
        val q = search.trim().lowercase()
        val counts = buildMap {
            put("ALL", contacts.size)
            ContactCategories.forEach { key ->
                put(key, contacts.count { it.category.equals(key, ignoreCase = true) })
            }
        }
        val filtered = contacts
            .asSequence()
            .filter { selected == "ALL" || it.category.equals(selected, ignoreCase = true) }
            .filter {
                q.isBlank() ||
                    it.name.lowercase().contains(q) ||
                    it.phone.lowercase().contains(q) ||
                    it.email.lowercase().contains(q) ||
                    it.address.lowercase().contains(q) ||
                    it.notes.lowercase().contains(q)
            }
            .sortedBy { it.name.lowercase() }
            .toList()
        ContactsUiState(
            contacts = filtered,
            query = search,
            category = selected,
            total = contacts.size,
            categoryCounts = counts,
            decoy = decoy,
            loading = false,
            importState = import
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(loading = true)
        )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategory(value: String) {
        category.value = if (value in ContactCategories) value else "ALL"
    }

    fun beginPermissionRequest() {
        importState.update {
            it.copy(status = ContactImportStatus.IDLE, message = "")
        }
    }

    fun reportPermissionDenied() {
        importState.value = ContactImportState(
            status = ContactImportStatus.ERROR,
            message = "Contacts permission was denied. Enable it in Android settings to import your address book."
        )
    }

    fun importFromDevice() {
        if (sessionManager.isDecoy()) {
            importState.value = ContactImportState(
                status = ContactImportStatus.ERROR,
                message = "The restricted vault cannot import device contacts."
            )
            return
        }
        if (importState.value.status == ContactImportStatus.READING ||
            importState.value.status == ContactImportStatus.IMPORTING
        ) {
            return
        }
        viewModelScope.launch {
            importState.value = ContactImportState(
                status = ContactImportStatus.READING,
                message = "Reading your address book…"
            )
            val read = importer.readContacts { rows ->
                importState.update {
                    it.copy(message = "Reading address book · $rows rows", progress = 0.35f)
                }
            }
            read.fold(
                onSuccess = { result ->
                    if (result.contacts.isEmpty()) {
                        importState.value = ContactImportState(
                            status = ContactImportStatus.DONE,
                            message = "No contacts were found on this device."
                        )
                        return@fold
                    }
                    importState.update {
                        it.copy(
                            status = ContactImportStatus.IMPORTING,
                            message = "Encrypting ${result.contacts.size} contacts…",
                            progress = 0.45f
                        )
                    }
                    val summary = repository.importDeviceContacts(result.contacts) { done, total ->
                        val fraction = if (total <= 0) 0.5f else done.toFloat() / total
                        importState.update {
                            it.copy(
                                message = "Sealing contacts · $done / $total",
                                progress = 0.45f + fraction * 0.5f
                            )
                        }
                    }
                    importState.value = ContactImportState(
                        status = ContactImportStatus.DONE,
                        message = "Imported ${summary.imported} new contacts · merged ${summary.merged} · skipped ${summary.skipped}.",
                        progress = 1f,
                        imported = summary.imported,
                        merged = summary.merged,
                        skipped = summary.skipped
                    )
                    // A filter left over from before the import would hide the new
                    // rows while the totals above already changed. Show everything.
                    query.value = ""
                    category.value = "ALL"
                },
                onFailure = { error ->
                    importState.value = ContactImportState(
                        status = ContactImportStatus.ERROR,
                        message = error.message?.take(200) ?: "The address book could not be read."
                    )
                }
            )
        }
    }

    fun dismissImportResult() {
        importState.update {
            if (it.status == ContactImportStatus.READING || it.status == ContactImportStatus.IMPORTING) it
            else ContactImportState()
        }
    }
}
