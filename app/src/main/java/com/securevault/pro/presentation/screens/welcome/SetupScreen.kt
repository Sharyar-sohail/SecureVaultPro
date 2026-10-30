package com.securevault.pro.presentation.screens.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.CloudOperationOverlay
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.StrengthMeter
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val busy = state.busy || cloudState.active

    NeonBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = vaultGutter(), vertical = 12.dp)
        ) {
            SetupHeader(step = state.step, onBack = viewModel::previousStep, backEnabled = !busy)

            if (state.notice != null) {
                Spacer(Modifier.height(14.dp))
                RestoredBanner(
                    message = state.notice.orEmpty(),
                    isError = state.noticeIsError,
                    warnings = state.warnings
                )
            }

            Spacer(Modifier.height(22.dp))

            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it / 6 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 6 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 6 } + fadeOut())
                    }
                },
                label = "setup-step"
            ) { step ->
                if (step <= 1) {
                    MasterStep(
                        state = state,
                        busy = busy,
                        viewModel = viewModel,
                        onContinue = viewModel::nextStep
                    )
                } else {
                    PinStep(
                        state = state,
                        busy = busy,
                        viewModel = viewModel,
                        onCreate = viewModel::createVault,
                        onBack = viewModel::previousStep
                    )
                }
            }
        }
    }

    var allowCancel by remember { mutableStateOf(false) }
    LaunchedEffect(cloudState.active) {
        if (!cloudState.active) {
            allowCancel = false
        } else {
            allowCancel = false
            delay(CANCEL_GRACE_MS)
            allowCancel = true
        }
    }
    CloudOperationOverlay(
        visible = cloudState.active,
        title = cloudState.title.ifBlank { "Restoring your vault" },
        detail = cloudState.detail.ifBlank { "Finishing the encrypted import" },
        percent = cloudState.percent,
        onCancel = if (allowCancel) {
            {
                allowCancel = false
                viewModel.cancelPendingFlow()
            }
        } else {
            null
        }
    )
}

private const val CANCEL_GRACE_MS = 20_000L

