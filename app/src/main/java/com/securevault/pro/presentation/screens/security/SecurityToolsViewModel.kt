package com.securevault.pro.presentation.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.presentation.screens.secureinput.RecoverySeedStore
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityToolsState(
    val panicShake: Boolean = false,
    val autoSync: Boolean = true,
    val decoy: Boolean = false,
    /** Word count of the seed registered on this install, or 0 when there is none yet. */
    val seedWordCount: Int = 0
)

@HiltViewModel
class SecurityToolsViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    sessionManager: SessionManager,
    private val seedStore: RecoverySeedStore
) : ViewModel() {

    private val seedWordCount = MutableStateFlow(0)

    init {
        refreshSeedRecord()
    }

    val state = combine(
        appLockManager.panicShakeEnabled,
        appLockManager.autoSyncEnabled,
        MutableStateFlow(sessionManager.isDecoy()),
        seedWordCount
    ) { shake, sync, decoy, seedWords ->
        SecurityToolsState(
            panicShake = shake,
            autoSync = sync,
            decoy = decoy,
            seedWordCount = seedWords
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SecurityToolsState()
    )

    /** Re-reads the seed bookkeeping after the seed screen has been open. Never reads the phrase. */
    fun refreshSeedRecord() {
        viewModelScope.launch(Dispatchers.IO) {
            seedWordCount.value = runCatching { seedStore.record()?.wordCount ?: 0 }.getOrDefault(0)
        }
    }

    fun setPanicShake(enabled: Boolean) {
        if (state.value.decoy) return
        viewModelScope.launch { appLockManager.setPanicShakeEnabled(enabled) }
    }

    fun setAutoSync(enabled: Boolean) {
        if (state.value.decoy) return
        viewModelScope.launch { appLockManager.setAutoSyncEnabled(enabled) }
    }
}
