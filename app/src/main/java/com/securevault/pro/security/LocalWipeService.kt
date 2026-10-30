package com.securevault.pro.security

import android.content.Context
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.realtime.RealtimeEngine
import com.securevault.pro.data.local.dao.MailAccountDao
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class WipeOutcome(
    val localCleared: Boolean,
    val cloudDeleted: Int,
    val archiveName: String? = null,
    val cloudError: String? = null,
    /** Realtime nodes (`data`, `meta`, `keyring`) hard-deleted for this account. */
    val realtimeDeleted: Int = 0
)

/**
 * Survives [AppLockManager.wipeSecurityPrefs] so the welcome screen can still
 * point the owner at the archive that was uploaded before the wipe.
 */
object WipeArchiveStore {
    private const val PREFS = "wipe_archive"
    private const val KEY_NAME = "archive_name"
    private const val KEY_TIME = "archive_time"
    private const val KEY_WIPED_AT = "wiped_at"

    fun record(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putLong(KEY_WIPED_AT, System.currentTimeMillis())
            .commit()
    }

    fun markWiped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_WIPED_AT, System.currentTimeMillis())
            .commit()
    }

    /**
     * Survives the DataStore wipe on purpose: a wiped install must never silently
     * re-seed the embedded demo vault, or Home shows counts with no records.
     */
    fun wasWiped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_WIPED_AT, 0L) > 0L

    fun latestName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

/**
 * Single place that knows how to destroy local vault material, and the only entry point is
 * [wipeAll]: "Erase all data" archives the vault into a visible Drive ZIP first, clears the private
 * appDataFolder slot afterwards and only then erases local state. The unarchived variant this class
 * used to carry (`wipeLocalOnly`, run by the dead man's switch on a timer) is gone — destroying the
 * Keystore key without a copy is the one thing this product must never do by itself.
 *
 * Locking the session first is deliberate: the live sync observer ignores
 * mutations outside a REAL session, so a wipe can never upload an empty vault.
 *
 * ## The three rules this file exists to enforce
 *
 * 1. **Never erase without a copy.** The archive is uploaded to a *visible* Drive folder
 *    before anything is touched locally, and a failed or timed-out archive aborts the whole
 *    operation with `localCleared = false`. Losing the vault and the only copy of it in the
 *    same action is the one failure mode this product must not have.
 * 2. **A wiped device must never keep sealing.** [RealtimeEngine.stopAndClear] (which drops
 *    the device vault key internally) plus [Keyring.clearDeviceKey] run in a `finally` after
 *    the local wipe, so even a cloud step that fails half-way cannot leave a live engine
 *    writing records under a key the owner just destroyed.
 * 3. **No dead ends.** Every network step is bounded by its own timeout, so a stalled Drive
 *    call returns a worded outcome to the UI instead of hanging the wipe coroutine forever.
 * 4. **"All data" means all of it.** Once a copy exists, [RealtimeEngine.eraseCloudData] hard-deletes
 *    the realtime tree the web companion reads — every module table, the revision meta and the
 *    keyring — so a wiped account cannot keep serving ciphertext, or the key that opens it, to a
 *    browser. It is gated on the same archive as rule 1 and counted in `WipeOutcome.realtimeDeleted`.
 */