@Composable
private fun RestoredBanner(message: String, isError: Boolean, warnings: List<String>) {
    val accent = if (isError) VaultColor.warning else VaultColor.credit
    GlassCard(
        accent = accent,
        borderColor = accent.copy(alpha = 0.3f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 13.dp,
            vertical = 11.dp
        )
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ThemedIcon(
                glyph = if (isError) Glyph.errorOutline else Glyph.checkCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp
                )
                warnings.take(2).forEach { warning ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(step: Int, backEnabled: Boolean, onBack: () -> Unit) {
    val palette = VaultPaletteController.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 1) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(VaultShapes.input)
                        .clickable(enabled = backEnabled, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronLeft,
                        contentDescription = "Back",
                        tint = VaultColor.textBody,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(VaultColor.accent.copy(alpha = 0.12f), VaultShapes.input)
                        .border(1.dp, VaultColor.accent.copy(alpha = 0.32f), VaultShapes.input),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.security,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "SECURE WALLET PRO",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = VaultColor.textBody,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (step <= 1) "STEP 1 · MASTER KEY" else "STEP 2 · QUICK ACCESS",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    color = VaultColor.textFaint
                )
            }
            Text(
                text = "0${step.coerceIn(1, 2)} / 02",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = VaultColor.accent.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(2) { index ->
                val position = index + 1
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(VaultShapes.tag)
                        .background(
                            when {
                                position < step -> VaultColor.credit
                                position == step -> VaultColor.accent
                                else -> palette.surfaceOverlay.copy(alpha = 0.08f)
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun MasterStep(
    state: SetupUiState,
    busy: Boolean,
    viewModel: SetupViewModel,
    onContinue: () -> Unit
) {
    Column {
        Text(
            text = "Forge your\nmaster key.",
            style = MaterialTheme.typography.displaySmall,
            color = VaultColor.textStrong,
            lineHeight = 40.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This password unlocks the vault. It is stretched locally with 600,000 PBKDF2 rounds and never leaves this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColor.textBody,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(22.dp))
        GlassCard(accent = VaultColor.accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.lock,
                    contentDescription = null,
                    tint = VaultColor.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Master password",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
            }
            Spacer(Modifier.height(14.dp))
            VaultTextField(
                value = state.masterPassword,
                onValueChange = viewModel::setMasterPassword,
                label = "Master password",
                leadingIcon = Glyph.security,
                isPassword = true,
                enabled = !busy,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(10.dp))
            StrengthMeter(score = state.passwordScore, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.passwordLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = securityColor(state.passwordScore)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${state.masterPassword.length} characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip("8+ characters")
                TagChip("12+ recommended", accent = VaultColor.credit)
                TagChip("symbols add entropy", accent = VaultColor.warning)
            }
            Spacer(Modifier.height(14.dp))
            VaultTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::setConfirmPassword,
                label = "Confirm master password",
                leadingIcon = Glyph.checkCircle,
                isPassword = true,
                enabled = !busy,
                isError = state.confirmPassword.isNotEmpty() &&
                    state.confirmPassword != state.masterPassword,
                supportingText = if (state.confirmPassword.isNotEmpty() &&
                    state.confirmPassword != state.masterPassword
                ) {
                    "Passwords do not match"
                } else {
                    null
                },
                imeAction = ImeAction.Done,
                onImeSubmit = { if (state.masterReady && !busy) onContinue() }
            )
        }

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.debit
            )
        }

        Spacer(Modifier.height(22.dp))
        GradientButton(
            text = "Continue · quick access",
            icon = Glyph.chevronRight,
            enabled = state.masterReady && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onContinue
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "There is no recovery. Store this key somewhere physically safe.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun PinStep(
    state: SetupUiState,
    busy: Boolean,
    viewModel: SetupViewModel,
    onCreate: () -> Unit,
    onBack: () -> Unit
) {
    val palette = VaultPaletteController.current
    Column {
        Text(
            text = "Daily access,\nwithout friction.",
            style = MaterialTheme.typography.displaySmall,
            color = VaultColor.textStrong,
            lineHeight = 40.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose a short PIN for everyday unlocks, then enable face or fingerprint for instant entry.",
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColor.textBody,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(22.dp))
        GlassCard(accent = VaultColor.credit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.fingerprint,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Daily unlock PIN",
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = "4–8 digits · PBKDF2 210,000 · randomizable pad",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            VaultTextField(
                value = state.pin,
                onValueChange = viewModel::setPin,
                label = "PIN",
                leadingIcon = Glyph.lock,
                isPassword = true,
                keyboardType = KeyboardType.NumberPassword,
                enabled = !busy,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(10.dp))
            VaultTextField(
                value = state.confirmPin,
                onValueChange = viewModel::setConfirmPin,
                label = "Confirm PIN",
                leadingIcon = Glyph.lock,
                isPassword = true,
                keyboardType = KeyboardType.NumberPassword,
                enabled = !busy,
                isError = state.confirmPin.isNotEmpty() && state.confirmPin != state.pin,
                supportingText = if (state.confirmPin.isNotEmpty() && state.confirmPin != state.pin) {
                    "PINs do not match"
                } else {
                    null
                },
                imeAction = ImeAction.Done,
                onImeSubmit = { if (state.canSubmit && !busy) onCreate() }
            )
            if (state.biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceOverlay.copy(alpha = 0.035f), VaultShapes.input)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.fingerprint,
                        contentDescription = null,
                        tint = VaultColor.credit,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Face or fingerprint unlock",
                            style = MaterialTheme.typography.labelLarge,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = "BiometricPrompt · no lock-screen PIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                    Switch(
                        checked = state.useBiometric,
                        onCheckedChange = viewModel::setUseBiometric,
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VaultColor.background,
                            checkedTrackColor = VaultColor.credit,
                            uncheckedThumbColor = VaultColor.textFaint,
                            uncheckedTrackColor = VaultColor.elevated
                        )
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.debit
            )
        }

        Spacer(Modifier.height(22.dp))
        GradientButton(
            text = if (busy) "Sealing your vault…" else "Create secure vault",
            icon = Glyph.lock,
            enabled = state.canSubmit && !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCreate
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Back to master key", color = VaultColor.textFaint)
        }
        Text(
            text = "By creating this vault you accept that the master password cannot be reset. A decoy PIN can be configured later in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint.copy(alpha = 0.75f),
            lineHeight = 14.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}
