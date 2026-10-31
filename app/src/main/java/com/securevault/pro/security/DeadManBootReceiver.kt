package com.securevault.pro.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A persisted "the deadline was already missed" marker, written by [DeadManBootReceiver] and read
 * by [DeadManSwitchManager] on the next launch.
 *
 * It exists because a boot or an app update can land after the deadline while the wall clock is
 * ambiguous (timezone change, manual clock, a date restored by a backup). A boot that saw an
 * overdue switch must not become invisible just because the date moved again: the next launch
 * enforces whenever either the deadline math *or* this flag says the miss happened.
 *
 * Only the instant the flag was written is stored. A later check-in has a later timestamp, so a
 * flag the owner has already answered is stale and ignored - a clear that never reached the disk
 * therefore cannot force an enforcement the owner already cancelled. No secret, no vault data.
 */
object DeadManOverdueStore {
    private const val PREFS = "dead_man_overdue"
    private const val KEY_OVERDUE_AT = "overdue_at"

    fun mark(context: Context, at: Long = System.currentTimeMillis()) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_OVERDUE_AT, at)
                .commit()
        }
    }

    /** When the boot flag was written, or 0 when there is none. */
    fun flaggedAt(context: Context): Long = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_OVERDUE_AT, 0L)
    }.getOrDefault(0L)

    fun clear(context: Context) {
        // Best effort: staleness is decided by comparing timestamps, not by this write landing.
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_OVERDUE_AT)
                .apply()
        }
    }
}

/**
 * The graph needs only the deadline snapshot here. Exposing [AppLockManager] rather than
 * [DeadManSwitchManager] keeps the boot path light: building the switch would also build the wipe
 * service, the cloud manager, Room and the realtime engine inside a broadcast's short budget,
 * while the snapshot is one DataStore read. No field injection and no [dagger.hilt.android.AndroidEntryPoint]
 * - the entry point is resolved explicitly.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeadManSnapshotEntryPoint {
    fun appLockManager(): AppLockManager
}

/**
 * Records an expired dead man's switch at boot.
 *
 * This receiver never erases: enforcement belongs to the next real app launch
 * ([DeadManSwitchManager.enforce]), where the session can be locked and the owner sees what
 * happened. All it does is make sure that launch cannot miss the miss - see [DeadManOverdueStore].
 */
class DeadManBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appLockManager = EntryPointAccessors
                    .fromApplication(appContext, DeadManSnapshotEntryPoint::class.java)
                    .appLockManager()
                val (armed, interval, lastCheckInAt) = appLockManager.deadManSnapshot()
                if (DeadManStatus(armed, interval, lastCheckInAt).isDue()) {
                    DeadManOverdueStore.mark(appContext)
                }
            } catch (error: Throwable) {
                // A boot check must never take the process down; the next launch re-evaluates
                // the deadline from the same snapshot anyway.
                runCatching {
                    CrashLogger.noteFault(
                        appContext,
                        "Dead man's switch: the boot check could not read the deadline",
                        error
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
