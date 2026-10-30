package com.securevault.pro.presentation.screens.notes

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.imports.WhatsAppTranscript
import com.securevault.pro.presentation.screens.files.ChatExportDestination
import com.securevault.pro.presentation.screens.files.WhatsAppExportViewModel
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.NoteColors
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.MarkdownText
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.WhatsAppChatView
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.noteAccent
import com.securevault.pro.presentation.components.noteTint
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewMode by remember { mutableStateOf(false) }
    var chatMode by remember { mutableStateOf(false) }
    var chatChosen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }

    // An archived chat has to be able to leave the vault as well as arrive in it, and the export
    // engine lives with the WhatsApp module rather than being written twice.
    val exportViewModel: WhatsAppExportViewModel = hiltViewModel()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()

    val saveChatText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exportViewModel.finishExport(uri)
    }
    val saveChatZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportViewModel.finishExport(uri)
    }
    LaunchedEffect(exportState.requestId) {
        val ready = exportState.ready ?: return@LaunchedEffect
        if (ready.mimeType.contains("zip")) saveChatZip.launch(ready.displayName) else saveChatText.launch(ready.displayName)
    }
    LaunchedEffect(exportState.share) {
        val share = exportState.share ?: return@LaunchedEffect
        exportViewModel.shareHandled()
        shareExportedChat(context, java.io.File(share.path), share.mimeType, share.displayName)
    }

    /**
     * An archived WhatsApp export is a conversation, so it gets the conversation's own viewer.
     * Parsed off the main thread: a transcript can run to hundreds of thousands of lines, and
     * doing it inside composition freezes the editor before it draws its first frame.
     */
    var chatMessages by remember(state.content) {
        mutableStateOf<List<WhatsAppTranscript.ChatMessage>>(emptyList())
    }
    LaunchedEffect(state.content) {
        val content = state.content
        chatMessages = withContext(Dispatchers.Default) {
            if (WhatsAppTranscript.isChat(content)) {
                WhatsAppTranscript.messages(WhatsAppTranscript.transcriptOf(content))
            } else {
                emptyList()
            }
        }
    }
    LaunchedEffect(state.loading, chatMessages.isEmpty()) {
        if (!state.loading && chatMessages.isNotEmpty() && !chatChosen) chatMode = true
    }

    val openAttachment: (NoteAttachment) -> Unit = { attachment ->
        viewModel.exportAttachment(attachment.id) { file ->
            if (file == null) {
                attachmentError = "The attachment could not be decrypted."
                return@exportAttachment
            }
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, attachment.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(intent, attachment.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                attachmentError = "No app can open this file type."
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = queryDisplayName(context, uri, "attachment")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = readDocumentBytes(context, uri)
                viewModel.addAttachment(name, mime, bytes)
            }.onFailure { failure ->
                attachmentError = failure.message ?: "The attachment could not be read."
            }
        }
    }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onSaved()
    }

    GlassScaffold(
        title = if (state.isNew) "New note" else "Edit note",
        subtitle = when {
            state.decoy -> "Decoy vault · read only"
            chatMessages.isNotEmpty() -> "WhatsApp clone · ${chatMessages.size} messages"
            else -> "Markdown · encrypted attachments"
        },
        onBack = onBack,
        actions = {
            if (!state.isNew && !state.decoy) {
                GlassIconButton(
                    icon = Glyph.delete,
                    contentDescription = "Delete note",
                    onClick = { confirmDelete = true },
                    tint = VaultColor.debit
                )
            }
        }
    ) { padding ->
        if (state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = VaultColor.accent)
            }
            return@GlassScaffold
        }
        if (chatMode && chatMessages.isNotEmpty()) {
            val media = chatMessages.sumOf { it.files.size }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(
                        label = "Chat",
                        selected = true,
                        onClick = { chatChosen = true }
                    )
                    Pill(
                        label = "Write",
                        selected = false,
                        onClick = {
                            chatChosen = true
                            chatMode = false
                            previewMode = false
                        }
                    )
                    Pill(
                        label = "Preview",
                        selected = false,
                        onClick = {
                            chatChosen = true
                            chatMode = false
                            previewMode = true
                        }
                    )
                }
                Column {
                    Text(
                        text = state.title.ifBlank { "WhatsApp chat" },
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${chatMessages.size} messages · $media media · archived read-only " +
                            "from the export",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
                // The way back out, from inside the conversation itself: a chat you can only read
                // on this phone is a chat you have not really saved.
                if (!state.decoy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GhostButton(
                            text = if (exportState.busy) "Exporting…" else "Save .txt",
                            icon = Glyph.description,
                            enabled = !exportState.busy && !state.isNew,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                exportViewModel.beginExport(
                                    exportViewModel.routedNoteId,
                                    includeMedia = false,
                                    destination = ChatExportDestination.Save
                                )
                            }
                        )
                        GhostButton(
                            text = if (media > 0) "Save .zip" else "Save .txt (no media)",
                            icon = Glyph.attachFile,
                            enabled = !exportState.busy && !state.isNew,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                exportViewModel.beginExport(
                                    exportViewModel.routedNoteId,
                                    includeMedia = media > 0,
                                    destination = ChatExportDestination.Save
                                )
                            }
                        )
                        GlassIconButton(
                            icon = Glyph.openInNew,
                            contentDescription = "Send this chat to another app",
                            onClick = {
                                exportViewModel.beginExport(
                                    exportViewModel.routedNoteId,
                                    includeMedia = media > 0,
                                    destination = ChatExportDestination.Share
                                )
                            },
                            tint = VaultColor.credit,
                            background = Color.Transparent,
                            size = 40.dp
                        )
                    }
                    val exportLine = when {
                        exportState.busy -> exportState.stage.ifBlank { "Preparing the export…" }
                        exportState.error != null -> exportState.error
                        exportState.result != null -> exportState.result
                        else -> null
                    }
                    if (exportLine != null) {
                        Text(
                            text = exportLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (exportState.error != null) VaultColor.debit else VaultColor.textFaint,
                            lineHeight = 16.sp
                        )
                    }
                }
                WhatsAppChatView(
                    messages = chatMessages,
                    attachments = state.attachments,
                    onOpenAttachment = openAttachment,
                    resolveFile = { attachment -> viewModel.attachmentFile(attachment.id) },
                    modifier = Modifier
                        .weight(1f)
                        .clip(VaultShapes.card)
                )
            }
            return@GlassScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            VaultTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = "Title",
                placeholder = "Untitled note",
                leadingIcon = Glyph.description,
                imeAction = ImeAction.Next
            )
            if (state.sealed) {
                Text(
                    text = "This note was sealed on another device and its content cannot be decrypted here. Typing replaces it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    label = "Write",
                    selected = !previewMode,
                    onClick = { previewMode = false }
                )
                Pill(
                    label = "Preview",
                    selected = previewMode,
                    onClick = { previewMode = true }
                )
                if (chatMessages.isNotEmpty()) {
                    Pill(
                        label = "Chat",
                        selected = false,
                        onClick = {
                            chatChosen = true
                            chatMode = true
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                ThemedIcon(
                    glyph = Glyph.pushPin,
                    contentDescription = null,
                    tint = if (state.isPinned) noteAccent(state.color) else VaultColor.textFaint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = state.isPinned,
                    onCheckedChange = viewModel::setPinned,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = noteAccent(state.color),
                        checkedTrackColor = noteAccent(state.color).copy(alpha = 0.3f),
                        uncheckedThumbColor = VaultColor.textFaint,
                        uncheckedTrackColor = VaultColor.elevated
                    )
                )
            }

            if (previewMode) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = noteAccent(state.color),
                    borderColor = noteAccent(state.color).copy(alpha = 0.25f)
                ) {
                    if (state.content.isBlank()) {
                        Text(
                            text = "Nothing to preview yet. Switch to Write and add some Markdown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textFaint
                        )
                    } else {
                        MarkdownText(content = state.content)
                    }
                }
            } else {
                VaultTextField(
                    value = state.content,
                    onValueChange = viewModel::setContent,
                    label = "Note",
                    placeholder = "# Heading\n\nWrite anything. **Bold**, _italic_, `code`, lists and links render in preview.",
                    singleLine = false,
                    minLines = 12,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    imeAction = ImeAction.Default
                )
            }

            SectionHeader(title = "Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NoteColors.forEach { color ->
                    val selected = state.color == color
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(noteTint(color))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) noteAccent(color) else noteAccent(color).copy(alpha = 0.25f),
                                shape = CircleShape
                            )
                            .clickable { viewModel.setColor(color) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(noteAccent(color))
                        )
                    }
                }
            }

            SectionHeader(title = "Tags")
            VaultTextField(
                value = state.tagsInput,
                onValueChange = viewModel::setTags,
                label = "Tags",
                placeholder = "recovery, travel, ideas",
                supportingText = "Separate tags with commas",
                imeAction = ImeAction.Done
            )

            SectionHeader(
                title = "Attachments (${state.attachments.size})",
                actionLabel = if (!state.decoy && state.attachments.size < 10) "Attach" else null,
                onAction = if (!state.decoy && state.attachments.size < 10) {
                    { picker.launch(arrayOf("*/*")) }
                } else {
                    null
                }
            )
            if (state.attachments.isEmpty()) {
                Text(
                    text = "Attachments are encrypted into the file vault and linked to this note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textFaint,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            val shownAttachments = if (chatMessages.isEmpty()) {
                state.attachments
            } else {
                state.attachments.take(12)
            }
            shownAttachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    onOpen = { openAttachment(attachment) },
                    onRemove = { viewModel.removeAttachment(attachment) },
                    canRemove = !state.decoy
                )
            }
            if (state.attachments.size > shownAttachments.size) {
                Text(
                    text = "${state.attachments.size - shownAttachments.size} more files from this chat " +
                        "are sealed in the vault — every one of them opens from its bubble in Chat.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (state.uploading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = VaultColor.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Encrypting attachment…",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textBody
                    )
                }
            }
            GhostButton(
                text = "Attach an encrypted file",
                icon = Glyph.attachFile,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.decoy && state.attachments.size < 10,
                onClick = { picker.launch(arrayOf("*/*")) }
            )

            val shownError = attachmentError ?: state.error
            if (shownError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.input)
                        .background(VaultColor.debit.copy(alpha = 0.1f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shownError,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.debit,
                        modifier = Modifier.weight(1f)
                    )
                    GlassIconButton(
                        icon = Glyph.close,
                        contentDescription = "Dismiss error",
                        onClick = {
                            attachmentError = null
                            viewModel.clearError()
                        },
                        tint = VaultColor.debit,
                        background = Color.Transparent,
                        size = 28.dp
                    )
                }
            }

            GradientButton(
                text = if (state.isNew) "Seal note" else "Save note",
                icon = Glyph.description,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
                onClick = viewModel::save
            )
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "Delete this note?",
        message = "The note and its encrypted attachments will be permanently deleted.",
        confirmLabel = "Delete",
        onConfirm = {
            confirmDelete = false
            viewModel.delete()
        },
        onDismiss = { confirmDelete = false }
    )
}

