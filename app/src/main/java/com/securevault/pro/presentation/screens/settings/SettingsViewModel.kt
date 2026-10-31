package com.securevault.pro.presentation.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.backup.BloggerThemeExporter
import com.securevault.pro.data.backup.BloggerThemeFile
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.data.remote.google.CloudSyncManager
import com.securevault.pro.data.remote.google.LocalExportData
import com.securevault.pro.data.remote.google.XmlExportData
import com.securevault.pro.data.remote.realtime.RealtimeEngine
import com.securevault.pro.presentation.components.BiometricUtils
import com.securevault.pro.presentation.theme.VaultFontController
import com.securevault.pro.presentation.theme.VaultFonts
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.DeadManStatus
import com.securevault.pro.security.DeadManSwitchManager
import com.securevault.pro.security.LocalWipeService
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URL
import javax.inject.Inject

data class SettingsUiState(
    val decoy: Boolean = false,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val screenshotBlocked: Boolean = true,
    val randomizeKeypad: Boolean = true,
    val travelMode: Boolean = false,
    val autoLockSeconds: Int = 60,
    val inactivityLockSeconds: Int = 120,
    val clipboardSeconds: Int = 30,
    val deadManArmed: Boolean = false,
    val deadManIntervalDays: Int = 7,
    val deadManDaysLeft: Int = 7,
    val decoyEnabled: Boolean = false,
    val pinLength: Int = 6,
    val cloudEmail: String = "",
    val companionUrl: String = "",
    val companionEnabled: Boolean = false,
    val companionPairCode: String = "",
    val themeId: String = VaultPalettes.DEFAULT_ID,
    val fontId: String = VaultFonts.DEFAULT_ID,
    val working: Boolean = false,
    val exportPassphrase: String = "",
    val bloggerClientId: String = "",
    val exportBusy: Boolean = false,
    val exportData: LocalExportData? = null,
    val bloggerBusy: Boolean = false,
    val bloggerExport: BloggerThemeFile? = null,
    val xmlBusy: Boolean = false,
    val xmlExport: XmlExportData? = null,
    val credentialVersion: Int = 0,
    val credentialError: String? = null,
    val wipeError: String? = null,
    val wipeArchiveName: String? = null,
    val wiped: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,

    // ---- realtime sync status (live numbers only; never any key material) ----
    val realtimeUid: String? = null,
    val realtimeListening: Boolean = false,
    val realtimeNeedsKey: Boolean = false,
    val realtimeLatencyMs: Long? = null,
    val realtimePending: Int = 0,
    val realtimeError: String? = null,
    val realtimePassphrase: String = "",
    val realtimeAttaching: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val sessionManager: SessionManager,
    private val cloudSyncManager: CloudSyncManager,
    private val bloggerThemeExporter: BloggerThemeExporter,
    private val deadManSwitchManager: DeadManSwitchManager,
    private val localWipeService: LocalWipeService,
    private val realtimeEngine: RealtimeEngine,
    private val keyring: Keyring
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            decoy = sessionManager.isDecoy(),
            biometricAvailable = BiometricUtils.canAuthenticate(context),
            cloudEmail = cloudSyncManager.signedInEmail(),
            themeId = VaultPaletteController.current.id,
            fontId = VaultFontController.current.id
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private companion object {
        /** Hard escape on top of the keyring's own 30 s bound, so the attach button is never stuck. */
        const val ATTACH_ESCAPE_MS = 45_000L
    }

    init {
        viewModelScope.launch {
            sessionManager.mode.collect { mode ->
                _state.update { it.copy(decoy = mode == SessionMode.DECOY) }
            }
        }
        viewModelScope.launch {
            appLockManager.biometricEnabled.collect { value ->
                _state.update { it.copy(biometricEnabled = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.screenshotBlocked.collect { value ->
                _state.update { it.copy(screenshotBlocked = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.randomizeKeypad.collect { value ->
                _state.update { it.copy(randomizeKeypad = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.travelMode.collect { value ->
                _state.update { it.copy(travelMode = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.autoLockSeconds.collect { value ->
                _state.update { it.copy(autoLockSeconds = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.inactivityLockSeconds.collect { value ->
                _state.update { it.copy(inactivityLockSeconds = value) }
            }
        }
        viewModelScope.launch {
            combine(
                appLockManager.deadManArmed,
                appLockManager.deadManIntervalDays,
                appLockManager.deadManLastCheckIn
            ) { armed, interval, last -> DeadManStatus(armed, interval, last) }
                .collect { status ->
                    _state.update {
                        it.copy(
                            deadManArmed = status.armed,
                            deadManIntervalDays = status.intervalDays,
                            deadManDaysLeft = if (status.armed) status.daysRemaining() else status.intervalDays
                        )
                    }
                }
        }
        viewModelScope.launch {
            appLockManager.clipboardSeconds.collect { value ->
                _state.update { it.copy(clipboardSeconds = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.decoyEnabled.collect { value ->
                _state.update { it.copy(decoyEnabled = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.pinLength.collect { value ->
                _state.update { it.copy(pinLength = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.cloudEmail.collect { value ->
                _state.update { it.copy(cloudEmail = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.companionUrl.collect { value ->
                _state.update { it.copy(companionUrl = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.companionEnabled.collect { value ->
                _state.update { it.copy(companionEnabled = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.companionPairCode.collect { value ->
                _state.update { it.copy(companionPairCode = value) }
            }
        }
        viewModelScope.launch {
            appLockManager.themeId.collect { value ->
                _state.update { it.copy(themeId = VaultPalettes.find(value).id) }
            }
        }
        viewModelScope.launch {
            appLockManager.fontId.collect { value ->
                _state.update { it.copy(fontId = VaultFonts.find(value).id) }
            }
        }
        // The realtime engine publishes the live numbers; the failure sentence is shown verbatim.
        viewModelScope.launch {
            realtimeEngine.status.collect { rt ->
                _state.update {
                    it.copy(
                        realtimeUid = rt.uid,
                        realtimeListening = rt.listenersActive,
                        realtimeNeedsKey = rt.needsPassphrase,
                        realtimeLatencyMs = rt.lastLatencyMs,
                        realtimePending = rt.recordsPending,
                        realtimeError = rt.lastError
                    )
                }
            }
        }
    }

    fun setRealtimePassphrase(value: String) {
        _state.update { it.copy(realtimePassphrase = value.take(128)) }
    }

    /**
     * Second-phone / fresh-install path: open the cloud keyring with the typed passphrase and adopt it
     * as this device's copy, so the first realtime write can seal. [Keyring.attachKeyring] is itself
     * bounded to 30 s; this adds a hard escape so a stalled network can never spin the button forever.
     * A failure surfaces the verbatim sentence — the wrong-passphrase case is the only signal the user
     * gets — and the block stays non-modal so the owner can always leave.
     */
    fun attachRealtimeKeyring() {
        val passphrase = _state.value.realtimePassphrase
        if (passphrase.isBlank()) {
            _state.update { it.copy(realtimeError = "Enter your passphrase to connect this device.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(realtimeAttaching = true, realtimeError = null) }
            val outcome = runCatching { withTimeout(ATTACH_ESCAPE_MS) { keyring.attachKeyring(passphrase) } }
            val outer = outcome.exceptionOrNull()
            // A real coroutine cancellation (screen gone) must propagate, not be swallowed as success.
            if (outer is CancellationException && outer !is TimeoutCancellationException) throw outer
            if (outer is TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        realtimeAttaching = false,
                        realtimeError = "Connecting did not answer in time. Retry when you have signal."
                    )
                }
                return@launch
            }
            // inner failure is attachKeyring's own Result.failure — a worded sentence to show verbatim.
            val failure = outer ?: outcome.getOrNull()?.exceptionOrNull()
            if (failure != null) {
                _state.update {
                    it.copy(
                        realtimeAttaching = false,
                        realtimeError = failure.message?.take(240) ?: "The vault key could not be attached."
                    )
                }
                return@launch
            }
            // Success: start the engine (idempotent) and clear the draft passphrase from memory.
            realtimeEngine.start()
            _state.update {
                it.copy(
                    realtimeAttaching = false,
                    realtimePassphrase = "",
                    realtimeError = null,
                    message = "This device is connected to web sync.",
                    messageIsError = false
                )
            }
        }
    }

    /**
     * Font changes are visual only, so they stay available in a decoy session too.
     * The family is applied to the live snapshot and persisted for the next launch.
     */
    fun setFont(id: String) {
        val font = VaultFonts.find(id)
        VaultFontController.apply(font)
        _state.update { it.copy(fontId = font.id) }
        viewModelScope.launch { appLockManager.setFontId(font.id) }
    }

    /**
     * Theme changes are visual only, so they stay available even in a decoy
     * session. The palette is applied to the live snapshot immediately and the
     * id is persisted for the next launch.
     */
    fun setTheme(id: String) {
        val palette = VaultPalettes.find(id)
        VaultPaletteController.apply(palette)
        _state.update { it.copy(themeId = palette.id) }
        viewModelScope.launch { appLockManager.setThemeId(palette.id) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        if (!ensureWritable()) return
        if (enabled && !state.value.biometricAvailable) {
            _state.update {
                it.copy(
                    message = "No face or fingerprint is enrolled on this device.",
                    messageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            appLockManager.setBiometricEnabled(enabled)
            _state.update {
                it.copy(
                    message = if (enabled) {
                        "Biometric unlock enabled for this device."
                    } else {
                        "Biometric unlock disabled. Your PIN still works."
                    },
                    messageIsError = false
                )
            }
        }
    }

    fun setAllowScreenshots(enabled: Boolean) {
        if (!ensureWritable()) return
        viewModelScope.launch {
            appLockManager.setScreenshotBlocked(!enabled)
            _state.update {
                it.copy(
                    message = if (enabled) {
                        "Screenshots are now allowed. Use with care."
                    } else {
                        "Screenshots and recent-app previews are blocked."
                    },
                    messageIsError = enabled
                )
            }
        }
    }

    fun setRandomizeKeypad(enabled: Boolean) {
        if (!ensureWritable()) return
        viewModelScope.launch { appLockManager.setRandomizeKeypad(enabled) }
    }

    fun setTravelMode(enabled: Boolean) {
        if (!ensureWritable()) return
        viewModelScope.launch {
            appLockManager.setTravelMode(enabled)
            _state.update {
                it.copy(
                    message = if (enabled) {
                        "Travel mode is on. Flagged items are hidden from lists and search."
                    } else {
                        "Travel mode is off. All items are visible again."
                    },
                    messageIsError = false
                )
            }
        }
    }

    fun setAutoLockSeconds(seconds: Int) {
        if (!ensureWritable()) return
        viewModelScope.launch { appLockManager.setAutoLockSeconds(seconds) }
    }

    fun setClipboardSeconds(seconds: Int) {
        if (!ensureWritable()) return
        viewModelScope.launch { appLockManager.setClipboardSeconds(seconds) }
    }

    fun setInactivityLockSeconds(seconds: Int) {
        if (!ensureWritable()) return
        viewModelScope.launch { appLockManager.setInactivityLockSeconds(seconds) }
    }

    fun armDeadManSwitch(masterPassword: String, intervalDays: Int) {
        if (!ensureWritable()) return
        if (masterPassword.isBlank()) {
            _state.update {
                it.copy(message = "Enter your master password to arm the dead man's switch.", messageIsError = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(masterPassword)
            }
            if (!valid) {
                _state.update {
                    it.copy(working = false, message = "Master password is incorrect.", messageIsError = true)
                }
                return@launch
            }
            deadManSwitchManager.arm(intervalDays)
            _state.update {
                it.copy(
                    working = false,
                    message = "Dead man's switch armed. Open and unlock the app within $intervalDays day(s) to keep local data. Drive backups are never touched.",
                    messageIsError = false
                )
            }
        }
    }

    fun disarmDeadManSwitch(masterPassword: String) {
        if (!ensureWritable()) return
        if (masterPassword.isBlank()) {
            _state.update {
                it.copy(message = "Enter your master password to disarm the switch.", messageIsError = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(masterPassword)
            }
            if (!valid) {
                _state.update {
                    it.copy(working = false, message = "Master password is incorrect.", messageIsError = true)
                }
                return@launch
            }
            deadManSwitchManager.disarm()
            _state.update {
                it.copy(
                    working = false,
                    message = "Dead man's switch disarmed. Nothing will be wiped automatically.",
                    messageIsError = false
                )
            }
        }
    }

    fun checkInDeadManSwitch() {
        if (!ensureWritable()) return
        viewModelScope.launch {
            deadManSwitchManager.checkIn()
            _state.update {
                it.copy(
                    message = "Check-in recorded. The countdown restarted.",
                    messageIsError = false
                )
            }
        }
    }

    fun changeMasterPassword(current: String, newPassword: String, confirm: String) {
        if (!ensureWritable()) return
        if (current.isBlank()) {
            credentialFailure("Enter your current master password.")
            return
        }
        if (newPassword.length < 8) {
            credentialFailure("Use at least 8 characters for the new master password.")
            return
        }
        if (newPassword != confirm) {
            credentialFailure("The new master passwords do not match.")
            return
        }
        if (newPassword == current) {
            credentialFailure("Choose a master password that differs from the current one.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, credentialError = null, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(current)
            }
            if (!valid) {
                credentialFailure("Current master password is incorrect.")
                return@launch
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    appLockManager.setMasterPassword(newPassword, previousPassphrase = current)
                }
            }.onSuccess {
                credentialSuccess("Master password updated. There is still no recovery if it is lost.")
            }.onFailure {
                credentialFailure(it.message?.take(180) ?: "The master password could not be updated.")
            }
        }
    }

    fun changePin(currentMaster: String, newPin: String, confirm: String) {
        if (!ensureWritable()) return
        if (currentMaster.isBlank()) {
            credentialFailure("Enter your master password to authorise this change.")
            return
        }
        if (newPin.length !in 4..8 || !newPin.all(Char::isDigit)) {
            credentialFailure("Choose a 4–8 digit PIN.")
            return
        }
        if (newPin != confirm) {
            credentialFailure("The new PINs do not match.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, credentialError = null, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(currentMaster)
            }
            if (!valid) {
                credentialFailure("Master password is incorrect.")
                return@launch
            }
            val decoyClash = runCatching { appLockManager.verifyDecoyPin(newPin) }.getOrDefault(false)
            if (decoyClash) {
                credentialFailure("That PIN already unlocks the restricted vault. Choose another.")
                return@launch
            }
            runCatching { appLockManager.setPin(newPin) }
                .onSuccess { credentialSuccess("Daily unlock PIN updated.") }
                .onFailure { credentialFailure(it.message?.take(180) ?: "The PIN could not be updated.") }
        }
    }

    fun setDecoyPin(currentMaster: String, decoyPin: String, confirm: String) {
        if (!ensureWritable()) return
        if (currentMaster.isBlank()) {
            credentialFailure("Enter your master password to authorise this change.")
            return
        }
        if (decoyPin.length !in 4..8 || !decoyPin.all(Char::isDigit)) {
            credentialFailure("Choose a 4–8 digit decoy PIN.")
            return
        }
        if (decoyPin != confirm) {
            credentialFailure("The decoy PINs do not match.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, credentialError = null, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(currentMaster)
            }
            if (!valid) {
                credentialFailure("Master password is incorrect.")
                return@launch
            }
            val realClash = runCatching { appLockManager.verifyPin(decoyPin) }.getOrDefault(false)
            if (realClash) {
                credentialFailure("Choose a decoy PIN that differs from your real PIN.")
                return@launch
            }
            val masterClash = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(decoyPin)
            }
            if (masterClash) {
                credentialFailure("Choose a decoy PIN that differs from your master password.")
                return@launch
            }
            runCatching { appLockManager.setDecoyPin(decoyPin) }
                .onSuccess {
                    credentialSuccess("Decoy PIN active. A separate restricted vault opens with it.")
                }
                .onFailure {
                    credentialFailure(it.message?.take(180) ?: "The decoy PIN could not be saved.")
                }
        }
    }

    fun removeDecoyPin(currentMaster: String) {
        if (!ensureWritable()) return
        if (currentMaster.isBlank()) {
            credentialFailure("Enter your master password to remove the decoy PIN.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, credentialError = null, message = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(currentMaster)
            }
            if (!valid) {
                credentialFailure("Master password is incorrect.")
                return@launch
            }
            runCatching { appLockManager.setDecoyPin(null) }
                .onSuccess { credentialSuccess("Decoy PIN removed. Only your real PIN unlocks now.") }
                .onFailure { credentialFailure("The decoy PIN could not be removed.") }
        }
    }

    fun updateCompanionUrl(url: String) {
        if (!ensureWritable()) return
        val normalized = url.trim().trimEnd('/')
        if (!CompanionPairing.isTrustworthy(normalized)) {
            _state.update {
                it.copy(
                    message = CompanionPairing.rejectReason(normalized),
                    messageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            appLockManager.setCompanionUrl(normalized)
            _state.update {
                it.copy(message = "Companion address saved.", messageIsError = false)
            }
        }
    }

    fun setCompanionEnabled(enabled: Boolean) {
        if (!ensureWritable()) return
        if (enabled && !CompanionPairing.isTrustworthy(state.value.companionUrl)) {
            _state.update {
                it.copy(
                    message = CompanionPairing.rejectReason(state.value.companionUrl),
                    messageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            appLockManager.setCompanionEnabled(enabled)
            _state.update {
                it.copy(
                    message = if (enabled) {
                        "Companion portal enabled. Only encrypted envelopes are transferred."
                    } else {
                        "Companion portal disabled."
                    },
                    messageIsError = false
                )
            }
        }
    }

    fun signOutOfDrive() {
        if (!ensureWritable()) return
        viewModelScope.launch {
            cloudSyncManager.signOutOfDrive()
            _state.update {
                it.copy(
                    message = "Google Drive disconnected from this app.",
                    messageIsError = false
                )
            }
        }
    }

    fun wipeVault(masterPassword: String, deleteCloudBackups: Boolean = true) {
        if (!ensureWritable()) return
        if (masterPassword.isBlank()) {
            _state.update {
                it.copy(wipeError = "Enter your master password to erase the vault.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, wipeError = null) }
            val valid = withContext(Dispatchers.Default) {
                appLockManager.verifyMasterPassword(masterPassword)
            }
            if (!valid) {
                _state.update {
                    it.copy(working = false, wipeError = "Master password is incorrect.")
                }
                return@launch
            }
            val outcome = localWipeService.wipeAll(deleteCloud = deleteCloudBackups)
            _state.update {
                it.copy(
                    working = false,
                    wiped = outcome.localCleared,
                    wipeError = outcome.cloudError,
                    wipeArchiveName = outcome.archiveName
                )
            }
        }
    }

    fun setExportPassphrase(value: String) {
        _state.update { it.copy(exportPassphrase = value.take(128)) }
    }

    /**
     * Sealed with the password typed on this screen — never the master password —
     * so the file can be stored anywhere without exposing the vault key.
     */
    fun exportEncryptedBackup() {
        if (!ensureWritable()) return
        val current = _state.value
        if (current.exportBusy || current.exportData != null) return
        val passphrase = current.exportPassphrase.trim()
        if (passphrase.length < 8) {
            _state.update {
                it.copy(
                    message = "Use at least 8 characters for the export password.",
                    messageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(exportBusy = true, message = null, messageIsError = false) }
            cloudSyncManager.exportLocalBackup(passphrase).fold(
                onSuccess = { data ->
                    _state.update { it.copy(exportBusy = false, exportData = data) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            exportBusy = false,
                            message = error.message?.take(240) ?: "The export could not be created.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun onExportSaved() {
        _state.update {
            it.copy(
                exportData = null,
                exportPassphrase = "",
                message = "Encrypted export saved. Keep the password separate from the file.",
                messageIsError = false
            )
        }
    }

    fun onExportDiscarded(reason: String? = null) {
        _state.update {
            it.copy(
                exportData = null,
                message = reason ?: "Export cancelled. Nothing was written.",
                messageIsError = reason != null
            )
        }
    }

    fun setBloggerClientId(value: String) {
        _state.update { it.copy(bloggerClientId = value.trim().take(160)) }
    }

    fun exportBloggerTheme() {
        if (!ensureWritable()) return
        val current = _state.value
        if (current.bloggerBusy || current.bloggerExport != null) return
        val passphrase = current.exportPassphrase.trim()
        if (passphrase.length < 8) {
            _state.update {
                it.copy(
                    message = "Choose an export password (8+ characters). The web theme needs that same password.",
                    messageIsError = true
                )
            }
            return
        }
        val clientId = current.bloggerClientId.trim()
        viewModelScope.launch {
            _state.update { it.copy(bloggerBusy = true, message = null, messageIsError = false) }
            cloudSyncManager.exportLocalBackup(passphrase).fold(
                onSuccess = { data ->
                    val xml = runCatching {
                        withContext(Dispatchers.IO) { bloggerThemeExporter.build(data.bytes, clientId) }
                    }
                    xml.fold(
                        onSuccess = { content ->
                            val stamp = java.text.SimpleDateFormat(
                                "yyyyMMdd_HHmmss",
                                java.util.Locale.US
                            ).format(java.util.Date())
                            _state.update {
                                it.copy(
                                    bloggerBusy = false,
                                    bloggerExport = BloggerThemeFile(
                                        content = content,
                                        fileName = "SecureWallet_Blogger_$stamp.xml"
                                    )
                                )
                            }
                        },
                        onFailure = { error ->
                            _state.update {
                                it.copy(
                                    bloggerBusy = false,
                                    message = error.message?.take(200)
                                        ?: "The Blogger theme could not be generated.",
                                    messageIsError = true
                                )
                            }
                        }
                    )
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            bloggerBusy = false,
                            message = error.message?.take(240)
                                ?: "The sealed snapshot for the theme could not be created.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun onBloggerSaved() {
        _state.update {
            it.copy(
                bloggerExport = null,
                exportPassphrase = "",
                message = "Blogger XML saved. Upload it in Blogger → Theme → Edit HTML.",
                messageIsError = false
            )
        }
    }

    fun onBloggerDiscarded(reason: String? = null) {
        _state.update {
            it.copy(
                bloggerExport = null,
                message = reason ?: "Nothing was written.",
                messageIsError = reason != null
            )
        }
    }

    /** The portable .xml.enc archive — the third export, kept here so export lives in one place. */
    fun exportXmlArchive() {
        if (!ensureWritable()) return
        val current = _state.value
        if (current.xmlBusy || current.xmlExport != null) return
        val passphrase = current.exportPassphrase.trim()
        if (passphrase.length < 8) {
            _state.update {
                it.copy(
                    message = "Use at least 8 characters for the export password.",
                    messageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(xmlBusy = true, message = null, messageIsError = false) }
            cloudSyncManager.exportXmlBackup(passphrase).fold(
                onSuccess = { data -> _state.update { it.copy(xmlBusy = false, xmlExport = data) } },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            xmlBusy = false,
                            message = error.message?.take(240) ?: "The XML archive could not be created.",
                            messageIsError = true
                        )
                    }
                }
            )
        }
    }

    fun onXmlSaved() {
        _state.update {
            it.copy(
                xmlExport = null,
                exportPassphrase = "",
                message = "XML archive saved.",
                messageIsError = false
            )
        }
    }

    fun onXmlDiscarded(reason: String? = null) {
        _state.update {
            it.copy(
                xmlExport = null,
                message = reason ?: "Nothing was written.",
                messageIsError = reason != null
            )
        }
    }

    fun clearCredentialError() {
        _state.update { it.copy(credentialError = null) }
    }

    fun clearWipeError() {
        _state.update { it.copy(wipeError = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun ensureWritable(): Boolean {
        if (!sessionManager.isDecoy()) return true
        _state.update {
            it.copy(
                message = "Settings are read-only in a restricted session.",
                messageIsError = true
            )
        }
        return false
    }

    private fun credentialSuccess(message: String) {
        _state.update {
            it.copy(
                working = false,
                credentialVersion = it.credentialVersion + 1,
                credentialError = null,
                message = message,
                messageIsError = false
            )
        }
    }

    /**
     * The pairing QR is regenerated on demand so a screenshot of an old code cannot
     * be replayed later; rotating invalidates whatever the browser still holds.
     */
    fun pairingPayload(): String {
        val url = state.value.companionUrl.trim()
        val code = state.value.companionPairCode
        if (!CompanionPairing.isTrustworthy(url) || code.isBlank()) return ""
        return CompanionPairing.payload(url, code)
    }

    /** Opens the pairing sheet: issues a code on first use so the QR is never blank. */
    fun ensurePairCode() {
        if (state.value.companionPairCode.isNotBlank()) return
        viewModelScope.launch { appLockManager.setCompanionPairCode(CompanionPairing.newCode()) }
    }

    fun rotatePairCode() {
        if (!ensureWritable()) return
        val code = CompanionPairing.newCode()
        viewModelScope.launch {
            appLockManager.setCompanionPairCode(code)
            _state.update {
                it.copy(message = "New pairing code issued. Any previously shown code no longer pairs.", messageIsError = false)
            }
        }
    }

    /** Consumes a QR read from the browser mirror and adopts the address it carries. */
    fun applyPairing(raw: String) {
        if (!ensureWritable()) return
        val parsed = CompanionPairing.parse(raw)
        if (parsed == null) {
            _state.update {
                it.copy(
                    message = "That code is not a Secure Wallet pairing QR. Open the companion site and show its pairing code.",
                    messageIsError = true
                )
            }
            return
        }
        val (url, code) = parsed
        viewModelScope.launch {
            appLockManager.setCompanionUrl(url)
            if (code.isNotBlank()) appLockManager.setCompanionPairCode(code)
            _state.update {
                it.copy(
                    companionUrl = url,
                    companionPairCode = code.ifBlank { it.companionPairCode },
                    message = "Companion paired with $url",
                    messageIsError = false
                )
            }
        }
    }

    private fun credentialFailure(message: String) {
        _state.update {
            it.copy(
                working = false,
                credentialError = message,
                message = message,
                messageIsError = true
            )
        }
    }

}
