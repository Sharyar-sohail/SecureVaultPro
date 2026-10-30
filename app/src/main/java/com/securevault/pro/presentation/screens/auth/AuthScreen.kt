package com.securevault.pro.presentation.screens.auth

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun AuthScreen(
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onLock: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalSecureClipboard.current

    GlassScaffold(
        title = "Authenticator",
        subtitle = "${state.total} linked accounts · RFC 6238",
        actions = {
            GlassIconButton(
                icon = Glyph.qrCodeScanner,
                contentDescription = "Scan QR code",
                onClick = onScan,
                tint = VaultColor.credit
            )
            Spacer(Modifier.width(8.dp))
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
                    contentDescription = "Add authenticator",
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
                placeholder = "Search issuers or accounts"
            )
            Spacer(Modifier.height(6.dp))
            if (state.rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Glyph.timer,
                        title = if (state.query.isBlank()) "No authenticators yet" else "No matching account",
                        message = if (state.query.isBlank()) {
                            "Scan a QR code or paste a Base32 secret to generate offline one-time codes."
                        } else {
                            "Try a different issuer or account name."
                        },
                        actionLabel = if (state.query.isBlank()) "Scan QR code" else null,
                        onAction = if (state.query.isBlank()) onScan else null
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
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            accent = VaultColor.warning,
                            borderColor = VaultColor.warning.copy(alpha = 0.22f),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ThemedIcon(
                                    glyph = Glyph.shield,
                                    contentDescription = null,
                                    tint = VaultColor.warning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Codes are computed on-device from encrypted secrets. Nothing leaves this phone.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textBody,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                    items(state.rows, key = { it.account.id }) { row ->
                        TotpCard(
                            row = row,
                            onCopy = {
                                if (row.code != "------") {
                                    clipboard.copy(row.code, "One-time code · ${row.account.issuer}")
                                }
                            },
                            onEdit = { onEdit(row.account.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotpCard(
    row: TotpRow,
    onCopy: () -> Unit,
    onEdit: () -> Unit
) {
    val palette = VaultPaletteController.current
    val urgent = row.seconds <= 5
    val accent = if (urgent) VaultColor.warning else VaultColor.credit
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        borderColor = accent.copy(alpha = 0.22f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        onClick = onEdit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(seed = row.account.issuer, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.account.issuer,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.account.accountName.ifBlank { row.account.algorithm + " · " + row.account.digits + " digits" },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { row.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    color = accent,
                    trackColor = palette.surfaceOverlay.copy(alpha = 0.07f)
                )
                Text(
                    text = row.seconds.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (urgent) VaultColor.warning else VaultColor.textBody,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(VaultShapes.button)
                .background(palette.surfaceShade.copy(alpha = 0.22f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTotp(row.code),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = if (row.code == "------") VaultColor.debit else accent,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${row.account.digits} digits",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Glyph.contentCopy,
                contentDescription = "Copy code",
                onClick = onCopy,
                tint = accent,
                background = accent.copy(alpha = 0.12f),
                size = 34.dp
            )
        }
    }
}

private fun formatTotp(code: String): String {
    if (code.length < 6) return code
    val half = (code.length + 1) / 2
    return "${code.take(half)} ${code.drop(half)}"
}
