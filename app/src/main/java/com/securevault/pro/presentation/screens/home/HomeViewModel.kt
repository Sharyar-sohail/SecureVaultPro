package com.securevault.pro.presentation.screens.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.remote.google.AutoSyncManager
import com.securevault.pro.data.remote.google.SyncLogEntry
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.domain.model.CategoryCounts
import com.securevault.pro.domain.model.SearchHit
import com.securevault.pro.domain.model.SecurityReport
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.presentation.theme.LayoutTuning
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    val syncCounts: BackupCounts? = null,
    val expiringSoon: List<VaultEntry> = emptyList()
)

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: VaultRepository,
    appLockManager: AppLockManager,
    sessionManager: SessionManager,
    private val autoSyncManager: AutoSyncManager
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

    /**
     * Time the board was first stood up, and the moment the first real counts landed.
     *
     * Nothing on this path measured anything before: "Home feels slow" and "Home is instant" were
     * both unfalsifiable. [CrashLogger]'s written trail is the one artifact the owner can actually
     * read without a cable, so the number goes there, once per launch, from the thread the store
     * query already runs on — never from the composable, and never as extra disk or crypto work.
     */
    private val bootedAt = SystemClock.elapsedRealtime()
    private val countsLogged = AtomicBoolean(false)

    /** The cheap half of the board: five indexed COUNT queries plus the session mode. */
    private val boardSlice = combine(
        repository.observeCounts(),
        repository.observeFavorites(4),
        repository.observeCategoryCounts(),
        sessionManager.mode
    ) { counts, favorites, categoryCounts, mode ->
        if (countsLogged.compareAndSet(false, true)) {
            CrashLogger.noteProbe("home/counts ${SystemClock.elapsedRealtime() - bootedAt}ms")
        }
        VaultSlice(
            counts = counts,
            favorites = favorites,
            categoryCounts = categoryCounts,
            decoy = mode == SessionMode.DECOY
        )
    }.catch { emit(VaultSlice(CategoryCounts(), emptyList(), emptyMap(), false)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VaultSlice(CategoryCounts(), emptyList(), emptyMap(), false)
        )

    /**
     * The expensive half: the whole active-entry table, decoded and filtered for expiry.
     *
     * It used to be welded underneath [boardSlice], and `combine` only speaks once every input has
     * spoken — so the dashboard's counts, badges and pie waited behind a full-table read and a
     * per-entity decode of the entire vault before drawing a single number. Seeding it as a
     * [StateFlow] keeps the same query and the same data, and lets the board paint first with an
     * empty expiry list that fills in on its own frame.
     */
    private val expirySlice = repository.observeEntries()
        .map { entries ->
            val now = System.currentTimeMillis()
            val horizon = now + 60L * 24L * 60L * 60L * 1000L
            entries
                .filter { entry ->
                    val expires = entry.expiresAt ?: return@filter false
                    expires in now..horizon
                }
                .sortedBy { it.expiresAt }
                .take(6)
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val vaultSlice = combine(boardSlice, expirySlice) { board, expiring ->
        board.copy(expiringSoon = expiring)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VaultSlice(CategoryCounts(), emptyList(), emptyMap(), false)
    )

    /**
     * The four stored preferences the header reads. Seeded for the same reason as [expirySlice]:
     * a cold DataStore read must not be what stands between the unlock and a drawn board.
     */
    private val preferenceSlice = combine(
        report,
        appLockManager.lastBackupAt,
        appLockManager.cloudEmail,
        appLockManager.travelMode
    ) { security, backup, email, travel ->
        PreferenceSlice(security, backup, email, travel)
    }.catch { emit(PreferenceSlice(SecurityReport(), 0L, "", false)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PreferenceSlice(SecurityReport(), 0L, "", false)
        )

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
            syncCounts = sync.lastCounts,
            expiringSoon = vault.expiringSoon
        )
    }.catch {
        // A store failure must not reach the coroutine's default handler. Uncaught
        // exceptions thrown into this collector are what took the process down the
        // instant setup handed over to Home; the dashboard keeps its empty state and
        // the next table invalidation brings the data back.
        emit(HomeUiState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * The owner's board tuning for whichever theme is active.
     *
     * It follows the theme id, not a fixed key: switching themes in Settings re-reads the four
     * numbers stored under the new id, so each theme keeps its own column count and scales. The
     * initial value is [LayoutTuning.DEFAULT] — the theme's own board — which is what a phone with
     * no stored tuning must draw anyway.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val layoutTuning: StateFlow<LayoutTuning> = appLockManager.themeId
        .map { VaultPalettes.find(it).id }
        .distinctUntilChanged()
        .flatMapLatest { id -> appLockManager.layoutTuning(id) }
        .catch { emit(LayoutTuning.DEFAULT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LayoutTuning.DEFAULT)

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

    /** The sync orb beside the pie chart is a button: it pushes the vault now, not on a timer. */
    fun syncNow() {
        viewModelScope.launch { autoSyncManager.syncNow() }
    }
}
