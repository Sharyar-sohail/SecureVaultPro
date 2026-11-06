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
        val terms = search.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val scoped = entries
            .filter { selected == "ALL" || it.category.equals(selected, ignoreCase = true) }
        val filtered = if (terms.isEmpty()) {
            // Browsing: favourites first, then whatever was touched last.
            scoped.sortedWith(
                compareByDescending<VaultEntry> { it.isFavorite }.thenByDescending { it.updatedAt }
            )
        } else {
            // Searching: rank by what the owner typed, not by recency.
            scoped.mapNotNull { entry ->
                relevance(entry, terms)?.let { score -> score to entry }
            }.sortedWith(
                compareByDescending<Pair<Int, VaultEntry>> { it.first }
                    .thenByDescending { it.second.isFavorite }
                    .thenByDescending { it.second.updatedAt }
            ).map { it.second }
        }
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

    /** Every term has to land somewhere, or the entry is not a hit at all. */
    private fun relevance(entry: VaultEntry, terms: List<String>): Int? {
        var total = 0
        for (term in terms) {
            val score = termScore(entry, term) ?: return null
            total += score
        }
        if (terms.size > 1 && entry.title.lowercase().contains(terms.joinToString(" "))) total += 30
        return total
    }

    private fun termScore(entry: VaultEntry, term: String): Int? {
        if (term.isBlank()) return 0
        var best = 0
        fun bump(score: Int) {
            if (score > best) best = score
        }

        val title = entry.title.lowercase()
        when {
            title == term -> bump(100)
            title.startsWith(term) -> bump(60)
            title.contains(term) -> bump(40)
        }
        // "gmail" should still find "Google Mail" and "my github account".
        if (best < 40 && title.split(Regex("[^a-z0-9]+")).any { it == term }) bump(50)

        for (identity in listOf(entry.username, entry.email)) {
            val value = identity.lowercase()
            if (value.isBlank()) continue
            when {
                value == term -> bump(35)
                value.substringBefore('@') == term -> bump(30)
                value.startsWith(term) -> bump(28)
                value.contains(term) -> bump(22)
            }
        }

        val site = entry.displayUrl.lowercase()
        val host = site.removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (host.isNotBlank() && host != title) {
            if (host == term || host.split('.').any { it == term }) bump(20)
            else if (host.contains(term)) bump(15)
        }

        val tagTokens = entry.tags.flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }
        if (tagTokens.any { it == term }) bump(18)
        else if (entry.tags.any { it.lowercase().contains(term) }) bump(12)

        for (field in entry.customFields) {
            val label = field.label.lowercase()
            val value = field.value.lowercase()
            when {
                label == term -> bump(10)
                label.contains(term) -> bump(8)
            }
            if (value.isNotBlank() && value.contains(term)) bump(6)
        }

        if (entry.notes.isNotBlank() && entry.notes.lowercase().contains(term)) bump(5)

        return if (best == 0) null else best
    }
}