@Composable
private fun AttachmentRow(
    attachment: NoteAttachment,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = Glyph.attachFile,
                contentDescription = null,
                accent = VaultColor.accent,
                size = 36.dp,
                iconSize = 18.dp,
                background = VaultColor.accent.copy(alpha = 0.1f)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(attachment.size)} · ${attachment.mimeType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GlassIconButton(
                icon = Glyph.openInNew,
                contentDescription = "Open attachment",
                onClick = onOpen,
                tint = VaultColor.textBody,
                background = Color.Transparent,
                size = 32.dp
            )
            if (canRemove) {
                GlassIconButton(
                    icon = Glyph.close,
                    contentDescription = "Remove attachment",
                    onClick = onRemove,
                    tint = VaultColor.debit,
                    background = Color.Transparent,
                    size = 32.dp
                )
            }
        }
    }
}

/**
 * Hands a freshly written export to another app.
 *
 * The copy that arrives here is already in `secure_share/`, the one cache folder the
 * FileProvider is configured to serve, so no new manifest path is needed and nothing else in
 * private storage becomes visible by accident.
 */
private fun shareExportedChat(context: android.content.Context, file: java.io.File, mimeType: String, displayName: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, displayName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, displayName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        // No chooser, no crash: the save route is still there and says so in its own line.
        android.widget.Toast.makeText(
            context,
            "This phone refused to hand the chat to another app — use \"Save .txt\" instead.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
