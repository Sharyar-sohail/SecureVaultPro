package com.securevault.pro.presentation.screens.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.imports.WhatsAppChat
import com.securevault.pro.data.imports.WhatsAppChatUnit
import com.securevault.pro.data.imports.WhatsAppPackages
import com.securevault.pro.data.imports.WhatsAppTree
import com.securevault.pro.presentation.components.ChatGlyph
import com.securevault.pro.presentation.components.ChatGlyphBoard
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.FileUploadOverlay
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.overlayPanel
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.vaultGutter
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

@Composable
fun FilesScreen(
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenVoice: () -> Unit,
    /** Opens the note a finished import just wrote, so the chat is what the user lands on. */
    onOpenNote: (String) -> Unit = {},
    /** Opens the stored conversations; a chat id jumps straight into that conversation. */
    onOpenChat: (String?) -> Unit = {},
    viewModel: FilesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingShares by viewModel.pendingShares.collectAsStateWithLifecycle()
    val importSummary by viewModel.importSummary.collectAsStateWithLifecycle()
    val sealedNoteId by viewModel.openNote.collectAsStateWithLifecycle()
    val openedChatId by viewModel.openChat.collectAsStateWithLifecycle()
    val storedChats by viewModel.storedChats.collectAsStateWithLifecycle()
    val exportViewModel: WhatsAppExportViewModel = hiltViewModel()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    val vaultChats by exportViewModel.chats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<VaultFile?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var chatImportOpen by remember { mutableStateOf(false) }
    var chatExportOpen by remember { mutableStateOf(false) }

    /** A sealed chat opens itself: the conversation, not the picker, is the result of the tap. */
    LaunchedEffect(sealedNoteId) {
        val id = sealedNoteId ?: return@LaunchedEffect
        viewModel.noteOpened()
        onOpenNote(id)
    }

    /** A chat that landed in the vault's own tables opens its own conversation, not a note. */
    LaunchedEffect(openedChatId) {
        val id = openedChatId ?: return@LaunchedEffect
        viewModel.chatOpened()
        onOpenChat(id)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importFromUri(uri)
    }

    /**
     * The way back out. `CreateDocument` is the only contract that lets this app write into the
     * owner's own Downloads: it names the file, the system picks the place, and Secure Wallet
     * streams the bytes into whatever URI comes back — or nothing, if the owner walks away.
     */
    val saveChatText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exportViewModel.finishExport(uri)
    }
    val saveChatZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportViewModel.finishExport(uri)
    }

    /** One staged export raises exactly one save dialog, then the request id moves on. */
    LaunchedEffect(exportState.requestId) {
        val ready = exportState.ready ?: return@LaunchedEffect
        if (ready.mimeType.contains("zip")) saveChatZip.launch(ready.displayName) else saveChatText.launch(ready.displayName)
    }

    /** "Send to another app" needs no folder and no save step: the chooser takes it from cache. */
    LaunchedEffect(exportState.share) {
        val share = exportState.share ?: return@LaunchedEffect
        exportViewModel.shareHandled()
        openExported(
            context = context,
            file = java.io.File(share.path),
            mimeType = share.mimeType,
            displayName = share.displayName,
            share = true
        )
    }

    /**
     * Kept open while WhatsApp is in the foreground: when the user comes back the
     * result of the jump and the export steps are still on screen next to the picker.
     */
    var whatsAppResult by remember { mutableStateOf<WhatsAppLaunch?>(null) }

    /** The main path in: one chat file, picked off this phone's own storage. */
    val chatFilePicker = rememberLauncherForActivityResult(DownloadsPicker()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uri.keepReadable(context)
        viewModel.importWhatsApp(uri)
    }

    /**
     * Still no grant needed: several chats or media files, multi-selected from the same
     * folder. The WhatsApp tree grant below is the fallback for reading the app's own
     * directory, which importing an export never requires.
     */
    val manyFilesPicker = rememberLauncherForActivityResult(DownloadsManyPicker()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { it.keepReadable(context) }
        viewModel.importWhatsAppPicks(uris)
    }

    /** The one grant that makes the whole folder readable: a tree, remembered across restarts. */
    val whatsAppTreeGrant = rememberLauncherForActivityResult(WhatsAppTreeGrant()) { uri ->
        if (uri != null) viewModel.attachWhatsAppFolder(uri)
        else viewModel.refreshWhatsAppHub()
    }

    val whatsAppHub by viewModel.whatsAppState.collectAsStateWithLifecycle()

    /** Opening the dialog is the refresh: exports appear the moment a chat is exported. */
    LaunchedEffect(chatImportOpen) {
        if (chatImportOpen) viewModel.refreshWhatsAppHub()
    }

    /**
     * The document picker is the entry point that can never fail: WhatsApp exports
     * arrive as .txt or .zip, and their MIME type is reported inconsistently across
     * OEM file managers, so the filter stays open and the importer sniffs the bytes.
     */
    val requestChatFile: () -> Unit = {
        chatImportOpen = false
        chatFilePicker.launch(arrayOf("*/*"))
    }

    val requestManyFiles: () -> Unit = {
        chatImportOpen = false
        manyFilesPicker.launch(arrayOf("*/*"))
    }

    val whatsApps = remember(context) {
        WhatsAppPackages.launchable.filter { (id, _) -> isWhatsAppInstalled(context, id) }
    }

    FileUploadOverlay(
        visible = state.upload.active,
        title = state.upload.title,
        detail = state.upload.detail,
        percent = state.upload.percent,
        cloud = state.upload.cloud,
        failed = state.upload.failed,
        // A standing rule of this app: no progress surface without a way out of it.
        onStop = if (state.upload.active && !state.upload.failed) viewModel::cancelRunningOperation else null
    )

    WhatsAppImportDialog(
        visible = chatImportOpen,
        pending = pendingShares,
        launch = whatsAppResult,
        hub = whatsAppHub,
        installed = whatsApps,
        onOpenChat = { packageId ->
            whatsAppResult = openWhatsAppChat(context, packageId, "")
        },
        onGrantFolder = {
            chatImportOpen = false
            whatsAppTreeGrant.launch(null)
        },
        onRescan = viewModel::refreshWhatsAppHub,
        onForgetFolder = viewModel::forgetWhatsAppFolder,
        onImport = { mode, selector ->
            chatImportOpen = false
            viewModel.importFromWhatsAppHub(mode, selector)
        },
        onPickFile = requestChatFile,
        onPickMany = requestManyFiles,
        onImportShared = {
            chatImportOpen = false
            viewModel.importPendingShares()
        },
        onDiscardShared = viewModel::dismissPendingShares,
        onDiscardOne = viewModel::dismissPendingShare,
        onDismiss = {
            chatImportOpen = false
            whatsAppResult = null
        }
    )

    WhatsAppExportDialog(
        visible = chatExportOpen,
        chats = vaultChats,
        state = exportState,
        onSaveText = { id -> exportViewModel.beginExport(id, includeMedia = false, destination = ChatExportDestination.Save) },
        onSaveWithMedia = { id -> exportViewModel.beginExport(id, includeMedia = true, destination = ChatExportDestination.Save) },
        onSendToApp = { id -> exportViewModel.beginExport(id, includeMedia = true, destination = ChatExportDestination.Share) },
        onCancel = exportViewModel::cancelExport,
        onDismiss = {
            chatExportOpen = false
            exportViewModel.clearResult()
        }
    )

    GlassScaffold(
        title = "Files",
        subtitle = "${state.total} encrypted files",
        onBack = onBack,
        actions = {
            if (!state.decoy) {
                GlassIconButton(
                    icon = Glyph.mic,
                    contentDescription = "Voice memos",
                    onClick = onOpenVoice,
                    tint = NeonMint
                )
            }
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = NeonCyan
            )
        },
        fab = if (!state.decoy) {
            {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(NeonCyan, Color(0xFF00E5A0)))),
                    contentAlignment = Alignment.Center
                ) {
                    GlassIconButton(
                        icon = Glyph.add,
                        contentDescription = "Add file",
                        onClick = { picker.launch(arrayOf("*/*")) },
                        tint = Color(0xFF071018),
                        background = Color.Transparent,
                        size = 58.dp
                    )
                }
            }
        } else {
            null
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.decoy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = vaultGutter(), vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonAmber.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.lock,
                        contentDescription = null,
                        tint = NeonAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Decoy vault · demonstration files only",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            val shownError = actionError ?: state.error
            if (shownError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = vaultGutter(), vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonCoral.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shownError,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonCoral,
                        modifier = Modifier.weight(1f)
                    )
                    GlassIconButton(
                        icon = Glyph.close,
                        contentDescription = "Dismiss",
                        onClick = {
                            actionError = null
                            viewModel.clearError()
                        },
                        tint = NeonCoral,
                        background = Color.Transparent,
                        size = 24.dp
                    )
                }
            }
            if (!state.decoy) {
                if (importSummary != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = vaultGutter(), vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonMint.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ThemedIcon(
                            glyph = Glyph.chat,
                            contentDescription = null,
                            tint = NeonMint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = importSummary.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonMint,
                            modifier = Modifier.weight(1f),
                            lineHeight = 17.sp
                        )
                        GlassIconButton(
                            icon = Glyph.close,
                            contentDescription = "Dismiss",
                            onClick = viewModel::clearImportSummary,
                            tint = NeonMint,
                            background = Color.Transparent,
                            size = 24.dp
                        )
                    }
                }
                WhatsAppEntryCard(
                    pending = pendingShares,
                    chats = maxOf(storedChats.size, vaultChats.size),
                    onImport = { chatImportOpen = true },
                    onExport = { chatExportOpen = true },
                    onOpenChats = { onOpenChat(null) },
                    onSealPending = viewModel::importPendingShares,
                    onDiscardPending = viewModel::dismissPendingShares,
                    modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = vaultGutter(), vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FileToolTile(
                        icon = Glyph.mic,
                        accent = NeonMint,
                        title = "Audio & voice",
                        hint = "Encrypted memos",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenVoice
                    )
                    FileToolTile(
                        icon = Glyph.folderOpen,
                        accent = NeonCyan,
                        title = "Every file",
                        hint = "Docs, media, scans",
                        modifier = Modifier.weight(1f),
                        onClick = { picker.launch(arrayOf("*/*")) }
                    )
                }
            }
            if (!state.decoy) {
                DriveStorageCard(
                    drive = state.drive,
                    onRefresh = viewModel::refreshDriveStorage,
                    modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 4.dp)
                )
            }
            VaultSearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 6.dp),
                placeholder = "Search encrypted files"
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = vaultGutter(), vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf("ALL", "Docs", "Media", "Audio", "Scans")) { category ->
                    Pill(
                        label = category.lowercase().replaceFirstChar { it.uppercase() },
                        selected = state.category == category,
                        count = state.counts[category] ?: 0,
                        onClick = { viewModel.setCategory(category) }
                    )
                }
            }
            if (state.files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = if (state.query.isBlank()) Glyph.folder else Glyph.search,
                        title = if (state.query.isBlank()) "No encrypted files" else "No matching files",
                        message = if (state.query.isBlank()) {
                            "Import scans, PDFs or media. Every file is sealed into the private vault up to 2 GB per file; anything over 25 MB stays on this device only."
                        } else {
                            "Try a different file name or category."
                        },
                        actionLabel = if (state.query.isBlank() && !state.decoy) "Add a file" else null,
                        onAction = if (state.query.isBlank() && !state.decoy) {
                            { picker.launch(arrayOf("*/*")) }
                        } else {
                            null
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = vaultGutter(),
                        end = vaultGutter(),
                        top = 2.dp,
                        bottom = 120.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.files, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            decoy = state.decoy,
                            onOpen = {
                                if (state.decoy) {
                                    actionError = "Demonstration files are placeholders and have no content."
                                } else {
                                    onOpenFile(file.id)
                                }
                            },
                            onShare = {
                                viewModel.export(file.id) { exported ->
                                    if (exported == null) {
                                        actionError = "The file could not be decrypted."
                                        return@export
                                    }
                                    openExported(context, exported, file.mimeType, file.name, share = true)
                                }
                            },
                            onDelete = { pendingDelete = file }
                        )
                    }
                }
            }
        }
    }

    ConfirmDialog(
        visible = pendingDelete != null,
        title = "Delete this file?",
        message = "The encrypted file will be permanently removed from the phone vault.",
        confirmLabel = "Delete",
        onConfirm = {
            pendingDelete?.let { viewModel.delete(it.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )
}

@Composable
private fun FileToolTile(
    icon: Glyph,
    accent: Color,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = icon,
                contentDescription = null,
                accent = accent,
                size = 34.dp,
                iconSize = 18.dp
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DriveStorageCard(
    drive: DriveStorageState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val quota = drive.quota
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = Glyph.cloud,
                contentDescription = null,
                accent = NeonCyan,
                size = 34.dp,
                iconSize = 18.dp
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Google Drive storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        drive.loading -> "Checking your account…"
                        quota == null -> drive.error ?: "Storage is not available yet."
                        quota.unlimited -> "Unlimited plan · ${formatBytes(quota.usedBytes)} used"
                        else -> "${formatBytes(quota.availableBytes)} free · ${formatBytes(quota.usedBytes)} of ${formatBytes(quota.limitBytes)} used"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (quota == null && !drive.loading) NeonAmber else TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (quota != null && !quota.unlimited) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AbyssElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(quota.usedFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (quota.usedFraction > 0.9f) NeonCoral else NeonCyan
                                )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "This vault holds ${formatBytes(drive.vaultBytes)} · up to 2 GB per file, files over 25 MB stay on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2
                    )
                }
            }
            GlassIconButton(
                icon = Glyph.refresh,
                contentDescription = "Refresh Drive storage",
                onClick = onRefresh,
                tint = NeonCyan
            )
        }
    }
}

