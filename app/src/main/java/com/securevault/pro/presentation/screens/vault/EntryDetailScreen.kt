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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.CustomField
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.MarkdownText
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.StrengthMeter
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.strengthLabel
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun EntryDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: EntryDetailViewModel = hiltViewModel()
) {
    val palette = VaultPaletteController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revealedPassword by remember { mutableStateOf(false) }
    var revealedFields by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = LocalSecureClipboard.current
    val uriHandler = LocalUriHandler.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.load()
    }

    val entry = state.entry
    GlassScaffold(
        title = entry?.title ?: "Secret",
        subtitle = entry?.domain,
        onBack = onBack,
        actions = {
            if (entry != null) {
                GlassIconButton(
                    icon = if (entry.isFavorite) Glyph.star else Glyph.starBorder,
                    contentDescription = "Favorite",
                    onClick = viewModel::toggleFavorite,
                    tint = if (entry.isFavorite) VaultColor.warningInk else VaultColor.textBody
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Glyph.edit,
                    contentDescription = "Edit entry",
                    onClick = { onEdit(entry.id) },
                    tint = VaultColor.accent
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Glyph.delete,
                    contentDescription = "Delete entry",
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
        } else if (entry == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Glyph.lock,
                    title = "Secret unavailable",
                    message = "This record may have been removed, or it belongs to a different vault."
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = securityColor(entry.strengthScore),
                    borderColor = securityColor(entry.strengthScore).copy(alpha = 0.3f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonogramBadge(seed = entry.title, size = 62.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = VaultColor.textStrong,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = entry.category.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                color = VaultColor.textBody
                            )
                            if (entry.lastAccessedAt != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Last used ${formatShortDate(entry.lastAccessedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                        }
                    }
                    if (entry.expiresAt != null && entry.expiresAt < System.currentTimeMillis()) {
                        Spacer(Modifier.height(12.dp))
                        WarningBanner(
                            text = "This secret expired on ${formatShortDate(entry.expiresAt)}. Rotate it now.",
                            color = VaultColor.debit
                        )
                    }
                }

                if (entry.passwordSealed || entry.notesSealed) {
                    WarningBanner(
                        text = "Some fields were sealed on another device and cannot be opened here. Saving a new value will replace them.",
                        color = VaultColor.warning
                    )
                }

                SectionHeader(title = "Password")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.lock,
                            contentDescription = null,
                            tint = VaultColor.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when {
                                entry.password.isBlank() && entry.passwordSealed -> "Sealed on another device"
                                entry.password.isBlank() -> "No password stored"
                                revealedPassword -> entry.password
                                else -> "•".repeat(entry.password.length.coerceIn(8, 18))
                            },
                            style = VaultType.amountCompact().copy(
                                fontFamily = if (revealedPassword) FontFamily.Monospace else null,
                                letterSpacing = if (revealedPassword) 0.5.sp else 2.sp
                            ),
                            color = if (entry.password.isBlank()) VaultColor.textFaint else VaultColor.textStrong,
                            softWrap = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (entry.password.isNotBlank()) {
                            GlassIconButton(
                                icon = if (revealedPassword) Glyph.visibilityOff else Glyph.visibility,
                                contentDescription = if (revealedPassword) "Hide password" else "Reveal password",
                                onClick = { revealedPassword = !revealedPassword },
                                tint = VaultColor.textBody,
                                background = Color.Transparent,
                                size = 36.dp
                            )
                            Spacer(Modifier.width(4.dp))
                            GlassIconButton(
                                icon = Glyph.contentCopy,
                                contentDescription = "Copy password",
                                onClick = { clipboard.copy(entry.password, "Password · ${entry.title}") },
                                tint = VaultColor.accent,
                                background = VaultColor.accent.copy(alpha = 0.1f),
                                size = 36.dp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StrengthMeter(
                            score = entry.strengthScore,
                            modifier = Modifier.weight(1f),
                            showLabel = false
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${strengthLabel(entry.strengthScore)} · ${entry.strengthScore}/100",
                            style = MaterialTheme.typography.labelSmall,
                            color = securityColor(entry.strengthScore),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val hasCredentials = entry.username.isNotBlank() || entry.email.isNotBlank() ||
                    entry.website.isNotBlank() || entry.url.isNotBlank()
                if (hasCredentials) {
                    SectionHeader(title = "Credentials")
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        if (entry.username.isNotBlank()) {
                            InfoRow(
                                icon = Glyph.person,
                                label = "Username",
                                value = entry.username,
                                onCopy = { clipboard.copy(entry.username, "Username") }
                            )
                        }
                        if (entry.email.isNotBlank()) {
                            InfoRow(
                                icon = Glyph.email,
                                label = "Email",
                                value = entry.email,
                                onCopy = { clipboard.copy(entry.email, "Email") }
                            )
                        }
                        val site = entry.displayUrl
                        if (site.isNotBlank()) {
                            InfoRow(
                                icon = Glyph.language,
                                label = "Website",
                                value = site,
                                onCopy = { clipboard.copy(site, "Website") },
                                onOpen = {
                                    runCatching {
                                        val target = if (site.startsWith("http://") || site.startsWith("https://")) {
                                            site
                                        } else {
                                            "https://$site"
                                        }
                                        uriHandler.openUri(target)
                                    }
                                }
                            )
                        }
                    }
                }

                if (entry.hasTotp && entry.totpCodeAvailable()) {
                    SectionHeader(title = "Authenticator")
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        accent = VaultColor.credit,
                        borderColor = VaultColor.credit.copy(alpha = 0.3f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(VaultColor.credit.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                ThemedIcon(
                                    glyph = Glyph.timer,
                                    contentDescription = null,
                                    tint = VaultColor.credit,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = formatCode(state.totpCode),
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 3.sp
                                    ),
                                    color = VaultColor.credit,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Refreshes in ${state.totpSeconds}s · " +
                                        "${state.totpAlgorithm} / ${state.totpDigits} digits",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                            GlassIconButton(
                                icon = Glyph.contentCopy,
                                contentDescription = "Copy verification code",
                                onClick = {
                                    if (state.totpCode.isNotBlank() && state.totpCode != "------") {
                                        clipboard.copy(state.totpCode, "One-time code")
                                    }
                                },
                                tint = VaultColor.credit,
                                background = VaultColor.credit.copy(alpha = 0.1f),
                                size = 36.dp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { state.totpProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(VaultShapes.tag),
                            color = VaultColor.credit,
                            trackColor = palette.surfaceOverlay.copy(alpha = 0.07f)
                        )
                    }
                }

                if (entry.notes.isNotBlank() || entry.notesSealed) {
                    SectionHeader(title = "Secure notes")
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        if (entry.notes.isNotBlank()) {
                            MarkdownText(content = entry.notes, compact = true)
                        } else {
                            Text(
                                text = "These notes were sealed on another device and cannot be decrypted here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textFaint
                            )
                        }
                    }
                }

                if (entry.customFields.isNotEmpty()) {
                    SectionHeader(title = "Custom fields")
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        entry.customFields.forEachIndexed { index, field ->
                            CustomFieldRow(
                                field = field,
                                revealed = index in revealedFields,
                                onToggleReveal = {
                                    revealedFields = if (index in revealedFields) {
                                        revealedFields - index
                                    } else {
                                        revealedFields + index
                                    }
                                },
                                onCopy = { clipboard.copy(field.value, field.label.ifBlank { "Field" }) }
                            )
                        }
                    }
                }

                if (entry.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entry.tags.take(6).forEach { tag -> TagChip(text = tag) }
                    }
                }

                Text(
                    text = "Created ${formatShortDate(entry.createdAt)} · Updated ${formatShortDate(entry.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "Delete this secret?",
        message = "It is erased from the device vault immediately, along with any 2FA code linked to it, and the next sync overwrites the Drive copy. This cannot be undone.",
        confirmLabel = "Delete",
        onConfirm = {
            confirmDelete = false
            viewModel.delete(onBack)
        },
        onDismiss = { confirmDelete = false }
    )
}

private fun VaultEntry.totpCodeAvailable(): Boolean =
    !totpSealed && totpSecret.isNotBlank()

private fun formatCode(code: String): String {
    if (code.isBlank()) return "------"
    val half = (code.length + 1) / 2
    return if (code.length >= 6) "${code.take(half)} ${code.drop(half)}" else code
}

@Composable
private fun WarningBanner(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShapes.button)
            .background(color.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = Glyph.warningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun InfoRow(
    icon: Glyph,
    label: String,
    value: String,
    onCopy: () -> Unit,
    onOpen: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
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
        if (onOpen != null) {
            GlassIconButton(
                icon = Glyph.openInNew,
                contentDescription = "Open $label",
                onClick = onOpen,
                tint = VaultColor.textBody,
                background = Color.Transparent,
                size = 32.dp
            )
            Spacer(Modifier.width(2.dp))
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 46.dp)
            .height(1.dp)
            .background(VaultColor.hairline.copy(alpha = 0.5f))
    )
}

@Composable
private fun CustomFieldRow(
    field: CustomField,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = Glyph.description,
            contentDescription = null,
            tint = VaultColor.textBody,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = field.label.ifBlank { "Field" },
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (field.concealed && !revealed) "•".repeat(field.value.length.coerceIn(6, 16)) else field.value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (field.concealed) FontFamily.Monospace else null
                ),
                color = VaultColor.textStrong,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (field.concealed) {
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Reveal field",
                onClick = onToggleReveal,
                tint = if (revealed) VaultColor.accent else VaultColor.textBody,
                background = Color.Transparent,
                size = 32.dp
            )
        }
        GlassIconButton(
            icon = Glyph.contentCopy,
            contentDescription = "Copy field",
            onClick = onCopy,
            tint = VaultColor.textBody,
            background = Color.Transparent,
            size = 32.dp
        )
    }
}
