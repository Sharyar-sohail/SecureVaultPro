package com.securevault.pro.presentation.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.google.AutoSyncManager
import com.securevault.pro.data.remote.google.AutoSyncState
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class SyncLocalState(
    val message: String? = null,
    val messageIsError: Boolean = false
)

data class SyncCenterState(
    val autoSync: AutoSyncState = AutoSyncState(),
    val lastBackupAt: Long = 0L,
    val cloudEmail: String = "",
    val decoy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
) {
    val lastSyncedAt: Long get() = maxOf(autoSync.lastSyncedAt, lastBackupAt)
}

@HiltViewModel
class SyncCenterViewModel @Inject constructor(
    private val autoSyncManager: AutoSyncManager,
    private val appLockManager: AppLockManager,
    sessionManager: SessionManager
) : ViewModel() {

    private val local = MutableStateFlow(SyncLocalState())
    private val decoy = MutableStateFlow(sessionManager.isDecoy())

    val state = combine(
        autoSyncManager.state,
        appLockManager.lastBackupAt,
        appLockManager.cloudEmail,
        combine(decoy, local) { isDecoy, extras -> isDecoy to extras }
    ) { sync, lastBackup, email, extras ->
        SyncCenterState(
            autoSync = sync,
            lastBackupAt = lastBackup,
            cloudEmail = email,
            decoy = extras.first,
            message = extras.second.message,
            messageIsError = extras.second.messageIsError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncCenterState()
    )

    fun syncNow() {
        if (state.value.decoy) {
            setMessage("The restricted vault cannot reach Drive.", error = true)
            return
        }
        viewModelScope.launch {
            val result = autoSyncManager.syncNow()
            result.fold(
                onSuccess = { outcome ->
                    setMessage(
                        "JSON + XML sealed and mirrored · ${outcome.counts.total} records",
                        error = false
                    )
                },
                onFailure = { error ->
                    setMessage(error.message ?: "Live sync failed.", error = true)
                }
            )
        }
    }

    fun clearMessage() {
        local.update { it.copy(message = null) }
    }

    private fun setMessage(message: String, error: Boolean) {
        local.update { it.copy(message = message, messageIsError = error) }
    }
}
