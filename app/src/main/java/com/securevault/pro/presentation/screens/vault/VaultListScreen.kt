package com.securevault.pro.presentation.screens.vault

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.EntryCategories
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.StrengthMeter
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.strengthLabel
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun VaultListScreen(
    onOpenEntry: (String) -> Unit,
    onAddEntry: () -> Unit,
    onLock: () -> Unit,
    viewModel: VaultListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassScaffold(
        title = "Vault",
        subtitle = "${state.total} encrypted secrets",
        actions = {
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.accent
            )
        },
        fab = {
            val heroFill = VaultColor.heroFill()
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(heroFill))
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Add password",
                    onClick = onAddEntry,
                    tint = VaultColor.onGradient(heroFill),
                    background = Color.Transparent,
                    size = 58.dp
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.fillMaxSize()) {
                VaultSearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 6.dp),
                    placeholder = "Search ${state.total} logins"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = vaultGutter(), vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(EntryCategories) { category ->
                        Pill(
                            label = category.lowercase().replaceFirstChar { it.uppercase() },
                            selected = state.category == category,
                            count = state.categoryCounts[category] ?: 0,
                            onClick = { viewModel.setCategory(category) }
                        )
                    }
                }
                if (state.entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 110.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(
                            icon = if (state.query.isBlank()) Glyph.key else Glyph.search,
                            title = if (state.query.isBlank()) "No secrets here yet" else "Nothing matches",
                            message = if (state.query.isBlank()) {
                                "Add a login and Secure Wallet Pro seals it with hardware-backed AES-256-GCM."
                            } else {
                                "Try a different name, username, website or tag."
                            },
                            actionLabel = if (state.query.isBlank()) "Add a login" else null,
                            onAction = if (state.query.isBlank()) onAddEntry else null
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(
                            start = vaultGutter(),
                        end = vaultGutter(),
                            top = 4.dp,
                            bottom = 120.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.entries, key = { it.id }) { entry ->
                            VaultRow(
                                entry = entry,
                                onOpen = { onOpenEntry(entry.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(entry.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRow(
    entry: VaultEntry,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(seed = entry.title, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.hasTotp) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(VaultShapes.tag)
                                .background(VaultColor.accent.copy(alpha = 0.13f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "2FA",
                                style = VaultType.micro(),
                                color = VaultColor.accentInk
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                if (entry.username.isNotBlank()) {
                    Text(
                        text = entry.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.email.isNotBlank()) {
                    Text(
                        text = entry.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.username.isBlank() && entry.email.isBlank()) {
                    Text(
                        text = entry.domain.ifBlank { "No username" },
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (entry.domain.isNotBlank()) {
                    Text(
                        text = entry.domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StrengthMeter(score = entry.strengthScore, modifier = Modifier.width(58.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = strengthLabel(entry.strengthScore),
                        style = MaterialTheme.typography.labelSmall,
                        color = securityColor(entry.strengthScore)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "·",
                        color = VaultColor.textFaint,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.category.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassIconButton(
                    icon = if (entry.isFavorite) Glyph.star else Glyph.starBorder,
                    contentDescription = if (entry.isFavorite) "Remove favorite" else "Favorite",
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp),
                    tint = if (entry.isFavorite) VaultColor.warningInk else VaultColor.textFaint,
                    background = Color.Transparent,
                    size = 32.dp
                )
                Spacer(Modifier.height(4.dp))
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = VaultColor.textFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
