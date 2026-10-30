package com.securevault.pro.presentation.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.SecureNote
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesUiState(
    val notes: List<SecureNote> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val pinned: Int = 0,
    val decoy: Boolean = false
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: VaultRepository,
    sessionManager: SessionManager
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state = combine(
        repository.observeNotes(),
        query,
        MutableStateFlow(sessionManager.isDecoy())
    ) { notes, search, decoy ->
        val q = search.trim().lowercase()
        val filtered = notes
            .asSequence()
            .filter {
                q.isBlank() ||
                    it.title.lowercase().contains(q) ||
                    it.content.lowercase().contains(q) ||
                    it.tags.any { tag -> tag.lowercase().contains(q) }
            }
            .sortedWith(
                compareByDescending<SecureNote> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
            .toList()
        NotesUiState(
            notes = filtered,
            query = search,
            total = notes.size,
            pinned = notes.count { it.isPinned },
            decoy = decoy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotesUiState()
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun togglePin(id: String) {
        viewModelScope.launch { repository.toggleNotePin(id) }
    }
}
