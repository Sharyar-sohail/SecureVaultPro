package com.securevault.pro.presentation.screens.vault

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.CustomField
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.StrengthMeter
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.strengthLabel
import com.securevault.pro.security.TotpGenerator
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun EntryEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onScanQr: () -> Unit = {},
    viewModel: EntryEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showGenerateHint by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    GlassScaffold(
        title = if (state.isNew) "New secret" else "Edit secret",
        subtitle = if (state.decoy) "Decoy vault · read only" else "AES-256-GCM at rest",
        onBack = onBack,
        actions = {
            if (!state.decoy) {
                GlassIconButton(
                    icon = if (state.isFavorite) Glyph.star else Glyph.starBorder,
                    contentDescription = "Toggle favorite",
                    onClick = { viewModel.setFavorite(!state.isFavorite) },
                    tint = if (state.isFavorite) VaultColor.warningInk else VaultColor.textBody
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth(), accent = VaultColor.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonogramBadge(seed = state.title.ifBlank { "New secret" }, size = 54.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.title.ifBlank { "Untitled secret" },
                            style = MaterialTheme.typography.titleMedium,
                            color = VaultColor.textStrong,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.category.lowercase().replaceFirstChar { it.uppercase() } +
                                " · ${if (state.isNew) "New record" else "Encrypted record"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                    ThemedIcon(
                        glyph = Glyph.lock,
                        contentDescription = null,
                        tint = VaultColor.accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            SectionHeader(title = "Basics")
            VaultTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = "Name",
                placeholder = "e.g. GitHub, Netflix, Bank",
                leadingIcon = Glyph.person,
                imeAction = ImeAction.Next
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EntryEditorViewModel.EntryCategoryOptions) { category ->
                    Pill(
                        label = category.lowercase().replaceFirstChar { it.uppercase() },
                        selected = state.category == category,
                        onClick = { viewModel.setCategory(category) }
                    )
                }
            }
            VaultTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = "Username",
                leadingIcon = Glyph.person,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.email,
                onValueChange = viewModel::setEmail,
                label = "Email",
                leadingIcon = Glyph.publicGlobe,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.website,
                onValueChange = viewModel::setWebsite,
                label = "Website",
                placeholder = "https://example.com",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            )

            SectionHeader(title = "Password")
            VaultTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = "Password",
                isPassword = true,
                leadingIcon = Glyph.lock,
                keyboardType = KeyboardType.Text,
                supportingText = when {
                    state.passwordSealed -> "The previous password is sealed on another device. Type a new one to replace it."
                    else -> null
                },
                imeAction = ImeAction.Next
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StrengthMeter(
                    score = state.score,
                    modifier = Modifier.weight(1f),
                    showLabel = false
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.password.isBlank()) "No password" else "${strengthLabel(state.score)} · ${state.score}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.password.isBlank()) VaultColor.textFaint else securityColor(state.score)
                )
            }
            GhostButton(
                text = "Generate strong password",
                icon = Glyph.autoAwesome,
                accent = VaultColor.accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.generatePassword()
                    showGenerateHint = true
                }
            )
            if (showGenerateHint && state.password.isNotBlank()) {
                Text(
                    text = "Generated ${state.password.length}-character password. Save to seal it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.credit,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            SectionHeader(title = "Notes")
            VaultTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = "Secure notes",
                placeholder = "Markdown is supported. Never stored in plaintext.",
                minLines = 5,
                singleLine = false,
                imeAction = ImeAction.Default
            )

            SectionHeader(title = "Two-factor authentication")
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = VaultColor.credit,
                borderColor = if (state.hasTotp) VaultColor.credit.copy(alpha = 0.3f) else VaultColor.hairline
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.timer,
                        contentDescription = null,
                        tint = if (state.hasTotp) VaultColor.credit else VaultColor.textBody,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Linked authenticator",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = if (state.hasTotp) "Live RFC 6238 code on the detail screen" else "Add a Base32 secret or otpauth:// URI",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                    Switch(
                        checked = state.hasTotp,
                        onCheckedChange = { viewModel.toggleTotp() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VaultColor.credit,
                            checkedTrackColor = VaultColor.credit.copy(alpha = 0.28f),
                            uncheckedThumbColor = VaultColor.textFaint,
                            uncheckedTrackColor = VaultColor.elevated
                        )
                    )
                }
                if (state.hasTotp) {
                    Spacer(Modifier.height(12.dp))
                    VaultTextField(
                        value = state.totpInput,
                        onValueChange = viewModel::setTotpInput,
                        label = "New 2FA secret",
                        placeholder = if (state.isNew) "JBSWY3DPEHPK3PXP" else "Leave blank to keep the current secret",
                        isPassword = true,
                        supportingText = when {
                            state.totpWasSealed -> "The existing secret is sealed and cannot be shown. Enter a new one to replace it."
                            else -> "Base32 or otpauth://. The secret is encrypted with the vault key."
                        },
                        imeAction = ImeAction.Done
                    )
                    val scanned = remember(state.totpInput) {
                        if (state.totpInput.trim().startsWith("otpauth://", ignoreCase = true)) {
                            TotpGenerator.parseOtpAuth(state.totpInput.trim())
                        } else {
                            null
                        }
                    }
                    if (scanned != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .background(VaultColor.credit.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThemedIcon(
                                glyph = Glyph.qrCodeScanner,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${scanned.issuer} · ${scanned.accountName.ifBlank { "account" }} · " +
                                    "${scanned.algorithm.name} · ${scanned.digits} digits · ${scanned.period}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textBody,
                                maxLines = 2
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "Scan QR code",
                        icon = Glyph.qrCodeScanner,
                        accent = VaultColor.credit,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onScanQr
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "Generate new TOTP secret",
                        icon = Glyph.timer,
                        accent = VaultColor.credit,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::generateTotpSecret
                    )
                }
            }

            SectionHeader(
                title = "Custom fields",
                actionLabel = if (state.customFields.size < 12) "Add" else null,
                onAction = if (state.customFields.size < 12) viewModel::addCustomField else null
            )
            if (state.customFields.isEmpty()) {
                Text(
                    text = "Add recovery codes, PINs, security question answers or any other concealed value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textFaint,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            state.customFields.forEachIndexed { index, field ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Field ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = VaultColor.accent,
                            modifier = Modifier.weight(1f)
                        )
                        GlassIconButton(
                            icon = Glyph.close,
                            contentDescription = "Remove field",
                            onClick = { viewModel.removeCustomField(index) },
                            tint = VaultColor.debit,
                            background = Color.Transparent,
                            size = 30.dp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    VaultTextField(
                        value = field.label,
                        onValueChange = { value ->
                            viewModel.updateCustomField(index) { it.copy(label = value) }
                        },
                        label = "Label",
                        imeAction = ImeAction.Next
                    )
                    Spacer(Modifier.height(8.dp))
                    VaultTextField(
                        value = field.value,
                        onValueChange = { value ->
                            viewModel.updateCustomField(index) { it.copy(value = value) }
                        },
                        label = "Value",
                        isPassword = field.concealed,
                        imeAction = ImeAction.Done
                    )
                    Spacer(Modifier.height(6.dp))
                    ToggleRow(
                        icon = Glyph.visibility,
                        title = "Conceal value",
                        subtitle = "Mask this field and reveal it on demand",
                        checked = field.concealed,
                        onCheckedChange = { value ->
                            viewModel.updateCustomField(index) { it.copy(concealed = value) }
                        }
                    )
                }
            }
            if (state.customFields.isEmpty()) {
                GhostButton(
                    text = "Add a custom field",
                    icon = Glyph.add,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::addCustomField
                )
            }

            SectionHeader(title = "Organisation")
            VaultTextField(
                value = state.tagsInput,
                onValueChange = viewModel::setTags,
                label = "Tags",
                placeholder = "work, finance, shared",
                supportingText = "Separate tags with commas",
                imeAction = ImeAction.Done
            )
            VaultTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = "Launch URL (optional)",
                placeholder = "https://example.com/login",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )

            GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                ToggleRow(
                    icon = Glyph.star,
                    title = "Favorite",
                    subtitle = "Pin this secret to your home screen",
                    checked = state.isFavorite,
                    onCheckedChange = viewModel::setFavorite
                )
                ToggleRow(
                    icon = Glyph.travelExplore,
                    title = "Hide in travel mode",
                    subtitle = "Disappear from the vault while travelling",
                    checked = state.hiddenInTravel,
                    onCheckedChange = viewModel::setHiddenInTravel
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .clickable { showDatePicker = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.calendarToday,
                        contentDescription = null,
                        tint = if (state.expiresAt != null) VaultColor.warning else VaultColor.textBody,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Expiry reminder",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = state.expiresAt?.let { "Expires ${formatShortDate(it)}" } ?: "No expiry set",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                    if (state.expiresAt != null) {
                        GlassIconButton(
                            icon = Glyph.close,
                            contentDescription = "Clear expiry",
                            onClick = { viewModel.setExpiresAt(null) },
                            tint = VaultColor.textBody,
                            background = Color.Transparent,
                            size = 30.dp
                        )
                    }
                }
            }

            if (state.error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(VaultColor.debit.copy(alpha = 0.1f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.debit,
                        modifier = Modifier.weight(1f)
                    )
                    GlassIconButton(
                        icon = Glyph.close,
                        contentDescription = "Dismiss error",
                        onClick = viewModel::clearError,
                        tint = VaultColor.debit,
                        background = Color.Transparent,
                        size = 28.dp
                    )
                }
            }

            GradientButton(
                text = if (state.isNew) "Seal secret" else "Save changes",
                icon = Glyph.lock,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
                onClick = viewModel::save
            )
            if (state.decoy) {
                Text(
                    text = "Decoy mode is strictly read-only. Changes cannot be written.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.expiresAt
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { viewModel.setExpiresAt(it) }
                        showDatePicker = false
                    }
                ) {
                    Text("Set expiry", color = VaultColor.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = VaultColor.textBody)
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ToggleRow(
    icon: Glyph,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = if (checked) VaultColor.accent else VaultColor.textBody,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VaultColor.accent,
                checkedTrackColor = VaultColor.accent.copy(alpha = 0.28f),
                uncheckedThumbColor = VaultColor.textFaint,
                uncheckedTrackColor = VaultColor.elevated
            )
        )
    }
}
