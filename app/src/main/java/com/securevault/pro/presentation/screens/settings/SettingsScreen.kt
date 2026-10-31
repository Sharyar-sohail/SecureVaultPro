package com.securevault.pro.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.BuildConfig
import com.securevault.pro.autofill.AutofillSettings
import com.securevault.pro.data.backup.BloggerThemeFile
import com.securevault.pro.data.remote.google.LocalExportData
import com.securevault.pro.data.remote.google.XmlExportData
import com.securevault.pro.presentation.components.BrandModuleIcon
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.PieLegendMarker
import com.securevault.pro.presentation.components.QrCodeImage
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultDivider
import com.securevault.pro.presentation.components.VaultModule
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.DockStyle
import com.securevault.pro.presentation.theme.HeaderStyle
import com.securevault.pro.presentation.theme.LayoutTuning
import com.securevault.pro.presentation.theme.NavIndicator
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPalettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onVaultWiped: () -> Unit,
    onOpenBackup: () -> Unit,
    onScanPairing: () -> Unit = {},
    pairingPayload: String? = null,
    onPairingConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autofillEnabled by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        autofillEnabled = AutofillSettings.isOwnServiceEnabled(context)
    }

    var showMasterDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showDecoyDialog by remember { mutableStateOf(false) }
    var showRemoveDecoyDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var showCompanionDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingOpenError by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }

    var exportPayload by remember { mutableStateOf<LocalExportData?>(null) }
    var bloggerPayload by remember { mutableStateOf<BloggerThemeFile?>(null) }
    var xmlPayload by remember { mutableStateOf<XmlExportData?>(null) }
    var exportConfirm by remember { mutableStateOf("") }

    val encryptedExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = exportPayload
        exportPayload = null
        if (uri == null || data == null) {
            viewModel.onExportDiscarded()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(data.bytes)
                        stream.flush()
                    } ?: throw IllegalStateException("Unable to open the selected file")
                }.isSuccess
            }
            if (written) viewModel.onExportSaved()
            else viewModel.onExportDiscarded("The encrypted export could not be written to that location.")
        }
    }

    val bloggerExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml")
    ) { uri ->
        val data = bloggerPayload
        bloggerPayload = null
        if (uri == null || data == null) {
            viewModel.onBloggerDiscarded()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(data.content.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    } ?: throw IllegalStateException("Unable to open the selected file")
                }.isSuccess
            }
            if (written) viewModel.onBloggerSaved()
            else viewModel.onBloggerDiscarded("The Blogger XML could not be written to that location.")
        }
    }

    LaunchedEffect(state.exportData) {
        val data = state.exportData ?: return@LaunchedEffect
        exportPayload = data
        showExportDialog = false
        encryptedExportLauncher.launch(data.fileName)
    }

    LaunchedEffect(state.bloggerExport) {
        val data = state.bloggerExport ?: return@LaunchedEffect
        bloggerPayload = data
        showExportDialog = false
        bloggerExportLauncher.launch(data.fileName)
    }

    val xmlExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = xmlPayload
        xmlPayload = null
        if (uri == null || data == null) {
            viewModel.onXmlDiscarded()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(data.bytes)
                        stream.flush()
                    } ?: throw IllegalStateException("Unable to open the selected file")
                }.isSuccess
            }
            if (written) viewModel.onXmlSaved()
            else viewModel.onXmlDiscarded("The XML archive could not be written to that location.")
        }
    }

    LaunchedEffect(state.xmlExport) {
        val data = state.xmlExport ?: return@LaunchedEffect
        xmlPayload = data
        showExportDialog = false
        xmlExportLauncher.launch(data.fileName)
    }

    LaunchedEffect(pairingPayload) {
        pairingPayload?.takeIf { it.isNotBlank() }?.let {
            viewModel.applyPairing(it)
            onPairingConsumed()
        }
    }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showInactivityDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showDeadManDialog by remember { mutableStateOf(false) }
    var showDeadManDisarmDialog by remember { mutableStateOf(false) }

    var masterCurrent by remember { mutableStateOf("") }
    var masterNew by remember { mutableStateOf("") }
    var masterConfirm by remember { mutableStateOf("") }

    var pinCurrent by remember { mutableStateOf("") }
    var pinNew by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }

    var decoyCurrent by remember { mutableStateOf("") }
    var decoyNew by remember { mutableStateOf("") }
    var decoyConfirm by remember { mutableStateOf("") }

    var removeDecoyMaster by remember { mutableStateOf("") }
    var wipeMaster by remember { mutableStateOf("") }
    var wipeDeleteCloud by remember { mutableStateOf(true) }
    var deadManMaster by remember { mutableStateOf("") }
    var deadManInterval by remember { mutableIntStateOf(state.deadManIntervalDays) }

    var companionDraft by remember { mutableStateOf("") }
    var companionError by remember { mutableStateOf<String?>(null) }

    var seenCredentialVersion by remember { mutableIntStateOf(state.credentialVersion) }
    LaunchedEffect(state.credentialVersion) {
        if (state.credentialVersion != seenCredentialVersion) {
            seenCredentialVersion = state.credentialVersion
            showMasterDialog = false
            showPinDialog = false
            showDecoyDialog = false
            showRemoveDecoyDialog = false
            masterCurrent = ""
            masterNew = ""
            masterConfirm = ""
            pinCurrent = ""
            pinNew = ""
            pinConfirm = ""
            decoyCurrent = ""
            decoyNew = ""
            decoyConfirm = ""
            removeDecoyMaster = ""
        }
    }

    LaunchedEffect(state.wiped) {
        if (state.wiped) onVaultWiped()
    }

    val autoLockOptions = remember {
        listOf(
            15 to "15 seconds",
            30 to "30 seconds",
            60 to "1 minute",
            120 to "2 minutes",
            300 to "5 minutes",
            900 to "15 minutes"
        )
    }
    val clipboardOptions = remember {
        listOf(
            30 to "30 seconds",
            45 to "45 seconds",
            60 to "1 minute"
        )
    }
    val inactivityOptions = remember {
        listOf(
            30 to "30 seconds",
            60 to "1 minute",
            120 to "2 minutes",
            300 to "5 minutes",
            900 to "15 minutes"
        )
    }
    val deadManOptions = remember {
        listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", 14 to "14 days", 30 to "30 days")
    }

    GlassScaffold(
        title = "Settings",
        subtitle = "Security, unlock & sync",
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = vaultGutter())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.decoy) {
                GlassCard(accent = VaultColor.warning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.security,
                            contentDescription = null,
                            tint = VaultColor.warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Restricted session · settings are read-only. Unlock your primary vault to make changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            val writable = !state.decoy && !state.working

            val activeTheme = VaultPalettes.find(state.themeId)
            SettingsSection(
                title = "Theme switcher",
                subtitle = "Fifteen themes · one board per theme, its own plate colours, glow and grain",
                accent = VaultColor.accent
            ) {
                SettingsActionRow(
                    icon = Glyph.palette,
                    accent = VaultColor.decorative,
                    title = "Themes",
                    subtitle = "${activeTheme.name} · ${activeTheme.tagline}",
                    onClick = { showThemes = true }
                )
            }

            SettingsSection(
                title = "Security",
                subtitle = "Device-bound protection",
                accent = VaultColor.accent
            ) {
                if (state.biometricAvailable) {
                    SettingsSwitchRow(
                        icon = Glyph.fingerprint,
                        accent = VaultColor.credit,
                        title = "Biometric unlock",
                        subtitle = "Face or fingerprint via BiometricPrompt — no PIN detour",
                        checked = state.biometricEnabled,
                        enabled = writable,
                        onChanged = viewModel::setBiometricEnabled
                    )
                    RowDivider()
                }
                SettingsSwitchRow(
                    icon = Glyph.visibilityOff,
                    accent = VaultColor.accent,
                    title = "Allow screenshots",
                    subtitle = "Off by default · FLAG_SECURE blocks screenshots and recent-app previews",
                    checked = !state.screenshotBlocked,
                    enabled = writable,
                    onChanged = viewModel::setAllowScreenshots
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Glyph.shuffle,
                    accent = VaultColor.secondary,
                    title = "Randomize PIN keypad",
                    subtitle = "Shuffles digits on every lock screen",
                    checked = state.randomizeKeypad,
                    enabled = writable,
                    onChanged = viewModel::setRandomizeKeypad
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Glyph.security,
                    accent = VaultColor.warning,
                    title = "Travel mode",
                    subtitle = "Hides items flagged as sensitive from lists and search",
                    checked = state.travelMode,
                    enabled = writable,
                    onChanged = viewModel::setTravelMode
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.timer,
                    accent = VaultColor.accent,
                    title = "Auto-lock",
                    subtitle = "Locks when the app leaves the foreground",
                    value = durationLabel(state.autoLockSeconds),
                    enabled = writable,
                    onClick = {
                        viewModel.clearMessage()
                        showAutoLockDialog = true
                    }
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.timer,
                    accent = VaultColor.warning,
                    title = "Inactivity lock",
                    subtitle = "Locks after this long without any interaction",
                    value = durationLabel(state.inactivityLockSeconds),
                    enabled = writable,
                    onClick = {
                        viewModel.clearMessage()
                        showInactivityDialog = true
                    }
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.contentPaste,
                    accent = VaultColor.credit,
                    title = "Clear clipboard",
                    subtitle = "Wipes copied secrets automatically",
                    value = durationLabel(state.clipboardSeconds),
                    enabled = writable,
                    onClick = {
                        viewModel.clearMessage()
                        showClipboardDialog = true
                    }
                )
            }

            SettingsSection(
                title = "Dead man's switch",
                subtitle = "Archives to Drive first · then erases this device only",
                accent = VaultColor.debit
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.warning,
                        contentDescription = null,
                        tint = VaultColor.debit,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.deadManArmed) {
                            "Armed · ${state.deadManDaysLeft} day(s) until this device is archived to Drive and erased."
                        } else {
                            "Disarmed. No automatic erase will run."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.deadManArmed) VaultColor.warning else VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (state.deadManArmed) {
                    SettingsActionRow(
                        icon = Glyph.checkCircle,
                        accent = VaultColor.credit,
                        title = "Check in now",
                        subtitle = "Restarts the countdown · every unlock also counts as a check-in",
                        enabled = writable,
                        onClick = viewModel::checkInDeadManSwitch
                    )
                    RowDivider()
                    SettingsActionRow(
                        icon = Glyph.warning,
                        accent = VaultColor.debit,
                        title = "Disarm",
                        subtitle = "Requires your master password",
                        enabled = writable,
                        destructive = true,
                        onClick = {
                            deadManMaster = ""
                            showDeadManDisarmDialog = true
                        }
                    )
                } else {
                    SettingsActionRow(
                        icon = Glyph.security,
                        accent = VaultColor.debit,
                        title = "Arm dead man's switch",
                        subtitle = "Past the interval, this device is archived to Drive and then erased",
                        value = "${state.deadManIntervalDays}d",
                        enabled = writable,
                        onClick = {
                            deadManMaster = ""
                            deadManInterval = state.deadManIntervalDays
                            showDeadManDialog = true
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "On the first launch after the deadline the vault is uploaded to a visible Google Drive ZIP, and only then is this device erased (vault tables, attachments, offline backups and the Keystore key). Google Drive must be connected; if the archive fails nothing is erased. Encrypted Drive envelopes are never deleted by this switch.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    lineHeight = 15.sp
                )
            }

            SettingsSection(
                title = "Autofill",
                subtitle = "Fill saved logins in other apps and browsers",
                accent = VaultColor.warning
            ) {
                SettingsActionRow(
                    icon = Glyph.autoFixHigh,
                    accent = VaultColor.warning,
                    title = "Android Autofill service",
                    subtitle = if (autofillEnabled) {
                        "Connected · suggestions appear in apps and browsers"
                    } else {
                        "Not enabled · tap to choose Secure Wallet Pro"
                    },
                    value = if (autofillEnabled) "ON" else "OFF",
                    enabled = !state.decoy,
                    onClick = { AutofillSettings.open(context) }
                )
                if (!autofillEnabled) {
                    RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.info,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Choose Secure Wallet Pro in Android settings. Your vault unlocks inline when a login is requested.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textFaint,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            SettingsSection(
                title = "Unlock credentials",
                subtitle = "Hashed locally · never transmitted",
                accent = VaultColor.credit
            ) {
                SettingsActionRow(
                    icon = Glyph.lock,
                    accent = VaultColor.credit,
                    title = "Change master password",
                    subtitle = "Authorised by your current master password",
                    enabled = writable,
                    onClick = {
                        viewModel.clearCredentialError()
                        masterCurrent = ""
                        masterNew = ""
                        masterConfirm = ""
                        showMasterDialog = true
                    }
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.pin,
                    accent = VaultColor.accent,
                    title = "Change daily PIN",
                    subtitle = "Currently ${state.pinLength} digits",
                    enabled = writable,
                    onClick = {
                        viewModel.clearCredentialError()
                        pinCurrent = ""
                        pinNew = ""
                        pinConfirm = ""
                        showPinDialog = true
                    }
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.visibilityOff,
                    accent = VaultColor.decorative,
                    title = if (state.decoyEnabled) "Change decoy PIN" else "Set decoy PIN",
                    subtitle = "Opens an isolated empty vault that never reaches real data",
                    enabled = writable,
                    onClick = {
                        viewModel.clearCredentialError()
                        decoyCurrent = ""
                        decoyNew = ""
                        decoyConfirm = ""
                        showDecoyDialog = true
                    }
                )
                if (state.decoyEnabled) {
                    RowDivider()
                    SettingsActionRow(
                        icon = Glyph.delete,
                        accent = VaultColor.debit,
                        title = "Remove decoy PIN",
                        subtitle = "The restricted vault becomes unreachable",
                        enabled = writable,
                        destructive = true,
                        onClick = {
                            viewModel.clearCredentialError()
                            removeDecoyMaster = ""
                            showRemoveDecoyDialog = true
                        }
                    )
                }
            }

            SettingsSection(
                title = "Google & backup",
                subtitle = "One account · import above, export below · only sealed envelopes leave this device",
                accent = VaultColor.secondary
            ) {
                SettingsActionRow(
                    icon = Glyph.cloud,
                    accent = VaultColor.secondary,
                    title = "Google account, snapshots & import",
                    subtitle = if (state.cloudEmail.isBlank()) {
                        "Not connected · connect, restore a Drive snapshot or import a file"
                    } else {
                        "${state.cloudEmail} · restore or import here"
                    },
                    enabled = !state.decoy,
                    onClick = onOpenBackup
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.fileDownload,
                    accent = VaultColor.accent,
                    title = "Export encrypted backup",
                    subtitle = "Encrypted file · Blogger theme XML · .xml.enc archive",
                    enabled = writable,
                    onClick = {
                        viewModel.clearMessage()
                        showExportDialog = true
                    }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Glyph.sync,
                    accent = VaultColor.credit,
                    title = "Companion portal",
                    subtitle = if (state.companionEnabled) "Enabled" else "Disabled by default",
                    checked = state.companionEnabled,
                    enabled = writable,
                    onChanged = viewModel::setCompanionEnabled
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.cloud,
                    accent = VaultColor.credit,
                    title = "Companion address",
                    subtitle = state.companionUrl,
                    enabled = writable,
                    onClick = {
                        companionDraft = state.companionUrl
                        companionError = null
                        viewModel.clearMessage()
                        showCompanionDialog = true
                    }
                )
                RowDivider()
                SettingsActionRow(
                    icon = Glyph.qrCode,
                    accent = VaultColor.credit,
                    title = "Pair browser mirror",
                    subtitle = "Show a QR to the site, or scan the code it displays",
                    enabled = writable,
                    onClick = {
                        viewModel.ensurePairCode()
                        companionError = null
                        showPairingDialog = true
                    }
                )
                if (state.cloudEmail.isNotBlank()) {
                    RowDivider()
                    SettingsActionRow(
                        icon = Glyph.cloud,
                        accent = VaultColor.debit,
                        title = "Disconnect Google Drive",
                        subtitle = "Snapshots remain encrypted in Drive",
                        enabled = writable,
                        destructive = true,
                        onClick = viewModel::signOutOfDrive
                    )
                }
            }

            SettingsSection(
                title = "Realtime sync",
                subtitle = "Per-record encrypted deltas to the web companion",
                accent = VaultColor.credit
            ) {
                val realtimeAccent = when {
                    state.realtimeError != null -> VaultColor.debit
                    state.realtimeNeedsKey -> VaultColor.warning
                    state.realtimeListening -> VaultColor.credit
                    else -> VaultColor.textFaint
                }
                GlassCard(accent = realtimeAccent, borderColor = realtimeAccent.copy(alpha = 0.3f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = when {
                                state.realtimeError != null -> Glyph.warning
                                state.realtimeNeedsKey -> Glyph.lock
                                state.realtimeListening -> Glyph.cloud
                                else -> Glyph.sync
                            },
                            contentDescription = null,
                            tint = realtimeAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    state.realtimeNeedsKey -> "Connect this device once"
                                    state.realtimeListening -> "Realtime engine listening"
                                    else -> "Realtime engine idle"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = state.realtimeUid?.let { "Account ${it.takeLast(8)}" }
                                    ?: "No Google session resolved yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
                        }
                        TagChip(
                            text = when {
                                state.realtimeNeedsKey -> "KEY NEEDED"
                                state.realtimeListening -> "LIVE"
                                else -> "IDLE"
                            },
                            accent = realtimeAccent
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        RealtimeMetric(
                            label = "PUSH LATENCY",
                            value = state.realtimeLatencyMs?.let { "${it} ms" } ?: "—",
                            accent = VaultColor.credit,
                            modifier = Modifier.weight(1f)
                        )
                        RealtimeMetric(
                            label = "PENDING",
                            value = state.realtimePending.toString(),
                            accent = VaultColor.warning,
                            modifier = Modifier.weight(1f)
                        )
                        RealtimeMetric(
                            label = "LISTENERS",
                            value = if (state.realtimeListening) "ON" else "OFF",
                            accent = VaultColor.accent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // The verbatim Result.failure sentence when the keyring cannot open — the only signal
                // a user gets, shown whole, never truncated to a code.
                state.realtimeError?.let { error ->
                    RowDivider()
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.debit,
                        lineHeight = 18.sp
                    )
                }
                // A Google session with no device key must attach the keyring before the first write.
                if (state.realtimeNeedsKey || (state.cloudEmail.isNotBlank() && state.realtimeError != null)) {
                    RowDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Enter your passphrase to fetch this account's vault key. " +
                                "This is a one-time connection — a stalled network will time out, " +
                                "and you can always leave this screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                        VaultTextField(
                            value = state.realtimePassphrase,
                            onValueChange = viewModel::setRealtimePassphrase,
                            label = "Vault passphrase",
                            isPassword = true,
                            leadingIcon = Glyph.lock
                        )
                        GradientButton(
                            text = if (state.realtimeAttaching) "Connecting…" else "Connect this device",
                            icon = Glyph.refresh,
                            enabled = !state.realtimeAttaching && state.realtimePassphrase.isNotBlank(),
                            loading = state.realtimeAttaching,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = viewModel::attachRealtimeKeyring
                        )
                    }
                }
            }

            SettingsSection(
                title = "About",
                subtitle = "Release information",
                accent = VaultColor.secondary
            ) {
                AboutRow("Application", "Secure Wallet Pro")
                AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                AboutRow("Application ID", "com.securevault.app")
                AboutRow("Namespace", "com.securevault.pro")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip("AES-256-GCM")
                    TagChip("PBKDF2 600k", accent = VaultColor.credit)
                    TagChip("Keystore", accent = VaultColor.decorative)
                }
            }

            GlassCard(
                borderColor = VaultColor.debit.copy(alpha = 0.32f),
                accent = VaultColor.debit
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(VaultShapes.input)
                            .background(VaultColor.debit.copy(alpha = 0.11f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedIcon(
                            glyph = Glyph.warning,
                            contentDescription = null,
                            tint = VaultColor.debit,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Danger zone",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = "Irreversible · master password required",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Erasing destroys every vault table (including crypto wallets), encrypted attachment, offline backup and the Android Keystore key on this device. With the cloud option ticked, the encrypted Drive snapshots and the live sync copies the web companion reads are deleted too, after a copy of your vault is archived to Google Drive. Untick it only if you want to keep them. The app returns to first-run setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    text = "Erase all data",
                    icon = Glyph.delete,
                    accent = VaultColor.debit,
                    enabled = writable,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.clearWipeError()
                        wipeMaster = ""
                        wipeDeleteCloud = true
                        showWipeDialog = true
                    }
                )
            }

            state.message?.let { message ->
                val accent = if (state.messageIsError) VaultColor.warning else VaultColor.credit
                GlassCard(borderColor = accent.copy(alpha = 0.3f), accent = accent) {
                    Row(verticalAlignment = Alignment.Top) {
                        ThemedIcon(
                            glyph = if (state.messageIsError) Glyph.errorOutline
                            else Glyph.checkCircle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textStrong,
                            modifier = Modifier.weight(1f),
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "DISMISS",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            modifier = Modifier
                                .clip(VaultShapes.chip)
                                .clickable { viewModel.clearMessage() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = "Five failed unlocks trigger a 30-second lockout. The master password cannot be recovered by anyone, including the developer.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint.copy(alpha = 0.8f),
                lineHeight = 15.sp
            )
        }
    }

    if (showMasterDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.working) showMasterDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Change master password", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Your current master password authorises this change. Vault records stay sealed with the device key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = masterCurrent,
                        onValueChange = { masterCurrent = it },
                        label = "Current master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                    VaultTextField(
                        value = masterNew,
                        onValueChange = { masterNew = it },
                        label = "New master password",
                        leadingIcon = Glyph.security,
                        isPassword = true,
                        enabled = !state.working,
                        supportingText = "12+ characters and a symbol are recommended"
                    )
                    VaultTextField(
                        value = masterConfirm,
                        onValueChange = { masterConfirm = it },
                        label = "Confirm new master password",
                        leadingIcon = Glyph.checkCircle,
                        isPassword = true,
                        enabled = !state.working,
                        isError = masterConfirm.isNotEmpty() && masterConfirm != masterNew,
                        onImeSubmit = {
                            if (masterCurrent.isNotBlank() && masterNew.isNotBlank() &&
                                masterConfirm.isNotBlank() && !state.working
                            ) {
                                viewModel.changeMasterPassword(masterCurrent, masterNew, masterConfirm)
                            }
                        }
                    )
                    state.credentialError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = VaultColor.debit)
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Update",
                    working = state.working,
                    enabled = masterCurrent.isNotBlank() && masterNew.isNotBlank() &&
                        masterConfirm.isNotBlank() && !state.working,
                    onClick = {
                        viewModel.changeMasterPassword(masterCurrent, masterNew, masterConfirm)
                    }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showMasterDialog = false },
                    enabled = !state.working
                ) { Text("Cancel", color = VaultColor.textBody) }
            }
        )
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.working) showPinDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Change daily PIN", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "The PIN is stretched locally and unlocks the primary vault. Your master password still works as a fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = pinCurrent,
                        onValueChange = { pinCurrent = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                    VaultTextField(
                        value = pinNew,
                        onValueChange = { pinNew = it.filter(Char::isDigit).take(8) },
                        label = "New PIN (4–8 digits)",
                        leadingIcon = Glyph.pin,
                        isPassword = true,
                        keyboardType = KeyboardType.NumberPassword,
                        enabled = !state.working
                    )
                    VaultTextField(
                        value = pinConfirm,
                        onValueChange = { pinConfirm = it.filter(Char::isDigit).take(8) },
                        label = "Confirm new PIN",
                        leadingIcon = Glyph.pin,
                        isPassword = true,
                        keyboardType = KeyboardType.NumberPassword,
                        enabled = !state.working,
                        isError = pinConfirm.isNotEmpty() && pinConfirm != pinNew,
                        onImeSubmit = {
                            if (pinCurrent.isNotBlank() && pinNew.isNotBlank() &&
                                pinConfirm.isNotBlank() && !state.working
                            ) {
                                viewModel.changePin(pinCurrent, pinNew, pinConfirm)
                            }
                        }
                    )
                    state.credentialError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = VaultColor.debit)
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Update",
                    working = state.working,
                    enabled = pinCurrent.isNotBlank() && pinNew.isNotBlank() &&
                        pinConfirm.isNotBlank() && !state.working,
                    onClick = { viewModel.changePin(pinCurrent, pinNew, pinConfirm) }
                )
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }, enabled = !state.working) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showDecoyDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.working) showDecoyDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = {
                Text(
                    text = if (state.decoyEnabled) "Change decoy PIN" else "Set decoy PIN",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "The decoy PIN opens a separate, completely empty restricted vault. It never mounts real ciphertext and blocks every write, cloud action and export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = decoyCurrent,
                        onValueChange = { decoyCurrent = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                    VaultTextField(
                        value = decoyNew,
                        onValueChange = { decoyNew = it.filter(Char::isDigit).take(8) },
                        label = "New decoy PIN (4–8 digits)",
                        leadingIcon = Glyph.visibilityOff,
                        isPassword = true,
                        keyboardType = KeyboardType.NumberPassword,
                        enabled = !state.working,
                        supportingText = "Must differ from your real PIN and master password"
                    )
                    VaultTextField(
                        value = decoyConfirm,
                        onValueChange = { decoyConfirm = it.filter(Char::isDigit).take(8) },
                        label = "Confirm decoy PIN",
                        leadingIcon = Glyph.visibilityOff,
                        isPassword = true,
                        keyboardType = KeyboardType.NumberPassword,
                        enabled = !state.working,
                        isError = decoyConfirm.isNotEmpty() && decoyConfirm != decoyNew,
                        onImeSubmit = {
                            if (decoyCurrent.isNotBlank() && decoyNew.isNotBlank() &&
                                decoyConfirm.isNotBlank() && !state.working
                            ) {
                                viewModel.setDecoyPin(decoyCurrent, decoyNew, decoyConfirm)
                            }
                        }
                    )
                    state.credentialError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = VaultColor.debit)
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Save",
                    working = state.working,
                    enabled = decoyCurrent.isNotBlank() && decoyNew.isNotBlank() &&
                        decoyConfirm.isNotBlank() && !state.working,
                    onClick = { viewModel.setDecoyPin(decoyCurrent, decoyNew, decoyConfirm) }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDecoyDialog = false }, enabled = !state.working) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showRemoveDecoyDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.working) showRemoveDecoyDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Remove decoy PIN", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "The restricted vault stays sealed and unreachable after removal. Your real PIN keeps working.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = removeDecoyMaster,
                        onValueChange = { removeDecoyMaster = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working,
                        onImeSubmit = {
                            if (removeDecoyMaster.isNotBlank() && !state.working) {
                                viewModel.removeDecoyPin(removeDecoyMaster)
                            }
                        }
                    )
                    state.credentialError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = VaultColor.debit)
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Remove",
                    working = state.working,
                    enabled = removeDecoyMaster.isNotBlank() && !state.working,
                    onClick = { viewModel.removeDecoyPin(removeDecoyMaster) }
                )
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDecoyDialog = false }, enabled = !state.working) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.working) showWipeDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.warning,
                        contentDescription = null,
                        tint = VaultColor.debit,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Erase everything?", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "This permanently erases every local record, encrypted attachment, offline backup and the device key. Before the active Drive slot is cleared, your old vault is sealed into a timestamped ZIP inside Drive → .SecureVaultPro-Archives. After the wipe this app restores nothing, but that archive stays until you delete it by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(VaultShapes.input)
                            .clickable(enabled = !state.working) {
                                wipeDeleteCloud = !wipeDeleteCloud
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = wipeDeleteCloud,
                            onCheckedChange = { wipeDeleteCloud = it },
                            enabled = !state.working,
                            colors = CheckboxDefaults.colors(
                                checkedColor = VaultColor.debit,
                                uncheckedColor = VaultColor.textFaint,
                                checkmarkColor = VaultColor.onAccent
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Also clear the active Google Drive slot",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Archives first, then removes the primary envelope, XML mirror and snapshots",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    VaultTextField(
                        value = wipeMaster,
                        onValueChange = { wipeMaster = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                    state.wipeError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = VaultColor.debit)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.wipeVault(wipeMaster, wipeDeleteCloud) },
                    enabled = wipeMaster.isNotBlank() && !state.working
                ) {
                    if (state.working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = VaultColor.debit
                        )
                    } else {
                        Text("Erase", color = VaultColor.debit, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWipeDialog = false
                        wipeMaster = ""
                    },
                    enabled = !state.working
                ) { Text("Cancel", color = VaultColor.textBody) }
            }
        )
    }

    if (showExportDialog) {
        val passphrase = state.exportPassphrase
        val ready = passphrase.trim().length >= 8 && exportConfirm == passphrase.trim()
        val busy = state.exportBusy || state.bloggerBusy || state.xmlBusy
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                viewModel.setExportPassphrase("")
                exportConfirm = ""
            },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Encrypted export", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Sealed with the password you type here — never your master password. Keep the two apart; without this password the file cannot be opened again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = passphrase,
                        onValueChange = viewModel::setExportPassphrase,
                        label = "Export password",
                        isPassword = true,
                        leadingIcon = Glyph.lock
                    )
                    VaultTextField(
                        value = exportConfirm,
                        onValueChange = { exportConfirm = it },
                        label = "Repeat export password",
                        isPassword = true,
                        leadingIcon = Glyph.check,
                        supportingText = when {
                            passphrase.trim().length < 8 -> "Use at least 8 characters"
                            exportConfirm.isBlank() -> "Repeat the same password"
                            !ready -> "The passwords do not match"
                            else -> "Ready to export"
                        }
                    )
                    state.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.messageIsError) VaultColor.debit else VaultColor.credit
                        )
                    }
                    GradientButton(
                        text = "Export encrypted file",
                        icon = Glyph.fileDownload,
                        enabled = ready && !busy,
                        loading = state.exportBusy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::exportEncryptedBackup
                    )
                    VaultTextField(
                        value = state.bloggerClientId,
                        onValueChange = viewModel::setBloggerClientId,
                        label = "Google Web Client ID for the theme (optional)",
                        placeholder = "1234-abc.apps.googleusercontent.com",
                        singleLine = true,
                        supportingText = "Only the Blogger theme uses this. Blank keeps the app project's client ID, and the blog can override it on the page."
                    )
                    GhostButton(
                        text = "Export Blogger theme XML",
                        icon = Glyph.description,
                        accent = VaultColor.credit,
                        enabled = ready && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::exportBloggerTheme
                    )
                    GhostButton(
                        text = "Export XML archive (.xml.enc)",
                        icon = Glyph.folderOpen,
                        accent = VaultColor.warning,
                        enabled = ready && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::exportXmlArchive
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        viewModel.setExportPassphrase("")
                        exportConfirm = ""
                    }
                ) { Text("Close", color = VaultColor.textBody) }
            }
        )
    }

    if (showCompanionDialog) {
        AlertDialog(
            onDismissRequest = { showCompanionDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Companion address", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "The browser mirror receives the same end-to-end encrypted envelope that is uploaded to Drive. Remote hosts must use https://; plain http is refused because Android and browsers block it, which is what produced the old certificate error.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = companionDraft,
                        onValueChange = {
                            companionDraft = it
                            companionError = null
                        },
                        label = "https://host",
                        leadingIcon = Glyph.cloud,
                        keyboardType = KeyboardType.Uri,
                        isError = companionError != null,
                        supportingText = companionError
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalized = companionDraft.trim().trimEnd('/')
                        if (!CompanionPairing.isTrustworthy(normalized)) {
                            companionError = CompanionPairing.rejectReason(normalized)
                        } else {
                            viewModel.updateCompanionUrl(normalized)
                            showCompanionDialog = false
                        }
                    }
                ) { Text("Save", color = VaultColor.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCompanionDialog = false }) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showPairingDialog) {
        LaunchedEffect(Unit) { pairingOpenError = null }
        val payload = viewModel.pairingPayload()
        AlertDialog(
            onDismissRequest = { showPairingDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Pair the browser mirror", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan this code with any camera or browser: it is a plain link to your companion site with the six characters already in it, so the site opens already paired. Or use “Scan site QR” below to read the code off the site, or type these characters into its “Pair with the app” panel. The code only links the two screens; it is not a key, and nothing decrypts without the export password you chose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    if (payload.isBlank()) {
                        Text(
                            text = "No companion address saved yet. Scanning the site's QR saves it for you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColor.warning,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        QrCodeImage(value = payload, contentDescription = "Pairing QR code", size = 200.dp)
                        Text(
                            text = state.companionPairCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = VaultColor.credit,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp
                        )
                        Text(
                            text = state.companionUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        GhostButton(
                            text = "Rotate code",
                            icon = Glyph.refresh,
                            accent = VaultColor.accent,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = viewModel::rotatePairCode
                        )
                        GhostButton(
                            text = "Open the site now",
                            icon = Glyph.openInNew,
                            accent = VaultColor.credit,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                pairingOpenError = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(payload))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.exceptionOrNull()?.let {
                                    "No browser on this device accepted the link. " +
                                        "Open the site yourself and type the code into its pairing panel."
                                }
                            }
                        )
                        pairingOpenError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.warning,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPairingDialog = false }) {
                    Text("Done", color = VaultColor.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPairingDialog = false
                        onScanPairing()
                    }
                ) { Text("Scan site QR", color = VaultColor.credit) }
            }
        )
    }

    if (showDeadManDialog) {
        LaunchedEffect(state.deadManArmed) {
            if (state.deadManArmed) showDeadManDialog = false
        }
        AlertDialog(
            onDismissRequest = { if (!state.working) showDeadManDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.warning,
                        contentDescription = null,
                        tint = VaultColor.debit,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Arm dead man's switch", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "If this app is not unlocked within the interval, the next launch first uploads your vault to a visible Google Drive ZIP, then erases this device only. Drive must be connected; if the archive fails, nothing is erased.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    deadManOptions.forEach { (days, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .clickable { deadManInterval = days }
                                .padding(horizontal = 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (days == deadManInterval) VaultColor.accent else VaultColor.textStrong,
                                modifier = Modifier.weight(1f)
                            )
                            if (days == deadManInterval) {
                                ThemedIcon(
                                    glyph = Glyph.check,
                                    contentDescription = null,
                                    tint = VaultColor.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    VaultTextField(
                        value = deadManMaster,
                        onValueChange = { deadManMaster = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Arm",
                    working = state.working,
                    enabled = deadManMaster.isNotBlank() && !state.working,
                    onClick = { viewModel.armDeadManSwitch(deadManMaster, deadManInterval) }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeadManDialog = false }, enabled = !state.working) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showDeadManDisarmDialog) {
        LaunchedEffect(state.deadManArmed) {
            if (!state.deadManArmed) showDeadManDisarmDialog = false
        }
        AlertDialog(
            onDismissRequest = { if (!state.working) showDeadManDisarmDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = { Text("Disarm dead man's switch", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "No automatic wipe will run after you disarm. Your master password authorises this change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 18.sp
                    )
                    VaultTextField(
                        value = deadManMaster,
                        onValueChange = { deadManMaster = it },
                        label = "Master password",
                        leadingIcon = Glyph.lock,
                        isPassword = true,
                        enabled = !state.working
                    )
                }
            },
            confirmButton = {
                DialogConfirmButton(
                    label = "Disarm",
                    working = state.working,
                    enabled = deadManMaster.isNotBlank() && !state.working,
                    onClick = { viewModel.disarmDeadManSwitch(deadManMaster) }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeadManDisarmDialog = false }, enabled = !state.working) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    if (showAutoLockDialog) {
        ChoiceDialog(
            title = "Auto-lock",
            message = "The vault locks itself after this much time in the background.",
            options = autoLockOptions,
            selected = state.autoLockSeconds,
            onSelect = {
                viewModel.setAutoLockSeconds(it)
                showAutoLockDialog = false
            },
            onDismiss = { showAutoLockDialog = false }
        )
    }

    if (showInactivityDialog) {
        ChoiceDialog(
            title = "Inactivity lock",
            message = "The vault locks itself after this much time without a tap, scroll or keystroke while you are in the app.",
            options = inactivityOptions,
            selected = state.inactivityLockSeconds,
            onSelect = {
                viewModel.setInactivityLockSeconds(it)
                showInactivityDialog = false
            },
            onDismiss = { showInactivityDialog = false }
        )
    }

    if (showClipboardDialog) {
        ChoiceDialog(
            title = "Clear clipboard",
            message = "Copied secrets are wiped from the clipboard after this delay.",
            options = clipboardOptions,
            selected = state.clipboardSeconds,
            onSelect = {
                viewModel.setClipboardSeconds(it)
                showClipboardDialog = false
            },
            onDismiss = { showClipboardDialog = false }
        )
    }

    if (showThemes) {
        ThemesSheet(
            activeId = state.themeId,
            tuning = state.themeTuning.tuning,
            onSelect = viewModel::setTheme,
            onTuningChange = viewModel::setLayoutTuning,
            onReset = viewModel::resetLayoutTuning,
            onDismiss = { showThemes = false }
        )
    }
}

/**
 * The theme drawer. One row in Settings opens it, so the fifteen boards no longer push the rest of
 * the screen below the fold; the grid stays two per row, and the layout dials under it tune the
 * board of whichever theme is selected, saved per theme id the moment they move.
 *
 * The sheet stays open on selection: picking a theme is a preview, not a commit. [draft] is seeded
 * from the saved tuning and only re-adopted when the incoming value did not come from this sheet,
 * so a theme switch reseeds the sliders while a slider's own echo never snaps the thumb back.
 */
@Composable
private fun ThemesSheet(
    activeId: String,
    tuning: LayoutTuning,
    onSelect: (String) -> Unit,
    onTuningChange: (LayoutTuning) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = VaultPalettes.find(activeId)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draft by remember { mutableStateOf(tuning) }
    var sent by remember { mutableStateOf<LayoutTuning?>(null) }
    LaunchedEffect(tuning) {
        if (tuning != sent) {
            draft = tuning
            sent = null
        }
    }

    fun apply(next: LayoutTuning) {
        draft = next
        sent = next
        onTuningChange(next)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        shape = palette.cardShape,
        dragHandle = { BottomSheetDefaults.DragHandle(color = palette.outlineColor) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Themes",
                        style = MaterialTheme.typography.headlineSmall,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = "${palette.name} · ${palette.tagline}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        lineHeight = 17.sp
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Done",
                        color = VaultColor.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A grid of eight rows plus the dials is taller than any sheet; the body
                    // scrolls inside a bounded height so "Done" never leaves the screen.
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                VaultPalettes.all.chunked(2).forEach { rowThemes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            ThemePreviewTile(
                                palette = theme,
                                selected = activeId == theme.id,
                                modifier = Modifier.weight(1f),
                                onSelect = { onSelect(theme.id) }
                            )
                        }
                        if (rowThemes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Board layout",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "Saved for ${palette.name} only · the board behind this sheet updates as you move",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Columns",
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textStrong
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LayoutTuning.COLUMN_CHOICES.forEach { choice ->
                        TuningChip(
                            label = choice?.toString() ?: "Theme default",
                            selected = draft.columns == choice,
                            modifier = Modifier.weight(1f),
                            onClick = { apply(draft.copy(columns = choice)) }
                        )
                    }
                }
                TuningSlider(
                    label = "Tile size",
                    value = draft.tileScale,
                    range = 0.85f..1.25f,
                    steps = 7,
                    onValueChange = { apply(draft.copy(tileScale = it)) }
                )
                TuningSlider(
                    label = "Icon scale",
                    value = draft.iconScale,
                    range = 0.80f..1.40f,
                    steps = 11,
                    onValueChange = { apply(draft.copy(iconScale = it)) }
                )
                TuningSlider(
                    label = "Padding & spacing",
                    value = draft.spacingScale,
                    range = 0.60f..1.60f,
                    steps = 19,
                    onValueChange = { apply(draft.copy(spacingScale = it)) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (draft.isDefault) {
                            "Using the theme's own board"
                        } else {
                            "Custom board for ${palette.name}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            draft = LayoutTuning.DEFAULT
                            sent = LayoutTuning.DEFAULT
                            onReset()
                        },
                        enabled = !draft.isDefault
                    ) {
                        Text(
                            text = "Reset layout",
                            color = if (draft.isDefault) VaultColor.textFaint else VaultColor.warning,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = "Every screen, card, button, icon set and the navigation bar re-skin immediately — no restart required.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** One segmented choice in the theme sheet. */
@Composable
private fun TuningChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(VaultRadius.sm)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) VaultColor.accent.copy(alpha = 0.18f) else VaultColor.elevated)
            .border(1.dp, if (selected) VaultColor.accent else VaultColor.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) VaultColor.textStrong else VaultColor.textBody,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** One 0.05-stepped multiplier dial. The value snaps on the way in, never after it is saved. */
@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = VaultColor.textStrong,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format(java.util.Locale.US, "%.2f×", value),
                style = MaterialTheme.typography.labelMedium,
                color = VaultColor.accent
            )
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(LayoutTuning.snap(it, range.start, range.endInclusive)) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = VaultColor.accent,
                activeTrackColor = VaultColor.accent,
                inactiveTrackColor = VaultColor.elevated
            )
        )
    }
}

@Composable
private fun ThemePreviewTile(
    palette: VaultPalette,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    val corner = palette.cardCorner.coerceAtMost(20.dp)
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(palette.abyssSurface, palette.abyssElevated)
                )
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) palette.cyan else palette.outlineColor,
                shape = shape
            )
            .clickable(onClick = onSelect)
            .padding(9.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(corner.coerceAtMost(12.dp)))
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.abyssDark,
                            palette.abyssElevated,
                            palette.cyan.copy(alpha = 0.18f)
                        )
                    )
                )
        ) {
            // Header geometry: where the title sits and how heavy it is.
            val headerWidth = when (palette.header) {
                HeaderStyle.LARGE_COMFORT -> 0.74f
                HeaderStyle.TAB_UPPER -> 0.5f
                HeaderStyle.CENTERED -> 0.38f
                HeaderStyle.INLINE_BOLD -> 0.52f
            }
            val headerAlign = when (palette.header) {
                HeaderStyle.CENTERED -> Alignment.TopCenter
                else -> Alignment.TopStart
            }
            Box(
                Modifier
                    .align(headerAlign)
                    .padding(horizontal = 7.dp, vertical = if (palette.header == HeaderStyle.LARGE_COMFORT) 9.dp else 7.dp)
                    .fillMaxWidth(headerWidth)
                    .height(if (palette.header == HeaderStyle.LARGE_COMFORT) 9.dp else 7.dp)
                    .clip(RoundedCornerShape(if (palette.hardOutline) 0.dp else 4.dp))
                    .background(palette.textPrimary.copy(alpha = 0.22f))
            )
            // The icon board, four across, in this theme's plate colours.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    VaultModule.PASSWORDS,
                    VaultModule.TWO_FA,
                    VaultModule.CARDS,
                    VaultModule.CRYPTO
                ).forEach { module ->
                    BrandModuleIcon(
                        module = module,
                        themeId = palette.id,
                        size = 13.dp,
                        contentDescription = null
                    )
                }
            }
            // Chart signature: the key this brand cuts its composition pie with.
            PieLegendMarker(
                color = palette.cyan,
                legend = palette.pieLegend,
                size = 8.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 7.dp)
            )
            // Dock geometry: silhouette, float and indicator style.
            MiniDockBar(
                palette = palette,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = if (palette.dock == DockStyle.PILL_FROSTED) 9.dp else 6.dp)
                    .padding(bottom = if (palette.dock == DockStyle.FLAT_PLATE || palette.dock == DockStyle.MINIMAL_BAR) 0.dp else 5.dp)
            )
            if (selected) {
                ThemedIcon(
                    glyph = Glyph.checkCircle,
                    contentDescription = null,
                    tint = palette.cyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(14.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = palette.name,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = palette.inspiredBy.uppercase(),
            style = VaultType.micro(),
            color = palette.cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = palette.tagline,
            style = VaultType.micro(),
            color = palette.textSecondary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 11.sp
        )
    }
}

