package com.securevault.pro.presentation.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.securevault.pro.data.imports.SharedInbox
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.overlayPanel
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.ChatGlyphBoard
import com.securevault.pro.presentation.theme.AbyssElevated
import com.securevault.pro.presentation.theme.NeonAmber
import com.securevault.pro.presentation.theme.NeonCoral
import com.securevault.pro.presentation.theme.NeonCyan
import com.securevault.pro.presentation.theme.NeonMint
import com.securevault.pro.presentation.theme.TextMuted
import com.securevault.pro.presentation.theme.TextPrimary
import com.securevault.pro.presentation.theme.TextSecondary
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.components.Glyph

/** WhatsApp's own green, read from the active theme so every palette can restyle the card. */
private val WhatsAppGreen: Color get() = VaultColor.whatsAppBrand

/**
 * The one place the owner meets the WhatsApp feature, and the reason this card exists: the import
 * and the archive were both wired and both invisible, so a whole chat could be sealed into the
 * vault while nobody could find the button. Two verbs, in the order they are needed — get a chat
 * in, get a chat out.
 */
@Composable
fun WhatsAppEntryCard(
    pending: List<SharedInbox.Pending>,
    chats: Int,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSealPending: () -> Unit,
    onDiscardPending: () -> Unit,
    /** Opens the conversations the vault holds, row by row, in the app's own viewer. */
    onOpenChats: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = WhatsAppGreen,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = Glyph.chat,
                contentDescription = null,
                accent = WhatsAppGreen,
                size = 40.dp,
                iconSize = 21.dp
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "WhatsApp chats",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        pending.isNotEmpty() ->
                            "${pending.size} chat${if (pending.size == 1) "" else "s"} shared in and " +
                                "waiting to be sealed"
                        chats > 0 -> "$chats conversation${if (chats == 1) "" else "s"} in this vault"
                        else -> "Import a whole chat with its photos · export one back out"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pending.isNotEmpty()) WhatsAppGreen else TextMuted,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (pending.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(
                    text = if (pending.size == 1) "Seal the shared chat" else "Seal ${pending.size} shared chats",
                    icon = Glyph.bolt,
                    modifier = Modifier.weight(1f),
                    onClick = onSealPending
                )
                GhostButton(
                    text = "Throw away",
                    icon = Glyph.close,
                    accent = NeonCoral,
                    onClick = onDiscardPending
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton(
                text = "Import a chat",
                icon = Glyph.download,
                modifier = Modifier.weight(1f),
                onClick = onImport
            )
            GhostButton(
                text = "Export a chat",
                icon = Glyph.upload,
                accent = WhatsAppGreen,
                modifier = Modifier.weight(1f),
                onClick = onExport
            )
        }
        if (onOpenChats != null && chats > 0) {
            Spacer(Modifier.height(8.dp))
            GhostButton(
                text = if (chats == 1) "Open the conversation" else "Open $chats conversations",
                icon = Glyph.chat,
                accent = WhatsAppGreen,
                onClick = onOpenChats
            )
        }
    }
}

@Composable
private fun HubDialogFrame(
    onDismiss: () -> Unit,
    accent: Color = WhatsAppGreen,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.widthIn(max = 460.dp),
                accent = accent,
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 660.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                    Spacer(Modifier.height(8.dp))
                    GhostButton(text = "Close", icon = Glyph.close, onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * The way back out: every conversation in the vault, each with the three things that make an
 * export usable — the plain transcript, the archive with its media, and a hand-over to another
 * app without ever naming a folder.
 */
@Composable
fun WhatsAppExportDialog(
    visible: Boolean,
    chats: List<VaultChatRow>,
    state: ChatExportState,
    onSaveText: (String) -> Unit,
    onSaveWithMedia: (String) -> Unit,
    onSendToApp: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    HubDialogFrame(onDismiss = onDismiss) {
        Text(
            text = "Export a chat from the vault",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Writes a chat back out as an ordinary file — the same shape WhatsApp itself " +
                "produces — so you can read it on a laptop, mail it, or move it to another phone. " +
                "It is decrypted only for this one export and only where you choose.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 18.sp
        )
        if (state.busy) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .overlayPanel(alpha = 0.06f)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.refresh,
                    contentDescription = null,
                    tint = WhatsAppGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.stage.ifBlank { "Preparing the export…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                GlassIconButton(
                    icon = Glyph.stop,
                    contentDescription = "Stop the export",
                    onClick = onCancel,
                    tint = NeonCoral,
                    background = Color.Transparent,
                    size = 30.dp
                )
            }
        }
        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = NeonCoral,
                lineHeight = 18.sp
            )
        }
        if (state.result != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.result.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = NeonMint,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(14.dp))
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .overlayPanel(alpha = 0.06f)
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        text = "No chats in the vault yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "There is nothing to export because no conversation has been imported " +
                            "yet — and this vault cannot reach into WhatsApp's own storage to take " +
                            "one. In WhatsApp: open the chat → ⋮ → More → Export chat → Without media " +
                            "→ Share → Secure Wallet Pro. It lands here in seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            chats.forEach { chat ->
                ExportChatRow(
                    chat = chat,
                    enabled = !state.busy,
                    onSaveText = { onSaveText(chat.noteId) },
                    onSaveWithMedia = { onSaveWithMedia(chat.noteId) },
                    onSendToApp = { onSendToApp(chat.noteId) }
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = "A .txt gives you the words. A .zip gives you the words plus the photos, " +
                    "videos and voice notes that chat was sealed with — the same file shape WhatsApp " +
                    "writes, so it opens anywhere.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ExportChatRow(
    chat: VaultChatRow,
    enabled: Boolean,
    onSaveText: () -> Unit,
    onSaveWithMedia: () -> Unit,
    onSendToApp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AbyssElevated.copy(alpha = if (enabled) 0.72f else 0.3f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = ChatGlyphBoard.glyphFor(chat.noteId),
                contentDescription = null,
                tint = WhatsAppGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${chat.messages} messages" +
                        if (chat.files > 0) " · ${chat.files} file${if (chat.files == 1) "" else "s"}" else "" +
                        " · ${formatShortDate(chat.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Save .txt",
                icon = Glyph.description,
                accent = NeonCyan,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onSaveText
            )
            GhostButton(
                text = "Save .zip",
                icon = Glyph.insertDriveFile,
                accent = NeonMint,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onSaveWithMedia
            )
        }
        Spacer(Modifier.height(6.dp))
        GhostButton(
            text = "Send to another app",
            icon = Glyph.send,
            accent = WhatsAppGreen,
            enabled = enabled,
            onClick = onSendToApp
        )
    }
}

/** The queued shares, spelled out: what arrived, how big it is, and the one tap that seals it. */
@Composable
fun PendingShareList(
    pending: List<SharedInbox.Pending>,
    enabled: Boolean,
    onSeal: () -> Unit,
    onDiscardOne: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .overlayPanel(alpha = 0.06f)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(NeonAmber)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Waiting in the share inbox",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            GradientButton(
                text = "Seal",
                icon = Glyph.bolt,
                enabled = enabled,
                onClick = onSeal
            )
        }
        Spacer(Modifier.height(8.dp))
        pending.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.readable) TextSecondary else NeonCoral,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.bytes > 0) {
                    Text(
                        text = "${entry.bytes / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Glyph.close,
                    contentDescription = "Throw away ${entry.name}",
                    onClick = { onDiscardOne(entry.id) },
                    tint = NeonCoral,
                    background = Color.Transparent,
                    size = 24.dp
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
