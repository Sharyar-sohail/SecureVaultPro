package com.securevault.pro.presentation.screens.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.data.backup.CloudOperation
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.CloudOperationOverlay
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.OperationProgressCard
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import com.securevault.pro.presentation.components.vaultGutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showSeedConfirm by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleSignInResult(result.data)
        } else {
            viewModel.onSignInCancelled()
        }
    }

    val encryptedPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = readDocumentBytes(context, uri)
                pendingImport = bytes to queryDisplayName(context, uri)
            } catch (e: Throwable) {
                viewModel.reportError(e.message?.take(180) ?: "The backup file could not be read.")
            }
        }
    }

    GlassScaffold(
        title = "Backup & restore",
        subtitle = "Encrypted vault · Google Drive",
        onBack = onBack,
        actions = {
            if (!state.decoy && state.signedInEmail.isNotBlank()) {
                GlassIconButton(
                    icon = Glyph.refresh,
                    contentDescription = "Refresh cloud backups",
                    onClick = { viewModel.refresh() },
                    tint = VaultColor.accent
                )
            }
        }
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
                Spacer(Modifier.height(44.dp))
                EmptyState(
                    icon = Glyph.lock,
                    title = "Backup is locked in this vault",
                    message = "Cloud sync, encrypted exports and restores are disabled in a restricted session. Unlock your primary vault to manage backups."
                )
                return@Column
            }

            BackupStatusCard(state)

            if (cloudState.active) {
                OperationProgressCard(
                    title = cloudState.title.ifBlank { "Working on your vault" },
                    detail = cloudState.detail,
                    percent = cloudState.percent,
                    active = true
                )
            }

            ImportCard(
                state = state,
                cloudActive = cloudState.active,
                onPassphrase = viewModel::setPassphrase,
                onPickFile = { encryptedPicker.launch(arrayOf("*/*")) }
            )

            if (state.signedInEmail.isBlank()) {
                GlassCard(accent = VaultColor.secondary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(icon = Glyph.cloud, accent = VaultColor.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Connect Google Drive",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Encrypted snapshots live in the hidden app-data folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Backups leave this device only after AES-256-GCM sealing with PBKDF2-SHA256 at 600,000 rounds. Google never receives your records in readable form, and the files stay invisible in the normal Drive interface.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    GradientButton(
                        text = "Connect Google Drive",
                        icon = Glyph.cloud,
                        enabled = !cloudState.active,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val unavailable = viewModel.googleAvailabilityFault()
                            if (unavailable != null) viewModel.reportError(unavailable)
                            else googleLauncher.launch(viewModel.signInIntent())
                        }
                    )
                }
            } else {
                GlassCard(accent = VaultColor.credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(icon = Glyph.cloud, accent = VaultColor.credit)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = state.signedInEmail,
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.storageQuota?.let { quota ->
                                    if (quota.unlimited) {
                                        "Unlimited plan · ${formatBytes(quota.usedBytes)} used"
                                    } else {
                                        "${formatBytes(quota.availableBytes)} free of ${formatBytes(quota.limitBytes)} · drive.appdata scope"
                                    }
                                } ?: "Drive app-data · drive.appdata scope",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
                        }
                        Text(
                            text = "Storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody,
                            modifier = Modifier
                                .clip(VaultShapes.chip)
                                .clickable(enabled = !cloudState.active) { viewModel.refreshStorage() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "Sign out",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody,
                            modifier = Modifier
                                .clip(VaultShapes.chip)
                                .clickable(enabled = !cloudState.active) { viewModel.signOut() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    GradientButton(
                        text = "Back up now",
                        icon = Glyph.cloudUpload,
                        enabled = !cloudState.active,
                        loading = cloudState.active && cloudState.operation == CloudOperation.BACKUP,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::backupNow
                    )
                }

                SectionLabel(
                    title = "Cloud snapshots",
                    trailing = if (state.loadingBackups) "SYNCING" else "${state.backups.size} FOUND"
                )
                when {
                    state.loadingBackups -> GlassCard(accent = VaultColor.accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = VaultColor.accent
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Listing encrypted snapshots in Drive…",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                    }
                    state.backups.isEmpty() -> GlassCard(accent = VaultColor.warning) {
                        Text(
                            text = "No snapshot has been found yet.",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Run “Back up now” to create the first encrypted snapshot, or restore an offline file below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                    }
                    else -> state.backups.forEach { file ->
                        CloudFileRow(
                            file = file,
                            selected = file.id == state.selectedId,
                            onClick = { viewModel.select(file) }
                        )
                    }
                }

                state.selected?.let { selected ->
                    SelectedBackupCard(
                        file = selected,
                        state = state,
                        cloudStateOperation = cloudState.operation,
                        cloudActive = cloudState.active,
                        onInspect = viewModel::previewSelected,
                        onRestore = { showRestoreConfirm = true }
                    )
                }
            }

            GlassCard(accent = VaultColor.decorative) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentIcon(icon = Glyph.lock, accent = VaultColor.decorative)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Embedded vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = "Authenticated offline recovery archive",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The authenticated seed is sealed inside the APK. Installing it merges the archive into your vault without deleting anything, and it is used automatically whenever a restore would otherwise leave you empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    text = "Restore embedded vault",
                    icon = Glyph.security,
                    enabled = !cloudState.active,
                    accent = VaultColor.decorative,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showSeedConfirm = true }
                )
            }

            if (state.companionEnabled && state.companionUrl.isNotBlank()) {
                GlassCard(accent = VaultColor.secondary) {
                    Text(
                        text = "Companion portal",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColor.textStrong
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.companionUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(
                            text = "Push vault",
                            icon = Glyph.cloudUpload,
                            enabled = !cloudState.active,
                            accent = VaultColor.secondary,
                            modifier = Modifier.weight(1f),
                            onClick = viewModel::pushToCompanion
                        )
                        GhostButton(
                            text = "Pull vault",
                            icon = Glyph.cloudDownload,
                            enabled = !cloudState.active,
                            accent = VaultColor.credit,
                            modifier = Modifier.weight(1f),
                            onClick = viewModel::pullFromCompanion
                        )
                    }
                }
            }

            state.message?.let { message ->
                val accent = if (state.messageIsError) VaultColor.warning else VaultColor.credit
                GlassCard(borderColor = accent.copy(alpha = 0.32f), accent = accent) {
                    Row(verticalAlignment = Alignment.Top) {
                        ThemedIcon(
                            glyph = if (state.messageIsError) Glyph.errorOutline
                            else Glyph.checkCircle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(19.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.messageIsError) VaultColor.textStrong else VaultColor.textStrong,
                                lineHeight = 18.sp
                            )
                            state.warnings.take(5).forEach { warning ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "• $warning",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        ThemedIcon(
                            glyph = Glyph.close,
                            contentDescription = "Dismiss message",
                            tint = VaultColor.textFaint,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { viewModel.clearMessage() }
                        )
                    }
                }
            }

            Text(
                text = "The master password, PIN and this device's Keystore never travel with a backup. Keep an offline copy in case every device is lost.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint.copy(alpha = 0.8f),
                lineHeight = 15.sp
            )
        }
    }

    pendingImport?.let { (bytes, name) ->
        ConfirmDialog(
            visible = true,
            title = "Apply this backup file",
            message = "$name is decrypted and ready.\n\nMerge keeps everything on this device and adds only what the file brings — logins, authenticators and cards already present are matched by content, never copied twice.\n\nReplace clears this device first and installs exactly what the file holds. The file itself is never modified.",
            confirmLabel = "Replace vault",
            altLabel = "Merge into vault",
            destructive = true,
            onConfirm = {
                pendingImport = null
                viewModel.importLocal(bytes, name, replace = true)
            },
            onAlt = {
                pendingImport = null
                viewModel.importLocal(bytes, name, replace = false)
            },
            onDismiss = { pendingImport = null }
        )
    }

    ConfirmDialog(
        visible = showRestoreConfirm,
        title = "Restore this backup?",
        message = "Records from ${state.selected?.name ?: "the selected snapshot"} will be merged into your vault. Existing items are kept, duplicates are matched instead of copied, and the embedded vault is installed automatically only if the result would be empty.",
        confirmLabel = "Restore",
        destructive = false,
        onConfirm = {
            showRestoreConfirm = false
            viewModel.restoreSelected()
        },
        onDismiss = { showRestoreConfirm = false }
    )

    ConfirmDialog(
        visible = showSeedConfirm,
        title = "Install the embedded vault?",
        message = "The embedded archive will be merged into your current vault. Nothing is deleted, and logins, authenticators and cards that already exist are matched instead of copied.",
        confirmLabel = "Install",
        destructive = false,
        onConfirm = {
            showSeedConfirm = false
            viewModel.restoreEmbeddedSeed()
        },
        onDismiss = { showSeedConfirm = false }
    )

    val restoring = cloudState.active && cloudState.operation in setOf(
        CloudOperation.RESTORE,
        CloudOperation.IMPORT,
        CloudOperation.SEED
    )
    // No cloud step may trap the owner: this overlay swallows the back key, so after a grace
    // period — long enough that a healthy transfer is never interrupted — it grows a way out.
    // "Stop waiting" only stops the waiting; the transfer is not cancelled and nothing is erased.
    val cloudWaiting = state.driveLinking || restoring
    var allowCloudExit by remember { mutableStateOf(false) }
    LaunchedEffect(cloudWaiting) {
        allowCloudExit = false
        if (cloudWaiting) {
            delay(CLOUD_CANCEL_GRACE_MS)
            allowCloudExit = true
        }
    }
    CloudOperationOverlay(
        visible = cloudWaiting,
        title = cloudState.title.ifBlank { "Downloading data from Google Drive..." },
        detail = cloudState.detail.ifBlank { "Connecting your secure cloud vault" },
        percent = when {
            cloudState.active -> cloudState.percent
            state.driveLinking -> 6
            else -> 0
        },
        onCancel = if (allowCloudExit) {
            {
                allowCloudExit = false
                viewModel.abandonCloudWait()
            }
        } else {
            null
        }
    )
}

