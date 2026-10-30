package com.securevault.pro.presentation.screens.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.EntryCategories
import com.securevault.pro.domain.model.VaultEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultListUiState(
    val entries: List<VaultEntry> = emptyList(),
    val total: Int = 0,
    val category: String = "ALL",
    val categoryCounts: Map<String, Int> = emptyMap(),
    val query: String = "",
    val favorites: Int = 0
)

@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val repository: VaultRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val category = MutableStateFlow(
        savedStateHandle.get<String>("category")?.uppercase()
            ?.takeIf { it in EntryCategories } ?: "ALL"
    )
    private val query = MutableStateFlow("")

    val state = combine(
        repository.observeEntries(),
        category,
        query
    ) { entries, selected, search ->
        val counts = buildMap {
            put("ALL", entries.size)
            EntryCategories.filter { it != "ALL" }.forEach { key ->
                put(key, entries.count { it.category.equals(key, ignoreCase = true) })
            }
        }
        val needle = search.trim()
        val filtered = entries
            .asSequence()
            .filter { selected == "ALL" || it.category.equals(selected, ignoreCase = true) }
            .filter { needle.isBlank() || matches(it, needle) }
            .sortedWith(compareByDescending<VaultEntry> { it.isFavorite }.thenByDescending { it.updatedAt })
            .toList()
        VaultListUiState(
            entries = filtered,
            total = entries.size,
            category = selected,
            categoryCounts = counts,
            query = search,
            favorites = entries.count { it.isFavorite }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultListUiState()
    )

    fun setCategory(value: String) {
        category.value = value.uppercase().takeIf { it in EntryCategories } ?: "ALL"
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteEntry(id) }
    }

    private fun matches(entry: VaultEntry, needle: String): Boolean {
        val q = needle.lowercase()
        return entry.title.lowercase().contains(q) ||
            entry.username.lowercase().contains(q) ||
            entry.email.lowercase().contains(q) ||
            entry.website.lowercase().contains(q) ||
            entry.url.lowercase().contains(q) ||
            entry.tags.any { it.lowercase().contains(q) }
    }
}
