package com.securevault.pro.data.remote.google

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.backup.BackupEngine
import com.securevault.pro.data.backup.BackupEntryDto
import com.securevault.pro.data.backup.BackupParser
import com.securevault.pro.data.backup.BackupPayloadDto
import com.securevault.pro.data.backup.BackupPreview
import com.securevault.pro.data.backup.BackupXmlSerializer
import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.data.backup.CloudOperation
import com.securevault.pro.data.backup.CloudSyncState
import com.securevault.pro.data.backup.ParsedBackup
import com.securevault.pro.data.backup.RestoreWalkOrder
import com.securevault.pro.data.remote.companion.CompanionSyncClient
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.KeyDerivation
import com.securevault.pro.security.SeedLoader
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.WipeArchiveStore
import com.securevault.pro.security.backup.BackupEnvelope
import com.securevault.pro.security.backup.BackupEnvelopeFormat
import com.securevault.pro.security.backup.EnvelopeDiagnostics
import com.securevault.pro.security.backup.LegacyEnvelopeOpener
import com.securevault.pro.security.backup.LegacyKeyPlan
import com.securevault.pro.security.backup.LegacySaltPlan
import com.securevault.pro.security.backup.OpeningSecret
import com.securevault.pro.security.backup.OpeningSecretKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class CloudSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RestoreOutcome(
    val counts: BackupCounts,
    val incoming: BackupCounts,
    val usedSeed: Boolean,
    val warnings: List<String>,
    val message: String,
    val failureReason: String? = null,
    val skipped: Int = 0,
    val replaced: Boolean = false
)

data class BackupOutcome(
    val counts: BackupCounts,
    val fileName: String,
    val cloudUploaded: Boolean,
    val offlineSaved: Boolean,
    val driveError: String? = null,
    /** Set when the slot held a vault this device could not open and its bytes were kept as [name]. */
    val preservedName: String? = null
)

data class LocalExportData(
    val bytes: ByteArray,
    val fileName: String,
    val counts: BackupCounts
)

data class PushOutcome(
    val counts: BackupCounts,
    val bytes: Int,
    val message: String
)

data class QuietSyncOutcome(
    val counts: BackupCounts,
    val jsonBytes: Int,
    val xmlBytes: Int,
    val uploadedAt: Long
)

data class XmlExportData(
    val bytes: ByteArray,
    val fileName: String,
    val counts: BackupCounts
)

data class VaultArchiveInfo(
    val id: String,
    val name: String,
    val size: Long,
    val counts: BackupCounts,
    val createdAt: Long
)

