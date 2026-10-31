package com.securevault.pro.presentation.screens.email

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.email.MailFolder
import com.securevault.pro.data.email.MailMessage
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.email.repository.MailFolderNames
import com.securevault.pro.data.email.repository.MailPartHandle
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.theme.VaultColor

/**
 * The built-in mail client. One route, five screens, all driven by [EmailViewModel]'s immutable
 * snapshot so the section can be left from any step without leaving a socket half-read.
 */
@Composable
fun EmailSection(onBack: () -> Unit, viewModel: EmailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val googleSignIn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // A backed-out picker is a RESULT_CANCELED with no data. Saying so here keeps the promise
        // that a cancelled sign-in reports itself instead of looking like a provider failure.
        viewModel.completeGoogleSignIn(
            if (result.resultCode == android.app.Activity.RESULT_OK) result.data else null
        )
    }
    val pickAttachment = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::stageAttachment) }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    val title = when (state.view) {
        EmailView.ACCOUNTS -> "Mail"
        EmailView.ADD_ACCOUNT -> "Add a mailbox"
        EmailView.MAILBOX -> state.openAccount?.address ?: "Mailbox"
        EmailView.MESSAGE -> state.opened?.message?.subject?.ifBlank { "Message" } ?: "Message"
        EmailView.COMPOSE -> "New message"
    }
    val subtitle = when (state.view) {
        EmailView.ACCOUNTS -> "IMAP and SMTP, with the password sealed in the vault"
        EmailView.ADD_ACCOUNT -> "Gmail in one tap, or any host by hand"
        EmailView.MAILBOX -> state.selection.currentFolder?.let { EmailCopy.folderLabel(it) }
        EmailView.MESSAGE -> state.opened?.let { it.message.fromAddress }
        EmailView.COMPOSE -> "Text message, sent through your own server"
    }

    GlassScaffold(
        title = title,
        subtitle = subtitle,
        onBack = when (state.view) {
            EmailView.ACCOUNTS -> onBack
            EmailView.ADD_ACCOUNT -> viewModel::leaveAdd
            EmailView.MAILBOX -> viewModel::leaveMailbox
            EmailView.MESSAGE -> viewModel::closeMessage
            EmailView.COMPOSE -> viewModel::leaveCompose
        },
        snackbarHostState = snackbar,
        actions = {
            if (state.view == EmailView.MAILBOX) {
                com.securevault.pro.presentation.components.GlassIconButton(
                    icon = Glyph.edit,
                    contentDescription = "Write a message",
                    onClick = viewModel::composeNew
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.view) {
                EmailView.ACCOUNTS -> AccountList(
                    state = state,
                    onAdd = viewModel::beginAdd,
                    onOpen = viewModel::openAccount,
                    onRemove = viewModel::remove,
                    onToggleAutoSync = viewModel::toggleAutoSync
                )
                EmailView.ADD_ACCOUNT -> AddAccount(
                    state = state,
                    onEditAddress = viewModel::editAddress,
                    onEditLabel = viewModel::editLabel,
                    onEditUsername = viewModel::editUsername,
                    onEditPassword = viewModel::editPassword,
                    onEditEndpoint = viewModel::editEndpoint,
                    onEditPort = viewModel::editPort,
                    onEditSecurity = viewModel::editSecurity,
                    onUseTemplate = viewModel::useTemplate,
                    onSave = viewModel::testAndSave,
                    googleUnavailable = viewModel.googleUnavailable(),
                    googleEmail = viewModel.googleLastEmail(),
                    onGoogle = { googleSignIn.launch(viewModel.signInIntent()) },
                    onManual = { viewModel.beginAdd(SetupMode.MANUAL) }
                )
                EmailView.MAILBOX -> Mailbox(
                    state = state,
                    onSelectFolder = viewModel::selectFolder,
                    onRefresh = viewModel::refreshFolder,
                    onLoadMore = viewModel::loadMore,
                    onToggle = viewModel::toggleSelection,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onOpen = viewModel::openMessage,
                    onSearch = viewModel::search,
                    onRunSearch = viewModel::runSearch,
                    onClearSearch = viewModel::clearSearch,
                    onMove = viewModel::moveSelected,
                    onDelete = viewModel::deleteSelected
                )
                EmailView.MESSAGE -> MessageReader(
                    state = state,
                    onReply = viewModel::replyTo,
                    onStar = viewModel::toggleStar,
                    onDownload = viewModel::downloadAttachment,
                    onOpenAttachment = viewModel::openAttachment,
                    onShareAttachment = viewModel::shareAttachment
                )
                EmailView.COMPOSE -> ComposeMessage(
                    state = state,
                    onTo = viewModel::editTo,
                    onCc = viewModel::editCc,
                    onBcc = viewModel::editBcc,
                    onSubject = viewModel::editSubject,
                    onBody = viewModel::editBody,
                    onAddAttachment = { pickAttachment.launch(arrayOf("*/*")) },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onSend = viewModel::send
                )
            }
            state.busy?.let { BusyPanel(it, viewModel::stopBusy) }
        }
    }

    state.problem?.let { problem ->
        AlertDialog(
            onDismissRequest = viewModel::dismissProblem,
            title = { Text(problem.headline) },
            text = {
                Column {
                    Text(problem.sentence)
                    if (problem.advice.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(problem.advice, color = VaultColor.textBody)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissProblem) { Text("Understood") } }
        )
    }
}

// ---------------------------------------------------------------------------------- accounts

@Composable
private fun AccountList(
    state: EmailViewModel.UiState,
    onAdd: (SetupMode) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleAutoSync: (String) -> Unit
) {
    if (state.rows.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            EmptyState(
                icon = Glyph.email,
                title = "No mailbox yet",
                message = "Add a Gmail account in one tap, or type the IMAP and SMTP servers of any " +
                    "other provider. The password is sealed in this vault and never leaves the device.",
                actionLabel = "Add a mailbox",
                onAction = { onAdd(SetupMode.CHOOSING) }
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.rows, key = { it.id }) { row ->
            GlassCard(onClick = { onOpen(row.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(VaultColor.accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedIcon(glyph = Glyph.email, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.address, style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody, maxLines = 1)
                    }
                    TagChip(text = row.providerLabel, accent = VaultColor.decorative)
                }
                Spacer(Modifier.height(10.dp))
                Text(row.unreadLabel, style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody)
                Text(
                    "${row.incomingLabel} · ${row.lastCheckedLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.lastError.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(row.lastError, style = MaterialTheme.typography.bodySmall, color = VaultColor.debitInk)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(text = row.state.label(), accent = VaultColor.textBody)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onToggleAutoSync(row.id) }) {
                        Text(if (row.autoSync) "Auto-check on" else "Auto-check off")
                    }
                    TextButton(onClick = { onRemove(row.id) }) { Text("Remove") }
                }
            }
        }
        item {
            GhostButton(
                text = "Add another mailbox",
                icon = Glyph.add,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onAdd(SetupMode.CHOOSING) }
            )
        }
    }
}

