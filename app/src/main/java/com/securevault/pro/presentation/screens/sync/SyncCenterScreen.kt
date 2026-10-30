package com.securevault.pro.presentation.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun SyncCenterScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SyncCenterViewModel = hiltViewModel()
) {
    val palette = VaultPaletteController.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val sync = state.autoSync
    val accent = when {
        sync.lastError != null -> VaultColor.debit
        sync.running -> VaultColor.accent
        sync.pending -> VaultColor.warning
        else -> VaultColor.credit
    }

    GlassScaffold(
        title = "Sync center",
        subtitle = "Real-time encrypted JSON · XML snapshots",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 4.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.decoy) {
                item {
                    GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.lock,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Restricted session · cloud actions are disabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(
                    accent = accent,
                    borderColor = accent.copy(alpha = 0.32f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(palette.iconShape)
                                .background(accent.copy(alpha = 0.12f))
                                .border(palette.cardBorderWidth.coerceAtLeast(1.dp), accent.copy(alpha = 0.3f), palette.iconShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sync.running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.4.dp,
                                    color = accent
                                )
                            } else {
                                ThemedIcon(
                                    glyph = when {
                                        sync.lastError != null -> Glyph.errorOutline
                                        state.lastSyncedAt > 0L -> Glyph.checkCircle
                                        else -> Glyph.cloud
                                    },
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(27.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    sync.running -> "Sealing and mirroring"
                                    sync.lastError != null -> "Sync needs attention"
                                    sync.pending -> "Changes queued"
                                    state.lastSyncedAt > 0L -> "Cloud mirror current"
                                    else -> "Waiting for first sync"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = when {
                                    sync.running -> "JSON + XML envelopes upload in the background."
                                    sync.lastError != null -> sync.lastError ?: ""
                                    sync.pending -> "A local mutation will sync within a second."
                                    state.lastSyncedAt > 0L ->
                                        "Last sealed ${formatShortDate(state.lastSyncedAt)}"
                                    else -> "Connect Drive, then run a manual sync."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 17.sp
                            )
                        }
                        TagChip(
                            text = when {
                                sync.running -> "SYNC"
                                sync.lastError != null -> "ERROR"
                                sync.pending -> "QUEUED"
                                else -> "LIVE"
                            },
                            accent = accent
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.cloud,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = state.cloudEmail.ifBlank { "Google Drive not linked" },
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!state.decoy) {
                            Text(
                                text = "BACKUP",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.accent,
                                modifier = Modifier
                                    .clip(VaultShapes.chip)
                                    .clickable(onClick = onOpenBackup)
                                    .padding(horizontal = 5.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GradientButton(
                        text = if (sync.running) "Syncing…" else "Sync now",
                        icon = Glyph.sync,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.decoy && !sync.running,
                        loading = sync.running,
                        onClick = viewModel::syncNow
                    )
                }
            }

            item {
                GlassCard(accent = VaultColor.secondary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SYNC ACTIVITY",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = VaultColor.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TagChip("${sync.log.size} ENTRIES", accent = VaultColor.secondary)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (sync.log.isEmpty()) {
                        Text(
                            text = "No sync attempts in this session yet. Every vault edit uploads a fresh encrypted envelope.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textFaint,
                            lineHeight = 17.sp
                        )
                    } else {
                        sync.log.take(12).forEachIndexed { index, entry ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(palette.surfaceOverlay.copy(alpha = 0.05f))
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier
                                        .padding(top = 5.dp)
                                        .size(7.dp)
                                        .background(
                                            if (entry.success) VaultColor.credit else VaultColor.debit,
                                            CircleShape
                                        )
                                )
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.success) VaultColor.textStrong else VaultColor.debit,
                                        lineHeight = 17.sp
                                    )
                                    Text(
                                        text = syncTimeLabel(entry.at),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultColor.textFaint
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                GlassCard(accent = VaultColor.credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(VaultShapes.input)
                                .background(VaultColor.credit.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.sync,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Real-time sync",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Any add, edit or delete triggers a debounced upload.",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
                        }
                        TagChip("ALWAYS ON", accent = VaultColor.credit)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EnvelopeTile(
                        title = "JSON envelope",
                        detail = "vault_backup.enc",
                        size = sync.lastJsonBytes.toLong(),
                        icon = Glyph.insertDriveFile,
                        accent = VaultColor.accent,
                        modifier = Modifier.weight(1f)
                    )
                    EnvelopeTile(
                        title = "XML envelope",
                        detail = "vault_backup.xml.enc",
                        size = sync.lastXmlBytes.toLong(),
                        icon = Glyph.description,
                        accent = VaultColor.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            sync.lastCounts?.let { counts ->
                item {
                    GlassCard {
                        Text(
                            text = "LAST MIRRORED SNAPSHOT",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            color = VaultColor.textFaint
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SnapshotMetric("LOGINS", counts.entries, VaultColor.accent, Modifier.weight(1f))
                            SnapshotMetric("2FA", counts.totps, VaultColor.credit, Modifier.weight(1f))
                            SnapshotMetric("CONTACTS", counts.contacts, VaultColor.secondary, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SnapshotMetric("CARDS", counts.cards, VaultColor.accent, Modifier.weight(1f))
                            SnapshotMetric("NOTES", counts.notes, VaultColor.warning, Modifier.weight(1f))
                            SnapshotMetric("FILES", counts.files, VaultColor.secondary, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${counts.total} records sealed in the last upload",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
            }

            item {
                GlassCard(accent = VaultColor.secondary) {
                    Text(
                        text = "HOW THE MIRROR WORKS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = VaultColor.secondary
                    )
                    Spacer(Modifier.height(10.dp))
                    SyncStep("1", "Local mutation", "Room write completes on an IO coroutine.", VaultColor.accent)
                    SyncStep("2", "Debounce", "Bursts coalesce for 2.5 seconds.", VaultColor.warning)
                    SyncStep("3", "Seal", "JSON and XML are encrypted with PBKDF2 + AES-256-GCM.", VaultColor.credit)
                    SyncStep("4", "Mirror", "Both envelopes upload to Drive appDataFolder.", VaultColor.secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Drive only ever receives ciphertext. The XML archive carries the same record set as the JSON backup and stays synchronized automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        lineHeight = 17.sp
                    )
                }
            }

            state.message?.let { message ->
                item {
                    val tone = if (state.messageIsError) VaultColor.debit else VaultColor.credit
                    GlassCard(accent = tone, borderColor = tone.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.Top) {
                            ThemedIcon(
                                glyph = if (state.messageIsError) {
                                    Glyph.errorOutline
                                } else {
                                    Glyph.checkCircle
                                },
                                contentDescription = null,
                                tint = tone,
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
            }

            item {
                GhostButton(
                    text = "Open backup & restore",
                    icon = Glyph.folder,
                    accent = VaultColor.accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenBackup
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(palette.surfaceOverlay.copy(alpha = 0.03f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.lock,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "Live sync runs on an application-scoped IO worker, so navigating or backgrounding the UI never cancels an upload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvelopeTile(
    title: String,
    detail: String,
    size: Long,
    icon: Glyph,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        accent = accent,
        contentPadding = PaddingValues(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = icon,
                contentDescription = null,
                accent = accent,
                size = 32.dp,
                iconSize = 16.dp,
                background = accent.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = VaultType.micro(),
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (size > 0) formatBytes(size) else "—",
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SnapshotMetric(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColor.textStrong,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = VaultType.micro(),
                color = VaultColor.textFaint
            )
        }
    }
}

@Composable
private fun SyncStep(index: String, title: String, detail: String, accent: Color) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                style = VaultType.micro(),
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = VaultColor.textStrong
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                lineHeight = 14.sp
            )
        }
    }
}

private fun syncTimeLabel(at: Long): String =
    SimpleDateFormat("HH:mm:ss · d MMM", Locale.getDefault()).format(Date(at))
