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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun TotpEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onScan: () -> Unit,
    viewModel: TotpEditorViewModel = hiltViewModel()
) {
    val palette = VaultPaletteController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onSaved()
    }

    GlassScaffold(
        title = if (state.isNew) "Add authenticator" else "Edit authenticator",
        subtitle = "RFC 6238 · SHA1 / SHA256 / SHA512",
        onBack = onBack,
        actions = {
            if (!state.isNew && !state.decoy) {
                com.securevault.pro.presentation.components.GlassIconButton(
                    icon = Glyph.delete,
                    contentDescription = "Delete authenticator",
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
                CircularProgressIndicator(color = VaultColor.credit)
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
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = VaultColor.credit,
                borderColor = VaultColor.credit.copy(alpha = 0.3f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.size(54.dp),
                            strokeWidth = 4.dp,
                            color = VaultColor.credit,
                            trackColor = palette.surfaceOverlay.copy(alpha = 0.07f)
                        )
                        Text(
                            text = state.seconds.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = VaultColor.credit,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Live preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                        Text(
                            text = formatTotpCode(state.previewCode),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (state.secretValid) VaultColor.credit else VaultColor.textFaint
                        )
                        Text(
                            text = if (state.secretValid) "Updates every ${state.period}s" else "Enter a valid secret to preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody
                        )
                    }
                }
            }

            if (state.sealed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(VaultColor.warning.copy(alpha = 0.09f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.warningAmber,
                        contentDescription = null,
                        tint = VaultColor.warning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "This secret was sealed on another device. Paste a new secret to replace it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textBody
                    )
                }
            }

            SectionHeader(title = "Account")
            VaultTextField(
                value = state.issuer,
                onValueChange = viewModel::setIssuer,
                label = "Issuer",
                placeholder = "Google, GitHub, Binance",
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.accountName,
                onValueChange = viewModel::setAccountName,
                label = "Account name",
                placeholder = "you@example.com",
                imeAction = ImeAction.Next
            )

            SectionHeader(title = "Secret")
            VaultTextField(
                value = state.secret,
                onValueChange = viewModel::setSecret,
                label = "Base32 secret",
                placeholder = "JBSWY3DPEHPK3PXP",
                isPassword = true,
                supportingText = "Paste the setup key or scan the QR code instead",
                imeAction = ImeAction.Next
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isNew) {
                    GhostButton(
                        text = "Scan QR",
                        icon = Glyph.qrCodeScanner,
                        accent = VaultColor.credit,
                        modifier = Modifier.weight(1f),
                        onClick = onScan
                    )
                }
                GhostButton(
                    text = "Generate",
                    icon = Glyph.autoAwesome,
                    accent = VaultColor.accent,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::generateSecret
                )
            }

            SectionHeader(title = "Algorithm")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("SHA1", "SHA256", "SHA512")) { algorithm ->
                    Pill(
                        label = algorithm,
                        selected = state.algorithm == algorithm,
                        onClick = { viewModel.setAlgorithm(algorithm) }
                    )
                }
            }
            SectionHeader(title = "Digits")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 7, 8).forEach { digits ->
                    Pill(
                        label = "$digits digits",
                        selected = state.digits == digits,
                        onClick = { viewModel.setDigits(digits) }
                    )
                }
            }
            VaultTextField(
                value = state.period.toString(),
                onValueChange = { raw ->
                    raw.filter(Char::isDigit).take(3).toIntOrNull()?.let(viewModel::setPeriod)
                },
                label = "Refresh period (seconds)",
                keyboardType = KeyboardType.Number,
                supportingText = "Allowed range 15–120 seconds · default 30",
                imeAction = ImeAction.Done
            )

            if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.debit,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            GradientButton(
                text = if (state.isNew) "Link authenticator" else "Save authenticator",
                icon = Glyph.timer,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
                onClick = viewModel::save
            )
            if (state.decoy) {
                Text(
                    text = "Decoy mode is read-only. Authenticator changes are disabled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    ConfirmDialog(
        visible = confirmDelete,
        title = "Delete authenticator?",
        message = "The encrypted TOTP secret will be permanently removed. You can re-add it with the original setup key.",
        confirmLabel = "Delete",
        onConfirm = {
            confirmDelete = false
            viewModel.delete()
        },
        onDismiss = { confirmDelete = false }
    )
}

private fun formatTotpCode(code: String): String {
    if (code.length < 6) return code
    val half = (code.length + 1) / 2
    return "${code.take(half)} ${code.drop(half)}"
}
