package com.securevault.pro.security

import android.content.Context
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.VaultArchiveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * True only when the switch is armed, a check-in actually exists and the deadline has been
     * reached. An armed switch with `lastCheckInAt = 0` has no deadline to enforce, and an
     * unarmed one never does - neither may trigger an erase.
     */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        armed && lastCheckInAt > 0L && now >= dueAt()
}

/** What [DeadManSwitchManager.enforce] did, said plainly enough for the UI to report. */
sealed interface DeadManOutcome {
    /** The deadline has not been reached (or the switch is unarmed): nothing was touched. */
    data object NotDue : DeadManOutcome

    /**
     * The deadline passed, but no Drive account can receive the pre-erase archive. Nothing was
     * erased and nothing will be until Drive is connected and the app is launched again.
     */
    data object NeedsDrive : DeadManOutcome

    /** The vault was archived to a visible Drive ZIP and only then erased from this device. */
    data class ArchivedAndErased(val outcome: WipeOutcome) : DeadManOutcome

    /** The archive could not be produced, so rule 1 aborted the erase: the vault is intact. */
    data class ArchiveFailed(val reason: String) : DeadManOutcome
}

/**
 * The dead man's switch.
 *
 * The countdown is armed and reset in Settings; every REAL unlock also counts as a check-in.
 * Enforcement happens on the **next app launch after the deadline** (and is flagged at boot by
 * [DeadManBootReceiver], so a handset that never sees a lock screen still reports the miss):
 *
 * 1. [evaluateOnStartup] reports whether the deadline has passed. Reporting alone erases nothing.
 * 2. [enforce] locks nothing by itself - the caller locks the session - and then, only when the
 *    deadline is genuinely overdue:
 *    - if Google Drive is not connected it returns [DeadManOutcome.NeedsDrive] and leaves the
 *      vault, the settings and the countdown untouched. There is no archive destination, so
 *      there is no erase;
 *    - otherwise it uploads the vault to a **visible Drive ZIP** first and aborts with
 *      [DeadManOutcome.ArchiveFailed] (nothing erased) if that upload fails or stalls;
 *    - only once the archive exists does it erase local material through
 *      [LocalWipeService.wipeAll] with `deleteCloud = false`, which keeps every cloud copy.
 *
 * Encrypted Drive envelopes (the appDataFolder slot, the archive folder and the realtime tree)
 * are never deleted by this switch; `deleteCloud = true` exists only behind the explicit
 * "Erase all data" action in Settings. See [LocalWipeService] rule 1.
 *
 * [expired] therefore means "the deadline was reached and the miss is not yet resolved", not
 * "something was destroyed".
 */
