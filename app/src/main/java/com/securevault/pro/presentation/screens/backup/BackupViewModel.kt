package com.securevault.pro.presentation.screens.backup

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.backup.BackupPreview
import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.DriveStorageQuota
import com.securevault.pro.data.remote.google.GoogleDriveService
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class BackupUiState(
    val decoy: Boolean = false,
    val signedInEmail: String = "",
    val hasDriveAccess: Boolean = false,
    val backups: List<CloudBackupFile> = emptyList(),
    val loadingBackups: Boolean = false,
    val driveLinking: Boolean = false,
    val selectedId: String? = null,
    val preview: BackupPreview? = null,
    val previewing: Boolean = false,
    val previewError: String? = null,
    val passphrase: String = "",
    val message: String? = null,
    val messageIsError: Boolean = false,
    val warnings: List<String> = emptyList(),
    val lastBackupAt: Long = 0L,
    val companionUrl: String = "",
    val companionEnabled: Boolean = false,
    val storageQuota: DriveStorageQuota? = null
) {
    val selected: CloudBackupFile? get() = backups.firstOrNull { it.id == selectedId }
}

/**
 * Wraps a nullable answer so that a `null` coming out of `withTimeoutOrNull` can only ever mean
 * "the clock ran out", never "the call legitimately returned nothing". Without it a stalled Drive
 * and a clean no-account result would print the same sentence to the owner.
 */
