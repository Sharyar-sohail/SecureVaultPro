package com.securevault.pro.presentation.screens.welcome

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

/**
 * The only pre-login surface: Google restore, encrypted file import, or a brand-new local
 * vault. Nothing on this screen asks for a passphrase or a seed phrase: the Google account
 * is the authentication and the file is opened with the keys this vault has always used.
 * Every path shares the activity-scoped [SetupViewModel] with [SetupScreen] so progress
 * survives the hand-off.
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

    LaunchedEffect(Unit) {
        viewModel.onWelcomeOpened()
    }

    // The gate must hand itself forward in both endings. `flowStarted` covers the normal one.
    // The queued-restore flag covers the race the watchdog can produce on a slow link: the
    // 30 s quiet stop clears `flowStarted` while the last Drive file is still mid-flight, the
    // cancelled flow then finishes, and the ViewModel lands the "waiting for this phone's
    // unlock" result with the tap flag gone. Without the second arm that result pins the
    // owner on this screen — a restore the app itself says will walk on by itself.
    LaunchedEffect(state.readyForSetup, state.driveRestoreQueued) {
        if (state.readyForSetup && (flowStarted || state.driveRestoreQueued)) onFinished()
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
        flowStarted = true
        backupPicker.launch(arrayOf("*/*"))
    }

    NeonBackground {
        val palette = com.securevault.pro.presentation.theme.VaultPaletteController.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The IME shrinks the scroll viewport (and the focused field is brought into
                // view inside it) instead of resizing the whole window.
                .imePadding()
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
                style = MaterialTheme.typography.headlineMedium,
                color = VaultColor.textStrong,
                lineHeight = 28.sp
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
                    "Restore the encrypted vault stored on your Google Drive"
                } else {
                    "${state.signedInEmail} · restores every Drive copy"
                },
                enabled = !state.busy && !cloudState.active,
                onClick = {
                    // The Google account is the whole authentication: no phrase, seed or any other
                    // typing stands between this tap and the restore. The picker only opens when
                    // this device has no usable Drive grant.
                    if (viewModel.onGoogleTileTapped()) launchGoogle() else flowStarted = true
                }
            )
            Spacer(Modifier.height(11.dp))
            WelcomeOption(
                icon = Glyph.uploadFile,
                accent = VaultColor.warning,
                badge = "LOCAL",
                title = "Import backup file",
                description = "Open an encrypted .enc export from this device or Drive",
                enabled = !state.busy && !cloudState.active,
                onClick = { showImportDialog = true }
            )
            Spacer(Modifier.height(11.dp))
            WelcomeOption(
                icon = Glyph.lock,
                accent = VaultColor.credit,
                badge = "NEW",
                title = "Set up this device",
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
                            glyph = if (state.error != null || state.noticeIsError) Glyph.errorOutline else Glyph.checkCircle,
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
                            val diagnostic = state.restoreDiagnostic
                            if (state.restoreNeedsSecret && diagnostic != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = diagnostic,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Only a passphrase-sealed export ever raises this. A Drive copy is never unlocked by a
            // typed secret at the gate: this device's own key opens it, or setting up the phone does.
            if (state.restoreNeedsSecret) {
                Spacer(Modifier.height(12.dp))
                GlassCard(
                    accent = VaultColor.warning,
                    borderColor = VaultColor.warning.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = "Unlock this export",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "The file is sealed with the passphrase you chose when exporting it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        VaultTextField(
                            value = state.restoreSecret,
                            onValueChange = viewModel::onRestoreSecretChange,
                            label = "Export passphrase",
                            leadingIcon = Glyph.lock,
                            isPassword = true,
                            enabled = !state.busy,
                            onImeSubmit = { viewModel.submitRestoreSecret() }
                        )
                        Spacer(Modifier.height(9.dp))
                        GradientButton(
                            text = "Unlock and restore",
                            icon = Glyph.key,
                            enabled = state.restoreSecret.trim().isNotEmpty() && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.submitRestoreSecret() }
                        )
                        TextButton(
                            onClick = { viewModel.cancelRestoreSecret() },
                            enabled = !state.busy,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "Not now",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
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
            title = { Text("Import encrypted backup") },
            text = {
                Text(
                    text = "Choose a Secure Wallet Pro .enc or .xml.enc export. Your own backups open " +
                        "automatically; a file you locked with a passphrase asks for it first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { openBackupPicker() }) {
                    Text("Choose file", color = VaultColor.accent, fontWeight = FontWeight.Bold)
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

}

private const val CANCEL_GRACE_MS = 6_000L
// The wall-clock backstop, not a progress timeout: Drive's own calls give up at 45-60 s, so a
// dead restore dies by itself well inside this window, while a live one on a slow link (5 KB/s
// moves one percent every minutes) needs the room. 30 s used to cancel restores that were
// downloading, and the orphaned result then pinned the gate behind a "waiting for your unlock"
// banner the screen could no longer hand forward.
private const val AUTO_STOP_MS = 120_000L
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