@Composable
private fun MiniDockBar(palette: VaultPalette, modifier: Modifier = Modifier) {
    val shape = when (palette.dock) {
        DockStyle.FLAT_PLATE ->
            RoundedCornerShape(topStart = palette.navCorner / 2, topEnd = palette.navCorner / 2)
        DockStyle.MINIMAL_BAR -> RectangleShape
        else -> RoundedCornerShape(palette.navCorner / 2)
    }
    val height = when (palette.dock) {
        DockStyle.COMFORT_PANEL -> 17.dp
        DockStyle.PILL_FROSTED -> 15.dp
        else -> 12.dp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(palette.abyssSurface.copy(alpha = palette.navFrostAlpha))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { index ->
            val active = index == 0
            val itemShape = when (palette.navIndicator) {
                NavIndicator.PILL -> VaultRadius.pill()
                else -> RectangleShape
            }
            Box(
                modifier = Modifier
                    .size(width = if (active) 15.dp else 10.dp, height = height - 6.dp)
                    .clip(itemShape)
                    .background(
                        if (active && palette.navIndicator == NavIndicator.PILL) {
                            palette.cyan.copy(alpha = 0.26f)
                        } else {
                            Color.Transparent
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (active) palette.cyan else palette.textPrimary.copy(alpha = 0.35f))
                    )
                    if (active && palette.navIndicator == NavIndicator.UNDERLINE) {
                        Spacer(Modifier.height(1.dp))
                        Box(
                            Modifier
                                .width(7.dp)
                                .height(1.dp)
                                .background(palette.cyan)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    accent: Color,
    content: @Composable () -> Unit
) {
    GlassCard(accent = accent) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun RealtimeMetric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint
        )
    }
}

@Composable
private fun RowDivider() {
    VaultDivider(alpha = 0.045f)
}

@Composable
private fun SettingsSwitchRow(
    icon: Glyph,
    accent: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, accent = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) VaultColor.textStrong else VaultColor.textFaint
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VaultColor.background,
                checkedTrackColor = accent,
                uncheckedThumbColor = VaultColor.textFaint,
                uncheckedTrackColor = VaultColor.elevated
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: Glyph,
    accent: Color,
    title: String,
    subtitle: String,
    value: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShapes.input)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, accent = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    !enabled -> VaultColor.textFaint
                    destructive -> VaultColor.debit
                    else -> VaultColor.textStrong
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) VaultColor.textBody else VaultColor.textFaint,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(4.dp))
        ThemedIcon(
            glyph = Glyph.chevronRight,
            contentDescription = null,
            tint = VaultColor.textFaint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun IconBadge(icon: Glyph, accent: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(VaultShapes.input)
            .background(accent.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = VaultColor.textStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DialogConfirmButton(
    label: String,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = VaultColor.accent
            )
        } else {
            Text(label, color = VaultColor.accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    message: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VaultColor.elevated,
        titleContentColor = VaultColor.textStrong,
        textContentColor = VaultColor.textBody,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(VaultShapes.input)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (value == selected) VaultColor.accent else VaultColor.textStrong,
                            modifier = Modifier.weight(1f)
                        )
                        if (value == selected) {
                            ThemedIcon(
                                glyph = Glyph.check,
                                contentDescription = null,
                                tint = VaultColor.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = VaultColor.accent)
            }
        }
    )
}

private fun durationLabel(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds == 60 -> "1 min"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds}s"
}