@Singleton
class LocalWipeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VaultRepository,
    private val cloudSyncManager: CloudSyncManager,
    private val crypto: CryptoManager,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val keyring: Keyring,
    private val realtimeEngine: RealtimeEngine,
    private val mailAccountDao: MailAccountDao
) {

    private companion object {
        /**
         * The archive is a whole-vault seal plus one Drive upload; Drive's own per-request
         * bound is 180 s, so this is deliberately longer than the sum of its parts. It can
         * only ever *abort* a wipe (nothing is erased), so a generous value is the safe one.
         */
        const val ARCHIVE_TIMEOUT_MS = 240_000L

        /** Deleting the app's own appDataFolder files - a handful of requests, not a payload. */
        const val CLOUD_DELETE_TIMEOUT_MS = 90_000L

        /**
         * Removing the three realtime roots (`data`, `meta`, `keyring`) for one account: three
         * requests, each already bounded inside the engine. Longer than the engine's own ceiling so
         * the outer bound only ever catches a genuine hang.
         */
        const val REALTIME_ERASE_TIMEOUT_MS = 60_000L

        /** Sign-out must never be the reason a wipe hangs; it is housekeeping, not safety. */
        const val DRIVE_SIGNOUT_TIMEOUT_MS = 10_000L

        /**
         * Bounds [RealtimeEngine.stopAndClear]. It has to run, so this is a ceiling on a
         * hang rather than a bet that it will not happen: on timeout the local key material
         * is still destroyed and the fault is recorded.
         */
        const val ENGINE_STOP_TIMEOUT_MS = 20_000L
    }

    suspend fun wipeAll(deleteCloud: Boolean): WipeOutcome = withContext(Dispatchers.IO) {
        var cloudDeleted = 0
        var realtimeDeleted = 0
        var cloudError: String? = null
        var archiveName: String? = null

        val cloudConnected = deleteCloud && cloudSyncManager.canAccessDrive()
        if (cloudConnected) {
            // Rule 1: the copy exists and is visible in Drive before anything is destroyed.
            val archiveStep = attemptWithin(ARCHIVE_TIMEOUT_MS) { cloudSyncManager.archiveVaultSnapshot() }
            val archiveInfo = archiveStep.value?.getOrNull()
            if (archiveInfo == null) {
                val reason = when {
                    archiveStep.fault != null ->
                        "The pre-wipe archive could not be uploaded: " +
                            (archiveStep.fault?.message?.take(200) ?: "Google Drive was unreachable.")
                    archiveStep.value == null ->
                        "The pre-wipe archive did not finish within " +
                            "${ARCHIVE_TIMEOUT_MS / 1000} seconds."
                    else -> archiveStep.value?.exceptionOrNull()?.message?.take(200)
                        ?: "The pre-wipe archive could not be uploaded."
                }
                return@withContext WipeOutcome(
                    localCleared = false,
                    cloudDeleted = 0,
                    cloudError = "Nothing was erased. $reason " +
                        "Check your connection and erase again - your vault is still intact on this device."
                )
            }
            archiveName = archiveInfo.name
            WipeArchiveStore.record(context, archiveInfo.name)
        }

        // Local wipe. The session is locked first so no observer can re-upload what is being
        // deleted, and the vault key is released in the `finally` below whatever happens next.
        try {
            sessionManager.lock()
            runCatching { repository.wipeAllData() }
            // The mail client's rows are device-local, so the cloud archive never held them and
            // nothing else would clear them: `Erase everything` means the configured mailboxes go
            // too. Deliberately not a TTL or a background sweep — this is the owner's press.
            runCatching { mailAccountDao.deleteAll() }
            runCatching { crypto.deleteKeystoreKey() }
            runCatching { appLockManager.wipeSecurityPrefs() }
            clearTransientFiles()
            WipeArchiveStore.markWiped(context)

            // Cloud erase. Best effort now that the copy is safe and the local vault is gone;
            // Drive stays connected on failure so the owner can retry it immediately.
            if (cloudConnected) {
                val deleteStep = attemptWithin(CLOUD_DELETE_TIMEOUT_MS) {
                    cloudSyncManager.deleteAllCloudBackups()
                }
                val deleted = deleteStep.value
                cloudError = when {
                    deleted == null && deleteStep.fault != null ->
                        "The cloud copies could not be deleted: " +
                            (deleteStep.fault?.message?.take(200) ?: "Google Drive was unreachable.") +
                            " Your archive is saved as $archiveName. Retry while online."
                    deleted == null ->
                        "The cloud copies could not be deleted within " +
                            "${CLOUD_DELETE_TIMEOUT_MS / 1000} seconds. " +
                            "Your archive is saved as $archiveName. Retry while online."
                    deleted.isSuccess -> {
                        cloudDeleted = deleted.getOrDefault(0)
                        runCatching {
                            withTimeout(DRIVE_SIGNOUT_TIMEOUT_MS) { cloudSyncManager.signOutOfDrive() }
                        }
                        null
                    }
                    else -> "The cloud copies could not be deleted: " +
                        (deleted.exceptionOrNull()?.message?.take(200) ?: "Google Drive was unavailable.") +
                        " Your archive is saved as $archiveName. Retry while online."
                }
            }

            // Realtime erase. The sync tree is the part the web companion reads, so an "erase all
            // data" that left `svp1/users/$uid` intact would leave the owner's ciphertext — and the
            // key that opens it — on the account forever. It runs only once a Drive archive exists,
            // under rule 1: the copy is what makes deleting the keyring safe.
            if (deleteCloud && archiveName != null) {
                val erase = attemptWithin(REALTIME_ERASE_TIMEOUT_MS) { realtimeEngine.eraseCloudData() }
                val outcome = erase.value
                when {
                    outcome == null -> cloudError = joinError(
                        cloudError,
                        "The synced copies could not be deleted" +
                            (if (erase.fault != null) ": ${erase.fault?.message?.take(200)}" else
                                " within ${REALTIME_ERASE_TIMEOUT_MS / 1000} seconds") +
                            ". Your archive is saved as $archiveName. Erase again while online."
                    )
                    outcome.error != null -> cloudError = joinError(
                        cloudError,
                        "Some synced copies could not be deleted: ${outcome.error} " +
                            "Your archive is saved as $archiveName. Erase again while online."
                    )
                }
                realtimeDeleted = outcome?.nodesRemoved ?: 0
            } else if (deleteCloud) {
                cloudError = joinError(
                    cloudError,
                    "The synced copies are still on the account because no archive could be made. " +
                        "Erase again while signed in to Drive."
                )
            }
        } finally {
            // Rule 2: runs even when a step above threw, so this device cannot go on sealing
            // records with the key the owner just erased.
            releaseRealtimeKeyring()
        }

        WipeOutcome(
            localCleared = true,
            cloudDeleted = cloudDeleted,
            archiveName = archiveName,
            cloudError = cloudError,
            realtimeDeleted = realtimeDeleted
        )
    }

    /** Keeps both failures when Drive and the realtime tree each went wrong. */
    private fun joinError(first: String?, second: String): String =
        if (first.isNullOrBlank()) second else "$first $second"

    /**
     * Stops the realtime engine (which clears the device vault key) and clears the keyring's own
     * copy, in that order, never throwing. A wiped or dead-man-triggered device must not be able
     * to seal another record under a destroyed vault.
     */
    private suspend fun releaseRealtimeKeyring() {
        val stopped = attemptWithin(ENGINE_STOP_TIMEOUT_MS) { realtimeEngine.stopAndClear() }
        if (stopped.fault != null || stopped.value == null) {
            CrashLogger.noteFault(
                context,
                "Wipe: the realtime engine did not stop cleanly, the local key was cleared anyway",
                stopped.fault ?: IllegalStateException("stopAndClear timed out after $ENGINE_STOP_TIMEOUT_MS ms")
            )
        }
        runCatching { keyring.clearDeviceKey() }
    }

    /** The result of a bounded step: a value, or the reason it never produced one. */
    private class Step<out T>(val value: T?, val fault: Throwable?)

    /**
     * Runs [block] under its own ceiling. A timeout arrives as `value = null` with no fault; any
     * other failure keeps its message so the owner can be told what actually went wrong. A
     * cancelled scope is re-thrown rather than reported - swallowing it here would let a wipe
     * continue after its caller had gone away.
     */
    private suspend fun <T> attemptWithin(timeoutMs: Long, block: suspend () -> T): Step<T> =
        try {
            Step(withTimeoutOrNull(timeoutMs) { block() }, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Step(null, e)
        }

    private fun clearTransientFiles() {
        runCatching { File(context.filesDir, "offline_backup").deleteRecursively() }
        runCatching { File(context.cacheDir, "secure_share").deleteRecursively() }
        runCatching { File(context.cacheDir, "vault_exports").deleteRecursively() }
        runCatching { File(context.cacheDir, "vault_staging").deleteRecursively() }
        runCatching { File(context.cacheDir, "voice_memos").deleteRecursively() }
    }
}
