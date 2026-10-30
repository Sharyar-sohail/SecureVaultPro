package com.securevault.pro.data.remote.google

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import com.securevault.pro.data.remote.companion.CompanionSyncClient
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.KeyDerivation
import com.securevault.pro.security.SeedLoader
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.WipeArchiveStore
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    val driveError: String? = null
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
    private val gson: Gson
) {
    companion object {
        const val PRIMARY_BACKUP_NAME = "vault_backup.enc"
        const val XML_BACKUP_NAME = "vault_backup.xml.enc"

        /**
         * READ-ONLY since the per-user keyring landed. These strings seal nothing any more: they
         * survive only so a vault that was backed up before the keyring can still be opened once,
         * and the first write after that re-seals it under the user's own key. No new envelope may
         * use them — [sealingSecret] is the only writer's key, and a test guards the call sites.
         */
        const val PRIMARY_CLOUD_KEY = "SecureVaultPro_Universal_Cloud_Key_v3"
        const val LEGACY_CLOUD_KEY_V2 = "SecureVaultPro_Universal_Cloud_Key_v2"
        const val LEGACY_CLOUD_KEY_V1 = "SecureVaultPro_Universal_Cloud_Key_v1"
        const val LEGACY_CLOUD_KEY = "SecureVaultPro_Universal_Cloud_Key"
        const val PBKDF2_ITERATIONS = KeyDerivation.PBKDF2_ITERATIONS
        private const val MAX_BACKUP_BYTES = 120L * 1024L * 1024L
        private const val RESTORE_TIMEOUT_MS = 60_000L
    }

    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(CloudSyncState())
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size > 12) {
                eldest?.value?.fill(0)
                return true
            }
            return false
        }
    }

    // ------------------------- Public operations -------------------------

    suspend fun backupToDrive(email: String = ""): Result<BackupOutcome> = guarded {
        onOperation(CloudOperation.BACKUP, "Creating encrypted backup", "Collecting vault records", 6)
        val counts = requireVaultRecords()
        update("Encrypting vault records", "PBKDF2-SHA256 · 600,000 iterations", 32)
        val payload = withContext(Dispatchers.IO) { backupEngine.buildPayload() }
        val json = gson.toJson(payload)
        val envelopeBytes = createEnvelope(json, sealingSecret(), 3)
        update("Writing offline safety copy", "vault_backup.enc", 48)
        val offlineSaved = writeOfflineCopy(envelopeBytes)
        update("Uploading to Google Drive", "App-data folder · hidden from normal Drive", 62)
        var driveError: String? = null
        var uploaded = false
        try {
            val existing = driveService.findBackupByName(PRIMARY_BACKUP_NAME)
            driveService.upload(PRIMARY_BACKUP_NAME, envelopeBytes, existing?.id)
            uploaded = true
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
                    sealingSecret(),
                    3
                )
                writeOfflineCopy(xmlBytes, XML_BACKUP_NAME)
                val existingXml = driveService.findBackupByName(XML_BACKUP_NAME)
                driveService.upload(XML_BACKUP_NAME, xmlBytes, existingXml?.id)
            }
            update("Archiving cloud snapshot", "Rotating the most recent four snapshots", 84)
            runCatching { uploadSnapshot(envelopeBytes) }
        }
        val finalCounts = backupEngine.countAll()
        vaultRepository.reloadVaultState()
        val message = when {
            uploaded -> "Encrypted vault backed up · ${summary(finalCounts)}"
            offlineSaved -> "Offline encrypted backup saved. Google Drive upload failed."
            else -> throw CloudSyncException(driveError ?: "Backup could not be saved anywhere")
        }
        completeOperation(message)
        BackupOutcome(
            counts = finalCounts,
            fileName = PRIMARY_BACKUP_NAME,
            cloudUploaded = uploaded,
            offlineSaved = offlineSaved,
            driveError = driveError
        )
    }

    suspend fun listCloudBackups(): Result<List<CloudBackupFile>> = kotlin.runCatching {
        withContext(Dispatchers.IO) {
            driveService.listBackups().filterNot {
                it.name == XML_BACKUP_NAME
            }
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
                val existing = driveService.findBackupByName(PRIMARY_BACKUP_NAME)
                driveService.upload(PRIMARY_BACKUP_NAME, jsonBytes, existing?.id)
                val existingXml = driveService.findBackupByName(XML_BACKUP_NAME)
                driveService.upload(XML_BACKUP_NAME, xmlBytes, existingXml?.id)
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

    suspend fun restoreFromCloud(
        file: CloudBackupFile,
        email: String = ""
    ): Result<RestoreOutcome> = guarded {
        val accountEmail = resolveEmail(email)
        onOperation(
            CloudOperation.RESTORE,
            "Downloading data from Google Drive...",
            "Connecting to Drive AppData",
            8
        )
        try {
            update("Downloading data from Google Drive...", file.name, 30)
            val bytes = withTimeout(RESTORE_TIMEOUT_MS) { driveService.download(file.id) }
            if (bytes.size > MAX_BACKUP_BYTES) {
                throw CloudSyncException("Backup is larger than the supported 120 MB limit")
            }
            update("Decrypting vault...", "PBKDF2-SHA256 · your vault key first, then legacy keys", 45)
            val decoded = decryptBackup(bytes, accountEmail)
            update("Importing data into app...", "Passwords, cards, 2FA, contacts, notes & files", 55)
            mergeDecoded(decoded)
        } catch (e: TimeoutCancellationException) {
            recoverOrFail(
                reason = "The cloud restore timed out after 60 seconds.",
                incoming = BackupCounts()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recoverOrFail(reason = driveMessage(e), incoming = BackupCounts())
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
            update("Decrypting vault...", "Trying your passphrase and cloud keys", 35)
            val decoded = decryptBackup(bytes, accountEmail, passphrase = passphrase)
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
     */
    suspend fun hydrateVault(mergeExisting: Boolean = false): RestoreOutcome? {
        if (sessionManager.isDecoy()) return null
        if (!mergeExisting && backupEngine.countAll().total > 0) return null
        // A wiped install stays empty until the user restores a backup explicitly.
        if (!mergeExisting && WipeArchiveStore.wasWiped(context)) return null
        if (driveService.hasDriveAccess()) {
            val primary = listCloudBackups().getOrNull()
                .orEmpty()
                .let { files -> files.firstOrNull { it.isPrimary } ?: files.firstOrNull() }
            if (primary != null) {
                val outcome = restoreFromCloud(primary).getOrNull()
                if (outcome != null && backupEngine.countAll().total > 0) {
                    // The one-time migration: a vault that arrived under a retired universal key is
                    // re-sealed under this user's own key as soon as it is hydrated, which is what
                    // lets the web companion open the very next backup without any shared literal.
                    val cameFromLegacyKey = outcome.warnings.any {
                        it.contains("universal cloud key", ignoreCase = true) ||
                            it.contains("legacy", ignoreCase = true)
                    }
                    if (cameFromLegacyKey) runCatching { syncNowSilently() }
                    return outcome
                }
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
        3. Leave the passphrase blank — the app ships the universal cloud key.
           If you exported with a personal passphrase, enter that instead.

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
        incoming: BackupCounts
    ): RestoreOutcome {
        val existing = backupEngine.countAll()
        if (existing.total > 0) {
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

    private suspend fun decryptBackup(
        bytes: ByteArray,
        email: String,
        passphrase: String? = null
    ): DecodedBackup = withContext(Dispatchers.Default) {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) throw CloudSyncException("The backup file is empty")
        val envelope = readEnvelope(text)
            ?: return@withContext DecodedBackup(
                json = readPlainJson(text, bytes),
                encrypted = false,
                warnings = listOf("This backup was stored without envelope encryption.")
            )
        val vaultSecret = runCatching { keyring.sealingSecret() }.getOrNull()
        val candidates = buildCandidates(email, passphrase, vaultSecret)
        for (candidate in candidates) {
            val key = deriveCached(candidate, envelope.salt, PBKDF2_ITERATIONS)
            val plain = decryptWithDerivedKey(envelope.data, key)
            if (plain != null) {
                return@withContext DecodedBackup(
                    json = plain,
                    encrypted = true,
                    // The vault key is the shape everything is written in now, so opening with it
                    // is not worth mentioning. Anything else is a retired universal key, and that
                    // warning is what tells the caller this copy still has to be re-sealed once.
                    warnings = when (candidate) {
                        vaultSecret -> emptyList()
                        PRIMARY_CLOUD_KEY -> listOf(
                            "Opened with the retired universal cloud key. It will be re-sealed under your own vault key."
                        )
                        else -> listOf("Decrypted with a legacy vault key.")
                    }
                )
            }
        }
        if (envelope.version < 3) {
            val legacyCandidates = listOf(PRIMARY_CLOUD_KEY, LEGACY_CLOUD_KEY_V2)
            for (pass in legacyCandidates) {
                for (iterationCount in listOf(210_000, 120_000)) {
                    val key = deriveCached(pass, envelope.salt, iterationCount)
                    val plain = decryptWithDerivedKey(envelope.data, key)
                    if (plain != null) {
                        return@withContext DecodedBackup(
                            json = plain,
                            encrypted = true,
                            warnings = listOf("Decrypted using a legacy key derivation profile.")
                        )
                    }
                }
            }
        }
        throw CloudSyncException(
            "The backup could not be decrypted with this account or key. Restore will fall back to the embedded vault."
        )
    }

    private fun readEnvelope(text: String): Envelope? {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!trimmed.startsWith("{")) {
            val decoded = decodeBase64OrNull(trimmed) ?: return null
            val decodedText = runCatching { decoded.toString(Charsets.UTF_8) }.getOrNull() ?: return null
            return readEnvelopeObject(decodedText)
        }
        return readEnvelopeObject(trimmed)
    }

    private fun readEnvelopeObject(text: String): Envelope? {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val saltElement = obj.get("salt") ?: obj.get("kdfSalt")
        val dataElement = obj.get("data") ?: obj.get("ciphertext") ?: obj.get("payload")
        if (saltElement == null || dataElement == null) return null
        if (!saltElement.isJsonPrimitive || !dataElement.isJsonPrimitive) return null
        val salt = decodeBase64OrNull(saltElement.asString) ?: return null
        if (salt.size < 8) return null
        var data = dataElement.asString.trim()
        val ivElement = obj.get("iv")
        if (!data.contains('.') && ivElement?.isJsonPrimitive == true) {
            data = "${ivElement.asString}.$data"
        }
        if (!data.contains('.')) return null
        val version = obj.get("v")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: 3
        return Envelope(salt = salt, data = data, version = version)
    }

    private fun readPlainJson(text: String, bytes: ByteArray): String {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) return trimmed
        if (trimmed.startsWith("[")) return trimmed
        val decoded = decodeBase64OrNull(trimmed)
            ?: decodeBase64OrNull(bytes.toString(Charsets.ISO_8859_1))
        if (decoded != null) {
            val decodedText = runCatching { decoded.toString(Charsets.UTF_8).trim() }.getOrNull()
            if (decodedText != null && (decodedText.startsWith("{") || decodedText.startsWith("["))) {
                return decodedText
            }
        }
        throw CloudSyncException("This is not a Secure Wallet Pro backup file")
    }

    /**
     * What a stored envelope might be sealed with, best guess first. The user's own vault key leads;
     * the retired universal keys sit below it as a strictly read-only path, so a vault backed up
     * before the keyring still opens. They are candidates for opening a file and never for writing
     * one — the guard test in `KeyringProtocolTest` holds every writer to that rule.
     */
    private suspend fun buildCandidates(
        email: String,
        passphrase: String?,
        vaultSecret: String? = runCatching { keyring.sealingSecret() }.getOrNull()
    ): List<String> {
        val list = mutableListOf<String>()
        if (!passphrase.isNullOrBlank()) list += passphrase.trim()
        vaultSecret?.let { list += it }
        list += PRIMARY_CLOUD_KEY
        val emails = buildList {
            add(email)
            add(runCatching { appLockManager.cloudEmail.first() }.getOrDefault(""))
            add(runCatching { signedInEmail() }.getOrDefault(""))
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        emails.forEach {
            list += it
            list += it.lowercase(Locale.US)
        }
        list += LEGACY_CLOUD_KEY_V2
        list += LEGACY_CLOUD_KEY_V1
        list += LEGACY_CLOUD_KEY
        return list.distinct()
    }

    private fun deriveCached(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val fingerprint = keyDerivation.sha256(
            "$iterations|$saltB64|$passphrase".toByteArray(Charsets.UTF_8)
        )
        val key = Base64.encodeToString(fingerprint, Base64.NO_WRAP)
        synchronized(keyCache) { keyCache[key] }?.let { return it }
        val derived = keyDerivation.deriveKey(passphrase.toCharArray(), salt, iterations)
        synchronized(keyCache) { keyCache[key] = derived }
        return derived
    }

    private fun decryptWithDerivedKey(data: String, key: ByteArray): String? {
        val direct = crypto.decryptWithDerivedKey(data, key)
        if (direct != null) return direct
        return try {
            val parts = data.trim().split('.')
            if (parts.size != 3) return null
            val iv = crypto.decodeBase64(parts[0])
            val a = crypto.decodeBase64(parts[1])
            val b = crypto.decodeBase64(parts[2])
            fun attempt(payload: ByteArray): String? = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, iv)
                )
                String(cipher.doFinal(payload), Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            attempt(b + a) ?: attempt(a + b)
        } catch (_: Throwable) {
            null
        }
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
     * Releases a UI-blocking operation the owner chose to abandon. Any request still
     * in flight simply finishes on its own; this only stops the spinner from holding
     * the screen hostage forever.
     */
    fun abandonOperation(reason: String) {
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

    private data class Envelope(
        val salt: ByteArray,
        val data: String,
        val version: Int
    )

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
