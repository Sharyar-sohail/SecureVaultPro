package com.securevault.pro.presentation.screens.welcome

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.DriveFaults
import com.securevault.pro.data.remote.google.GoogleDriveException
import com.securevault.pro.data.remote.google.GoogleDriveService
import com.securevault.pro.presentation.components.BiometricUtils
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.PasswordAnalyzer
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupRestoreMode { NONE, DRIVE, FILE }

data class SetupUiState(
    val step: Int = 1,
    val masterPassword: String = "",
    val confirmPassword: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val useBiometric: Boolean = true,
    val biometricAvailable: Boolean = false,
    val signedInEmail: String = "",
    val restoreMode: SetupRestoreMode = SetupRestoreMode.NONE,
    val busy: Boolean = false,
    val passwordScore: Int = 0,
    val passwordLabel: String = "Empty",
    val error: String? = null,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val warnings: List<String> = emptyList(),
    val lastRestoredFrom: String = "",
    val restoreComplete: Boolean = false,
    val readyForSetup: Boolean = false
) {
    val canSubmit: Boolean
        get() = masterPassword.length >= 8 &&
            masterPassword == confirmPassword &&
            pin.length in 4..8 &&
            pin == confirmPin &&
            !busy

    val masterReady: Boolean
        get() = masterPassword.length >= 8 && masterPassword == confirmPassword

    val pinReady: Boolean
        get() = pin.length in 4..8 && pin == confirmPin
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val cloudSyncManager: CloudSyncManager,
    private val driveService: GoogleDriveService,
    private val analyzer: PasswordAnalyzer
) : ViewModel() {

    private val _state = MutableStateFlow(
        SetupUiState(
            biometricAvailable = BiometricUtils.canAuthenticate(context),
            signedInEmail = driveService.lastSignedInAccount()?.email.orEmpty()
        )
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    val cloudState = cloudSyncManager.state

    /** The in-flight sign-in / restore / import job, so the welcome screen can abort it. */
    private var flowJob: Job? = null

    /**
     * Set when Drive refused the credential. The cached account can keep reporting a grant it no
     * longer has, so the next tap must reach Google's picker instead of rebuilding the same dead
     * request; without this the owner ping-pongs between the same error and the same tap.
     */
    private var forcePicker = false

    fun onWelcomeOpened() {
        // Only a genuinely running restore may hold the welcome state open; a stale
        // busy or active-cloud flag must never freeze the three options.
        if (flowJob?.isActive == true && cloudSyncManager.state.value.active) return
        forcePicker = false
        if (cloudSyncManager.state.value.active) cloudSyncManager.abandonOperation("Previous attempt was interrupted.")
        val email = runCatching { driveService.lastSignedInAccount()?.email.orEmpty() }.getOrDefault("")
        _state.update {
            it.copy(
                step = 1,
                masterPassword = "",
                confirmPassword = "",
                pin = "",
                confirmPin = "",
                signedInEmail = email,
                restoreMode = SetupRestoreMode.NONE,
                busy = false,
                error = null,
                notice = null,
                noticeIsError = false,
                warnings = emptyList(),
                lastRestoredFrom = "",
                restoreComplete = false,
                readyForSetup = false
            )
        }
    }

    fun setMasterPassword(value: String) {        val analysis = analyzer.analyze(value)
        _state.update {
            it.copy(
                masterPassword = value,
                passwordScore = analysis.score,
                passwordLabel = analysis.label,
                error = null
            )
        }
    }

    fun setConfirmPassword(value: String) {
        _state.update { it.copy(confirmPassword = value, error = null) }
    }

    fun setPin(value: String) {
        val digits = value.filter(Char::isDigit).take(8)
        _state.update { it.copy(pin = digits, error = null) }
    }

    fun setConfirmPin(value: String) {
        val digits = value.filter(Char::isDigit).take(8)
        _state.update { it.copy(confirmPin = digits, error = null) }
    }

    fun setUseBiometric(enabled: Boolean) {
        _state.update { it.copy(useBiometric = enabled) }
    }

    fun signInIntent(): Intent = driveService.signInIntent()

    /**
     * Asked before the picker is launched. A handset whose Google Play services cannot serve a
     * sign-in used to look exactly like the owner pressing Back; this names that instead.
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

    /**
     * One tap on "Continue with Google".
     *
     * True means the screen must start Google's account picker; false means a restore is already
     * running with the account this device holds. Nothing is typed in between: the OAuth account
     * is the authentication, and no seed or passphrase may stand in front of it.
     */
    fun onGoogleTileTapped(): Boolean {
        val account = runCatching { driveService.lastSignedInAccount() }.getOrNull()
        val usable = !forcePicker &&
            account != null &&
            !account.email.isNullOrBlank() &&
            runCatching { driveService.hasDriveAccess(account) }.getOrDefault(false)
        if (!usable) {
            _state.update { it.copy(signedInEmail = account?.email.orEmpty(), busy = false) }
            return true
        }
        val email = account.email!!.trim()
        _state.update {
            it.copy(
                signedInEmail = email,
                restoreMode = SetupRestoreMode.DRIVE,
                error = null,
                notice = null,
                noticeIsError = false,
                warnings = emptyList()
            )
        }
        flowJob = viewModelScope.launch { restoreFromDrive(email) }
        return false
    }

    fun onSignInCancelled() {
        _state.update {
            it.copy(
                busy = false,
                notice = "Google closed the sign-in screen before it finished. Nothing was changed — " +
                    "try again, or use an encrypted file or the embedded vault.",
                noticeIsError = false
            )
        }
    }

    fun reportError(message: String) {
        _state.update { it.copy(busy = false, error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun nextStep() {
        val snapshot = _state.value
        if (snapshot.step != 1) return
        val error = validateMaster(snapshot)
        if (error != null) {
            _state.update { it.copy(error = error) }
        } else {
            _state.update { it.copy(step = 2, error = null) }
        }
    }

    fun previousStep() {
        _state.update { it.copy(step = (it.step - 1).coerceAtLeast(1), error = null) }
    }

    fun handleGoogleSignInResult(data: Intent?) {
        flowJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null, warnings = emptyList()) }
            try {
                val email = cloudSyncManager.completeGoogleSignIn(data)
                if (email.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = "Google sign-in did not complete. Try again or restore from a file.",
                            noticeIsError = true
                        )
                    }
                    return@launch
                }
                forcePicker = false
                _state.update {
                    it.copy(signedInEmail = email, restoreMode = SetupRestoreMode.DRIVE)
                }
                restoreFromDrive(email)
            } catch (e: GoogleDriveException) {
                _state.update {
                    it.copy(
                        busy = false,
                        notice = e.message ?: "Google sign-in failed.",
                        noticeIsError = true
                    )
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        notice = e.message?.take(200) ?: "Google sign-in failed.",
                        noticeIsError = true
                    )
                }
            }
        }
    }

    /**
     * Drops a restore that is not making progress. The caller decides when the wait
     * is long enough to offer this, so the welcome screen always keeps an exit.
     */
    fun cancelPendingFlow() {
        flowJob?.cancel()
        flowJob = null
        cloudSyncManager.abandonOperation("Restoring was stopped.")
        _state.update {
            it.copy(
                busy = false,
                restoreMode = SetupRestoreMode.NONE,
                notice = "Restoring stopped. Anything already downloaded stays in the vault and nothing was erased - finish setting up here or try again.",
                noticeIsError = false
            )
        }
    }

    /**
     * Restore every copy in Drive with the keys this app holds. Nothing is asked for: the
     * account authorises the download and [CloudSyncManager.restoreAnyCloudBackup] sweeps
     * the vault key, the retired universal keys and the account address itself.
     */
    private suspend fun restoreFromDrive(email: String) {
        _state.update { it.copy(busy = true, error = null) }
        val listed = withTimeoutOrNull(LIST_TIMEOUT_MS) { cloudSyncManager.listCloudBackups() }
        if (listed == null) {
            _state.update {
                it.copy(
                    busy = false,
                    notice = "Google Drive is taking too long to answer. Tap Continue with Google to try again.",
                    noticeIsError = true
                )
            }
            return
        }
        val files = listed.getOrElse { error ->
            val message = error.message?.take(160).orEmpty()
            if (DriveFaults.needsFreshSignIn(message)) {
                // Clear the dead credential so the next tap opens Google's picker instead of
                // rebuilding the same request and reporting the same fault.
                forcePicker = true
                cloudSyncManager.signOutOfDrive()
                _state.update {
                    it.copy(
                        busy = false,
                        signedInEmail = "",
                        restoreMode = SetupRestoreMode.NONE,
                        notice = DriveFaults.SIGN_IN_AGAIN,
                        noticeIsError = true
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        notice = "Drive is reachable, but the backups could not be listed. $message".trim(),
                        noticeIsError = true
                    )
                }
            }
            return
        }
        val primary = files.firstOrNull { it.isPrimary } ?: files.firstOrNull()
        if (primary == null) {
            _state.update {
                it.copy(
                    busy = false,
                    restoreMode = SetupRestoreMode.DRIVE,
                    notice = "No encrypted backup was found in Drive. The embedded vault will be installed when you create the vault.",
                    noticeIsError = false,
                    lastRestoredFrom = email,
                    restoreComplete = false,
                    readyForSetup = true
                )
            }
            return
        }
        // Every copy is tried, newest first, and no typed secret is involved.
        val outcome = cloudSyncManager.restoreAnyCloudBackup(email)
        outcome.fold(
            onSuccess = { result ->
                val complete = result.failureReason == null
                // A partial restore still leaves a real vault on the device, so the user
                // is allowed forward with the failure shown — never stranded on Welcome.
                val usable = complete || result.counts.total > 0
                _state.update {
                    it.copy(
                        busy = false,
                        notice = result.message,
                        noticeIsError = result.failureReason != null,
                        warnings = result.warnings,
                        lastRestoredFrom = primary.name,
                        restoreComplete = complete,
                        readyForSetup = usable
                    )
                }
            },
            onFailure = { error ->
                val message = error.message?.take(600).orEmpty()
                if (DriveFaults.needsFreshSignIn(message)) {
                    forcePicker = true
                    cloudSyncManager.signOutOfDrive()
                    _state.update {
                        it.copy(
                            busy = false,
                            signedInEmail = "",
                            restoreMode = SetupRestoreMode.NONE,
                            notice = DriveFaults.SIGN_IN_AGAIN,
                            noticeIsError = true
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = message.ifBlank { "The cloud restore could not complete." },
                            noticeIsError = true
                        )
                    }
                }
            }
        )
    }

    fun importLocalFile(bytes: ByteArray, fileName: String) {
        flowJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    restoreMode = SetupRestoreMode.FILE,
                    error = null,
                    notice = null,
                    warnings = emptyList(),
                    lastRestoredFrom = fileName
                )
            }
            cloudSyncManager.importLocalBackup(bytes, passphrase = null).fold(
                onSuccess = { result ->
                    val complete = result.failureReason == null
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = result.message,
                            noticeIsError = result.failureReason != null,
                            warnings = result.warnings,
                            readyForSetup = complete || result.counts.total > 0
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = error.message?.take(600) ?: "The encrypted file could not be imported.",
                            noticeIsError = true
                        )
                    }
                }
            )
        }
    }

    fun createVault() {
        val snapshot = _state.value
        if (snapshot.busy) return
        val validation = validate(snapshot)
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                cloudSyncManager.ensureVaultPopulated().getOrThrow()
                // Credentials, the biometric choice and the setup flag land in one edit,
                // then the session opens. Any await between the flag and the unlock lets
                // the stage machine see "setup done, still locked" and put the lock
                // screen between the PIN pad and the dashboard.
                appLockManager.completeSetup(
                    masterPassword = snapshot.masterPassword,
                    pin = snapshot.pin,
                    biometricEnabled = snapshot.useBiometric && snapshot.biometricAvailable
                )
                sessionManager.unlock(decoy = false)
                _state.update {
                    it.copy(
                        busy = false,
                        masterPassword = "",
                        confirmPassword = "",
                        pin = "",
                        confirmPin = "",
                        error = null
                    )
                }
            } catch (e: Throwable) {
                sessionManager.lock()
                _state.update {
                    it.copy(
                        busy = false,
                        error = "The vault could not be initialised. ${e.message?.take(160) ?: ""}".trim()
                    )
                }
            }
        }
    }

    private fun validate(state: SetupUiState): String? =
        validateMaster(state) ?: validatePin(state)

    private fun validateMaster(state: SetupUiState): String? = when {
        state.masterPassword.length < 8 -> "Use at least 8 characters for your master password."
        state.masterPassword != state.confirmPassword -> "The master passwords do not match."
        else -> null
    }

    private fun validatePin(state: SetupUiState): String? = when {
        state.pin.length !in 4..8 -> "Choose a 4–8 digit PIN."
        state.pin != state.confirmPin -> "The PINs do not match."
        else -> null
    }

    private companion object {
        /** Long enough for a slow mobile handshake; the Drive call itself gives up at 45 s. */
        const val LIST_TIMEOUT_MS = 60_000L
    }
}
