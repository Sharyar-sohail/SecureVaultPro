package com.securevault.pro.presentation.screens.welcome

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.securevault.pro.security.keyboard.SecureKeyboardHost
import com.securevault.pro.security.keyboard.SecurePasswordField

// Height tiers. `available` is the height the form really has after status/navigation bars and
// the IME have been subtracted, so a keyboard that leaves too little room moves the screen into
// the compact form by itself. Nothing here is a device guess.
private val SETUP_COMPACT_BELOW = 720.dp
private val SETUP_DENSE_BELOW = 520.dp

@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val busy = state.busy || cloudState.active

    NeonBackground {
        // The window never resizes or scrolls: imePadding eats into this container instead, so
        // `maxHeight` is exactly the room the fields and the action button have while typing.
        // One dock for both master-password fields. The board is measured as a sibling of the
        // form, so maxHeight below already excludes it when the keys are up.
        SecureKeyboardHost(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val available = maxHeight
                val compact = available < SETUP_COMPACT_BELOW
                val dense = available < SETUP_DENSE_BELOW

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = vaultGutter(),
                            vertical = if (dense) 6.dp else if (compact) 8.dp else 12.dp
                        )
                ) {
                    SetupHeader(
                        step = state.step,
                        onBack = viewModel::previousStep,
                        backEnabled = !busy,
                        compact = compact
                    )

                    if (state.notice != null) {
                        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                        RestoredBanner(
                            message = state.notice.orEmpty(),
                            isError = state.noticeIsError,
                            warnings = state.warnings
                        )
                    }

                    // Both steps share one fillMaxSize container, and the crossfade only fades the
                    // outgoing form: the region keeps its height for the whole transition.
                    Crossfade(
                        targetState = state.step,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        animationSpec = tween(durationMillis = 220),
                        label = "setup-step"
                    ) { step ->
                        if (step <= 1) {
                            MasterStep(
                                state = state,
                                busy = busy,
                                viewModel = viewModel,
                                onContinue = viewModel::nextStep,
                                compact = compact,
                                dense = dense
                            )
                        } else {
                            PinStep(
                                state = state,
                                busy = busy,
                                viewModel = viewModel,
                                onCreate = viewModel::createVault,
                                onBack = viewModel::previousStep,
                                compact = compact,
                                dense = dense
                            )
                        }
                    }
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
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp)
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
private fun SetupHeader(step: Int, backEnabled: Boolean, onBack: () -> Unit, compact: Boolean) {
    val palette = VaultPaletteController.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 1) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 32.dp else 36.dp)
                        .clip(VaultShapes.input)
                        .clickable(enabled = backEnabled, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronLeft,
                        contentDescription = "Back",
                        tint = VaultColor.textBody,
                        modifier = Modifier.size(if (compact) 20.dp else 22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(if (compact) 32.dp else 38.dp)
                        .background(VaultColor.accent.copy(alpha = 0.12f), VaultShapes.input)
                        .border(1.dp, VaultColor.accent.copy(alpha = 0.32f), VaultShapes.input),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.security,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(if (compact) 17.dp else 20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "SECURE WALLET PRO",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = VaultColor.textBody,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = if (step <= 1) "STEP 1 · MASTER KEY" else "STEP 2 · QUICK ACCESS",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    color = VaultColor.textFaint,
                    maxLines = 1
                )
            }
            Text(
                text = "0${step.coerceIn(1, 2)} / 02",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = VaultColor.accent.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(if (compact) 9.dp else 14.dp))
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
    onContinue: () -> Unit,
    compact: Boolean,
    dense: Boolean
) {
    // Slack lives in weighted spacers. In a short window they collapse to zero instead of
    // pushing the action button below the fold, and there is no scroll container anywhere.
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(if (dense) 0.4f else 1f))

        Text(
            text = if (dense) "Forge your master key." else "Forge your\nmaster key.",
            style = when {
                dense -> MaterialTheme.typography.titleMedium
                compact -> MaterialTheme.typography.headlineMedium
                else -> MaterialTheme.typography.displaySmall
            },
            color = VaultColor.textStrong,
            lineHeight = when {
                dense -> 26.sp
                compact -> 32.sp
                else -> 40.sp
            },
            maxLines = 2
        )
        if (!dense) {
            Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
            Text(
                text = "This password unlocks the vault. It is stretched locally with 600,000 PBKDF2 rounds and never leaves this device.",
                style = if (compact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = VaultColor.textBody,
                lineHeight = if (compact) 18.sp else 21.sp,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(if (dense) 8.dp else if (compact) 12.dp else 22.dp))

        GlassCard(
            accent = VaultColor.accent,
            contentPadding = PaddingValues(if (dense) 11.dp else if (compact) 14.dp else 18.dp)
        ) {
            if (!dense) {
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
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            }
            SecurePasswordField(
                value = state.masterPassword,
                onValueChange = viewModel::setMasterPassword,
                label = "Master password"
            )
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            StrengthMeter(score = state.passwordScore, modifier = Modifier.fillMaxWidth())
            if (!dense) {
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
            }
            if (!compact) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip("8+ characters")
                    TagChip("12+ recommended", accent = VaultColor.credit)
                    TagChip("symbols add entropy", accent = VaultColor.warning)
                }
            }
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
            SecurePasswordField(
                value = state.confirmPassword,
                onValueChange = viewModel::setConfirmPassword,
                label = "Confirm master password",
                onSubmit = { if (state.masterReady && !busy) onContinue() }
            )
            if (state.confirmPassword.isNotEmpty() &&
                state.confirmPassword != state.masterPassword
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Passwords do not match",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.debit
                )
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.debit,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.weight(1f))
        GradientButton(
            text = "Continue · quick access",
            icon = Glyph.chevronRight,
            enabled = state.masterReady && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onContinue
        )
        if (!compact) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "There is no recovery. Store this key somewhere physically safe.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun PinStep(
    state: SetupUiState,
    busy: Boolean,
    viewModel: SetupViewModel,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    compact: Boolean,
    dense: Boolean
) {
    val palette = VaultPaletteController.current
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(if (dense) 0.4f else 1f))

        Text(
            text = if (dense) "Daily access PIN." else "Daily access,\nwithout friction.",
            style = when {
                dense -> MaterialTheme.typography.titleMedium
                compact -> MaterialTheme.typography.headlineMedium
                else -> MaterialTheme.typography.displaySmall
            },
            color = VaultColor.textStrong,
            lineHeight = when {
                dense -> 26.sp
                compact -> 32.sp
                else -> 40.sp
            },
            maxLines = 2
        )
        if (!dense) {
            Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
            Text(
                text = "Choose a short PIN for everyday unlocks, then enable face or fingerprint for instant entry.",
                style = if (compact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = VaultColor.textBody,
                lineHeight = if (compact) 18.sp else 21.sp,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(if (dense) 8.dp else if (compact) 12.dp else 22.dp))

        GlassCard(
            accent = VaultColor.credit,
            contentPadding = PaddingValues(if (dense) 11.dp else if (compact) 14.dp else 18.dp)
        ) {
            if (!dense) {
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
                        if (!compact) {
                            Text(
                                text = "4–8 digits · PBKDF2 210,000 · randomizable pad",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            }
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
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
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
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceOverlay.copy(alpha = 0.035f), VaultShapes.input)
                        .padding(
                            horizontal = 12.dp,
                            vertical = if (compact) 6.dp else 8.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.fingerprint,
                        contentDescription = null,
                        tint = VaultColor.credit,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    if (dense) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Face or fingerprint unlock",
                                style = MaterialTheme.typography.labelLarge,
                                color = VaultColor.textStrong
                            )
                            if (!compact) {
                                Text(
                                    text = "BiometricPrompt · no lock-screen PIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                        }
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
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.debit,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.weight(1f))
        GradientButton(
            text = if (busy) "Sealing your vault…" else "Create secure vault",
            icon = Glyph.lock,
            enabled = state.canSubmit && !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCreate
        )
        if (!compact) {
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
}
