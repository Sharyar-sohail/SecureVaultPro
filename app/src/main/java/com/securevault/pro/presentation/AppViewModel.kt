package com.securevault.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.DeadManOutcome
import com.securevault.pro.security.DeadManSwitchManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.presentation.theme.VaultFonts
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppStage { LOADING, INTRO, ONBOARDING, SETUP, LOCKED, UNLOCKED }

data class AppUiState(
    val stage: AppStage = AppStage.LOADING,
    val decoy: Boolean = false
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val cloudSyncManager: CloudSyncManager,
    private val vaultRepository: VaultRepository,
    private val deadManSwitchManager: DeadManSwitchManager
) : ViewModel() {

    private val splashDone = MutableStateFlow(false)
    private val hydrated = MutableStateFlow(false)

    private data class FirstRunFlags(
        val introSeen: Boolean,
        val onboardingDone: Boolean,
        val setupComplete: Boolean
    )

    private val firstRunFlags = combine(
        appLockManager.introSeen,
        appLockManager.onboardingDone,
        appLockManager.setupComplete
    ) { intro, onboarding, setup -> FirstRunFlags(intro, onboarding, setup) }

    val uiState: StateFlow<AppUiState> = combine(
        splashDone,
        firstRunFlags,
        sessionManager.mode,
        hydrated
    ) { splash, flags, mode, ready ->
        val stage = when {
            !splash -> AppStage.LOADING
            !flags.introSeen -> AppStage.INTRO
            !flags.onboardingDone -> AppStage.ONBOARDING
            !flags.setupComplete -> AppStage.SETUP
            mode == SessionMode.LOCKED -> AppStage.LOCKED
            !ready -> AppStage.LOADING
            else -> AppStage.UNLOCKED
        }
        AppUiState(stage = stage, decoy = mode == SessionMode.DECOY)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    val autoLockSeconds: StateFlow<Int> = appLockManager.autoLockSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)

    val inactivityLockSeconds: StateFlow<Int> = appLockManager.inactivityLockSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 120)

    val clipboardSeconds: StateFlow<Int> = appLockManager.clipboardSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val screenshotBlocked: StateFlow<Boolean> = appLockManager.screenshotBlocked
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val biometricEnabled: StateFlow<Boolean> = appLockManager.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val themeId: StateFlow<String> = appLockManager.themeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, VaultPalettes.DEFAULT_ID)

    val fontId: StateFlow<String> = appLockManager.fontId
        .stateIn(viewModelScope, SharingStarted.Eagerly, VaultFonts.DEFAULT_ID)

    val panicShakeEnabled: StateFlow<Boolean> = appLockManager.panicShakeEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val cloudState = cloudSyncManager.state

    private val _deadManNotice = MutableStateFlow<String?>(null)

    /**
     * One sentence about what the dead man's switch actually did on this launch (archived and
     * erased, refused because Drive was not connected, or aborted with nothing erased). Null on
     * every ordinary launch. Dismissing it only hides the text - it is not consent, so it does
     * not restart the countdown or cancel a pending enforcement.
     */
    val deadManNotice: StateFlow<String?> = _deadManNotice.asStateFlow()

    init {
        viewModelScope.launch {
            delay(1_250)
            splashDone.value = true
        }
        viewModelScope.launch {
            // A settings store that cannot be read completes after one fallback emission,
            // so waiting for "setup complete" can finish without ever matching. That has to
            // stay a non-event: the stage machine already routes the owner to Setup, and an
            // exception here would take the process down on the lock screen instead.
            val setupIsComplete = runCatching { appLockManager.setupComplete.first { it } }.getOrDefault(false)
            if (!setupIsComplete) return@launch
            val deadManOverdue = runCatching { deadManSwitchManager.evaluateOnStartup() }
                .getOrDefault(false)
            if (deadManOverdue) {
                // Lock before enforcing so no live sync observer can re-upload what is about to
                // be deleted (LocalWipeService locks again itself; this closes the earlier gap).
                sessionManager.lock()
                val result = runCatching { deadManSwitchManager.enforce() }
                _deadManNotice.value = describeDeadManResult(result)
                // A completed erase left no vault to hydrate, and hydration would try to pull
                // the emptied account straight back in.
                if (result.getOrNull() is DeadManOutcome.ArchivedAndErased) return@launch
            }
            // Never hold the UI hostage to a network hydration pass. Local data is
            // already available; the Drive merge streams in and recomposes flows.
            hydrated.value = true
            launch { runCatching { cloudSyncManager.hydrateVault() } }
        }
        viewModelScope.launch {
            val saved = runCatching { appLockManager.themeId.first() }.getOrDefault(VaultPalettes.DEFAULT_ID)
            val resolved = VaultPalettes.find(saved)
            if (resolved.id != saved) runCatching { appLockManager.setThemeId(resolved.id) }
        }
        viewModelScope.launch {
            sessionManager.mode.collect { mode ->
                if (mode == SessionMode.REAL) {
                    runCatching { deadManSwitchManager.checkIn() }
                }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { runCatching { appLockManager.setOnboardingDone(true) } }
    }

    fun completeIntro() {
        viewModelScope.launch { runCatching { appLockManager.setIntroSeen(true) } }
    }

    fun toggleLightMode() {
        viewModelScope.launch {
            val current = VaultPalettes.find(
                runCatching { appLockManager.themeId.first() }.getOrDefault(VaultPalettes.DEFAULT_ID)
            )
            val next = if (current.isLight) VaultPalettes.DEFAULT_LIGHT_ID else VaultPalettes.DEFAULT_DARK_ID
            VaultPaletteController.apply(next)
            runCatching { appLockManager.setThemeId(next) }
        }
    }

    fun lockNow() {
        sessionManager.lock()
    }

    /** Hides the switch report. Deliberately not an acknowledgement: see [deadManNotice]. */
    fun dismissDeadManNotice() {
        _deadManNotice.value = null
    }

    private fun describeDeadManResult(result: Result<DeadManOutcome>): String? =
        result.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    DeadManOutcome.NotDue -> null
                    DeadManOutcome.NeedsDrive ->
                        "Dead man's switch: the deadline passed. Connect Google Drive so the " +
                            "switch can archive before erasing. Nothing has been erased."
                    is DeadManOutcome.ArchivedAndErased -> {
                        val name = outcome.outcome.archiveName ?: "a new archive in Google Drive"
                        "Dead man's switch: the deadline passed. Your vault was archived to " +
                            "Google Drive as $name, then erased from this device. The Drive " +
                            "copies were kept."
                    }
                    is DeadManOutcome.ArchiveFailed ->
                        "Dead man's switch: the deadline passed, but your vault is intact. " +
                            outcome.reason
                }
            },
            onFailure = { error ->
                // Never claim "nothing was erased" here: an unexpected failure could have
                // happened after the local wipe had already started.
                "Dead man's switch: it could not finish (" +
                    (error.message?.take(200) ?: "unknown reason") +
                    "). Reopen the app to see the current state."
            }
        )
}