@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveService: GoogleDriveService,
    private val companionSyncClient: CompanionSyncClient,
    private val backupEngine: BackupEngine,
    private val seedLoader: SeedLoader,
    private val crypto: CryptoManager,
    private val keyDerivation: KeyDerivation,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val vaultRepository: VaultRepository,
    private val keyring: Keyring,
    private val legacyOpener: LegacyEnvelopeOpener,
    private val gson: Gson
) {
    companion object {
        const val PRIMARY_BACKUP_NAME = "vault_backup.enc"
        const val XML_BACKUP_NAME = "vault_backup.xml.enc"

        /** The salt file the original writer kept beside a salt-less backup. */
        const val LEGACY_SALT_FILE_NAME = "key.salt"

        /**
         * READ-ONLY since the per-user keyring landed. These strings seal nothing any more: they
         * survive only as candidates for *opening* a vault that was backed up before the keyring —
         * which is exactly what a reinstall with a lost device key needs — and the first write after
         * that re-seals it under the user's own key. They live in `LegacyKeyPlan` now, next to the
         * order that uses them; no new envelope may use them, and a test guards the call sites.
         */
        const val PBKDF2_ITERATIONS = KeyDerivation.PBKDF2_ITERATIONS
        private const val MAX_BACKUP_BYTES = 120L * 1024L * 1024L
        private const val RESTORE_TIMEOUT_MS = 60_000L

        /** Ceiling on the whole walk: primary slot plus the newest fallbacks, never every file ever. */
        private const val MAX_WALK_FILES = 3

        /** How long a passphrase is allowed to fetch the account's vault key before the restore moves on. */
        private const val KEYRING_ATTACH_TIMEOUT_MS = 45_000L
    }

    private val operationMutex = Mutex()

    /**
     * Bumped by [abandonOperation]. A decrypt sweep captures it at the start and stops on the next
     * candidate when it changes, so "Stop waiting" ends CPU work immediately instead of letting a
     * blocked coroutine finish its key list first.
     */
    private val operationEpoch = AtomicLong(0)
    private val _state = MutableStateFlow(CloudSyncState())
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val slotProofs by lazy { BackupSlotGuard.Prefs(context) }

    // ------------------------- Public operations -------------------------

    suspend fun backupToDrive(email: String = ""): Result<BackupOutcome> = guarded {
        onOperation(CloudOperation.BACKUP, "Creating encrypted backup", "Collecting vault records", 6)
        val counts = requireVaultRecords()
        update("Encrypting vault records", "PBKDF2-SHA256 · 600,000 iterations", 32)
        val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
        val json = gson.toJson(payload)
        val secret = sealingSecret()
        val envelopeBytes = createEnvelope(json, secret, 3)
        update("Writing offline safety copy", "vault_backup.enc", 48)
        val offlineSaved = writeOfflineCopy(envelopeBytes)
        update("Uploading to Google Drive", "App-data folder · hidden from normal Drive", 62)
        var driveError: String? = null
        var uploaded = false
        var preservedName: String? = null
        try {
            val outcome = writeSlot(PRIMARY_BACKUP_NAME, envelopeBytes, secret)
            if (outcome is BackupSlotGuard.Outcome.Held) {
                throw CloudSyncException(outcome.reason)
            }
            uploaded = true
            preservedName = (outcome as BackupSlotGuard.Outcome.Written).preservedName
            appLockManager.markBackupComplete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            driveError = driveMessage(e)
        }
        if (uploaded) {
            update("Mirroring XML vault", "Second sealed copy · vault_backup.xml.enc", 76)
            runCatching {
                val xmlBytes = createEnvelope(
                    BackupXmlSerializer.serialize(payload),
                    secret,
                    3
                )
                writeOfflineCopy(xmlBytes, XML_BACKUP_NAME)
                writeSlot(XML_BACKUP_NAME, xmlBytes, secret)
            }
            update("Archiving cloud snapshot", "Keeping a dated copy of this snapshot", 84)
            runCatching { uploadSnapshot(envelopeBytes) }
        }
        val finalCounts = backupEngine.countAll()
        vaultRepository.reloadVaultState()
        val message = when {
            uploaded ->
                "Encrypted vault backed up · ${summary(finalCounts)}" +
                    (preservedName?.let { " · previous unreadable copy kept as $it" } ?: "")
            offlineSaved -> "Offline encrypted backup saved. Google Drive upload failed."
            else -> throw CloudSyncException(driveError ?: "Backup could not be saved anywhere")
        }
        completeOperation(message)
        BackupOutcome(
            counts = finalCounts,
            fileName = PRIMARY_BACKUP_NAME,
            cloudUploaded = uploaded,
            offlineSaved = offlineSaved,
            driveError = driveError,
            preservedName = preservedName
        )
    }

    suspend fun listCloudBackups(): Result<List<CloudBackupFile>> = kotlin.runCatching {
        withContext(Dispatchers.IO) {
            if (!driveService.hasDriveAccess()) {
                throw CloudSyncException(DriveFaults.SIGN_IN_AGAIN)
            }
            // The XML mirror is a first-class backup: a JSON slot this device cannot read must
            // not stop the XML copy of the same vault from being tried.
            driveService.listBackups()
        }
    }

    /**
     * Silent path used by the live sync engine. It deliberately avoids the shared
     * [CloudSyncState] so background edits never surface spinners inside the UI.
     */
    suspend fun syncNowSilently(email: String = ""): Result<QuietSyncOutcome> {
        if (sessionManager.isDecoy()) {
            return Result.failure(CloudSyncException("Live sync is disabled in this vault."))
        }
        return operationMutex.withLock {
            try {
                if (!driveService.hasDriveAccess()) {
                    throw CloudSyncException("Google Drive is not connected.")
                }
                val counts = backupEngine.countAll()
                if (counts.total == 0) {
                    throw CloudSyncException("The vault is empty.")
                }
                val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
                val secret = sealingSecret()
                val jsonBytes = createEnvelope(gson.toJson(payload), secret, 3)
                val xmlBytes = withContext(Dispatchers.Default) {
                    createEnvelope(
                        BackupXmlSerializer.serialize(payload),
                        secret,
                        3
                    )
                }
                writeOfflineCopy(jsonBytes, PRIMARY_BACKUP_NAME)
                writeOfflineCopy(xmlBytes, XML_BACKUP_NAME)
                val jsonOutcome = writeSlot(PRIMARY_BACKUP_NAME, jsonBytes, secret)
                if (jsonOutcome is BackupSlotGuard.Outcome.Held) {
                    throw CloudSyncException(jsonOutcome.reason)
                }
                writeSlot(XML_BACKUP_NAME, xmlBytes, secret)
                appLockManager.markBackupComplete()
                Result.success(
                    QuietSyncOutcome(
                        counts = counts,
                        jsonBytes = jsonBytes.size,
                        xmlBytes = xmlBytes.size,
                        uploadedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(CloudSyncException(driveMessage(e), e))
            }
        }
    }

    suspend fun exportXmlBackup(passphrase: String): Result<XmlExportData> = guarded {
        val trimmed = passphrase.trim()
        if (trimmed.length < 8) {
            throw CloudSyncException("Use at least 8 characters for the export passphrase")
        }
        onOperation(
            CloudOperation.EXPORT,
            "Exporting XML vault",
            "Collecting every encrypted record",
            16
        )
        val counts = requireVaultRecords()
        val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
        update("Sealing XML archive", "AES-256-GCM · PBKDF2 600,000", 52)
        val bytes = createEnvelope(BackupXmlSerializer.serialize(payload), trimmed, 3)
        val fileName = "SecureWallet_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }_vault.xml.enc"
        completeOperation("XML export ready · ${summary(counts)}")
        XmlExportData(bytes = bytes, fileName = fileName, counts = counts)
    }

    /**
     * Inspects a cloud backup without writing to the database. The restore action does
     * not depend on this succeeding, so the UI can always offer a direct restore.
     */
    suspend fun previewCloud(
        file: CloudBackupFile,
        email: String = ""
    ): Result<BackupPreview> = withContext(Dispatchers.IO) {
        try {
            val bytes = withTimeout(RESTORE_TIMEOUT_MS) { driveService.download(file.id) }
            val decoded = decryptBackup(bytes, email)
            val parsed = BackupParser.parse(decoded.json)
            val warnings = (decoded.warnings + parsed.warnings).distinct().take(6)
            Result.success(
                BackupPreview(
                    fileName = file.name,
                    counts = payloadCounts(parsed.payload),
                    encrypted = decoded.encrypted,
                    warnings = warnings,
                    readyForDirectRestore = true
                )
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(CloudSyncException("Preview timed out. Restore stays available and will retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Restore one named Drive file. [passphrase] is only ever supplied by the post-login backup
     * screen for an export the owner deliberately sealed; the welcome screen passes nothing and
     * this device's keys open the file.
     *
     * [seedSafetyNet] is off while a walk is still trying other copies. Installing the embedded
     * vault after one unreadable file made an empty device look populated, the next silent sync
     * pushed those demo records to Drive, and the owner's real vault was left only as a preserved
     * copy. A walk decides about the safety net once, at the end.
     */
    suspend fun restoreFromCloud(
        file: CloudBackupFile,
        email: String = "",
        passphrase: String? = null,
        seedSafetyNet: Boolean = true
    ): Result<RestoreOutcome> = guarded {
        val epoch = operationEpoch.get()
        val accountEmail = resolveEmail(email)
        onOperation(
            CloudOperation.RESTORE,
            "Downloading data from Google Drive...",
            "Connecting to Drive AppData",
            8
        )
        try {
            update("Fetching your vault key", "Account keyring and this device's key", 18)
            val keyringNote = attachKeyringFromCloud(passphrase)
            update("Downloading data from Google Drive...", file.name, 30)
            val bytes = withTimeout(RESTORE_TIMEOUT_MS) { driveService.download(file.id) }
            if (bytes.size > MAX_BACKUP_BYTES) {
                throw CloudSyncException("Backup is larger than the supported 120 MB limit")
            }
            update("Decrypting vault...", "The exact key this copy was written with", 45)
            val decoded = try {
                decryptBackup(bytes, accountEmail, passphrase, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A passphrase was typed precisely so the account keyring could be unwrapped. If the
                // file still refuses, the reason the keyring could not be used is the answer the
                // owner needs; dropping it leaves them re-typing the same password blindly.
                val detail = listOfNotNull(e.message, keyringNote?.let { "Vault key: $it" })
                    .joinToString(" ")
                throw CloudSyncException(detail.ifBlank { "This backup could not be opened." }, e)
            }

            update("Importing data into app...", "Passwords, cards, 2FA, contacts, notes & files", 55)
            val outcome = mergeDecoded(decoded)
            outcome.copy(
                warnings = (listOf("Restored from ${file.name}") + outcome.warnings +
                    listOfNotNull(keyringNote)).distinct().take(8)
            )
        } catch (e: TimeoutCancellationException) {
            recoverOrFail(
                reason = "The cloud restore timed out after 60 seconds.",
                incoming = BackupCounts(),
                seedSafetyNet = seedSafetyNet
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recoverOrFail(
                reason = driveMessage(e),
                incoming = BackupCounts(),
                seedSafetyNet = seedSafetyNet
            )
        }
    }

    /**
     * Restore the first Drive file this device can actually open.
     *
     * The primary slot is tried first, then every dated snapshot and every preserved legacy copy,
     * newest first. Looking only at the slot is what turned a lost device key into "your backup is
     * corrupted" while a readable copy sat in the same folder under another name.
     */
    suspend fun restoreAnyCloudBackup(
        email: String = "",
        passphrase: String? = null
    ): Result<RestoreOutcome> {
        val files = listCloudBackups().getOrElse {
            return Result.failure(
                CloudSyncException("Drive is connected, but its backups could not be listed. ${it.message}".trim())
            )
        }
        if (files.isEmpty()) {
            return Result.failure(CloudSyncException("No encrypted backup was found in Google Drive."))
        }
        val ordered = RestoreWalkOrder.order(files)
        // Each copy is opened with its one exact key, so a failed walk costs one derivation per
        // file rather than a minute of guessing. Only the primary slot and the newest fallbacks
        // are walked.
        val candidates = ordered.take(MAX_WALK_FILES)
        val epoch = operationEpoch.get()
        var lastReason: String? = null
        for (file in candidates) {
            if (operationEpoch.get() != epoch) {
                return Result.failure(CloudSyncException("Restoring was stopped."))
            }
            val outcome = restoreFromCloud(
                file = file,
                email = email,
                passphrase = passphrase,
                seedSafetyNet = false
            )
            if (outcome.isSuccess) {
                // A backup that opens but carries no records is not the vault: the original drive
                // copy could be malformed or the XML mirror empty, and the real slot is still ahead
                // in the walk. Returning "restored" here used to stop the walk on an empty file.
                val opened = outcome.getOrNull()
                if (opened == null || opened.incoming.total > 0 || file === candidates.last()) {
                    return outcome
                }
                lastReason = "That copy opened but held no records"
                continue
            }
            lastReason = outcome.exceptionOrNull()?.message ?: lastReason
            // Every remaining copy is behind the same credential. Retrying them turns one consent
            // fault into N slow failures and reads to the owner as a loop; stop and let the caller
            // send them through Google's picker once.
            if (DriveFaults.needsFreshSignIn(lastReason)) break
        }
        return Result.failure(
            CloudSyncException(
                "None of the ${candidates.size} backups in Drive could be opened with the exact " +
                    "key each was written with. ${lastReason ?: ""}".trim()
            )
        )
    }

    /**
     * Bring this device the account's vault key, using a passphrase the owner just typed.
     *
     * A reinstall loses the Keystore-wrapped copy, and a backup sealed before that loss then looks
     * unreadable. The keyring node in the realtime backend is the same key wrapped under the
     * passphrase, so one fetch turns "corrupted backup" back into a vault. Failures are reported, not
     * thrown: a restore that cannot reach the keyring may still open the file with the passphrase or
     * a retired key, and it must be allowed to try.
     */
    private suspend fun attachKeyringFromCloud(passphrase: String?): String? {
        val typed = passphrase?.trim().orEmpty()
        if (typed.isBlank()) return null
        val outcome = runCatching {
            withTimeout(KEYRING_ATTACH_TIMEOUT_MS) { keyring.attachKeyring(typed) }
        }
        return when {
            outcome.isSuccess && outcome.getOrThrow().isSuccess ->
                "Your vault key was fetched from the account, not generated on this device."
            outcome.isFailure ->
                outcome.exceptionOrNull()?.message?.take(160)
                    ?: "The vault key could not be fetched, so the restore continued with the keys on this device."
            else -> {
                val reason = outcome.getOrNull()?.exceptionOrNull()?.message?.take(160)
                // An account that never published a keyring is not an error for this restore: the
                // passphrase may still open the file directly, and saying "no key in the cloud"
                // here would read as a failure while the vault is being recovered fine.
                if (reason?.contains("no vault key in the cloud", ignoreCase = true) == true) null
                else reason ?: "That passphrase did not open the account's vault key, so the copy was opened with its exact key instead."
            }
        }
    }

    suspend fun importLocalBackup(
        bytes: ByteArray,
        passphrase: String? = null,
        replace: Boolean = false
    ): Result<RestoreOutcome> = guarded {
        onOperation(CloudOperation.IMPORT, "Importing encrypted backup", "Authenticating file", 12)
        val accountEmail = resolveEmail("")
        try {
            if (bytes.isEmpty()) throw CloudSyncException("The selected backup is empty")
            if (bytes.size > MAX_BACKUP_BYTES) throw CloudSyncException("Backup exceeds the 120 MB limit")
            update("Decrypting vault...", "The exact key this export was written with", 35)
            val decoded = decryptBackup(
                bytes,
                accountEmail,
                passphrase = passphrase,
                preferPassphrase = true
            )
            update(
                if (replace) "Replacing vault from file" else "Importing data into app...",
                if (replace) "The current records are cleared first" else "Resilient v3 parser",
                55
            )
            mergeDecoded(decoded, replace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recoverOrFail(reason = e.message ?: "This file could not be opened", incoming = BackupCounts())
        }
    }

    suspend fun exportLocalBackup(passphrase: String): Result<LocalExportData> = guarded {
        val trimmed = passphrase.trim()
        if (trimmed.length < 8) {
            throw CloudSyncException("Use at least 8 characters for the export passphrase")
        }
        onOperation(CloudOperation.EXPORT, "Exporting encrypted backup", "Collecting vault records", 8)
        val counts = requireVaultRecords()
        update("Encrypting with your passphrase", "AES-256-GCM · PBKDF2 600,000", 40)
        val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
        val json = gson.toJson(payload)
        val bytes = createEnvelope(json, trimmed, 3)
        val fileName = "SecureWallet_Backup_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.enc"
        completeOperation("Encrypted export ready · ${summary(counts)}")
        LocalExportData(bytes = bytes, fileName = fileName, counts = counts)
    }

    suspend fun ensureVaultPopulated(): Result<RestoreOutcome> = guarded {
        val existing = backupEngine.countAll()
        if (existing.total > 0) {
            return@guarded RestoreOutcome(
                counts = existing,
                incoming = BackupCounts(),
                usedSeed = false,
                warnings = emptyList(),
                message = "Vault ready · ${summary(existing)}"
            )
        }
        // Never resurrect demo records after an erase: counts must stay at zero.
        if (WipeArchiveStore.wasWiped(context)) {
            return@guarded RestoreOutcome(
                counts = existing,
                incoming = BackupCounts(),
                usedSeed = false,
                warnings = emptyList(),
                message = "Vault reset · 0 sealed items"
            )
        }
        onOperation(CloudOperation.SEED, "Provisioning encrypted vault", "Opening embedded backup", 15)
        val seed = seedLoader.restore { stage, percent -> update(stage, "Encrypted batch import", percent) }
        if (seed.total == 0) throw CloudSyncException("The embedded vault could not be installed")
        vaultRepository.reloadVaultState()
        val counts = backupEngine.countAll()
        val message = "Vault provisioned · ${summary(counts)}"
        completeOperation(message)
        RestoreOutcome(
            counts = counts,
            incoming = counts,
            usedSeed = true,
            warnings = emptyList(),
            message = message
        )
    }

    /**
     * Startup hydration. A reinstall wipes Room, so pull the newest Drive snapshot and
     * decrypt it before falling back to the embedded vault. Returns immediately once the
     * local database already holds records. [mergeExisting] is used right after an
     * in-app Google sign-in so a freshly seeded vault can absorb the real snapshot.
     *
     * [passphrase] is the owner's secret and is offered to the cloud keyring first: without it a
     * reinstall has nothing to open a keyring-sealed vault with, and every file in Drive then looks
     * corrupted.
     */
    suspend fun hydrateVault(
        mergeExisting: Boolean = false,
        passphrase: String? = null
    ): RestoreOutcome? {
        if (sessionManager.isDecoy()) return null
        if (!mergeExisting && backupEngine.countAll().total > 0) return null
        // A wiped install stays empty until the user restores a backup explicitly.
        if (!mergeExisting && WipeArchiveStore.wasWiped(context)) return null
        if (driveService.hasDriveAccess()) {
            val restored = restoreAnyCloudBackup(passphrase = passphrase).getOrNull()
            if (restored != null && backupEngine.countAll().total > 0) {
                // The one-time migration: a vault that arrived under a retired universal key is
                // re-sealed under this user's own key as soon as it is hydrated, which is what
                // lets the web companion open the very next backup without any shared literal.
                val cameFromLegacyKey = restored.warnings.any {
                    it.contains("universal cloud key", ignoreCase = true) ||
                        it.contains("legacy", ignoreCase = true)
                }
                if (cameFromLegacyKey) runCatching { syncNowSilently() }
                return restored
            }
        }
        return ensureVaultPopulated().getOrNull()
    }

    suspend fun restoreEmbeddedSeed(): Result<RestoreOutcome> = guarded {
        onOperation(CloudOperation.SEED, "Restoring embedded vault", "Opening authenticated asset", 10)
        val seed = seedLoader.restore { stage, percent -> update(stage, "Encrypted batch import", percent) }
        if (seed.total == 0) throw CloudSyncException("The embedded vault could not be restored")
        vaultRepository.reloadVaultState()
        val counts = backupEngine.countAll()
        val message = "Embedded vault restored · ${summary(counts)}"
        completeOperation(message)
        RestoreOutcome(
            counts = counts,
            incoming = seed.merge.toCounts(),
            usedSeed = true,
            warnings = seed.merge.warnings,
            message = message
        )
    }

    suspend fun pushToCompanion(): Result<PushOutcome> = guarded {
        if (sessionManager.isDecoy()) slayDecoy()
        onOperation(CloudOperation.BACKUP, "Syncing to companion", "Encrypting vault for the portal", 12)
        val counts = requireVaultRecords()
        update("Building encrypted envelope", "AES-256-GCM · this user's vault key", 40)
        val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
        val envelope = createEnvelope(gson.toJson(payload), sealingSecret(), 3)
        update("Pushing to companion portal", "End-to-end encrypted payload", 68)
        val url = appLockManager.companionUrl.first()
        val sent = companionSyncClient.push(url, envelope)
        val message = "Companion sync complete · ${summary(counts)}"
        completeOperation(message)
        PushOutcome(counts = counts, bytes = sent, message = message)
    }

    suspend fun pullFromCompanion(): Result<RestoreOutcome> = guarded {
        if (sessionManager.isDecoy()) slayDecoy()
        onOperation(CloudOperation.RESTORE, "Pulling from companion", "Connecting to portal", 15)
        try {
            val url = appLockManager.companionUrl.first()
            update("Downloading encrypted envelope", "Companion vault", 30)
            val bytes = companionSyncClient.pull(url)
            update("Decrypting companion vault", "Vault key, then legacy keys", 45)
            val decoded = decryptBackup(bytes, resolveEmail(""))
            mergeDecoded(decoded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recoverOrFail(e.message ?: "Companion pull failed", BackupCounts())
        }
    }

    fun resetState() {
        _state.value = CloudSyncState()
    }

    fun signedInEmail(): String = driveService.lastSignedInAccount()?.email.orEmpty()

    /**
     * The Google account ID — the numeric identifier, not the address. The original same-package
     * app sealed its Drive backup with this, and it appears in neither the address nor the profile,
     * so it is the one candidate a restore cannot reconstruct. Empty when nothing is signed in.
     */
    fun signedInAccountId(): String = driveService.lastSignedInAccount()?.id.orEmpty()

    fun signedInDisplayName(): String? =
        driveService.lastSignedInAccount()?.displayName?.trim()?.takeIf { it.isNotBlank() }

    fun isDriveSignedIn(): Boolean = driveService.lastSignedInAccount() != null

    fun canAccessDrive(): Boolean = driveService.hasDriveAccess()

    /** Live Drive quota for the signed-in account; never uses the sync overlay. */
    suspend fun driveStorageQuota(): Result<DriveStorageQuota> = withContext(Dispatchers.IO) {
        if (!driveService.hasDriveAccess()) {
            Result.failure(IllegalStateException("Connect Google Drive to see available storage."))
        } else {
            runCatching { driveService.storageQuota() }
        }
    }

    suspend fun completeGoogleSignIn(data: android.content.Intent?): String? {
        val account = driveService.completeSignIn(data) ?: return null
        val email = account.email?.trim().orEmpty()
        if (email.isNotBlank()) appLockManager.setCloudEmail(email)
        return email.ifBlank { null }
    }

    suspend fun signOutOfDrive() {
        driveService.signOut()
        appLockManager.setCloudEmail("")
    }

    /**
     * Deletes every file this app created in its private Drive appDataFolder:
     * primary envelope, XML mirror, rotating snapshots and the salt file.
     * Only the app's own appDataFolder is touched.
     */
    suspend fun deleteAllCloudBackups(): Result<Int> = withContext(Dispatchers.IO) {
        if (!driveService.hasDriveAccess()) {
            return@withContext Result.failure(
                CloudSyncException("Google Drive is not connected.")
            )
        }
        try {
            Result.success(driveService.deleteAllAppDataFiles())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Seals the current vault into a timestamped ZIP and uploads it to a visible
     * Drive folder before the active appDataFolder slot is cleared. The ZIP holds
     * the per-user vault-key envelopes plus restore instructions, never plaintext.
     */
    suspend fun archiveVaultSnapshot(): Result<VaultArchiveInfo> = operationMutex.withLock {
        try {
            if (!driveService.hasDriveAccess()) {
                throw CloudSyncException("Google Drive is not connected, so no archive can be stored.")
            }
            if (!driveService.hasArchiveAccess()) {
                throw CloudSyncException(
                    "Google Drive file access is missing. Reconnect Google Drive before wiping."
                )
            }
            val counts = backupEngine.countAll()
            val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
            val secret = sealingSecret()
            val jsonEnvelope = withContext(Dispatchers.Default) {
                sealPayload(gson.toJson(payload), secret, 3).bytes
            }
            val xmlEnvelope = withContext(Dispatchers.Default) {
                sealPayload(BackupXmlSerializer.serialize(payload), secret, 3).bytes
            }
            val now = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
            val name = ".SecureVaultPro-Archive-$stamp.zip"
            val manifest = gson.toJson(
                JsonObject().apply {
                    addProperty("app", "Secure Wallet Pro")
                    addProperty("version", com.securevault.pro.BuildConfig.VERSION_NAME)
                    addProperty("createdAt", now)
                    addProperty("createdAtReadable", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
                    addProperty("logins", counts.entries)
                    addProperty("contacts", counts.contacts)
                    addProperty("totps", counts.totps)
                    addProperty("notes", counts.notes)
                    addProperty("cards", counts.cards)
                    addProperty("files", counts.files)
                    addProperty("cryptoWallets", counts.wallets)
                    addProperty("encryption", "AES-256-GCM · PBKDF2-SHA256 600000 · per-user vault key")
                }
            )
            val zip = buildZip(
                mapOf(
                    PRIMARY_BACKUP_NAME to jsonEnvelope,
                    XML_BACKUP_NAME to xmlEnvelope,
                    "MANIFEST.json" to manifest.toByteArray(Charsets.UTF_8),
                    "RESTORE.txt" to restoreInstructions(name).toByteArray(Charsets.UTF_8)
                )
            )
            val uploaded = driveService.uploadArchive(name, zip)
            Result.success(
                VaultArchiveInfo(
                    id = uploaded.id,
                    name = uploaded.name,
                    size = uploaded.size,
                    counts = counts,
                    createdAt = now
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(CloudSyncException(driveMessage(e), e))
        }
    }

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            entries.forEach { (entryName, bytes) ->
                val entry = java.util.zip.ZipEntry(entryName).apply { time = System.currentTimeMillis() }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun restoreInstructions(archiveName: String): String = """
        Secure Wallet Pro — pre-wipe vault archive
        ==========================================

        Archive: $archiveName
        Sealed with: universal cloud key v3 (AES-256-GCM, PBKDF2-SHA256, 600,000 iterations)

        Contents
        - vault_backup.enc      Encrypted JSON envelope of every vault record.
        - vault_backup.xml.enc  Encrypted XML mirror of the same records.
        - MANIFEST.json         Record counts and creation time.

        How to restore
        1. Open Secure Wallet Pro and choose "Import Backup File" on the welcome screen.
        2. Unzip this archive and select vault_backup.enc (or vault_backup.xml.enc).
        3. Nothing needs typing: the app opens the file with the keys it has always used.

        This archive is never deleted by "Wipe All Data". Delete it manually in
        Google Drive → .SecureVaultPro-Archives when you no longer need it.
    """.trimIndent()

    // ------------------------- Internal pipeline -------------------------

    private suspend fun mergeDecoded(
        decoded: DecodedBackup,
        replace: Boolean = false
    ): RestoreOutcome {
        val parsed = BackupParser.parse(decoded.json)
        val incoming = payloadCounts(parsed.payload)
        if (replace && incoming.total == 0) {
            throw CloudSyncException(
                "Replace needs a file that actually holds records — this one is empty, so nothing was changed."
            )
        }
        update("Importing data into app...", "Passwords, cards, 2FA, contacts, notes & files", 60)
        val merge = backupEngine.merge(parsed, replace) { stage, percent ->
            update(
                "Importing data into app...",
                stage,
                60 + (percent * 0.30f).toInt().coerceIn(0, 30)
            )
        }
        vaultRepository.reloadVaultState()
        var counts = backupEngine.countAll()
        var usedSeed = false
        val warnings = (decoded.warnings + parsed.warnings + merge.warnings).distinct().toMutableList()
        if (counts.total == 0 && !replace) {
            update("Restoring embedded vault", "Safety net: the cloud vault was empty", 88)
            val seed = seedLoader.restoreIfEmpty { stage, percent ->
                update(stage, "Encrypted safety net", 88 + (percent * 0.08f).toInt().coerceIn(0, 8))
            }
            usedSeed = !seed.alreadyPopulated
            warnings += "The incoming vault was empty, so the embedded vault was merged."
            counts = backupEngine.countAll()
            vaultRepository.reloadVaultState()
        }
        val head = when {
            usedSeed -> "Embedded vault restored"
            replace -> "Vault replaced"
            else -> "Vault restored"
        }
        val tail = when {
            merge.skipped > 0 && replace -> " · ${summary(counts)} · ${merge.skipped} already matched"
            merge.skipped > 0 -> " · ${summary(counts)} · ${merge.skipped} already in the vault, not copied twice"
            else -> " · ${summary(counts)}"
        }
        val message = head + tail
        completeOperation(message)
        return RestoreOutcome(
            counts = counts,
            incoming = incoming,
            usedSeed = usedSeed,
            warnings = warnings.take(8),
            message = message,
            skipped = merge.skipped,
            replaced = replace
        )
    }

    private suspend fun recoverOrFail(
        reason: String,
        incoming: BackupCounts,
        seedSafetyNet: Boolean = true
    ): RestoreOutcome {
        val existing = backupEngine.countAll()
        if (existing.total > 0 || !seedSafetyNet) {
            failOperation(reason)
            throw CloudSyncException(reason)
        }
        update("Cloud restore unavailable", "Installing the embedded encrypted vault", 72)
        val seedResult = runCatching {
            seedLoader.restoreIfEmpty { stage, percent ->
                update(stage, "Encrypted safety net", 72 + (percent * 0.24f).toInt().coerceIn(0, 24))
            }
        }.getOrNull()
        val counts = backupEngine.countAll()
        if (seedResult == null || counts.total == 0) {
            val message = "$reason No local vault could be installed either."
            failOperation(message)
            throw CloudSyncException(message)
        }
        vaultRepository.reloadVaultState()
        val message = "Restore could not complete. Embedded vault installed · ${summary(counts)}"
        _state.update {
            it.copy(
                operation = CloudOperation.SEED,
                active = false,
                percent = 100,
                title = "Safety net complete",
                detail = "The embedded vault is ready",
                success = message,
                error = null
            )
        }
        return RestoreOutcome(
            counts = counts,
            incoming = incoming,
            usedSeed = true,
            warnings = listOf(reason),
            message = message,
            failureReason = reason
        )
    }

    /**
     * Backups and exports mirror the database exactly as it stands. The embedded safety
     * net is deliberately NOT installed here: seeding an empty vault before an export
     * used to push the packaged records into the user's own file, and they then arrived
     * as extra rows on every restore.
     */
    private suspend fun requireVaultRecords(): BackupCounts {
        val counts = backupEngine.countAll()
        if (counts.total == 0) {
            throw CloudSyncException("The vault is empty. Add an item or restore a backup first.")
        }
        return counts
    }

    private suspend fun uploadSnapshot(envelope: ByteArray) {
        val name = "vault_backup_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.enc"
        driveService.upload(name, envelope)
        // Rotation used to delete every snapshot past the fourth, silently, inside a sync the owner
        // pressed. A snapshot may be the only copy of rows that were later wiped on-device, so an
        // uploaded backup is never removed except by the owner (Settings -> cloud backups) or by the
        // archive-gated Erase all data.
    }

    private suspend fun createEnvelope(
        json: String,
        passphrase: String,
        version: Int
    ): ByteArray = sealPayload(json, passphrase, version).bytes

    /**
     * The secret every new cloud envelope is sealed with: this user's own vault key, straight from
     * the keyring. There is no fallback to the retired universal key — if the device cannot open
     * its own key, the upload stops with a sentence that tells the owner what to do, because a
     * silently re-keyed backup would look identical and open nowhere.
     */
    private suspend fun sealingSecret(): String = withContext(Dispatchers.Default) {
        keyring.sealingSecret()
    } ?: throw CloudSyncException(
        "This device could not open its vault key, so nothing was uploaded. Unlock the vault with your passphrase and sync again."
    )

    private suspend fun sealPayload(
        plaintext: String,
        passphrase: String,
        version: Int
    ): SealedEnvelope = withContext(Dispatchers.Default) {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS)
        try {
            val encrypted = crypto.encryptWithDerivedKey(plaintext, key)
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val envelope = JsonObject().apply {
                addProperty("v", version)
                addProperty("salt", saltB64)
                addProperty("data", encrypted)
            }
            val parts = encrypted.split('.')
            SealedEnvelope(
                bytes = gson.toJson(envelope).toByteArray(Charsets.UTF_8),
                saltBase64 = saltB64,
                ivBase64 = parts.firstOrNull().orEmpty(),
                ciphertextBase64 = parts.getOrNull(1).orEmpty()
            )
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens a stored backup without changing a byte of it.
     *
     * The envelope format has never changed, but what sealed it has — this device's vault key, the
     * owner's own passphrase, the retired universal strings, and in the earliest builds the account
     * address — so a file is only unreadable once every one of those has been tried. That sweep, and
     * the iteration counts each build used, live in [LegacyEnvelopeOpener] where a test can reach
     * them; this function only decides what the answer means for the restore.
     */
    private suspend fun decryptBackup(
        bytes: ByteArray,
        email: String,
        passphrase: String? = null,
        epoch: Long = operationEpoch.get(),
        preferPassphrase: Boolean = false
    ): DecodedBackup = withContext(Dispatchers.Default) {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) throw CloudSyncException("The backup file is empty")
        val envelope = BackupEnvelopeFormat.parse(bytes)
        if (envelope != null && envelope.recognised && envelope.salt.size >= 8) {
            val salts = LegacySaltPlan.variants(envelope.salt)
            val attempt = if (envelope.version == null || envelope.version < 3) {
                // Only a pre-v3 envelope needs the original reader's wider list; its own version
                // says so in the file, so this is history, not guessing.
                openSecrets(
                    salts = salts,
                    data = envelope.data,
                    secrets = buildSecrets(email, passphrase, version = envelope.version),
                    epoch = epoch,
                    detail = "Opening an older backup the way its reader did"
                )
            } else {
                val first = openPrimary(
                    salts = salts,
                    data = envelope.data,
                    passphrase = passphrase,
                    preferPassphrase = preferPassphrase,
                    epoch = epoch
                )
                // A typed secret means the owner deliberately retried. Only then is the original
                // reader's historical list worth its handful of derivations; the common path is
                // one exact key, never a 60-step sweep.
                if (first.opened != null || passphrase.isNullOrBlank()) {
                    first
                } else {
                    openSecrets(
                        salts = salts,
                        data = envelope.data,
                        secrets = buildSecrets(email, passphrase, version = envelope.version)
                            .map { it.copy(iterationProfiles = listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS)) },
                        epoch = epoch,
                        detail = "Checking the keys this account has used"
                    )
                }
            }
            if (operationEpoch.get() != epoch) {
                throw CancellationException("Restore stopped by the owner")
            }
            val opened = attempt.opened ?: throw CloudSyncException(
                EnvelopeDiagnostics.describe(envelope, attempt.candidatesTried)
            )
            val paddedSaltNote = if (opened.saltBytes != envelope.salt.size) {
                listOf(
                    "Opened with a ${envelope.salt.size}-byte salt padded to ${opened.saltBytes} bytes, " +
                        "the way the original reader derived it."
                )
            } else {
                emptyList()
            }
            return@withContext DecodedBackup(
                json = opened.json,
                encrypted = true,
                warnings = warningsFor(opened.kind, opened.iterations) + paddedSaltNote
            )
        }

        // No usable envelope salt. The earliest Drive backup was a bare `iv.ciphertext` blob whose
        // salt lived in the separate `key.salt` file in the same folder, so that shape is opened
        // the way its own reader did rather than being read as plain JSON and called corrupted.
        val bare = envelope?.data?.takeIf { looksLikeCiphertext(it) }
            ?: text.takeIf { looksLikeCiphertext(it) }
        if (bare != null) {
            val legacySalt = driveLegacySalt()
            val attempt = openSecrets(
                salts = LegacySaltPlan.fallback(legacySalt, addressCandidates(email)),
                data = bare,
                secrets = buildSecrets(email, passphrase, version = null),
                epoch = epoch,
                detail = "Opening a salt-less backup the way its reader did"
            )
            if (operationEpoch.get() != epoch) {
                throw CancellationException("Restore stopped by the owner")
            }
            val opened = attempt.opened ?: throw CloudSyncException(
                EnvelopeDiagnostics.describeBareBlob(
                    data = bare,
                    saltSource = legacySalt?.let { "key.salt on Drive (${it.size} bytes)" }
                        ?: "no key.salt on Drive",
                    secretsTried = attempt.candidatesTried
                )
            )
            return@withContext DecodedBackup(
                json = opened.json,
                encrypted = true,
                warnings = warningsFor(opened.kind, opened.iterations) +
                    "Opened a salt-less backup the way its original reader did."
            )
        }

        DecodedBackup(
            json = readPlainJson(text, bytes),
            encrypted = false,
            warnings = listOf("This backup was stored without envelope encryption.")
        )
    }

    /**
     * The exact original derivation: one secret, one count, one attempt.
     *
     * The original app's writer used [LegacyKeyPlan.PRIMARY_CLOUD_KEY] at 600,000 rounds; the
     * keyring-era writer uses this account's vault key at the same count. Offering both as a list is
     * what produced the 60-step screen, while a wrong key can only ever cost one PBKDF2. The retry
     * with a typed secret is the only place a list is justified, and even then it is one count.
     */
    private fun openPrimary(
        salts: List<ByteArray>,
        data: String,
        passphrase: String?,
        preferPassphrase: Boolean,
        epoch: Long
    ): LegacyEnvelopeOpener.OpenAttempt {
        val secret = LegacyKeyPlan.primary(
            passphrase = passphrase,
            vaultSecret = storedVaultSecret(),
            preferPassphrase = preferPassphrase
        )
        val detail = when (secret.kind) {
            OpeningSecretKind.VAULT_KEY -> "Opening with your account's vault key"
            OpeningSecretKind.PASSPHRASE -> "Opening with your passphrase"
            else -> "Opening with the original key"
        }
        update("Decrypting vault...", detail, 48)
        return legacyOpener.openWithSalts(
            salts = salts,
            data = data,
            secrets = listOf(secret),
            shouldStop = { operationEpoch.get() != epoch }
        )
    }

    /**
     * The original reader's candidate list for a file that predates the one-key format, or for a
     * deliberate retry after the owner supplied a secret. Progress moves but never prints a count:
     * the owner asked to stop watching key numbers, and a count is not information they can act on.
     */
    private fun openSecrets(
        salts: List<ByteArray>,
        data: String,
        secrets: List<OpeningSecret>,
        epoch: Long,
        detail: String
    ): LegacyEnvelopeOpener.OpenAttempt {
        val perSalt = secrets.sumOf { it.iterationProfiles?.size?.takeIf { size -> size > 0 } ?: 1 }
        val total = (perSalt * salts.size).coerceAtLeast(1)
        return legacyOpener.openWithSalts(
            salts = salts,
            data = data,
            secrets = secrets,
            shouldStop = { operationEpoch.get() != epoch },
            onAttempt = { tried ->
                update(
                    "Decrypting vault...",
                    detail,
                    45 + (tried * 9 / total)
                )
            }
        )
    }

    /** The salt sources the original reader used for a file that carried none. */
    private suspend fun addressCandidates(email: String): List<String> =
        buildList {
            add(email)
            add(runCatching { appLockManager.cloudEmail.first() }.getOrDefault(""))
            add(runCatching { signedInEmail() }.getOrDefault(""))
            add(signedInDisplayName().orEmpty())
            addAll(LegacyKeyPlan.HISTORICAL_OWNER_ADDRESSES)
            // The original also seeded a salt from the account address alone when its `key.salt`
            // was missing; the last-resort fallbacks do the same.
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /** The separate salt file the original writer kept beside the backup. */
    private suspend fun driveLegacySalt(): ByteArray? = runCatching {
        if (!driveService.hasDriveAccess()) return@runCatching null
        val file = driveService.findBackupByName(LEGACY_SALT_FILE_NAME) ?: return@runCatching null
        driveService.download(file.id).takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** A bare `iv.ciphertext` body (or a `data:` URI), with no JSON envelope around it. */
    private fun looksLikeCiphertext(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{")) return false
        if (trimmed.startsWith("data:")) return trimmed.contains(',')
        val parts = trimmed.split('.')
        if (parts.size !in 2..3) return false
        return parts.all { it.isNotBlank() && it.length >= 4 }
    }

    private fun warningsFor(kind: OpeningSecretKind, iterations: Int): List<String> {
        val byProfile = if (iterations != PBKDF2_ITERATIONS) {
            listOf("Decrypted using a legacy key derivation profile.")
        } else {
            emptyList()
        }
        val bySecret = when (kind) {
            // The vault key is the shape everything is written in now, so opening with it is not
            // worth mentioning. Anything else is a retired or personal secret, and that warning is
            // what tells the caller this copy still has to be re-sealed once.
            OpeningSecretKind.VAULT_KEY -> emptyList()
            OpeningSecretKind.RETIRED_UNIVERSAL_KEY -> listOf(
                "Opened with the retired universal cloud key. It will be re-sealed under your own vault key."
            )
            OpeningSecretKind.PASSPHRASE ->
                listOf("Opened with the passphrase you typed, not this device's vault key.")
            OpeningSecretKind.ACCOUNT_EMAIL ->
                listOf("Opened with the account address older builds sealed backups with.")
            OpeningSecretKind.ACCOUNT_ID ->
                listOf("Opened with your Google account ID, which the original app sealed backups with.")
            OpeningSecretKind.SHARED_LEGACY_KEY ->
                listOf("Opened with a retired shared key from an early build.")
            OpeningSecretKind.OTHER -> listOf("Decrypted with a legacy vault key.")
        }
        return (bySecret + byProfile).distinct()
    }

    private fun readPlainJson(text: String, bytes: ByteArray): String {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        // The XML mirror is a real backup body, not a foreign file; BackupParser opens it.
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<")) return trimmed
        val decoded = decodeBase64OrNull(trimmed)
            ?: decodeBase64OrNull(bytes.toString(Charsets.ISO_8859_1))
        if (decoded != null) {
            val decodedText = runCatching { decoded.toString(Charsets.UTF_8).trim() }.getOrNull()
            if (decodedText != null &&
                (decodedText.startsWith("{") || decodedText.startsWith("[") || decodedText.startsWith("<"))
            ) {
                return decodedText
            }
        }
        throw CloudSyncException("This is not a Secure Wallet Pro backup file")
    }

    /**
     * The secrets a stored envelope is offered, in the order the writers used them.
     *
     * The order and the exact derivation counts live in [LegacyKeyPlan] so they can be unit-tested
     * without Android. The envelope's own version decides the key list; the counts are the ones the
     * old code used for that version, not a sweep.
     *
     * A vault key is offered only if the keyring already has one: [Keyring] *creates* a fresh key
     * when asked for a sealing secret, and a generated key can never open the owner's file — it only
     * spends a derivation ahead of the key that can.
     */
    private suspend fun buildSecrets(
        email: String,
        passphrase: String?,
        version: Int?,
        vaultSecret: String? = storedVaultSecret()
    ): List<OpeningSecret> {
        val emails = buildList {
            add(email)
            add(runCatching { appLockManager.cloudEmail.first() }.getOrDefault(""))
            add(runCatching { signedInEmail() }.getOrDefault(""))
        }
        return LegacyKeyPlan.ordered(
            emails = emails,
            passphrase = passphrase,
            vaultSecret = vaultSecret,
            accountIds = listOf(runCatching { signedInAccountId() }.getOrDefault("")),
            displayName = runCatching { signedInDisplayName() }.getOrNull(),
            version = version
        )
    }

    private fun storedVaultSecret(): String? = runCatching {
        if (keyring.hasDeviceKey()) keyring.sealingSecret() else null
    }.getOrNull()

    /**
     * Replace the backup slot, keeping whatever was in it if this device cannot open that.
     *
     * All writes go through here. The rule it enforces — never destroy bytes you cannot read — is the
     * difference between a reinstall that costs a sync and a reinstall that costs a vault.
     */
    private suspend fun writeSlot(
        slotName: String,
        bytes: ByteArray,
        secret: String
    ): BackupSlotGuard.Outcome = withContext(Dispatchers.IO) {
        val existing = driveService.findBackupByName(slotName)
        BackupSlotGuard(
            opener = legacyOpener,
            derivation = keyDerivation,
            ourSecret = secret,
            proofs = slotProofs,
            download = { file ->
                withTimeout(RESTORE_TIMEOUT_MS) { driveService.download(file.id) }
            },
            uploadAsNew = { name, payload -> driveService.upload(name, payload) },
            uploadOver = { name, payload, existingId -> driveService.upload(name, payload, existingId) }
        ).write(slotName, bytes, existing)
    }

    private fun resolveEmail(explicit: String): String {
        val signed = runCatching { signedInEmail() }.getOrDefault("")
        return listOf(explicit, signed)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun payloadCounts(payload: BackupPayloadDto) = BackupCounts(
        entries = payload.entries.size,
        notes = payload.notes.size,
        cards = payload.cards.size,
        totps = payload.totps.size,
        contacts = payload.contacts.size,
        files = payload.files.size,
        wallets = payload.cryptoWallets.size
    )

    private suspend fun writeOfflineCopy(
        bytes: ByteArray,
        name: String = PRIMARY_BACKUP_NAME
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "offline_backup").apply { mkdirs() }
            val target = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("Unable to finalise offline backup")
        }.isSuccess
    }

    // ------------------------- Helpers -------------------------

    private fun summary(counts: BackupCounts): String {
        val parts = buildList {
            if (counts.entries > 0) add("${counts.entries} logins")
            if (counts.contacts > 0) add("${counts.contacts} contacts")
            if (counts.totps > 0) add("${counts.totps} 2FA")
            if (counts.notes > 0) add("${counts.notes} notes")
            if (counts.cards > 0) add("${counts.cards} cards")
            if (counts.files > 0) add("${counts.files} files")
            if (counts.wallets > 0) add("${counts.wallets} wallets")
        }
        return if (parts.isEmpty()) "0 items" else parts.joinToString(" · ")
    }

    private fun driveMessage(e: Throwable): String {
        return when (e) {
            is GoogleDriveException -> e.message ?: "Google Drive is unavailable"
            is CloudSyncException -> e.message ?: "Backup operation failed"
            else -> e.message?.take(180) ?: "Google Drive is unavailable"
        }
    }

    private fun slayDecoy(): Nothing {
        throw CloudSyncException("Cloud backup and restore are disabled in this vault.")
    }

    private fun onOperation(op: CloudOperation, title: String, detail: String, percent: Int) {
        if (sessionManager.isDecoy() && op != CloudOperation.SEED) slayDecoy()
        _state.value = CloudSyncState(
            operation = op,
            active = true,
            percent = percent,
            title = title,
            detail = detail
        )
    }

    private fun update(title: String, detail: String, percent: Int) {
        _state.update {
            it.copy(
                active = true,
                percent = percent.coerceIn(0, 100),
                title = title,
                detail = detail,
                error = null
            )
        }
    }

    private fun completeOperation(message: String) {
        _state.update {
            it.copy(
                active = false,
                percent = 100,
                title = "Vault secure",
                detail = message,
                error = null,
                success = message
            )
        }
    }

    private fun failOperation(message: String) {
        _state.update {
            it.copy(
                active = false,
                title = "Operation failed",
                detail = message,
                error = message,
                success = null
            )
        }
    }

    /**
     * Releases a UI-blocking operation the owner chose to abandon.
     *
     * The epoch bump is what makes this real: a decrypt sweep checks it before every PBKDF2, so the
     * wait ends at the next candidate rather than after the whole key list. Any request still in
     * flight finishes, and nothing downloaded or written is discarded.
     */
    fun abandonOperation(reason: String) {
        operationEpoch.incrementAndGet()
        _state.update {
            it.copy(
                active = false,
                title = "Operation stopped",
                detail = reason,
                error = null,
                success = null
            )
        }
    }

    private suspend fun <T> guarded(
        block: suspend () -> T
    ): Result<T> {
        if (sessionManager.isDecoy()) {
            failOperation("Cloud and backup actions are disabled in this vault.")
            return Result.failure(CloudSyncException("Cloud and backup actions are disabled in this vault."))
        }
        return operationMutex.withLock {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = when (e) {
                    is CloudSyncException -> e.message ?: "Backup operation failed"
                    is GoogleDriveException -> e.message ?: "Google Drive is unavailable"
                    else -> e.message?.take(220) ?: "Backup operation failed"
                }
                failOperation(message)
                Result.failure(CloudSyncException(message, e))
            }
        }
    }

    private data class SealedEnvelope(
        val bytes: ByteArray,
        val saltBase64: String,
        val ivBase64: String,
        val ciphertextBase64: String
    )

    private data class DecodedBackup(
        val json: String,
        val encrypted: Boolean,
        val warnings: List<String> = emptyList()
    )

    private fun decodeBase64OrNull(value: String): ByteArray? {
        val clean = value.trim().replace("\n", "").replace("\r", "")
        if (clean.isBlank()) return null
        return runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { Base64.decode(clean, Base64.URL_SAFE) }.getOrNull()
    }
}