private class Box<out T>(val value: T)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val cloudSyncManager: CloudSyncManager,
    private val driveService: GoogleDriveService,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private companion object {
        /**
         * Ceilings on the network steps this screen can start. Every one of them exists so a
         * stalled Drive answer returns the owner to a usable screen with a worded result instead
         * of leaving a spinner — or a full-screen, back-key-proof overlay — on screen forever.
         * Each value is deliberately longer than the Drive call it wraps.
         */
        const val DRIVE_LINK_TIMEOUT_MS = 90_000L
        const val HYDRATE_TIMEOUT_MS = 150_000L
        const val LIST_TIMEOUT_MS = 60_000L
        const val PREVIEW_TIMEOUT_MS = 90_000L
    }

    private val _state = MutableStateFlow(
        BackupUiState(
            decoy = sessionManager.isDecoy(),
            signedInEmail = cloudSyncManager.signedInEmail(),
            hasDriveAccess = cloudSyncManager.canAccessDrive()
        )
    )
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    val cloudState = cloudSyncManager.state

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            sessionManager.mode.collect { mode ->
                _state.update { it.copy(decoy = mode == SessionMode.DECOY) }
            }
        }
        viewModelScope.launch {
            appLockManager.lastBackupAt.collect { timestamp ->
                _state.update { it.copy(lastBackupAt = timestamp) }
            }
        }
        viewModelScope.launch {
            appLockManager.companionUrl.collect { url ->
                _state.update { it.copy(companionUrl = url) }
            }
        }
        viewModelScope.launch {
            appLockManager.companionEnabled.collect { enabled ->
                _state.update { it.copy(companionEnabled = enabled) }
            }
        }
        if (!cloudSyncManager.state.value.active) cloudSyncManager.resetState()
        refresh()
    }

    fun signInIntent(): Intent = driveService.signInIntent()

    /**
     * Asked before the picker is launched, so a handset whose Google Play services cannot serve a
     * sign-in is named as that instead of being reported as a cancellation the owner did not make.
     */
    fun googleAvailabilityFault(): String? {
        val code = driveService.availabilityFault() ?: return null
        return when (code) {
            1 -> "Google Play services is missing on this device, so Google sign-in cannot run."
            2 -> "Google Play services is turned off on this device. Turn it on, then try again."
            9, 10, 12 -> "Google Play services on this device is too old for sign-in. Update it, then try again."
            else -> "Google Play services reported problem code $code. Update it and try again."
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val quota = cloudSyncManager.driveStorageQuota().getOrNull()
            if (quota != null) _state.update { it.copy(storageQuota = quota) }
        }
    }

    fun handleSignInResult(data: Intent?) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    driveLinking = true,
                    loadingBackups = true,
                    message = null,
                    warnings = emptyList()
                )
            }
            try {
                // Boxed so that `null` from withTimeoutOrNull means exactly one thing - the clock
                // ran out - and can never be confused with a legitimate "no account" answer.
                val linked = withTimeoutOrNull(DRIVE_LINK_TIMEOUT_MS) {
                    Box(cloudSyncManager.completeGoogleSignIn(data))
                }
                if (linked == null) {
                    _state.update {
                        it.copy(
                            driveLinking = false,
                            loadingBackups = false,
                            message = "Google Drive did not answer in time. Nothing was changed - " +
                                "try again, or continue offline; your local vault is safe either way.",
                            messageIsError = true
                        )
                    }
                    return@launch
                }
                val email = linked.value
                if (email.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            driveLinking = false,
                            loadingBackups = false,
                            message = "Google sign-in did not complete. Your local vault is untouched.",
                            messageIsError = true
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        signedInEmail = email,
                        hasDriveAccess = cloudSyncManager.canAccessDrive()
                    )
                }
                val hydrated = withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
                    Box(cloudSyncManager.hydrateVault(mergeExisting = true))
                }
                _state.update {
                    it.copy(
                        driveLinking = false,
                        loadingBackups = false,
                        message = when {
                            hydrated == null -> "Drive connected. The download is still finishing in the " +
                                "background - you can leave this screen, nothing was erased."
                            else -> "Drive linked · ${hydrated.value?.message ?: "Your vault is current."}"
                        },
                        messageIsError = hydrated?.value?.failureReason != null,
                        warnings = hydrated?.value?.warnings.orEmpty()
                    )
                }
                refresh()
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        driveLinking = false,
                        loadingBackups = false,
                        message = e.message?.take(200) ?: "Google sign-in failed.",
                        messageIsError = true
                    )
                }
            }
        }
    }

    fun onSignInCancelled() {
        _state.update {
            it.copy(
                driveLinking = false,
                loadingBackups = false,
                message = "Google sign-in was cancelled.",
                messageIsError = false
            )
        }
    }

    /**
     * The way out of a cloud step that has stopped answering. The overlay this screen shows is
     * deliberately undismissable by the back key, so without this action a stalled Drive call
     * would trap the owner on a spinner. Nothing is cancelled in the manager and nothing is
     * erased: the download may still land in the background, and only the waiting stops.
     */
    fun abandonCloudWait() {
        refreshJob?.cancel()
        cloudSyncManager.resetState()
        _state.update {
            it.copy(
                driveLinking = false,
                loadingBackups = false,
                previewing = false,
                message = "Stopped waiting on Google Drive. Your local vault is untouched — " +
                    "the transfer may still finish by itself, so pull to refresh in a moment.",
                messageIsError = false
            )
        }
    }

    /** True while a cloud step owns the screen, i.e. while [abandonCloudWait] is the escape. */
    fun isWaitingOnCloud(): Boolean =
        state.value.driveLinking ||
            state.value.loadingBackups ||
            state.value.previewing ||
            cloudSyncManager.state.value.active

    fun refresh() {
        if (state.value.decoy) return
        val email = cloudSyncManager.signedInEmail()
        val hasAccess = cloudSyncManager.canAccessDrive()
        _state.update { it.copy(signedInEmail = email, hasDriveAccess = hasAccess) }
        if (email.isBlank() || !hasAccess) {
            refreshJob?.cancel()
            _state.update { it.copy(backups = emptyList(), loadingBackups = false) }
            return
        }
        refreshStorage()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loadingBackups = true) }
            val listed = withTimeoutOrNull(LIST_TIMEOUT_MS) { cloudSyncManager.listCloudBackups() }
            val outcome = listed ?: Result.failure<List<CloudBackupFile>>(
                IllegalStateException("Google Drive did not answer in time.")
            )
            outcome.fold(
                onSuccess = { files ->
                    _state.update { current ->
                        val selected = current.selectedId?.let { id -> files.firstOrNull { it.id == id } }
                        current.copy(
                            backups = files,
                            loadingBackups = false,
                            selectedId = selected?.id ?: files.firstOrNull()?.id,
                            preview = if (selected?.id == current.selectedId) current.preview else null,
                            previewError = if (selected?.id == current.selectedId) current.previewError else null
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            loadingBackups = false,
                            message = error.message?.take(200) ?: "Cloud backups could not be listed.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            cloudSyncManager.signOutOfDrive()
            refreshJob?.cancel()
            _state.update {
                it.copy(
                    signedInEmail = "",
                    hasDriveAccess = false,
                    backups = emptyList(),
                    selectedId = null,
                    preview = null,
                    previewing = false,
                    previewError = null,
                    loadingBackups = false,
                    message = "Google Drive disconnected. Cloud snapshots stay encrypted in Drive.",
                    messageIsError = false,
                    warnings = emptyList()
                )
            }
        }
    }

    fun select(file: CloudBackupFile) {
        if (state.value.decoy) return
        _state.update {
            it.copy(selectedId = file.id, preview = null, previewError = null)
        }
        previewSelected()
    }

    fun previewSelected() {
        if (state.value.decoy) return
        val file = state.value.selected ?: return
        if (state.value.previewing) return
        viewModelScope.launch {
            _state.update { it.copy(previewing = true, preview = null, previewError = null) }
            val inspected = withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
                cloudSyncManager.previewCloud(file, email = state.value.signedInEmail)
            } ?: Result.failure<BackupPreview>(IllegalStateException("Drive did not answer in time."))
            inspected.fold(
                onSuccess = { preview ->
                    _state.update { current ->
                        if (current.selectedId != file.id) current
                        else current.copy(previewing = false, preview = preview, previewError = null)
                    }
                },
                onFailure = { error ->
                    _state.update { current ->
                        if (current.selectedId != file.id) current
                        else current.copy(
                            previewing = false,
                            preview = null,
                            previewError = error.message?.take(220)
                                ?: "Preview failed. Restore stays available."
                        )
                    }
                }
            )
        }
    }

    fun restoreSelected() {
        if (state.value.decoy) return
        val file = state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(message = null, warnings = emptyList()) }
            cloudSyncManager.restoreFromCloud(file, state.value.signedInEmail).fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            message = outcome.message,
                            messageIsError = outcome.failureReason != null,
                            warnings = outcome.warnings,
                            preview = null
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "The cloud restore could not complete.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun backupNow() {
        if (state.value.decoy) return
        viewModelScope.launch {
            _state.update { it.copy(message = null, warnings = emptyList()) }
            cloudSyncManager.backupToDrive(state.value.signedInEmail).fold(
                onSuccess = { outcome ->
                    val text = when {
                        outcome.cloudUploaded && outcome.offlineSaved ->
                            "Encrypted vault uploaded to Drive and an offline copy was kept."
                        outcome.cloudUploaded ->
                            "Encrypted vault uploaded to Google Drive."
                        outcome.offlineSaved ->
                            "Offline encrypted backup saved. ${outcome.driveError ?: "Drive was unavailable."}"
                        else -> "The backup finished, but no copy could be saved."
                    }
                    _state.update {
                        it.copy(
                            message = text,
                            messageIsError = !outcome.cloudUploaded && !outcome.offlineSaved,
                            warnings = outcome.driveError
                                ?.takeIf { !outcome.cloudUploaded }
                                ?.let { listOf(it) }
                                ?: emptyList()
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "The backup could not be completed.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun restoreEmbeddedSeed() {
        if (state.value.decoy) return
        viewModelScope.launch {
            _state.update { it.copy(message = null, warnings = emptyList()) }
            cloudSyncManager.restoreEmbeddedSeed().fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            message = outcome.message,
                            messageIsError = false,
                            warnings = outcome.warnings
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "The embedded vault could not be restored.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun pushToCompanion() {
        if (state.value.decoy) return
        viewModelScope.launch {
            _state.update { it.copy(message = null, warnings = emptyList()) }
            cloudSyncManager.pushToCompanion().fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(message = outcome.message, messageIsError = false)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "Companion push failed.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun pullFromCompanion() {
        if (state.value.decoy) return
        viewModelScope.launch {
            _state.update { it.copy(message = null, warnings = emptyList()) }
            cloudSyncManager.pullFromCompanion().fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            message = outcome.message,
                            messageIsError = outcome.failureReason != null,
                            warnings = outcome.warnings
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "Companion pull failed.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun setPassphrase(value: String) {
        _state.update { it.copy(passphrase = value.take(128)) }
    }

    fun importLocal(bytes: ByteArray, fileName: String, replace: Boolean = false) {
        if (state.value.decoy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    message = null,
                    warnings = emptyList(),
                    preview = null
                )
            }
            val passphrase = state.value.passphrase.trim().takeIf { it.isNotBlank() }
            cloudSyncManager.importLocalBackup(bytes, passphrase, replace).fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            message = outcome.message,
                            messageIsError = outcome.failureReason != null,
                            warnings = outcome.warnings + "Source · $fileName"
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            message = error.message?.take(240) ?: "The encrypted file could not be imported.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun reportError(message: String) {
        _state.update {
            it.copy(message = message, messageIsError = true)
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, warnings = emptyList()) }
    }
}
