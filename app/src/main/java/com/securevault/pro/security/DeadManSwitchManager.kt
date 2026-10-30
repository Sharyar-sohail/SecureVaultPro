package com.securevault.pro.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeadManStatus(
    val armed: Boolean = false,
    val intervalDays: Int = 7,
    val lastCheckInAt: Long = 0L
) {
    fun dueAt(): Long =
        if (lastCheckInAt <= 0L) 0L
        else lastCheckInAt + intervalDays * 24L * 60L * 60L * 1000L

    fun daysRemaining(now: Long = System.currentTimeMillis()): Int {
        val due = dueAt()
        if (due <= 0L) return intervalDays
        val remaining = due - now
        if (remaining <= 0L) return 0
        return ((remaining + 86_399_999L) / 86_400_000L).toInt()
    }
}

/**
 * A deliberately local-only dead man's switch that no longer destroys anything.
 *
 * It used to erase the local vault on the first launch after the deadline passed, with no archive
 * and no confirmation — which is the exact behaviour the owner banned: data must stay recoverable
 * until they delete it by hand, and being offline is normal, not an offence. The countdown still
 * runs so an absent owner is told the truth when they come back, and the only way the deadline can
 * now cost data is the explicit "Erase all data" action in Settings, which archives first.
 *
 * There is intentionally no cloud call anywhere in this class.
 */
@Singleton
class DeadManSwitchManager @Inject constructor(
    private val appLockManager: AppLockManager
) {
    private val _expired = MutableStateFlow(false)

    /**
     * True when an armed switch's deadline passed while the owner was away. Reporting it is not the
     * same as acting on it: nothing has been deleted and nothing will be unless the owner says so.
     */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    suspend fun status(): DeadManStatus {
        val (armed, interval, last) = appLockManager.deadManSnapshot()
        return DeadManStatus(armed = armed, intervalDays = interval, lastCheckInAt = last)
    }

    suspend fun arm(intervalDays: Int) = appLockManager.armDeadManSwitch(intervalDays)

    suspend fun disarm() {
        appLockManager.disarmDeadManSwitch()
        _expired.value = false
    }

    suspend fun checkIn() = appLockManager.recordDeadManCheckIn()

    /**
     * Runs on every launch. Returns true when the deadline had passed; deletes nothing.
     */
    suspend fun evaluateOnStartup(): Boolean = withContext(Dispatchers.IO) {
        val snapshot = status()
        val overdue = snapshot.armed && snapshot.lastCheckInAt > 0L &&
            System.currentTimeMillis() >= snapshot.dueAt()
        _expired.value = overdue
        overdue
    }

    /** The owner has seen the notice; restart the countdown so it stops repeating. */
    suspend fun acknowledgeExpired() {
        checkIn()
        _expired.value = false
    }
}
