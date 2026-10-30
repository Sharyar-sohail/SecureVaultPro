package com.securevault.pro.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionMode { LOCKED, REAL, DECOY }

@Singleton
class SessionManager @Inject constructor() {
    private val _mode = MutableStateFlow(SessionMode.LOCKED)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    fun unlock(decoy: Boolean) {
        _mode.value = if (decoy) SessionMode.DECOY else SessionMode.REAL
    }

    fun lock() {
        _mode.value = SessionMode.LOCKED
    }

    fun isUnlocked(): Boolean = _mode.value != SessionMode.LOCKED
    fun isDecoy(): Boolean = _mode.value == SessionMode.DECOY
}
