package com.securevault.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.firebase.FaceMirror
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.presentation.theme.VaultFonts
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.SessionMode
import com.securevault.pro.presentation.screens.welcome.SplashTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val faceMirror: FaceMirror,
    private val vaultRepository: VaultRepository
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

    /** Whether the app's own board owns typing, read once by the root and handed down as a local. */
    val secureKeyboardEnabled: StateFlow<Boolean> = appLockManager.secureKeyboardEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The app board's keycap size (0 normal, 1 large, 2 extra large), handed down as a local. */
    val secureBoardHeight: StateFlow<Int> = appLockManager.secureBoardHeight
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Whether the app board buzzes on a key press, handed down as a local. */
    val secureKeyboardHaptics: StateFlow<Boolean> = appLockManager.secureKeyboardHaptics
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            delay(SplashTiming.HOLD_MS)
            splashDone.value = true
        }
        viewModelScope.launch {
            // A settings store that cannot be read completes after one fallback emission,
            // so waiting for "setup complete" can finish without ever matching. That has to
            // stay a non-event: the stage machine already routes the owner to Setup, and an
            // exception here would take the process down on the lock screen instead.
            val setupIsComplete = runCatching { appLockManager.setupComplete.first { it } }.getOrDefault(false)
            if (!setupIsComplete) return@launch
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
            // The browser face wears whatever the phone is wearing. Every theme path - the Settings
            // switcher, the light/dark toggle, the retired-id repair above - ends in this same flow,
            // so observing it here covers all of them without touching the sync engine. Only a real
            // session publishes: a decoy or locked screen has no mirror to dress.
            appLockManager.themeId.collect { id ->
                if (sessionManager.mode.value == SessionMode.REAL) {
                    runCatching { faceMirror.publish(id) }
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
}
