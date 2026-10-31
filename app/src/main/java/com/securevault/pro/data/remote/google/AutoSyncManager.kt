package com.securevault.pro.data.remote.google

import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.remote.realtime.RealtimeEngine
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class SyncLogEntry(
    val at: Long,
    val success: Boolean,
    val message: String,
    val records: Int = 0
)

data class AutoSyncState(
    val running: Boolean = false,
    val pending: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val lastError: String? = null,
    val lastCounts: BackupCounts? = null,
    val lastJsonBytes: Int = 0,
    val lastXmlBytes: Int = 0,
    val log: List<SyncLogEntry> = emptyList()
)

/**
 * Debounces local vault mutations and mirrors the encrypted JSON + XML envelopes to
 * Drive. Runs on its own scope so navigation and screen lifecycles never cancel it.
 * Every mutation is guaranteed to trigger a sync attempt: a conflated wake channel
 * preserves edits that arrive while an upload is in flight, and failed uploads retry
 * with backoff before the next mutation resets the cycle.
 */
@Singleton
class AutoSyncManager @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val cloudSyncManager: CloudSyncManager,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val realtimeEngine: RealtimeEngine
) {
    companion object {
        private const val DEBOUNCE_MS = 500L
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val MAX_LOG_ENTRIES = 40
    }

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                // A failed upload is reported through the sync state; it must never end
                // the process while the owner is looking at the dashboard.
                _state.update { it.copy(running = false, lastError = error.message ?: "Sync failed") }
            }
    )
    private val started = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(AutoSyncState())
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // The realtime engine owns its own scope; idempotent, so a re-start at boot is harmless.
        realtimeEngine.start()
        scope.launch {
            appLockManager.lastBackupAt.collect { at ->
                if (at > _state.value.lastSyncedAt) {
                    _state.update { it.copy(lastSyncedAt = at) }
                }
            }
        }
        scope.launch {
            vaultRepository.mutations.collect {
                _state.update { it.copy(pending = true) }
                // Realtime delta push runs the instant a mutation lands, ahead of the Drive debounce.
                realtimeEngine.scheduleReconcile()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            for (ignored in wake) {
                delay(DEBOUNCE_MS)
                // Coalesce edits that landed during the debounce window; the payload is
                // built after this point, so they are already included in this sync.
                wake.tryReceive()
                if (sessionManager.mode.value != SessionMode.REAL) continue
                if (!cloudSyncManager.canAccessDrive()) continue
                syncWithRetry()
            }
        }
    }

    fun markDirty() {
        _state.update { it.copy(pending = true) }
        // The single mutation signal every screen fires: push the changed record through the realtime
        // engine immediately (the millisecond path) instead of waiting on the Drive archive debounce.
        realtimeEngine.scheduleReconcile()
        wake.trySend(Unit)
    }

    suspend fun syncNow(): Result<QuietSyncOutcome> = syncInternal(requireDrive = true)

    private suspend fun syncWithRetry() {
        var attempt = 0
        while (true) {
            val result = syncInternal(requireDrive = false)
            if (result.isSuccess) return
            attempt++
            if (attempt >= MAX_RETRY_ATTEMPTS) return
            if (sessionManager.mode.value != SessionMode.REAL) return
            if (!cloudSyncManager.canAccessDrive()) return
            delay(1_500L * attempt)
        }
    }

    private suspend fun syncInternal(requireDrive: Boolean): Result<QuietSyncOutcome> {
        if (sessionManager.mode.value != SessionMode.REAL) {
            val failure = Result.failure<QuietSyncOutcome>(
                CloudSyncException("Unlock the primary vault to sync with Drive.")
            )
            _state.update { it.copy(lastError = failure.exceptionOrNull()?.message) }
            return failure
        }
        if (requireDrive && !cloudSyncManager.canAccessDrive()) {
            val failure = Result.failure<QuietSyncOutcome>(
                CloudSyncException("Google Drive is not connected. Link an account in Setup or Settings.")
            )
            _state.update { it.copy(lastError = failure.exceptionOrNull()?.message) }
            return failure
        }
        _state.update { it.copy(running = true, lastError = null) }
        // Both halves of "sync": the realtime delta the web page reads, and the Drive archive below.
        // Manual sync used to skip this, so the sheet said "pushed" while the web copy stayed stale.
        realtimeEngine.scheduleReconcile()
        val result = cloudSyncManager.syncNowSilently()
        result.fold(
            onSuccess = { outcome ->
                _state.update {
                    it.copy(
                        running = false,
                        pending = false,
                        lastSyncedAt = outcome.uploadedAt,
                        lastError = null,
                        lastCounts = outcome.counts,
                        lastJsonBytes = outcome.jsonBytes,
                        lastXmlBytes = outcome.xmlBytes,
                        log = appendLog(
                            it.log,
                            SyncLogEntry(
                                at = outcome.uploadedAt,
                                success = true,
                                message = "JSON + XML sealed and uploaded · ${outcome.counts.total} records · ${outcome.jsonBytes + outcome.xmlBytes} bytes",
                                records = outcome.counts.total
                            )
                        )
                    )
                }
            },
            onFailure = { error ->
                val now = System.currentTimeMillis()
                val message = error.message ?: "Live sync failed."
                _state.update {
                    it.copy(
                        running = false,
                        lastError = message,
                        log = appendLog(
                            it.log,
                            SyncLogEntry(at = now, success = false, message = message)
                        )
                    )
                }
            }
        )
        return result
    }

    private fun appendLog(current: List<SyncLogEntry>, entry: SyncLogEntry): List<SyncLogEntry> =
        (listOf(entry) + current).take(MAX_LOG_ENTRIES)
}
