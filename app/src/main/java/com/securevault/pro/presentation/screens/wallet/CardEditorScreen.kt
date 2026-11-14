package com.securevault.pro.presentation.screens.wallet

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.CardTypes
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun CardEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CardEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    GlassScaffold(
        title = if (state.isNew) "New card" else "Edit card",
        subtitle = "Number, CVV and PIN sealed at rest",
        onBack = onBack
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
            val start = remember(state.gradientStart) { colorOf(state.gradientStart, 0xFF1E273E) }
            val end = remember(state.gradientEnd) { colorOf(state.gradientEnd, 0xFF141B2D) }
            val ink = VaultColor.onGradient(listOf(start, end))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = VaultShapes.card,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = start.copy(alpha = 0.45f)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.20f), VaultShapes.card)
                    .clip(VaultShapes.card)
                    .background(Brush.linearGradient(listOf(start, end)))
            ) {
                // Specular top reflection line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.45f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.bankName.uppercase().ifBlank { state.nickname.uppercase().ifBlank { "YOUR CARD" } },
                            style = MaterialTheme.typography.labelMedium,
                            color = ink.copy(alpha = 0.85f),
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = state.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.7f),
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    ThemedIcon(
                        glyph = Glyph.creditCard,
                        contentDescription = null,
                        tint = VaultColor.warning,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "••••  ••••  ••••  ${state.last4.ifBlank { "••••" }}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        ),
                        color = ink
                    )
                    Spacer(Modifier.weight(1f))
                    Row {
                        Text(
                            text = state.holderName.uppercase().ifBlank { "CARD HOLDER" },
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.75f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = state.expiryLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = ink.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            if (state.sealed) {
                Text(
                    text = "Some stored values are sealed on another device and cannot be displayed. Enter new values to replace them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            SectionHeader(title = "Card type")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTypes.forEach { type ->
                    Pill(
                        label = type.lowercase().replaceFirstChar { it.uppercase() },
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) }
                    )
                }
            }

            SectionHeader(title = "Details")
            VaultTextField(
                value = state.nickname,
                onValueChange = viewModel::setNickname,
                label = "Nickname",
                placeholder = "Everyday spending",
                leadingIcon = Glyph.badge,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.bankName,
                onValueChange = viewModel::setBank,
                label = "Bank or issuer",
                leadingIcon = Glyph.accountBalance,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.holderName,
                onValueChange = viewModel::setHolder,
                label = "Cardholder name",
                leadingIcon = Glyph.person,
                imeAction = ImeAction.Next
            )
            VaultTextField(
                value = state.number,
                onValueChange = viewModel::setNumber,
                label = "Card number",
                placeholder = "16 digits",
                keyboardType = KeyboardType.Number,
                leadingIcon = Glyph.creditCard,
                supportingText = "Stored as digits and encrypted with the hardware-backed vault key",
                imeAction = ImeAction.Next
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultTextField(
                    value = state.cvv,
                    onValueChange = viewModel::setCvv,
                    label = "CVV",
                    isPassword = true,
                    keyboardType = KeyboardType.NumberPassword,
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
                VaultTextField(
                    value = state.pin,
                    onValueChange = viewModel::setPin,
                    label = "PIN",
                    isPassword = true,
                    keyboardType = KeyboardType.NumberPassword,
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultTextField(
                    value = state.monthText,
                    onValueChange = viewModel::setMonth,
                    label = "Expiry month",
                    placeholder = "MM",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
                VaultTextField(
                    value = state.yearText,
                    onValueChange = viewModel::setYear,
                    label = "Expiry year",
                    placeholder = "YY",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
            }

            SectionHeader(title = "Card colour")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(CardEditorViewModel.CardGradientPresets) { preset ->
                    val selected = preset.first == state.gradientStart && preset.second == state.gradientEnd
                    val swatch = listOf(
                        colorOf(preset.first, 0xFF1E273E),
                        colorOf(preset.second, 0xFF141B2D)
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 66.dp, height = 42.dp)
                            .clip(VaultShapes.input)
                            .background(Brush.linearGradient(swatch))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) VaultColor.accent else VaultColor.hairline,
                                shape = VaultShapes.input
                            )
                            .clickable { viewModel.setGradient(preset.first, preset.second) }
                    ) {
                        if (selected) {
                            ThemedIcon(
                                glyph = Glyph.palette,
                                contentDescription = null,
                                tint = VaultColor.onGradient(swatch),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(16.dp)
                            )
                        }
                    }
                }
            }

            SectionHeader(title = "Notes")
            VaultTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = "Card notes",
                placeholder = "Rewards, limits, cancellation line…",
                leadingIcon = Glyph.description,
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default
            )

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = VaultColor.warning,
                borderColor = VaultColor.warning.copy(alpha = 0.2f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.lock,
                        contentDescription = null,
                        tint = VaultColor.warning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Card number, CVV, PIN and notes are individually sealed with AES-256-GCM. Only a masked ending is shown in lists.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textBody,
                        lineHeight = 16.sp
                    )
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.debit,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            GradientButton(
                text = if (state.isNew) "Save card" else "Update card",
                icon = Glyph.creditCard,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
                onClick = viewModel::save
            )
        }
    }
}

private fun colorOf(value: String, fallback: Long): Color {
    return try {
        Color(android.graphics.Color.parseColor(value))
    } catch (_: Throwable) {
        Color(fallback)
    }
}