// ------------------------------------------------------------------------------ add account

@Composable
private fun AddAccount(
    state: EmailViewModel.UiState,
    onEditAddress: (String) -> Unit,
    onEditLabel: (String) -> Unit,
    onEditUsername: (String) -> Unit,
    onEditPassword: (String) -> Unit,
    onEditEndpoint: (EndpointSide, String) -> Unit,
    onEditPort: (EndpointSide, Int) -> Unit,
    onEditSecurity: (EndpointSide, SecurityMode) -> Unit,
    onUseTemplate: () -> Unit,
    onSave: () -> Unit,
    googleUnavailable: String?,
    googleEmail: String?,
    onGoogle: () -> Unit,
    onManual: () -> Unit
) {
    val draft = state.draft
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (draft.mode != SetupMode.MANUAL) {
            SectionHeader("One-tap Google sign-in")
            GlassCard {
                Text(
                    googleUnavailable ?: "Signs in with the Google account this phone already uses. " +
                        "Gmail needs its mail scope approved once, then the mailbox opens with no password typed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (googleUnavailable != null) VaultColor.debitInk else VaultColor.textBody
                )
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (googleEmail.isNullOrBlank()) "Sign in with Google" else "Continue as $googleEmail",
                    icon = Glyph.person,
                    enabled = googleUnavailable == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onGoogle
                )
            }
            SectionHeader("Any other provider")
            GlassCard {
                Text(
                    "Type the address, then the servers. Gmail, Outlook, Yahoo, iCloud and Titan " +
                        "fill themselves in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textBody
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    text = "Set up a server by hand",
                    icon = Glyph.settings,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onManual
                )
            }
            return@Column
        }

        SectionHeader("The account")
        VaultTextField(
            value = draft.address,
            onValueChange = onEditAddress,
            label = "Email address",
            placeholder = "you@example.com",
            leadingIcon = Glyph.email,
            keyboardType = KeyboardType.Email
        )
        VaultTextField(
            value = draft.label,
            onValueChange = onEditLabel,
            label = "Name on this device (optional)",
            placeholder = "Work, personal, …",
            leadingIcon = Glyph.person
        )
        if (draft.suggestion.isNotBlank()) {
            Text(draft.suggestion, style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody)
        }
        GhostButton(
            text = "Fill the servers from the address",
            icon = Glyph.autoFixHigh,
            modifier = Modifier.fillMaxWidth(),
            onClick = onUseTemplate
        )

        SectionHeader("Incoming (IMAP)")
        EndpointFields(
            side = EndpointSide.INCOMING,
            host = draft.incoming.host,
            port = draft.incoming.port,
            security = draft.incoming.security,
            onHost = onEditEndpoint,
            onPort = onEditPort,
            onSecurity = onEditSecurity
        )
        SectionHeader("Outgoing (SMTP)")
        EndpointFields(
            side = EndpointSide.OUTGOING,
            host = draft.outgoing.host,
            port = draft.outgoing.port,
            security = draft.outgoing.security,
            onHost = onEditEndpoint,
            onPort = onEditPort,
            onSecurity = onEditSecurity
        )

        SectionHeader("The login")
        VaultTextField(
            value = draft.username,
            onValueChange = onEditUsername,
            label = "Username (blank uses the address)",
            leadingIcon = Glyph.person
        )
        VaultTextField(
            value = draft.password,
            onValueChange = onEditPassword,
            label = "Password or app password",
            leadingIcon = Glyph.key,
            isPassword = true,
            supportingText = "Sealed into the vault the moment it is saved. Gmail and Outlook usually " +
                "need an app password, not the account password."
        )
        if (draft.providerNote.isNotBlank()) {
            Text(draft.providerNote, style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody)
        }

        GradientButton(
            text = "Test both servers, then save",
            icon = Glyph.check,
            // Always tappable while idle: an incomplete form answers with the first field that is
            // missing, which is more use than a dead button the owner cannot interrogate.
            enabled = state.busy?.key != MailOperation.VALIDATE,
            loading = state.busy?.key == MailOperation.VALIDATE,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSave
        )
        Text(
            "Nothing is saved until both servers answer. A failed test leaves the vault untouched.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EndpointFields(
    side: EndpointSide,
    host: String,
    port: Int,
    security: SecurityMode,
    onHost: (EndpointSide, String) -> Unit,
    onPort: (EndpointSide, Int) -> Unit,
    onSecurity: (EndpointSide, SecurityMode) -> Unit
) {
    VaultTextField(
        value = host,
        onValueChange = { onHost(side, it) },
        label = "Server host",
        placeholder = if (side == EndpointSide.INCOMING) "imap.example.com" else "smtp.example.com",
        leadingIcon = Glyph.cloud
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        VaultTextField(
            value = port.toString(),
            onValueChange = { text -> text.toIntOrNull()?.let { onPort(side, it) } },
            label = "Port",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        GhostButton(
            text = EmailCopy.securityLabel(security),
            icon = Glyph.lock,
            modifier = Modifier.weight(1f),
            onClick = {
                onSecurity(
                    side,
                    if (security == SecurityMode.SSL_TLS) SecurityMode.STARTTLS else SecurityMode.SSL_TLS
                )
            }
        )
    }
    Spacer(Modifier.height(8.dp))
}

// ----------------------------------------------------------------------------------- mailbox

@Composable
private fun Mailbox(
    state: EmailViewModel.UiState,
    onSelectFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpen: (Long) -> Unit,
    onSearch: (String) -> Unit,
    onRunSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit
) {
    val selection = state.selection
    var moveChooser by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selection.folders.forEach { folder ->
                FolderChip(
                    folder = folder,
                    selected = folder.wireName == selection.folderWireName,
                    onClick = { onSelectFolder(folder.wireName) }
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VaultTextField(
                value = selection.query,
                onValueChange = onSearch,
                label = "Search this folder",
                leadingIcon = Glyph.search,
                modifier = Modifier.weight(1f),
                onImeSubmit = onRunSearch
            )
            Spacer(Modifier.width(8.dp))
            com.securevault.pro.presentation.components.GlassIconButton(
                icon = Glyph.refresh,
                contentDescription = "Refresh this folder",
                onClick = onRefresh
            )
        }
        if (selection.isSearching) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Results for “${selection.query}”", style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearSearch) { Text("Clear") }
            }
        }
        if (selection.hasSelection) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TagChip(text = "${selection.selectedCount} selected", accent = VaultColor.accent)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSelectAll) {
                    Text(if (selection.allSelected) "None" else "All")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearSelection) { Text("Cancel") }
            }
        }
        if (selection.messages.isEmpty()) {
            EmptyState(
                icon = Glyph.folderOpen,
                title = "Nothing in this folder",
                message = "Refresh to ask the server again. This app keeps no message copy, so the list " +
                    "is always what the server has now."
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(selection.messages, key = { it.uid }) { message ->
                    MessageRow(
                        message = message,
                        selected = message.uid in selection.selectedUids,
                        onOpen = { onOpen(message.uid) },
                        onToggle = { onToggle(message.uid) }
                    )
                }
                if (selection.messages.size >= selection.limit && selection.limit < MailboxSelection.MAX_WINDOW) {
                    item {
                        GhostButton(
                            text = "Load older messages",
                            icon = Glyph.expandMore,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onLoadMore
                        )
                    }
                }
            }
        }
        if (selection.hasSelection) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostButton(
                    // The destination is the owner's choice: this used to move to whichever folder
                    // the server happened to list first, which on Gmail is Sent.
                    text = if (selection.moveTargets.isEmpty()) "No other folder" else "Move…",
                    icon = Glyph.folder,
                    enabled = selection.canMove,
                    modifier = Modifier.weight(1f),
                    onClick = { moveChooser = true }
                )
                if (!selection.canMove) {
                    // A single-folder mailbox has no move; say why rather than showing a dead chip.
                    Text(
                        "This server lists no other folder to move to.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        modifier = Modifier.weight(1f)
                    )
                }
                GhostButton(
                    text = "Delete",
                    icon = Glyph.delete,
                    accent = VaultColor.debitInk,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete
                )
            }
        }
        if (moveChooser && selection.canMove) {
            AlertDialog(
                onDismissRequest = { moveChooser = false },
                title = { Text("Move ${selection.selectedCount} to") },
                text = {
                    Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        selection.moveTargets.forEach { folder ->
                            TextButton(
                                onClick = {
                                    moveChooser = false
                                    onMove(folder.wireName)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(EmailCopy.folderLabel(folder))
                                    Text(
                                        folder.wireName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultColor.textFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { moveChooser = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun FolderChip(folder: MailFolder, selected: Boolean, onClick: () -> Unit) {
    TagChip(
        text = EmailCopy.folderLabel(folder) + if (folder.unseen > 0) " · ${folder.unseen}" else "",
        accent = if (selected) VaultColor.accent else VaultColor.textBody,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun MessageRow(
    message: MailMessage,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    GlassCard(
        selected = selected,
        accent = if (message.seen) VaultColor.textBody else VaultColor.accent,
        contentPadding = PaddingValues(14.dp),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(VaultColor.accent.copy(alpha = if (message.seen) 0.06f else 0.16f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = if (message.flagged) Glyph.star else Glyph.email,
                    contentDescription = if (message.flagged) "Starred" else "Message",
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    message.fromName.ifBlank { message.fromAddress },
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    message.subject.ifBlank { "(no subject)" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    EmailCopy.relativeTime(message.receivedAt, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
                if (message.hasAttachments) {
                    ThemedIcon(glyph = Glyph.attachFile, contentDescription = "Has attachments", modifier = Modifier.size(14.dp))
                }
            }
        }
        if (message.snippet.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                message.snippet,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
        }
    }
}

// ------------------------------------------------------------------------------------ reader

@Composable
private fun MessageReader(
    state: EmailViewModel.UiState,
    onReply: (MailMessage) -> Unit,
    onStar: (MailMessage) -> Unit,
    onDownload: (MailPartHandle) -> Unit,
    onOpenAttachment: (MailPartHandle) -> Unit,
    onShareAttachment: (MailPartHandle) -> Unit
) {
    val opened = state.opened ?: return
    val message = opened.message
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassCard {
            Text(message.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${message.fromName.ifBlank { message.fromAddress }} · ${message.fromAddress}",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            Text(
                EmailCopy.relativeTime(message.receivedAt, System.currentTimeMillis()) + " · " + opened.security,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Reply",
                    icon = Glyph.arrowBack,
                    modifier = Modifier.weight(1f),
                    onClick = { onReply(message) }
                )
                GhostButton(
                    text = if (message.flagged) "Starred" else "Star",
                    icon = if (message.flagged) Glyph.star else Glyph.starBorder,
                    modifier = Modifier.weight(1f),
                    onClick = { onStar(message) }
                )
            }
        }
        val bodyNotice = EmailCopy.bodyNotice(opened.truncated, opened.bodyWasHtml)
        if (bodyNotice.isNotBlank()) {
            Text(bodyNotice, style = MaterialTheme.typography.labelSmall, color = VaultColor.textFaint)
        }
        GlassCard {
            SelectionContainer {
                Text(
                    opened.body.ifBlank { "(This message has no readable text part.)" },
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
                )
            }
        }
        if (opened.parts.isNotEmpty()) {
            SectionHeader("Attachments")
            opened.parts.forEach { part ->
                GlassCard(contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(glyph = Glyph.attachFile, contentDescription = "Attachment", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(part.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${(part.sizeBytes / 1024).coerceAtLeast(1)} KB" + if (part.saved) " · saved" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
                        }
                        TextButton(onClick = { onDownload(part) }) {
                            Text(if (part.saved) "Again" else "Save")
                        }
                    }
                    if (part.saved) {
                        Spacer(Modifier.height(8.dp))
                        // Saving is not the end of the errand: the cached copy is only useful once
                        // the owner can read it or send it on, without leaving this screen first.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton(
                                text = "Open",
                                icon = Glyph.openInNew,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenAttachment(part) }
                            )
                            GhostButton(
                                text = "Share",
                                icon = Glyph.share,
                                modifier = Modifier.weight(1f),
                                onClick = { onShareAttachment(part) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ----------------------------------------------------------------------------------- compose

@Composable
private fun ComposeMessage(
    state: EmailViewModel.UiState,
    onTo: (String) -> Unit,
    onCc: (String) -> Unit,
    onBcc: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit
) {
    val draft = state.compose
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VaultTextField(
            value = draft.to,
            onValueChange = onTo,
            label = "To",
            placeholder = "name@example.com, other@example.com",
            leadingIcon = Glyph.person,
            keyboardType = KeyboardType.Email
        )
        VaultTextField(
            value = draft.cc,
            onValueChange = onCc,
            label = "Cc (optional)",
            leadingIcon = Glyph.person,
            keyboardType = KeyboardType.Email
        )
        VaultTextField(
            value = draft.bcc,
            onValueChange = onBcc,
            label = "Bcc (this build cannot send one)",
            leadingIcon = Glyph.lock,
            keyboardType = KeyboardType.Email
        )
        VaultTextField(
            value = draft.subject,
            onValueChange = onSubject,
            label = "Subject",
            leadingIcon = Glyph.email
        )
        VaultTextField(
            value = draft.body,
            onValueChange = onBody,
            label = "Message",
            singleLine = false,
            minLines = 8
        )
        if (draft.attachments.isNotEmpty()) {
            SectionHeader("Attached")
            draft.attachments.forEach { attachment ->
                GlassCard(contentPadding = PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(glyph = Glyph.attachFile, contentDescription = "Attachment", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemoveAttachment(attachment.path) }) { Text("Remove") }
                    }
                }
            }
        }
        GhostButton(
            text = "Attach a file",
            icon = Glyph.attachFile,
            modifier = Modifier.fillMaxWidth(),
            onClick = onAddAttachment
        )
        GradientButton(
            text = "Send",
            icon = Glyph.send,
            enabled = draft.canSend,
            loading = state.busy?.key == MailOperation.SEND,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSend
        )
        draft.problems().firstOrNull()?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = VaultColor.debitInk)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------------------ overlay

@Composable
private fun BusyPanel(work: BusyWork, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(VaultColor.scrim.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(Modifier.fillMaxWidth().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(work.detail, style = MaterialTheme.typography.bodyMedium)
                    Text(work.label, style = MaterialTheme.typography.labelSmall, color = VaultColor.textBody)
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(work.escape, style = MaterialTheme.typography.bodySmall, color = VaultColor.textBody)
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Stop",
                icon = Glyph.close,
                modifier = Modifier.fillMaxWidth(),
                onClick = onStop
            )
        }
    }
}
