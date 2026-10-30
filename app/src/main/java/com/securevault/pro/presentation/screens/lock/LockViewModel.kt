package com.securevault.pro.presentation.screens.lock

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.presentation.components.BiometricUtils
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.UnlockResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LockUiState(
    val ready: Boolean = false,
    val pinLength: Int = 6,
    val randomizeKeypad: Boolean = true,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val lockoutUntil: Long = 0L,
    val attemptsLeft: Int = 5,
    val busy: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Nothing here may escape: this coroutine runs while the lock screen is on
            // screen, and an exception it throws takes the process — and the owner's only
            // way into the vault — down with it.
            val loaded = runCatching {
                val available = runCatching { BiometricUtils.canAuthenticate(context) }.getOrDefault(false)
                LockUiState(
                    ready = true,
                    pinLength = appLockManager.pinLength.first().coerceIn(4, 8),
                    randomizeKeypad = appLockManager.randomizeKeypad.first(),
                    biometricAvailable = available,
                    biometricEnabled = available && appLockManager.isBiometricEnabled(),
                    lockoutUntil = appLockManager.lockedUntil()
                        .takeIf { it > System.currentTimeMillis() } ?: 0L,
                    attemptsLeft = (5 - appLockManager.failedAttempts()).coerceIn(0, 5)
                )
            }.getOrElse {
                LockUiState(ready = true, error = "Security settings could not be read.")
            }
            _state.value = loaded
        }
    }

    fun submit(secret: String) {
        val current = _state.value
        if (current.busy || secret.isBlank()) return
        if (current.lockoutUntil > System.currentTimeMillis()) {
            refreshLockoutState()
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { appLockManager.verifySecret(secret) }
            outcome.fold(
                onSuccess = { result ->
                    if (result == UnlockResult.INVALID) {
                        val lockout = runCatching { appLockManager.recordUnlockFailure() }.getOrDefault(0L)
                        val attempts = runCatching { appLockManager.failedAttempts() }.getOrDefault(5)
                        val remaining = if (lockout > 0L) 0 else (5 - attempts).coerceIn(1, 5)
                        _state.update {
                            it.copy(
                                busy = false,
                                lockoutUntil = lockout,
                                attemptsLeft = remaining,
                                error = if (lockout > 0L) {
                                    null
                                } else {
                                    "Incorrect credentials · $remaining ${if (remaining == 1) "attempt" else "attempts"} left"
                                }
                            )
                        }
                    } else {
                        runCatching { appLockManager.clearUnlockFailures() }
                        _state.update { it.copy(busy = false, error = null, lockoutUntil = 0L) }
                        sessionManager.unlock(decoy = result == UnlockResult.DECOY_PIN)
                    }
                },
                onFailure = {
                    _state.update {
                        it.copy(busy = false, error = "The vault could not be checked. Try again.")
                    }
                }
            )
        }
    }

    fun onBiometricSuccess() {
        if (_state.value.lockoutUntil > System.currentTimeMillis()) return
        viewModelScope.launch {
            runCatching { appLockManager.clearUnlockFailures() }
            _state.update { it.copy(lockoutUntil = 0L, error = null, busy = false) }
            sessionManager.unlock(decoy = false)
        }
    }

    fun refreshLockoutState() {
        viewModelScope.launch {
            val until = runCatching { appLockManager.lockedUntil() }.getOrDefault(0L)
            val attempts = runCatching { appLockManager.failedAttempts() }.getOrDefault(0)
            _state.update {
                it.copy(
                    lockoutUntil = if (until > System.currentTimeMillis()) until else 0L,
                    attemptsLeft = (5 - attempts).coerceIn(0, 5),
                    busy = false
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun biometricUnavailableMessage(): String = "Biometric unlock is not available on this device"
}
