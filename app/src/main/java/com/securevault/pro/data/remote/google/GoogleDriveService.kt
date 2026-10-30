package com.securevault.pro.data.remote.google

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.securevault.pro.R
import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.data.remote.firebase.FirebaseBootstrap
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class GoogleDriveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Drive account storage. [unlimited] is true when the account reports no hard quota (Workspace plans). */
data class DriveStorageQuota(
    val limitBytes: Long,
    val usedBytes: Long,
    val inDriveBytes: Long
) {
    val unlimited: Boolean get() = limitBytes <= 0L
    val availableBytes: Long get() = if (unlimited) 0L else (limitBytes - usedBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (unlimited || limitBytes <= 0L) 0f else (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
}

@Singleton
class GoogleDriveService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val APP_NAME = "Secure Wallet Pro"
        private const val OPERATION_TIMEOUT_MS = 45_000L
        private const val IDENTITY_TIMEOUT_MS = 20_000L
        /** Google's own "the user walked away" code - the only one allowed to read as a cancellation. */
        private const val CODE_CANCELED = 12501
        private const val PRIMARY_BACKUP = "vault_backup.enc"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val ARCHIVE_FOLDER = ".SecureVaultPro-Archives"
    }

    /**
     * The Drive sign-in, exactly as it was in the release this owner could sign in with.
     *
     * It asks for nothing but the account, its email and the two Drive scopes. The realtime layer
     * needs a Google id token, and putting `requestIdToken` on *this* builder was what turned the
     * picker into an instant "sign-in was cancelled" (12501) on the handset: the Drive flow then
     * depended on the OAuth web client, the registered certificate and the consent state all
     * lining up at once, and any one of them failing came back as a cancellation the owner had not
     * made. The id token is minted on its own path instead, so Drive keeps working exactly as it
     * always did and web sync can only ever degrade itself.
     */
    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions.Builder(
        GoogleSignInOptions.DEFAULT_SIGN_IN
    )
        .requestEmail()
        .requestScopes(
            Scope(DriveScopes.DRIVE_APPDATA),
            Scope(DriveScopes.DRIVE_FILE)
        )
        .build()

    /**
     * The id-token sign-in, held apart from Drive: the same Google account, no scopes beyond the
     * profile, so once Drive has signed the account in this completes without a second screen. A
     * missing or unusable web client id here can stop realtime sync; it cannot stop a backup, a
     * restore or the vault. Null is the "not configured" answer, never an exception at startup.
     */
    private val identitySignInOptions: GoogleSignInOptions? = run {
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.endsWith(".apps.googleusercontent.com") }
            ?: return@run null
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(webClientId)
            .build()
    }

    private fun signInClient() = GoogleSignIn.getClient(context, signInOptions)

    fun signInIntent(): Intent = signInClient().signInIntent

    /**
     * Whether Google Play Services on this handset can serve a sign-in at all. Null means it can.
     * Asked before the picker is launched, so "Google is missing/outdated" is never shown as a
     * cancellation the owner did not make.
     */
    fun availabilityFault(): Int? =
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }
            .getOrNull()?.takeIf { it != ConnectionResult.SUCCESS }

    /** The SHA-1 of the certificate this very build is signed with, lowercase, no separators. */
    internal fun signingCertificateSha1(): String? = runCatching {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures.orEmpty()
        }
        signers.firstOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    /** Null when this build has no usable web client id, i.e. when realtime sync is unavailable. */
    fun identitySignInIntent(): Intent? =
        identitySignInOptions?.let { GoogleSignIn.getClient(context, it).signInIntent }

    /**
     * A Google id token for the realtime backend, minted silently for the account Drive already
     * signed in. Null means "web sync waits for its own sign-in", never "Google sign-in failed".
     */
    suspend fun requestIdToken(): String? = withContext(Dispatchers.IO) {
        val options = identitySignInOptions ?: return@withContext null
        try {
            val account = withTimeout(IDENTITY_TIMEOUT_MS) {
                GoogleSignIn.getClient(context, options).silentSignIn().await()
            }
            runCatching { account?.idToken }.getOrNull()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun lastSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun hasDriveAccess(account: GoogleSignInAccount? = lastSignedInAccount()): Boolean {
        if (account == null) return false
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))
    }

    fun hasArchiveAccess(account: GoogleSignInAccount? = lastSignedInAccount()): Boolean {
        if (account == null) return false
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
    }

    suspend fun completeSignIn(data: Intent?): GoogleSignInAccount? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
            if (account == null) return@withContext null
            if (!GoogleSignIn.hasPermissions(
                    account,
                    Scope(DriveScopes.DRIVE_APPDATA),
                    Scope(DriveScopes.DRIVE_FILE)
                )
            ) {
                throw GoogleDriveException(
                    "Google Drive permission was not granted. Allow app data and file access, then try again."
                )
            }
            account
        } catch (e: ApiException) {
            // Only a real 12501 may be reported as a cancellation. Every other code is this app
            // being refused by Google, and saying "you cancelled" instead sends the owner to fix
            // the wrong thing.
            if (e.statusCode == CODE_CANCELED) return@withContext null
            throw GoogleDriveException(signInFailure(e.statusCode, e), e)
        } catch (e: GoogleDriveException) {
            throw e
        } catch (e: Throwable) {
            throw GoogleDriveException("Unable to finish Google Sign-In.", e)
        }
    }

    /** Maps a library status code to the thing the owner can actually fix; never a bare number. */
    private fun signInFailure(code: Int, error: Throwable): String {
        val sha1 = signingCertificateSha1()
        val configCodes = setOf(10, 12500, 16, 4)
        if (code in configCodes) {
            CrashLogger.noteFault(
                context,
                "Google sign-in refused ($code) · cert ${sha1 ?: "unknown"} · " +
                    "client ${FirebaseBootstrap.webClientId(context)?.takeIf { it.isNotBlank() } ?: "none"}",
                error
            )
        }
        return when (code) {
            10, 12500 ->
                "Google refused this app's sign-in configuration (code $code). The certificate this " +
                    "build is signed with is ${sha1 ?: "unknown"} — that SHA-1 must be listed in the " +
                    "Firebase project for ${context.packageName}."
            16 ->
                "Google's sign-in API refused this request (code 16), which is the same " +
                    "configuration problem: certificate ${sha1 ?: "unknown"} for ${context.packageName}."
            4 -> "Google Sign-In needs an account on this device. Add the account, then try again."
            2, 9, 12 ->
                "Google Play services on this device cannot serve sign-in (code $code). Update it and try again."
            1 -> "Google Play services is missing on this device; sign-in cannot run."
            8 -> "Google's servers did not answer in time. Try again in a moment."
            15 -> "Google sign-in timed out on this device. Try again."
            7 -> "No usable connection to Google right now. Try again once you are online."
            else -> "Google Sign-In error ($code)."
        }
    }

    suspend fun silentSignIn(): GoogleSignInAccount? = withContext(Dispatchers.IO) {
        try {
            val account = withTimeout(20_000L) { signInClient().silentSignIn().await() }
            if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
                account
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { signInClient().signOut().await() }
        identitySignInOptions?.let { options ->
            runCatching { GoogleSignIn.getClient(context, options).signOut().await() }
        }
        Unit
    }

    suspend fun listBackups(account: GoogleSignInAccount? = lastSignedInAccount()): List<CloudBackupFile> =
        withContext(Dispatchers.IO) {
            val drive = driveFor(account)
            val files = withTimeout(OPERATION_TIMEOUT_MS) {
                drive.files().list()
                    .setSpaces(APP_DATA_FOLDER)
                    .setFields("files(id,name,size,modifiedTime,mimeType)")
                    .setOrderBy("modifiedTime desc")
                    .setPageSize(100)
                    .execute()
            }.files.orEmpty()
            files.asSequence()
                .filter { it.name?.endsWith(".enc", ignoreCase = true) == true }
                .filter { it.name != "vault_salt.json" }
                .map { file ->
                    CloudBackupFile(
                        id = file.id.orEmpty(),
                        name = file.name ?: "backup.enc",
                        size = (file.size ?: 0L).toLong(),
                        modifiedTime = file.modifiedTime?.value?.toLong() ?: 0L,
                        isPrimary = file.name == PRIMARY_BACKUP
                    )
                }
                .filter { it.id.isNotBlank() }
                .sortedWith(compareByDescending<CloudBackupFile> { it.isPrimary }.thenByDescending { it.modifiedTime })
                .toList()
        }

    suspend fun findBackupByName(
        name: String,
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): CloudBackupFile? = withContext(Dispatchers.IO) {
        val drive = driveFor(account)
        val files = withTimeout(OPERATION_TIMEOUT_MS) {
            drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setFields("files(id,name,size,modifiedTime,mimeType)")
                .setOrderBy("modifiedTime desc")
                .setPageSize(100)
                .execute()
        }.files.orEmpty()
        files.firstOrNull { it.name == name }?.let { file ->
            CloudBackupFile(
                id = file.id.orEmpty(),
                name = file.name ?: name,
                size = (file.size ?: 0L).toLong(),
                modifiedTime = file.modifiedTime?.value?.toLong() ?: 0L,
                isPrimary = file.name == PRIMARY_BACKUP
            )
        }
    }

    suspend fun download(
        fileId: String,
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): ByteArray = withContext(Dispatchers.IO) {
        if (fileId.isBlank()) throw GoogleDriveException("No backup file was selected")
        val drive = driveFor(account)
        try {
            withTimeout(OPERATION_TIMEOUT_MS) {
                val out = ByteArrayOutputStream()
                drive.files().get(fileId).executeMediaAndDownloadTo(out)
                out.toByteArray()
            }
        } catch (e: GoogleJsonResponseException) {
            throw GoogleDriveException("Drive download failed (${e.statusCode}).", e)
        } catch (e: GoogleDriveException) {
            throw e
        } catch (e: Throwable) {
            throw GoogleDriveException("Unable to download the encrypted backup.", e)
        }
    }

    suspend fun upload(
        name: String,
        bytes: ByteArray,
        existingId: String? = null,
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): CloudBackupFile = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw GoogleDriveException("Refusing to upload an empty backup")
        val drive = driveFor(account)
        val metadata = DriveFile().apply {
            this.name = name
            mimeType = "application/octet-stream"
        }
        val content = ByteArrayContent("application/octet-stream", bytes)
        try {
            val uploaded = withTimeout(OPERATION_TIMEOUT_MS) {
                if (!existingId.isNullOrBlank()) {
                    drive.files().update(existingId, metadata, content)
                        .setFields("id,name,size,modifiedTime")
                        .execute()
                } else {
                    metadata.parents = listOf(APP_DATA_FOLDER)
                    drive.files().create(metadata, content)
                        .setFields("id,name,size,modifiedTime")
                        .execute()
                }
            }
            CloudBackupFile(
                id = uploaded.id.orEmpty(),
                name = uploaded.name ?: name,
                size = (uploaded.size ?: bytes.size.toLong()).toLong(),
                modifiedTime = uploaded.modifiedTime?.value?.toLong() ?: System.currentTimeMillis(),
                isPrimary = name == PRIMARY_BACKUP
            )
        } catch (e: GoogleJsonResponseException) {
            throw GoogleDriveException("Drive upload failed (${e.statusCode}).", e)
        } catch (e: GoogleDriveException) {
            throw e
        } catch (e: Throwable) {
            throw GoogleDriveException("Unable to upload the encrypted backup.", e)
        }
    }

    /**
     * Uploads the pre-wipe snapshot ZIP into a visible Drive folder so the
     * owner can still recover the old vault by hand after the active
     * appDataFolder slot has been cleared.
     */
    suspend fun uploadArchive(
        name: String,
        bytes: ByteArray,
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): CloudBackupFile = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw GoogleDriveException("Refusing to upload an empty archive")
        if (!hasArchiveAccess(account)) {
            throw GoogleDriveException(
                "Google Drive file access is required to keep an archive. Reconnect Google Drive and try again."
            )
        }
        val drive = driveForVisible(account)
        try {
            val folderId = ensureArchiveFolder(drive)
            val metadata = DriveFile().apply {
                this.name = name
                mimeType = "application/zip"
                parents = listOf(folderId)
            }
            val content = ByteArrayContent("application/zip", bytes)
            val uploaded = withTimeout(180_000L) {
                drive.files().create(metadata, content)
                    .setFields("id,name,size,modifiedTime")
                    .execute()
            }
            CloudBackupFile(
                id = uploaded.id.orEmpty(),
                name = uploaded.name ?: name,
                size = (uploaded.size ?: bytes.size.toLong()).toLong(),
                modifiedTime = uploaded.modifiedTime?.value?.toLong() ?: System.currentTimeMillis(),
                isPrimary = false
            )
        } catch (e: GoogleJsonResponseException) {
            throw GoogleDriveException("Drive archive upload failed (${e.statusCode}).", e)
        } catch (e: GoogleDriveException) {
            throw e
        } catch (e: Throwable) {
            throw GoogleDriveException("Unable to upload the vault archive.", e)
        }
    }

    private suspend fun ensureArchiveFolder(drive: Drive): String {
        val existing = withTimeout(OPERATION_TIMEOUT_MS) {
            drive.files().list()
                .setQ(
                    "trashed = false and mimeType = 'application/vnd.google-apps.folder' " +
                        "and name = '$ARCHIVE_FOLDER' and 'root' in parents"
                )
                .setFields("files(id,name)")
                .setPageSize(5)
                .execute()
        }.files.orEmpty().firstOrNull()?.id
        if (!existing.isNullOrBlank()) return existing
        val folder = withTimeout(OPERATION_TIMEOUT_MS) {
            drive.files().create(
                DriveFile().apply {
                    name = ARCHIVE_FOLDER
                    mimeType = "application/vnd.google-apps.folder"
                    parents = listOf("root")
                }
            ).setFields("id").execute()
        }
        return folder.id?.takeIf { it.isNotBlank() }
            ?: throw GoogleDriveException("Unable to create the Drive archive folder.")
    }

    suspend fun delete(
        fileId: String,
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): Boolean = withContext(Dispatchers.IO) {
        if (fileId.isBlank()) return@withContext false
        runCatching {
            withTimeout(OPERATION_TIMEOUT_MS) {
                driveFor(account).files().delete(fileId).execute()
            }
        }.isSuccess
    }

    /**
     * Removes every file in this app's private appDataFolder, including the
     * salt and XML mirror. Used only by the full erase flow so that no cloud
     * artefact survives a wipe.
     */
    suspend fun deleteAllAppDataFiles(
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): Int = withContext(Dispatchers.IO) {
        val drive = driveFor(account)
        val files = withTimeout(OPERATION_TIMEOUT_MS) {
            drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setFields("files(id,name)")
                .setPageSize(1000)
                .execute()
        }.files.orEmpty()
        var deleted = 0
        files.forEach { file ->
            val id = file.id.orEmpty()
            if (id.isNotBlank()) {
                runCatching {
                    withTimeout(OPERATION_TIMEOUT_MS) {
                        drive.files().delete(id).execute()
                    }
                }.onSuccess { deleted++ }
            }
        }
        deleted
    }

    suspend fun accountEmail(account: GoogleSignInAccount? = lastSignedInAccount()): String {
        return account?.email?.trim()?.lowercase().orEmpty()
    }

    /** Account-wide Drive quota - the "About" call reads the same app-data-scoped credential. */
    suspend fun storageQuota(
        account: GoogleSignInAccount? = lastSignedInAccount()
    ): DriveStorageQuota = withContext(Dispatchers.IO) {
        val drive = driveFor(account)
        val about = withTimeout(OPERATION_TIMEOUT_MS) {
            drive.about().get().setFields("storageQuota").execute()
        }
        val quota = about.storageQuota
            ?: throw GoogleDriveException("Google Drive did not report the account storage.")
        DriveStorageQuota(
            limitBytes = quota.limit ?: 0L,
            usedBytes = quota.usage ?: 0L,
            inDriveBytes = quota.usageInDrive ?: 0L
        )
    }

    @Synchronized
    private fun driveFor(account: GoogleSignInAccount?): Drive =
        buildDrive(account, listOf(DriveScopes.DRIVE_APPDATA))

    @Synchronized
    private fun driveForVisible(account: GoogleSignInAccount?): Drive =
        buildDrive(account, listOf(DriveScopes.DRIVE_FILE))

    @Synchronized
    private fun buildDrive(
        account: GoogleSignInAccount?,
        scopes: List<String>
    ): Drive {
        val signedIn = account ?: lastSignedInAccount()
            ?: throw GoogleDriveException("Sign in with Google to access your encrypted cloud vault.")
        val androidAccount = signedIn.account
            ?: Account(signedIn.email?.trim().orEmpty(), "com.google")
        if (androidAccount.name.isNullOrBlank()) {
            throw GoogleDriveException("This Google account cannot access Drive app data.")
        }
        val credential = GoogleAccountCredential
            .usingOAuth2(context, scopes)
            .apply { selectedAccount = androidAccount }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()
    }
}
