package com.securevault.pro.presentation.screens.welcome

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.CloudOperationOverlay
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import com.securevault.pro.presentation.screens.secureinput.RecoverySeedSection
import com.securevault.pro.security.keyboard.SecurePasswordField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

/**
 * The only pre-login surface: Google restore, encrypted file import, a master
 * recovery seed, or a brand-new local vault. Every path shares the activity-scoped
 * [SetupViewModel] with [SetupScreen] so their progress survives the hand-off.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Survives the activity recreation that Google's sign-in sheet and the file picker
    // both cause; without it the finished restore has nothing to hand off to.
    var flowStarted by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importWantsSeed by rememberSaveable { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }

    // The seed way in is a screen of its own, drawn over this list: it owns its own steps
    // and never touches Google.
    var showSeedRestore by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.onWelcomeOpened()
    }

    LaunchedEffect(state.readyForSetup) {
        if (flowStarted && state.readyForSetup) onFinished()
    }

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleSignInResult(result.data)
        } else {
            flowStarted = false
            viewModel.onSignInCancelled()
        }
    }

    val launchGoogle = {
        val unavailable = viewModel.googleAvailabilityFault()
        if (unavailable != null) {
            flowStarted = false
            viewModel.reportError(unavailable)
        } else {
            flowStarted = true
            runCatching { googleLauncher.launch(viewModel.signInIntent()) }
                .onFailure {
                    flowStarted = false
                    viewModel.reportError("Google sign-in could not start on this device.")
                }
        }
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            flowStarted = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val bytes = readDocumentBytes(context, uri)
                viewModel.importLocalFile(bytes, queryDisplayName(context, uri))
            }.onFailure {
                viewModel.reportError(it.message?.take(180) ?: "The backup file could not be read.")
            }
        }
    }

    val openBackupPicker: () -> Unit = {
        showImportDialog = false
        importWantsSeed = false
        viewModel.setRestorePassphrase(passphrase)
        flowStarted = true
        backupPicker.launch(arrayOf("*/*"))
    }

    NeonBackground {
        val palette = com.securevault.pro.presentation.theme.VaultPaletteController.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(VaultColor.accent.copy(alpha = 0.12f), VaultShapes.input)
                        .border(1.dp, VaultColor.accent.copy(alpha = 0.32f), VaultShapes.input),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.shield,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text(
                        text = "SECURE WALLET PRO",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = VaultColor.textStrong,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Zero-knowledge vault",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }

            Spacer(Modifier.height(34.dp))
            Text(
                text = "Your vault,\nthree ways in.",
                style = MaterialTheme.typography.displaySmall,
                color = VaultColor.textStrong,
                lineHeight = MaterialTheme.typography.displaySmall.lineHeight
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Everything is sealed with AES-256-GCM and keys held in the Android Keystore. Choose how you want to open your vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textBody,
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(26.dp))
            WelcomeOption(
                icon = Glyph.cloud,
                accent = VaultColor.accent,
                badge = "RESTORE",
                title = "Continue with Google",
                description = if (state.signedInEmail.isBlank()) {
                    "Restore the encrypted vault stored in your Drive app-data folder"
                } else {
                    state.signedInEmail
                },
                enabled = !state.busy && !cloudState.active,
                onClick = {
                    if (state.signedInEmail.isNotBlank()) {
                        flowStarted = true
                        viewModel.restoreFromExistingDrive()
                    } else {
                        launchGoogle()
                    }
                }
            )
            Spacer(Modifier.height(11.dp))
            WelcomeOption(
                icon = Glyph.uploadFile,
                accent = VaultColor.warning,
                badge = "LOCAL",
                title = "Import backup file",
                description = "Open an encrypted .enc export, or type a recovery seed phrase",
                enabled = !state.busy && !cloudState.active,
                onClick = { showImportDialog = true }
            )
            Spacer(Modifier.height(11.dp))
            WelcomeOption(
                icon = Glyph.lock,
                accent = VaultColor.credit,
                badge = "NEW",
                title = "Master password / Setup vault",
                description = "Create a fresh vault protected only on this device",
                enabled = !state.busy && !cloudState.active,
                onClick = { onFinished() }
            )

            if (state.notice != null || state.error != null) {
                Spacer(Modifier.height(18.dp))
                val message = state.error ?: state.notice.orEmpty()
                val accent = if (state.error != null || state.noticeIsError) VaultColor.debit else VaultColor.credit
                GlassCard(
                    accent = accent,
                    borderColor = accent.copy(alpha = 0.3f),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        ThemedIcon(
                            glyph = Glyph.errorOutline,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(17.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 17.sp
                            )
                            if (state.warnings.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "• ${state.warnings.first()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                text = "Your master password cannot be recovered. Keep a physical copy.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(26.dp))
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = VaultColor.elevated,
            titleContentColor = VaultColor.textStrong,
            textContentColor = VaultColor.textBody,
            title = {
                Text(if (importWantsSeed) "Recover with your seed phrase" else "Import encrypted backup")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { importWantsSeed = false }) {
                            Text(
                                text = "Backup file",
                                color = if (!importWantsSeed) VaultColor.accent else VaultColor.textBody,
                                fontWeight = if (!importWantsSeed) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        TextButton(onClick = { importWantsSeed = true }) {
                            Text(
                                text = "Seed phrase",
                                color = if (importWantsSeed) VaultColor.accent else VaultColor.textBody,
                                fontWeight = if (importWantsSeed) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    if (importWantsSeed) {
                        Text(
                            text = "Twelve or twenty-four words open this vault with no Google account " +
                                "and no network. Nothing is sent anywhere while you type them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                    } else {
                        Text(
                            text = "Select a Secure Wallet Pro .enc export. Leave the passphrase empty if it was sealed with the universal cloud key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                        SecurePasswordField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = "Passphrase (optional)",
                            onSubmit = { openBackupPicker() }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importWantsSeed) {
                            showImportDialog = false
                            showSeedRestore = true
                        } else {
                            openBackupPicker()
                        }
                    }
                ) {
                    Text(
                        text = if (importWantsSeed) "Type seed phrase" else "Choose file",
                        color = VaultColor.accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        )
    }

    // Only an attempt this screen started may cover the three options. A cloud flag
    // left over from anything else is dropped so the pre-login screen can never dead-end.
    val busy = state.busy || (cloudState.active && flowStarted)
    LaunchedEffect(cloudState.active, flowStarted, state.busy) {
        if (cloudState.active && !flowStarted && !state.busy) {
            viewModel.cancelPendingFlow()
        }
    }
    var allowCancel by remember { mutableStateOf(false) }
    // The clock runs on wall time from the moment an attempt starts, not on the progress
    // stream. It used to restart on every Drive update, which is what pinned the pre-login
    // screen: a restore that keeps reporting percent never let the Cancel control appear and
    // never reached the auto-stop, so all three options sat disabled behind an overlay with
    // no way out. Progress now only buys patience on the auto-stop.
    LaunchedEffect(busy) {
        allowCancel = false
        if (!busy) return@LaunchedEffect
        var elapsed = 0L
        var quiet = 0L
        var lastSignal = "${cloudState.percent}|${cloudState.detail}"
        while (true) {
            delay(STOP_TICK_MS)
            elapsed += STOP_TICK_MS
            quiet += STOP_TICK_MS
            val signal = "${cloudState.percent}|${cloudState.detail}"
            if (signal != lastSignal) {
                lastSignal = signal
                quiet = 0L
            }
            if (!allowCancel && elapsed >= CANCEL_GRACE_MS) allowCancel = true
            if (quiet >= AUTO_STOP_MS) break
        }
        if (state.busy || cloudState.active) {
            flowStarted = false
            viewModel.cancelPendingFlow()
        }
    }
    CloudOperationOverlay(
        visible = busy,
        title = cloudState.title.ifBlank {
            if (state.restoreMode == SetupRestoreMode.FILE) "Opening encrypted backup" else "Restoring your vault"
        },
        detail = cloudState.detail.ifBlank {
            if (state.restoreMode == SetupRestoreMode.FILE) {
                "Decrypting the selected export"
            } else {
                "Connecting to Google Drive"
            }
        },
        percent = if (cloudState.active) cloudState.percent else 6,
        onCancel = if (allowCancel) {
            {
                flowStarted = false
                allowCancel = false
                viewModel.cancelPendingFlow()
            }
        } else {
            null
        }
    )

    // The offline way in: the phrase, then a recovery file, then a new unlock passphrase - and
    // straight into setup with that passphrase already typed. No Google call happens in here.
    if (showSeedRestore) {
        RecoverySeedSection(
            startInRestore = true,
            onBack = { showSeedRestore = false },
            onRestoreFinished = { chosen ->
                showSeedRestore = false
                viewModel.setMasterPassword(chosen)
                viewModel.setConfirmPassword(chosen)
                onFinished()
            }
        )
    }
}

private const val CANCEL_GRACE_MS = 6_000L
private const val AUTO_STOP_MS = 30_000L
private const val STOP_TICK_MS = 250L

@Composable
private fun WelcomeOption(
    icon: Glyph,
    accent: Color,
    badge: String,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        borderColor = accent.copy(alpha = 0.22f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        onClick = if (enabled) onClick else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        accent.copy(alpha = 0.12f),
                        com.securevault.pro.presentation.theme.VaultPaletteController.current.chipShape
                    )
                    .border(
                        com.securevault.pro.presentation.theme.VaultPaletteController.current.cardBorderWidth,
                        accent.copy(alpha = 0.25f),
                        com.securevault.pro.presentation.theme.VaultPaletteController.current.chipShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(23.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) VaultColor.textStrong else VaultColor.textFaint,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = badge,
                        modifier = Modifier
                            .clip(
                                com.securevault.pro.presentation.theme.VaultPaletteController.current.chipShape
                            )
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        style = VaultType.micro(),
                        color = accent,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textFaint,
                    lineHeight = MaterialTheme.typography.labelMedium.lineHeight
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = accent.copy(alpha = 0.7f),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
