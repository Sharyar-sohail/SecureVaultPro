package com.securevault.pro.presentation.screens.welcome

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.DriveFaults
import com.securevault.pro.data.remote.google.GoogleDriveException
import com.securevault.pro.data.remote.google.GoogleDriveService
import com.securevault.pro.security.backup.EnvelopeDiagnostics
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
    val readyForSetup: Boolean = false,
    /** A Drive copy is waiting on this device's own unlock; setting it triggers the restore. */
    val driveRestoreQueued: Boolean = false,
    /** The last attempt failed on a key this device does not hold; a master password can supply it. */
    val restoreNeedsSecret: Boolean = false,
    val restoreSecret: String = "",
    /** The technical reason, kept so the owner can report it without re-running a slow sweep. */
    val restoreDiagnostic: String? = null
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
     * The file whose import failed, kept only so a passphrase can be offered and the same bytes
     * retried without a second trip through the document picker. Cleared on any successful import.
     */
    private var pendingImport: Pair<ByteArray, String>? = null

    /**
     * Set when Drive refused the credential. The cached account can keep reporting a grant it no
     * longer has, so the next tap must reach Google's picker instead of rebuilding the same dead
     * request; without this the owner ping-pongs between the same error and the same tap.
     */
    private var forcePicker = false

    /**
     * The Drive account whose copies this device could not open, kept so creating the vault can run
     * the restore once more. Copies written by 3.3.4 and later are sealed with the random key held in
     * the account keyring, and that key only comes back by unwrapping the keyring with a password —
     * which the owner types a few seconds later in this same flow. Retrying there needs no prompt.
     */
    private var pendingDriveEmail: String? = null

    fun onWelcomeOpened() {
        // Only a genuinely running restore may hold the welcome state open; a stale
        // busy or active-cloud flag must never freeze the three options.
        if (flowJob?.isActive == true && cloudSyncManager.state.value.active) return
        forcePicker = false
        pendingDriveEmail = null
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
                readyForSetup = false,
                driveRestoreQueued = false,
                restoreNeedsSecret = false,
                restoreSecret = "",
                restoreDiagnostic = null
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
            _state.update { it.copy(busy = true, error = null, notice = null, warnings = emptyList(), restoreNeedsSecret = false, restoreDiagnostic = null) }
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
        pendingDriveEmail = null
        _state.update {
            it.copy(
                busy = false,
                restoreMode = SetupRestoreMode.NONE,
                notice = "Restoring stopped. Anything already downloaded stays in the vault and nothing was erased - finish setting up here or try again.",
                noticeIsError = false,
                driveRestoreQueued = false
            )
        }
    }

    /**
     * Restore every copy in Drive with the keys this app holds.
     *
     * Nothing is asked for up front: the account authorises the download and
     * [CloudSyncManager.restoreAnyCloudBackup] opens each copy with the exact writers — this
     * account's vault key and the key 3.1.9 sealed Drive copies with — so a Google login restores an
     * old vault with no prompt, exactly as 3.1.9 did. A build from 3.3.4 on seals Drive copies with
     * the account's random vault key, which is not derivable from any literal: it only comes back by
     * unwrapping the cloud keyring with a password. Nothing is asked for here — the owner sets that
     * password seconds later in this same flow, and [createVault] runs the walk once more with it.
     * [secret] is that second pass; the keyless first pass is what a bare Google tap runs.
     */
    private suspend fun restoreFromDrive(email: String, secret: String? = null) {
        pendingDriveEmail = null
        _state.update {
            it.copy(busy = true, error = null, restoreNeedsSecret = false, restoreDiagnostic = null)
        }
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
                    readyForSetup = true,
                    driveRestoreQueued = false
                )
            }
            return
        }
        // Every copy is tried, newest first.
        val outcome = cloudSyncManager.restoreAnyCloudBackup(email, passphrase = secret)
        outcome.fold(
            onSuccess = { result ->
                val complete = result.failureReason == null
                // A partial restore still leaves a real vault on the device, so the user
                // is allowed forward with the failure shown — never stranded on Welcome.
                val usable = complete || result.counts.total > 0
                val keepSecret = secret?.trim().orEmpty()
                _state.update {
                    it.copy(
                        busy = false,
                        notice = result.message,
                        noticeIsError = result.failureReason != null,
                        warnings = result.warnings,
                        lastRestoredFrom = primary.name,
                        restoreComplete = complete,
                        readyForSetup = usable,
                        // A password that unwrapped the account keyring is the account's own master
                        // password; carry it into setup so the next keyring publish confirms it
                        // instead of replacing a live key with an unrelated new one.
                        masterPassword = if (usable && keepSecret.isNotBlank()) keepSecret else it.masterPassword,
                        confirmPassword = if (usable && keepSecret.isNotBlank()) keepSecret else it.confirmPassword,
                        restoreNeedsSecret = false,
                        driveRestoreQueued = false,
                        restoreSecret = if (usable) "" else it.restoreSecret,
                        restoreDiagnostic = null
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
                    // A Google sign-in opens the Drive copies this device can unlock, with nothing
                    // typed first. A copy sealed with the account's vault key is not a failure the
                    // owner reads about: that key arrives the moment this phone gets its own unlock,
                    // so the account is remembered, the flow walks on by itself, and the restore runs
                    // again automatically. Red is reserved for a retry that genuinely failed.
                    val keyRejected = looksLikeKeyMismatch(message)
                    val awaitsOwnUnlock = keyRejected && secret.isNullOrBlank()
                    if (awaitsOwnUnlock) pendingDriveEmail = email
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = when {
                                awaitsOwnUnlock ->
                                    "Signed in as $email. Your backups are safe in Google Drive and " +
                                        "load on their own once this phone has its unlock."
                                keyRejected ->
                                    "Signed in as $email. ${EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE}"
                                else -> message.ifBlank { "The cloud restore could not complete." }
                            },
                            noticeIsError = keyRejected && !awaitsOwnUnlock,
                            // The welcome screen never stops on a key it is still waiting for.
                            readyForSetup = keyRejected,
                            driveRestoreQueued = awaitsOwnUnlock,
                            restoreNeedsSecret = false,
                            restoreDiagnostic = null
                        )
                    }
                }
            }
        )
    }

    fun onRestoreSecretChange(value: String) {
        _state.update { it.copy(restoreSecret = value, error = null) }
    }

    fun cancelRestoreSecret() {
        _state.update {
            it.copy(
                restoreNeedsSecret = false,
                restoreSecret = "",
                notice = "Nothing was changed. You can try Google again, open an exported file, or create a vault on this device.",
                noticeIsError = false
            )
        }
    }

    /**
     * Retries the failed attempt with the password the owner typed.
     *
     * For Drive this unwraps the account keyring (the same random key every current copy is sealed
     * with). For a file it opens the passphrase-sealed export. The password never leaves this
     * process except as a PBKDF2 input.
     */
    fun submitRestoreSecret() {
        val snapshot = _state.value
        val secret = snapshot.restoreSecret.trim()
        if (secret.isBlank() || snapshot.busy) return
        flowJob = viewModelScope.launch {
            val pending = pendingImport
            if (snapshot.restoreMode == SetupRestoreMode.FILE && pending != null) {
                importLocalFile(pending.first, pending.second, secret)
            } else {
                restoreFromDrive(snapshot.signedInEmail, secret)
            }
        }    }

    /** True when a failure is a key mismatch rather than a network, consent or file-shape fault. */
    private fun looksLikeKeyMismatch(message: String): Boolean =
        message.contains(EnvelopeDiagnostics.KEY_NOT_ON_THIS_DEVICE, ignoreCase = true) ||
            message.contains("locked with a key", ignoreCase = true) ||
            message.contains("does not hold", ignoreCase = true) ||
            message.contains("doesnt have", ignoreCase = true) ||
            message.contains("could not be opened", ignoreCase = true)

    fun importLocalFile(bytes: ByteArray, fileName: String, passphrase: String? = null) {
        // Kept so a failure can be retried with a passphrase without asking for the file again.
        pendingImport = bytes to fileName
        flowJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    restoreMode = SetupRestoreMode.FILE,
                    error = null,
                    notice = null,
                    warnings = emptyList(),
                    lastRestoredFrom = fileName,
                    restoreNeedsSecret = false,
                    restoreDiagnostic = null
                )
            }
            cloudSyncManager.importLocalBackup(bytes, passphrase = passphrase).fold(
                onSuccess = { result ->
                    val complete = result.failureReason == null
                    val usable = complete || result.counts.total > 0
                    if (usable) pendingImport = null
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = result.message,
                            noticeIsError = result.failureReason != null,
                            warnings = result.warnings,
                            readyForSetup = usable,
                            restoreNeedsSecret = false,
                            restoreSecret = if (usable) "" else it.restoreSecret,
                            restoreDiagnostic = null
                        )
                    }
                },
                onFailure = { error ->
                    val message = error.message?.take(600).orEmpty()
                    val needsSecret = looksLikeKeyMismatch(message)
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = when {
                                !needsSecret -> message.ifBlank { "The encrypted file could not be imported." }
                                passphrase.isNullOrBlank() -> FILE_SECRET_PROMPT
                                else -> "That passphrase did not open this export. It is case-sensitive — enter it exactly as you chose it."
                            },
                            noticeIsError = true,
                            restoreNeedsSecret = needsSecret,
                            restoreDiagnostic = if (needsSecret) message else null
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
                val pendingEmail = pendingDriveEmail
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
                        error = null,
                        driveRestoreQueued = false
                    )
                }
                // A Drive copy that needed this account's vault key is fetched after the vault is
                // already open, never before. Nothing on the setup screen waits on a network call:
                // the restore lands in the banner when it finishes, and the realtime sync starts the
                // moment the session unlocks either way.
                if (pendingEmail != null) {
                    pendingDriveEmail = null
                    restoreInBackground(pendingEmail, snapshot.masterPassword)
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

    /**
     * The keyring retry, run off the setup screen.
     *
     * Signing in with Google and choosing a master password is enough to bring the account's vault
     * key down and open the copies that need it. That takes a network round trip, so it happens
     * after the dashboard is up rather than in front of it — the owner never sits on the setup
     * screen watching a cloud call, and the result arrives as a normal banner.
     */
    private fun restoreInBackground(email: String, masterPassword: String) {
        viewModelScope.launch {
            val outcome = runCatching {
                withTimeoutOrNull(DRIVE_RETRY_TIMEOUT_MS) {
                    cloudSyncManager.restoreAnyCloudBackup(email, passphrase = masterPassword)
                }
            }.getOrNull()
            outcome?.fold(
                onSuccess = { result ->
                    _state.update {
                        it.copy(
                            notice = "Your vault was restored from Google Drive. ${result.message}",
                            noticeIsError = result.failureReason != null,
                            warnings = result.warnings,
                            restoreDiagnostic = null
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            notice = "This device's vault is ready and your Drive backups are safe, untouched. " +
                                "Tap Continue with Google once you know the password this account uses. ${(error.message ?: "").take(140)}".trim(),
                            noticeIsError = true,
                            restoreDiagnostic = error.message?.take(600)
                        )
                    }
                }
            )
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

        /**
         * One keyring fetch (45 s) plus one download (60 s) is the shortest honest retry; the walk is
         * cut off there rather than running every copy, because finishing setup outranks the restore.
         */
        const val DRIVE_RETRY_TIMEOUT_MS = 105_000L

        const val FILE_SECRET_PROMPT =
            "This export is sealed with a passphrase, so it cannot open with the device keys alone. " +
                "Enter the passphrase you used when you exported it."
    }
}
