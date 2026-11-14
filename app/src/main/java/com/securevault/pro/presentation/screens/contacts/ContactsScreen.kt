package com.securevault.pro.presentation.screens.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.securevault.pro.domain.model.ContactCategories
import com.securevault.pro.domain.model.SecureContact
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.MarkdownText
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.components.Glyph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onLock: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalSecureClipboard.current
    var selected by remember { mutableStateOf<SecureContact?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.importFromDevice() else viewModel.reportPermissionDenied()
    }
    val requestImport = {
        viewModel.beginPermissionRequest()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.importFromDevice()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    GlassScaffold(
        title = "Contacts",
        subtitle = "${state.total} encrypted contacts",
        actions = {
            if (!state.decoy) {
                GlassIconButton(
                    icon = Glyph.contacts,
                    contentDescription = "Import phone contacts",
                    onClick = requestImport,
                    tint = VaultColor.credit,
                    background = VaultColor.credit.copy(alpha = 0.1f)
                )
            }
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.accent
            )
        },
        fab = {
            val heroFill = VaultColor.heroFill(VaultColor.credit, VaultColor.accent)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(heroFill)),
                contentAlignment = Alignment.Center
            ) {
                GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Add contact",
                    onClick = onAdd,
                    tint = VaultColor.onGradient(heroFill),
                    background = Color.Transparent,
                    size = 58.dp
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            VaultSearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 6.dp),
                placeholder = "Search names, numbers, notes"
            )
            if (!state.decoy) {
                ImportContactsCard(
                    state = state.importState,
                    onImport = requestImport,
                    onDismiss = viewModel::dismissImportResult,
                    modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 2.dp)
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = vaultGutter(), vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Pill(
                        label = "All",
                        selected = state.category == "ALL",
                        count = state.categoryCounts["ALL"] ?: 0,
                        onClick = { viewModel.setCategory("ALL") }
                    )
                }
                items(ContactCategories) { category ->
                    Pill(
                        label = category,
                        selected = state.category == category,
                        count = state.categoryCounts[category] ?: 0,
                        onClick = { viewModel.setCategory(category) }
                    )
                }
            }
            if (state.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = VaultColor.credit)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Decrypting contacts…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColor.textBody
                        )
                    }
                }
            } else if (state.contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = if (state.query.isBlank()) Glyph.person else Glyph.search,
                        title = if (state.query.isBlank()) "No contacts saved" else "No matches",
                        message = if (state.query.isBlank()) {
                            "Keep important people, support lines and emergency contacts encrypted and reachable offline."
                        } else {
                            "Try another name, number or category."
                        },
                        actionLabel = if (state.query.isBlank()) "Add a contact" else null,
                        onAction = if (state.query.isBlank()) onAdd else null
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
                    items(state.contacts, key = { it.id }) { contact ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            accent = VaultColor.credit,
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
                            onClick = { selected = contact }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MonogramBadge(
                                    seed = contact.name,
                                    size = 44.dp,
                                    textOverride = contact.initials
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = VaultColor.textStrong,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (contact.phone.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ThemedIcon(
                                                glyph = Glyph.phone,
                                                contentDescription = null,
                                                tint = VaultColor.credit.copy(alpha = 0.8f),
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = contact.phone,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = VaultColor.textBody,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (contact.email.isNotBlank()) {
                                        Spacer(Modifier.height(1.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ThemedIcon(
                                                glyph = Glyph.email,
                                                contentDescription = null,
                                                tint = VaultColor.accent.copy(alpha = 0.8f),
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = contact.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = VaultColor.textBody,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (contact.phone.isBlank() && contact.email.isBlank()) {
                                        Text(
                                            text = "No phone or email",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VaultColor.textFaint
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = contact.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultColor.textFaint
                                    )
                                }
                                if (contact.phone.isNotBlank()) {
                                    GlassIconButton(
                                        icon = Glyph.call,
                                        contentDescription = "Call ${contact.name}",
                                        onClick = { dial(context, contact.phone) },
                                        tint = VaultColor.credit,
                                        background = VaultColor.credit.copy(alpha = 0.1f),
                                        size = 34.dp
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    GlassIconButton(
                                        icon = Glyph.message,
                                        contentDescription = "Message ${contact.name}",
                                        onClick = { sms(context, contact.phone) },
                                        tint = VaultColor.accent,
                                        background = VaultColor.accent.copy(alpha = 0.1f),
                                        size = 34.dp
                                    )
                                }
                                ThemedIcon(
                                    glyph = Glyph.chevronRight,
                                    contentDescription = null,
                                    tint = VaultColor.textFaint,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { contact ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
            containerColor = VaultColor.elevated,
            sheetMaxWidth = 620.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(VaultColor.hairline)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MonogramBadge(seed = contact.name, size = 74.dp, textOverride = contact.initials)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = VaultColor.textStrong,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contact.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.accent
                )
                Spacer(Modifier.height(18.dp))
                if (contact.phone.isNotBlank()) {
                    ContactDetailRow(
                        icon = Glyph.phone,
                        label = "Phone",
                        value = contact.phone,
                        onCopy = { clipboard.copy(contact.phone, "Phone") }
                    )
                }
                if (contact.email.isNotBlank()) {
                    ContactDetailRow(
                        icon = Glyph.email,
                        label = "Email",
                        value = contact.email,
                        onCopy = { clipboard.copy(contact.email, "Email") }
                    )
                }
                if (contact.address.isNotBlank()) {
                    ContactDetailRow(
                        icon = Glyph.locationOn,
                        label = "Address",
                        value = contact.address,
                        onCopy = { clipboard.copy(contact.address, "Address") }
                    )
                }
                if (contact.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "NOTES",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        MarkdownText(content = contact.notes, compact = true)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (contact.phone.isNotBlank()) {
                        GhostButton(
                            text = "Call",
                            icon = Glyph.call,
                            accent = VaultColor.credit,
                            modifier = Modifier.weight(1f),
                            onClick = { dial(context, contact.phone) }
                        )
                        GhostButton(
                            text = "SMS",
                            icon = Glyph.message,
                            accent = VaultColor.accent,
                            modifier = Modifier.weight(1f),
                            onClick = { sms(context, contact.phone) }
                        )
                    }
                }
                if (contact.email.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "Send email",
                        icon = Glyph.email,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { email(context, contact.email) }
                    )
                }
                if (!state.decoy) {
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "Edit contact",
                        icon = Glyph.edit,
                        accent = VaultColor.accent,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val id = contact.id
                            selected = null
                            onEdit(id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportContactsCard(
    state: ContactImportState,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = state.status == ContactImportStatus.READING ||
        state.status == ContactImportStatus.IMPORTING
    val accent = when (state.status) {
        ContactImportStatus.ERROR -> VaultColor.warning
        ContactImportStatus.DONE -> VaultColor.credit
        else -> VaultColor.accent
    }
    val title = when (state.status) {
        ContactImportStatus.READING -> "Reading address book"
        ContactImportStatus.IMPORTING -> "Sealing contacts"
        ContactImportStatus.DONE -> "Import complete"
        ContactImportStatus.ERROR -> "Import needs attention"
        ContactImportStatus.IDLE -> "Import phone contacts"
    }
    val subtitle = when (state.status) {
        ContactImportStatus.IDLE ->
            "One tap · reads your device address book · encrypted into Room"
        else -> state.message
    }
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        onClick = when (state.status) {
            ContactImportStatus.IDLE, ContactImportStatus.ERROR -> onImport
            else -> null
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(VaultPaletteController.current.iconShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    busy -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    state.status == ContactImportStatus.DONE -> ThemedIcon(
                        glyph = Glyph.checkCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    state.status == ContactImportStatus.ERROR -> ThemedIcon(
                        glyph = Glyph.errorOutline,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    else -> ThemedIcon(
                        glyph = Glyph.contacts,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (state.status == ContactImportStatus.DONE &&
                        (state.imported > 0 || state.merged > 0)
                    ) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("SEALED", accent = accent)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                busy -> Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                state.status == ContactImportStatus.DONE -> GlassIconButton(
                    icon = Glyph.close,
                    contentDescription = "Dismiss import result",
                    onClick = onDismiss,
                    tint = VaultColor.textFaint,
                    background = Color.Transparent,
                    size = 30.dp
                )
                state.status == ContactImportStatus.ERROR -> GlassIconButton(
                    icon = Glyph.refresh,
                    contentDescription = "Retry import",
                    onClick = onImport,
                    tint = VaultColor.warning,
                    background = VaultColor.warning.copy(alpha = 0.1f),
                    size = 32.dp
                )
                else -> GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Import now",
                    onClick = onImport,
                    tint = accent,
                    background = accent.copy(alpha = 0.1f),
                    size = 34.dp
                )
            }
        }
        if (busy) {
            Spacer(Modifier.height(9.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(accent)
                )
            }
        }
    }
}

@Composable
private fun ContactDetailRow(
    icon: Glyph,
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = VaultColor.textBody,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textStrong,
                softWrap = true,
                lineHeight = 20.sp
            )
        }
        GlassIconButton(
            icon = Glyph.contentCopy,
            contentDescription = "Copy $label",
            onClick = onCopy,
            tint = VaultColor.textBody,
            background = Color.Transparent,
            size = 32.dp
        )
    }
}

private fun dial(context: Context, phone: String) {
    if (phone.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))
        )
    }
}

private fun sms(context: Context, phone: String) {
    if (phone.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
        )
    }
}

private fun email(context: Context, address: String) {
    if (address.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
        )
    }
}