@Composable
private fun FileRow(
    file: VaultFile,
    decoy: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = iconFor(file.mimeType)
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeonCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = icon,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(file.size)} · ${file.category} · ${formatShortDate(file.uploadedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val chatId = WhatsAppChatUnit.idFromContainer(file.folder)
                if (chatId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatGlyph(
                            chatId = chatId,
                            size = 11.dp,
                            tint = WhatsAppGreen,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Kept in its own chat module",
                            style = MaterialTheme.typography.labelSmall,
                            color = WhatsAppGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!decoy) {
                GlassIconButton(
                    icon = Glyph.share,
                    contentDescription = "Share file",
                    onClick = onShare,
                    tint = NeonCyan,
                    background = Color.Transparent,
                    size = 32.dp
                )
                GlassIconButton(
                    icon = Glyph.delete,
                    contentDescription = "Delete file",
                    onClick = onDelete,
                    tint = NeonCoral,
                    background = Color.Transparent,
                    size = 32.dp
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun iconFor(mime: String): Glyph = when {
    mime.startsWith("image/") -> Glyph.image
    mime.startsWith("audio/") -> Glyph.musicNote
    mime.startsWith("video/") -> Glyph.videocam
    mime == "application/pdf" -> Glyph.pictureAsPdf
    mime.startsWith("text/") -> Glyph.description
    else -> Glyph.insertDriveFile
}

private sealed interface WhatsAppLaunch {
    /** [appName] is what actually came to the front, so the dialog can name it. */
    data class Opened(val appName: String) : WhatsAppLaunch

    /** Neither package resolves on this handset. */
    data object NotInstalled : WhatsAppLaunch

    /** A WhatsApp package exists but the OS refused the jump (work profile, OEM lock). */
    data object Blocked : WhatsAppLaunch
}

/**
 * Opens one conversation rather than whatever chat WhatsApp happened to leave on screen.
 *
 * `wa.me` is the only address WhatsApp accepts from outside: the launcher intent restores the
 * last chat, which is why "Open WhatsApp" never looked like a way to choose one. With a number
 * the named chat comes forward; blank hands the choice to the user inside WhatsApp. Detection
 * goes through `getPackageInfo`, not the launch intent — under Android 11+ package visibility an
 * app missing from the manifest `<queries>` list reports as absent, and reading that as "not
 * installed" is what made WhatsApp Business look like a dead button.
 */
private fun openWhatsAppChat(context: Context, packageId: String, rawNumber: String): WhatsAppLaunch {
    if (!isWhatsAppInstalled(context, packageId)) return WhatsAppLaunch.NotInstalled
    val label = WhatsAppChat.folderName(packageId)
    val number = WhatsAppChat.normalizeNumber(rawNumber)
    val intent = if (number != null) {
        Intent(Intent.ACTION_VIEW, Uri.parse(WhatsAppChat.deepLink(number))).setPackage(packageId)
    } else {
        runCatching { context.packageManager.getLaunchIntentForPackage(packageId) }.getOrNull()
            ?: Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details=$packageId")
            )
    }
    val opened = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    return if (opened) WhatsAppLaunch.Opened(label) else WhatsAppLaunch.Blocked
}

private fun isWhatsAppInstalled(context: Context, packageId: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(packageId, 0) }.isSuccess

/**
 * Lands the folder grant on WhatsApp's own directory instead of the root of internal storage,
 * where the user would have to find it by hand. Some file managers ignore the hint; the grant
 * still works when they do.
 */
private class WhatsAppTreeGrant : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        // Business-only phones have no com.whatsapp folder, so the hint has to name the package
        // that is actually installed or the picker opens somewhere with nothing to grant.
        val packageId = WhatsAppPackages.launchable
            .firstOrNull { (id, _) -> isWhatsAppInstalled(context, id) }
            ?.first
            ?: WhatsAppPackages.MAIN
        return super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse(WhatsAppTree.initialTreeUri(WhatsAppTree.rootRelativePath(packageId)))
        )
    }
}

/**
 * Where a WhatsApp export actually lands: this phone's Downloads. Importing a chat that is
 * already on the device must not route through WhatsApp or through its private folder, so both
 * file pickers open standing there. The hint has to be a *documents* URI — the picker is a
 * DocumentsUI, and `content://media/external/downloads` is a content URI it cannot resolve, so
 * it would silently fall back to "Recent". The external-storage document id for the shared
 * Download volume is what every documents provider understands; one that ignores the hint simply
 * opens where it always did.
 */
private fun downloadsHint(): Uri? = runCatching {
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Download"
    )
}.getOrNull()

