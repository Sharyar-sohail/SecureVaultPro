package com.securevault.pro.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.google.AutoSyncManager
import com.securevault.pro.data.remote.google.SyncLogEntry
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.CategoryCounts
import com.securevault.pro.domain.model.SearchHit
import com.securevault.pro.domain.model.SecurityReport
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val counts: CategoryCounts = CategoryCounts(),
    val favorites: List<VaultEntry> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val report: SecurityReport = SecurityReport(),
    val lastBackupAt: Long = 0L,
    val cloudEmail: String = "",
    val isDecoy: Boolean = false,
    val travelMode: Boolean = false,
    val query: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val syncRunning: Boolean = false,
    val syncPending: Boolean = false,
    val syncLastAt: Long = 0L,
    val syncError: String? = null,
    val syncLog: List<SyncLogEntry> = emptyList(),
    val expiringSoon: List<VaultEntry> = emptyList()
)

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: VaultRepository,
    appLockManager: AppLockManager,
    sessionManager: SessionManager,
    autoSyncManager: AutoSyncManager
) : ViewModel() {

    private data class VaultSlice(
        val counts: CategoryCounts,
        val favorites: List<VaultEntry>,
        val categoryCounts: Map<String, Int>,
        val decoy: Boolean,
        val expiringSoon: List<VaultEntry> = emptyList()
    )

    private data class PreferenceSlice(
        val report: SecurityReport,
        val lastBackup: Long,
        val email: String,
        val travel: Boolean
    )

    private val query = MutableStateFlow("")
    private val report = MutableStateFlow(SecurityReport())
    private val hits = MutableStateFlow<List<SearchHit>>(emptyList())

    private val vaultSlice = combine(
        repository.observeCounts(),
        repository.observeFavorites(4),
        repository.observeCategoryCounts(),
        sessionManager.mode
    ) { counts, favorites, categoryCounts, mode ->
        VaultSlice(
            counts = counts,
            favorites = favorites,
            categoryCounts = categoryCounts,
            decoy = mode == SessionMode.DECOY
        )
    }.combine(repository.observeEntries()) { slice, entries ->
        val now = System.currentTimeMillis()
        val horizon = now + 60L * 24L * 60L * 60L * 1000L
        val expiring = entries
            .filter { entry ->
                val expires = entry.expiresAt ?: return@filter false
                expires in now..horizon
            }
            .sortedBy { it.expiresAt }
            .take(6)
        slice.copy(expiringSoon = expiring)
    }

    private val preferenceSlice = combine(
        report,
        appLockManager.lastBackupAt,
        appLockManager.cloudEmail,
        appLockManager.travelMode
    ) { security, backup, email, travel ->
        PreferenceSlice(security, backup, email, travel)
    }

    val state: StateFlow<HomeUiState> = combine(
        vaultSlice,
        preferenceSlice,
        query,
        hits,
        autoSyncManager.state
    ) { vault, prefs, searchQuery, searchHits, sync ->
        HomeUiState(
            counts = vault.counts,
            favorites = vault.favorites,
            categoryCounts = vault.categoryCounts,
            report = prefs.report,
            lastBackupAt = prefs.lastBackup,
            cloudEmail = prefs.email,
            isDecoy = vault.decoy,
            travelMode = prefs.travel,
            query = searchQuery,
            searchHits = searchHits,
            syncRunning = sync.running,
            syncPending = sync.pending,
            syncLastAt = maxOf(sync.lastSyncedAt, prefs.lastBackup),
            syncError = sync.lastError,
            syncLog = sync.log,
            expiringSoon = vault.expiringSoon
        )
    }.catch {
        // A store failure must not reach the coroutine's default handler. Uncaught
        // exceptions thrown into this collector are what took the process down the
        // instant setup handed over to Home; the dashboard keeps its empty state and
        // the next table invalidation brings the data back.
        emit(HomeUiState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            runCatching { repository.refreshFileCount() }
        }
        viewModelScope.launch {
            delay(450)
            report.value = runCatching { repository.buildSecurityReport() }.getOrDefault(SecurityReport())
        }
        viewModelScope.launch {
            repository.mutations.debounce(300).collect {
                runCatching { repository.refreshFileCount() }
                report.value = runCatching { repository.buildSecurityReport() }.getOrDefault(SecurityReport())
            }
        }
        viewModelScope.launch {
            sessionManager.mode.drop(1).collect {
                report.value = runCatching { repository.buildSecurityReport() }.getOrDefault(SecurityReport())
            }
        }
        viewModelScope.launch {
            query
                .debounce(220)
                .distinctUntilChanged()
                .collect { value ->
                    if (value.isBlank()) {
                        hits.value = emptyList()
                    } else {
                        hits.value = runCatching { repository.globalSearch(value) }.getOrDefault(emptyList())
                    }
                }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleFavorite(entryId: String) {
        viewModelScope.launch { repository.toggleFavorite(entryId) }
    }
}