/** Time a healthy Drive restore or sign-in is given before the screen offers a way out. */
private const val CLOUD_CANCEL_GRACE_MS = 20_000L

@Composable
private fun BackupStatusCard(state: BackupUiState) {
    GlassCard(accent = VaultColor.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(icon = Glyph.cloud, accent = VaultColor.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Encrypted vault backup",
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = if (state.hasDriveAccess) {
                        "Drive app-data folder · hidden from normal Drive"
                    } else {
                        "Offline envelope ready · connect Drive to sync"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TagChip(
                text = if (state.hasDriveAccess) "LIVE" else "OFFLINE",
                accent = if (state.hasDriveAccess) VaultColor.credit else VaultColor.warning
            )
        }
        Spacer(Modifier.height(16.dp))
        Row {
            MiniStat(
                label = "LAST BACKUP",
                value = formatShortDate(state.lastBackupAt),
                modifier = Modifier.weight(1f)
            )
            MiniStat(label = "CIPHER", value = "AES-256-GCM", modifier = Modifier.weight(1f))
            MiniStat(label = "KDF", value = "PBKDF2 600k", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = VaultColor.textFaint,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = VaultColor.textStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccentIcon(
    icon: Glyph,
    accent: androidx.compose.ui.graphics.Color
) {
    ThemedIconTile(
        icon = icon,
        contentDescription = null,
        accent = accent,
        size = 42.dp,
        iconSize = 21.dp,
        background = accent.copy(alpha = 0.11f)
    )
}

@Composable
private fun SectionLabel(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColor.textStrong,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = VaultColor.textFaint
        )
    }
}

@Composable
private fun CloudFileRow(
    file: CloudBackupFile,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = if (selected) VaultColor.accent else VaultColor.textBody
    GlassCard(
        borderColor = if (selected) VaultColor.accent.copy(alpha = 0.45f) else VaultColor.hairline,
        accent = if (selected) VaultColor.accent else VaultColor.textFaint,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(VaultShapes.input)
                    .background(accent.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = if (selected) Glyph.checkCircle else Glyph.cloud,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${formatBytes(file.size)} · ${formatShortDate(file.modifiedTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
            if (file.isPrimary) {
                Spacer(Modifier.width(8.dp))
                TagChip(text = "PRIMARY", accent = VaultColor.credit)
            }
        }
    }
}

@Composable
private fun SelectedBackupCard(
    file: CloudBackupFile,
    state: BackupUiState,
    cloudStateOperation: CloudOperation?,
    cloudActive: Boolean,
    onInspect: () -> Unit,
    onRestore: () -> Unit
) {
    GlassCard(borderColor = VaultColor.accent.copy(alpha = 0.28f), accent = VaultColor.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Selected snapshot",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = VaultColor.accent
                )
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (state.previewing) "INSPECTING" else "INSPECT",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.previewing) VaultColor.textFaint else VaultColor.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(VaultShapes.chip)
                    .clickable(enabled = !state.previewing) { onInspect() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.previewing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = VaultColor.accent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Decrypting a copy to inspect it. Your vault is not modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 17.sp
                    )
                }
            }
            state.preview != null -> {
                val preview = state.preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(
                        text = if (preview.encrypted) "ENCRYPTED" else "PLAIN",
                        accent = if (preview.encrypted) VaultColor.credit else VaultColor.warning
                    )
                    Spacer(Modifier.width(8.dp))
                    TagChip(text = "READY TO RESTORE", accent = VaultColor.accent)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = previewCountsLabel(preview.counts),
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textStrong,
                    lineHeight = 18.sp
                )
                preview.warnings.take(3).forEach { warning ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        lineHeight = 14.sp
                    )
                }
            }
            state.previewError != null -> {
                Row(verticalAlignment = Alignment.Top) {
                    ThemedIcon(
                        glyph = Glyph.errorOutline,
                        contentDescription = null,
                        tint = VaultColor.warning,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.previewError,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Preview never blocks a restore. You can restore this snapshot directly.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.accent,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
            else -> {
                Text(
                    text = "Tap INSPECT to preview the contents without touching your vault, or restore directly below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = if (cloudActive && cloudStateOperation == CloudOperation.RESTORE) {
                "Restoring vault…"
            } else {
                "Restore selected backup"
            },
            icon = Glyph.cloudDownload,
            enabled = !cloudActive,
            loading = cloudActive && cloudStateOperation == CloudOperation.RESTORE,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRestore
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Restore merges every record and automatically installs the embedded vault if the result would be empty.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun ImportCard(
    state: BackupUiState,
    cloudActive: Boolean,
    onPassphrase: (String) -> Unit,
    onPickFile: () -> Unit
) {
    GlassCard(accent = VaultColor.warning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(icon = Glyph.fileUpload, accent = VaultColor.warning)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Restore from a file",
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "Pick the backup file already saved on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Getting backup.json onto this phone",
            style = MaterialTheme.typography.labelLarge,
            color = VaultColor.textStrong
        )
        Spacer(Modifier.height(8.dp))
        ImportStep(1, "Open Gmail on any device and find the backup email.")
        ImportStep(2, "Tap the attachment · backup.json or SecureWallet_Backup_….enc · then choose Download.")
        ImportStep(3, "The file is now in this phone's Downloads folder.")
        ImportStep(4, "Tap “Choose file from storage” below and select that file.")
        ImportStep(5, "Enter the password that sealed it, then merge or replace.")
        Spacer(Modifier.height(14.dp))
        VaultTextField(
            value = state.passphrase,
            onValueChange = onPassphrase,
            label = "Password that sealed the file (optional)",
            leadingIcon = Glyph.lock,
            isPassword = true,
            enabled = !cloudActive,
            supportingText = "Needed for an encrypted export · leave blank for a plain backup.json."
        )
        Spacer(Modifier.height(12.dp))
        GradientButton(
            text = "Choose file from storage",
            icon = Glyph.fileUpload,
            enabled = !cloudActive,
            modifier = Modifier.fillMaxWidth(),
            onClick = onPickFile
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The app never opens your mailbox or sends this file anywhere · it is read straight from your storage, then merged into or used to replace this vault.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun ImportStep(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.warning,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textBody,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun previewCountsLabel(counts: BackupCounts): String {
    val parts = buildList {
        if (counts.entries > 0) add("${counts.entries} logins")
        if (counts.contacts > 0) add("${counts.contacts} contacts")
        if (counts.totps > 0) add("${counts.totps} 2FA")
        if (counts.notes > 0) add("${counts.notes} notes")
        if (counts.cards > 0) add("${counts.cards} cards")
        if (counts.files > 0) add("${counts.files} files")
    }
    return if (parts.isEmpty()) "The payload is empty." else parts.joinToString(" · ")
}