@Singleton
class DeadManSwitchManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val localWipeService: LocalWipeService,
    private val cloudSyncManager: CloudSyncManager
) {
    private companion object {
        /**
         * The archive is a whole-vault seal plus one Drive upload; Drive's own per-request bound
         * is 180 s. A timeout can only ever abort the erase, never cause one.
         */
        const val ARCHIVE_TIMEOUT_MS = 240_000L
    }

    private val _expired = MutableStateFlow(false)

    /**
     * True when an armed switch's deadline was reached and the miss has not yet been resolved
     * (archived and erased, or acknowledged by the owner). It is a status, never an instruction:
     * see [enforce] for the only code allowed to act on it.
     */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    suspend fun status(): DeadManStatus {
        val (armed, interval, last) = appLockManager.deadManSnapshot()
        return DeadManStatus(armed = armed, intervalDays = interval, lastCheckInAt = last)
    }

    suspend fun arm(intervalDays: Int) {
        appLockManager.armDeadManSwitch(intervalDays)
        DeadManOverdueStore.clear(context)
        _expired.value = false
    }

    suspend fun disarm() {
        appLockManager.disarmDeadManSwitch()
        DeadManOverdueStore.clear(context)
        _expired.value = false
    }

    suspend fun checkIn() {
        appLockManager.recordDeadManCheckIn()
        // The owner is present again, so a boot flag recorded before this check-in is stale.
        DeadManOverdueStore.clear(context)
        _expired.value = false
    }

    /**
     * Runs on every launch. Returns true when the deadline had passed; deletes nothing. A flag
     * written by [DeadManBootReceiver] counts as overdue even if the wall clock later moved
     * backwards, so a boot during the miss cannot make the switch forget it.
     */
    suspend fun evaluateOnStartup(now: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            val snapshot = status()
            if (!snapshot.armed) DeadManOverdueStore.clear(context)
            // A boot flag only counts for an armed switch that has a real check-in baseline and
            // that has not been answered by a later check-in; a damaged snapshot without a
            // baseline must never be read as "overdue".
            val overdue = snapshot.armed && snapshot.lastCheckInAt > 0L &&
                (bootFlagIsCurrent(snapshot) || snapshot.isDue(now))
            _expired.value = overdue
            overdue
        }

    /**
     * Acts on an overdue deadline. Returns [DeadManOutcome.NotDue] without touching anything when
     * the switch is not due; never erases without a visible Drive archive; keeps all cloud copies.
     * Lock the session before calling this (the caller owns that decision) so no live observer can
     * re-upload what is about to be deleted.
     */
    suspend fun enforce(now: Long = System.currentTimeMillis()): DeadManOutcome =
        withContext(Dispatchers.IO) {
            val snapshot = status()
            val overdue = snapshot.armed && snapshot.lastCheckInAt > 0L &&
                (bootFlagIsCurrent(snapshot) || snapshot.isDue(now))
            if (!overdue) {
                if (!snapshot.armed) DeadManOverdueStore.clear(context)
                _expired.value = false
                return@withContext DeadManOutcome.NotDue
            }
            _expired.value = true

            // No Drive means no destination for the archive, and rule 1 says no archive means no
            // erase. The vault, the settings and the countdown all stay exactly as they are.
            if (!cloudSyncManager.canAccessDrive()) {
                DeadManOverdueStore.mark(context)
                return@withContext DeadManOutcome.NeedsDrive
            }

            // The visible archive comes first. `wipeAll(deleteCloud = false)` deliberately makes
            // no archive of its own - it only archives when it is also asked to delete the cloud
            // copies - so the switch produces the copy here, before handing over to the one
            // erase path, and aborts if that copy does not exist.
            val archiveStep: Result<VaultArchiveInfo>? = try {
                withTimeoutOrNull(ARCHIVE_TIMEOUT_MS) { cloudSyncManager.archiveVaultSnapshot() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val archive = archiveStep?.getOrNull()
            if (archive == null) {
                val reason = archiveStep?.exceptionOrNull()?.message?.take(240)
                    ?: "The archive did not finish within ${ARCHIVE_TIMEOUT_MS / 1000} seconds."
                return@withContext DeadManOutcome.ArchiveFailed(
                    "Nothing was erased. The pre-erase archive could not be uploaded: $reason"
                )
            }
            val outcome = localWipeService.wipeAll(deleteCloud = false)
            if (!outcome.localCleared) {
                return@withContext DeadManOutcome.ArchiveFailed(
                    "Nothing was erased. " +
                        (outcome.cloudError ?: "The local wipe reported that it did not complete.")
                )
            }
            // Only now does the archived name describe a wiped device; recording it earlier would
            // set the "was wiped" marker even if the local erase had failed.
            WipeArchiveStore.record(context, archive.name)

            DeadManOverdueStore.clear(context)
            _expired.value = false
            DeadManOutcome.ArchivedAndErased(outcome.copy(archiveName = archive.name))
        }

    /** The owner has seen the notice; restart the countdown so it stops repeating. */
    suspend fun acknowledgeExpired() {
        checkIn()
    }

    /**
     * True when the boot flag was written after the last check-in. The comparison is what makes a
     * stale flag harmless: a check-in that follows the boot resets the countdown, so the flag it
     * outdates must not force an erase even if clearing it never reached the disk.
     */
    private fun bootFlagIsCurrent(snapshot: DeadManStatus): Boolean {
        val flaggedAt = DeadManOverdueStore.flaggedAt(context)
        return flaggedAt > 0L &&
            snapshot.lastCheckInAt > 0L &&
            snapshot.lastCheckInAt < flaggedAt
    }
}