/** A chat import reads the file long after the picker closes, so the read grant must persist. */
private fun Uri.keepReadable(context: Context) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            this,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private class DownloadsPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        withDownloadsHint(super.createIntent(context, input)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
}

private class DownloadsManyPicker : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        withDownloadsHint(super.createIntent(context, input)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
}

private fun withDownloadsHint(intent: Intent): Intent {
    val hint = downloadsHint() ?: return intent
    return intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint)
}

@Composable
private fun WhatsAppImportDialog(
    visible: Boolean,
    pending: List<com.securevault.pro.data.imports.SharedInbox.Pending>,
    launch: WhatsAppLaunch?,
    hub: WhatsAppHubState,
    installed: List<Pair<String, String>>,
    onOpenChat: (String) -> Unit,
    onGrantFolder: () -> Unit,
    onRescan: () -> Unit,
    onForgetFolder: () -> Unit,
    onImport: (WhatsAppImport, String) -> Unit,
    onPickFile: () -> Unit,
    onPickMany: () -> Unit,
    onImportShared: () -> Unit,
    onDiscardShared: () -> Unit,
    onDiscardOne: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    // The folder list is the import; folding it away was why the first tap looked empty on a phone
    // with no grant yet.
    var showFolder by remember { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.widthIn(max = 460.dp),
                accent = WhatsAppGreen,
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 660.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Import WhatsApp chat",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "One tap: WhatsApp opens, and the export you share back seals itself.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                lineHeight = 17.sp
                            )
                        }
                        GlassIconButton(
                            icon = Glyph.close,
                            contentDescription = "Close",
                            onClick = onDismiss,
                            tint = TextSecondary
                        )
                    }

                    if (pending.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        PendingShareList(
                            pending = pending,
                            enabled = true,
                            onSeal = onImportShared,
                            onDiscardOne = onDiscardOne
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradientButton(
                                text = if (pending.size == 1) {
                                    "Seal this chat"
                                } else {
                                    "Seal ${pending.size} chats"
                                },
                                icon = Glyph.lock,
                                modifier = Modifier.weight(1f),
                                onClick = onImportShared
                            )
                            GhostButton(
                                text = "Discard",
                                icon = Glyph.delete,
                                accent = NeonCoral,
                                onClick = onDiscardShared
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    if (installed.isNotEmpty()) {
                        installed.forEach { (packageId, name) ->
                            GradientButton(
                                text = "Open $name",
                                icon = Glyph.openInNew,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenChat(packageId) }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    } else {
                        WhatsAppNote(
                            icon = Glyph.info,
                            tint = NeonAmber,
                            text = "WhatsApp is not installed on this phone."
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    val notice = when (launch) {
                        is WhatsAppLaunch.Opened ->
                            "${launch.appName} is open. Pick the chat and share the export back here — " +
                                "it seals itself."
                        WhatsAppLaunch.NotInstalled ->
                            "WhatsApp could not be opened. Pick the exported file instead."
                        WhatsAppLaunch.Blocked ->
                            "This phone blocked the hand-off to WhatsApp. Pick the exported file instead."
                        null -> null
                    }
                    if (notice != null) {
                        Spacer(Modifier.height(8.dp))
                        WhatsAppNote(
                            icon = if (launch is WhatsAppLaunch.Opened) Glyph.chat else Glyph.errorOutline,
                            tint = if (launch is WhatsAppLaunch.Opened) WhatsAppGreen else NeonCoral,
                            text = notice
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(
                            text = "Pick the file",
                            icon = Glyph.download,
                            modifier = Modifier.weight(1f),
                            onClick = onPickFile
                        )
                        GhostButton(
                            text = "Pick several",
                            icon = Glyph.folderOpen,
                            modifier = Modifier.weight(1f),
                            onClick = onPickMany
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    GhostButton(
                        text = "WhatsApp folder",
                        icon = if (showFolder) Glyph.expandLess else Glyph.expandMore,
                        onClick = { showFolder = !showFolder }
                    )
                    if (showFolder) {
                        Spacer(Modifier.height(10.dp))
                        if (!hub.granted) {
                            WhatsAppNote(
                                icon = Glyph.folderOpen,
                                tint = WhatsAppGreen,
                                text = when {
                                    hub.scanning -> "Looking inside WhatsApp's own folders on this phone…"
                                    hub.error != null -> hub.error.orEmpty()
                                    else -> "Allow one folder to read exports WhatsApp already saved on this phone."
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            GradientButton(
                                text = "Allow folder",
                                icon = Glyph.folder,
                                onClick = onGrantFolder
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .overlayPanel(alpha = 0.06f)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ThemedIcon(
                                    glyph = Glyph.folderOpen,
                                    contentDescription = null,
                                    tint = WhatsAppGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Reading ${hub.app} / ${hub.folder}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            hub.scanning -> "Counting the exports…"
                                            hub.foundAnything -> buildString {
                                                append("${hub.chats.size} chat")
                                                append(if (hub.chats.size == 1) "" else "s")
                                                append(" · ${hub.chatFiles} export")
                                                append(if (hub.chatFiles == 1) "" else "s")
                                                append(" · ${hub.mediaFiles} file")
                                                append(if (hub.mediaFiles == 1) "" else "s")
                                                if (hub.unreadable > 0) {
                                                    append(" · ${hub.unreadable} backup skipped")
                                                }
                                                if (hub.capped) append(" · list trimmed to the newest")
                                            }
                                            else -> "No exported chats yet"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        lineHeight = 16.sp
                                    )
                                }
                                GlassIconButton(
                                    icon = Glyph.refresh,
                                    contentDescription = "Read the folder again",
                                    onClick = onRescan,
                                    tint = WhatsAppGreen
                                )
                            }
                            if (hub.error != null) {
                                Spacer(Modifier.height(8.dp))
                                WhatsAppNote(
                                    icon = Glyph.errorOutline,
                                    tint = NeonCoral,
                                    text = hub.error.orEmpty()
                                )
                            }
                            if (hub.foundAnything) {
                                Spacer(Modifier.height(10.dp))
                                GradientButton(
                                    text = if (hub.chats.size > 1) {
                                        "Archive all ${hub.chats.size} chats"
                                    } else {
                                        "Archive the exported chat"
                                    },
                                    icon = Glyph.lock,
                                    enabled = !hub.scanning,
                                    onClick = { onImport(WhatsAppImport.AllChats, "") }
                                )
                                Spacer(Modifier.height(6.dp))
                                GhostButton(
                                    text = "Archive chats and every file",
                                    icon = Glyph.add,
                                    enabled = !hub.scanning,
                                    accent = WhatsAppGreen,
                                    onClick = { onImport(WhatsAppImport.Everything, "") }
                                )
                                if (hub.chats.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "Archive one chat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    hub.chats.take(10).forEach { row ->
                                        WhatsAppRow(
                                            icon = ChatGlyphBoard.glyphFor(row.title, row.latest.name),
                                            tint = TextSecondary,
                                            title = row.title,
                                            detail = "${formatBytes(row.latest.size)} export · " +
                                                formatShortDate(row.latest.modifiedAt) +
                                                if (row.docs.size > 1) {
                                                    " · newest of ${row.docs.size}"
                                                } else {
                                                    ""
                                                } + " · its files come too",
                                            enabled = !hub.scanning,
                                            onClick = { onImport(WhatsAppImport.OneChat, row.key) }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                                if (hub.media.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Archive a media folder",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    WhatsAppRow(
                                        icon = Glyph.add,
                                        tint = WhatsAppGreen,
                                        title = "Archive every WhatsApp file",
                                        detail = "${hub.mediaFiles} file" +
                                            "${if (hub.mediaFiles == 1) "" else "s"} · " +
                                            "${hub.media.size} folder${if (hub.media.size == 1) "" else "s"}",
                                        enabled = !hub.scanning,
                                        onClick = { onImport(WhatsAppImport.AllMedia, "") }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    hub.media.take(8).forEach { folder ->
                                        WhatsAppRow(
                                            icon = when {
                                                folder.folder.contains("Video", true) -> Glyph.videocam
                                                folder.folder.contains("Voice", true) ||
                                                    folder.folder.contains("Audio", true) -> Glyph.mic
                                                folder.folder.contains("Image", true) ||
                                                    folder.folder.contains("Picture", true) -> Glyph.image
                                                else -> Glyph.description
                                            },
                                            tint = TextSecondary,
                                            title = "Archive all ${folder.folder}",
                                            detail = "${folder.docs.size} file" +
                                                "${if (folder.docs.size == 1) "" else "s"} · " +
                                                formatBytes(folder.bytes),
                                            enabled = !hub.scanning,
                                            onClick = { onImport(WhatsAppImport.ChatFiles, folder.folder) }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            } else if (!hub.scanning) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "No exported chats in that folder yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            if (!hub.local) {
                                GhostButton(
                                    text = "Stop reading that folder",
                                    icon = Glyph.delete,
                                    accent = NeonCoral,
                                    onClick = onForgetFolder
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        text = "Close",
                        icon = Glyph.close,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

/** One tappable line of the hub: a chat, a media folder, or a file shared into the app. */
@Composable
private fun WhatsAppRow(
    icon: Glyph,
    tint: Color,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AbyssElevated.copy(alpha = if (enabled) 0.72f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) {
            Spacer(Modifier.width(8.dp))
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun WhatsAppNote(icon: Glyph, tint: Color, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .overlayPanel(alpha = 0.06f)
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun openExported(
    context: Context,
    file: java.io.File,
    mimeType: String,
    displayName: String,
    share: Boolean
) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(
            Intent.createChooser(intent, displayName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
