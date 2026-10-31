package com.securevault.pro.presentation.screens.lock

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.presentation.components.BiometricUtils
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.security.keyboard.SecureKeyboardHost
import com.securevault.pro.security.keyboard.SecurePasswordField
import com.securevault.pro.presentation.theme.ThemeDesign
import com.securevault.pro.presentation.theme.VaultPaletteController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

private const val BIOMETRIC_RESUME_COOLDOWN_MS = 20_000L

// Height tiers, measured on the space that really remains after the system bars (and, for the
// master-password alternative, the keyboard). The keypad shrinks with the tier instead of
// scrolling: four key rows at the regular size do not fit a short phone.
private val LOCK_COMPACT_BELOW = 820.dp
private val LOCK_DENSE_BELOW = 620.dp

@Composable
fun LockScreen(viewModel: LockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val haptics = LocalHapticFeedback.current

    var pin by remember { mutableStateOf("") }
    var master by remember { mutableStateOf("") }
    var masterMode by remember { mutableStateOf(false) }
    var keypadSeed by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var biometricInFlight by remember { mutableStateOf(false) }
    var autoPrompted by remember { mutableStateOf(false) }
    var lastPromptAt by remember { mutableLongStateOf(0L) }
    var manualEntry by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var promptDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // While an unacknowledged crash report is on screen the prompt is never fired on the
    // app's own initiative: a crash caused by it would otherwise kill the report that
    // explains it, every single launch. The button stays, so unlocking is still one tap.
    val crashPending by CrashLogger.pending.collectAsStateWithLifecycle()

    val lockedOut = state.lockoutUntil > now
    val secondsLeft = (((state.lockoutUntil - now) + 999L) / 1000L).coerceAtLeast(0L)
    val canUseBiometric = state.ready && state.biometricEnabled && state.biometricAvailable
    val biometricGate = canUseBiometric && !manualEntry && !lockedOut

    fun openBiometric() {
        if (biometricInFlight || activity == null || state.lockoutUntil > System.currentTimeMillis()) return
        lastPromptAt = SystemClock.elapsedRealtime()
        biometricInFlight = true
        CrashLogger.noteStage("locked/biometric-prompt")
        // The prompt talks to a system service that OEM builds put in unusual states, and
        // an exception from it used to arrive on the main thread with nothing to catch it.
        val started = runCatching {
            BiometricUtils.authenticate(
                activity = activity,
                title = "Unlock Secure Wallet Pro",
                subtitle = "Use your face or fingerprint to unlock instantly",
                onSuccess = {
                    biometricInFlight = false
                    biometricError = null
                    viewModel.onBiometricSuccess()
                },
                onError = { message ->
                    biometricInFlight = false
                    biometricError = message
                    manualEntry = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onCancel = {
                    biometricInFlight = false
                    promptDismissed = true
                },
                onFallback = {
                    biometricInFlight = false
                    biometricError = null
                    manualEntry = true
                }
            )
        }
        if (started.isFailure) {
            biometricInFlight = false
            manualEntry = true
            biometricError = "The biometric prompt is unavailable. Use your PIN or master password."
        }
    }

    LaunchedEffect(state.lockoutUntil) {
        if (state.lockoutUntil > System.currentTimeMillis()) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= state.lockoutUntil) {
                    viewModel.refreshLockoutState()
                    keypadSeed++
                    break
                }
                delay(250)
            }
        } else {
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            pin = ""
            master = ""
            keypadSeed++
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(state.lockoutUntil) {
        if (state.lockoutUntil > System.currentTimeMillis()) {
            pin = ""
            master = ""
        }
    }

    LaunchedEffect(lockedOut) {
        if (lockedOut) manualEntry = true
    }

    LaunchedEffect(
        state.ready,
        state.biometricEnabled,
        state.biometricAvailable,
        state.lockoutUntil,
        manualEntry,
        crashPending
    ) {
        if (biometricGate && !autoPrompted && crashPending == null) {
            autoPrompted = true
            delay(350)
            openBiometric()
        }
    }

    // Re-arm biometric unlock whenever the user returns to the app, with a cooldown
    // so cancelling the prompt does not immediately trigger it again.
    DisposableEffect(
        lifecycleOwner,
        state.ready,
        state.biometricEnabled,
        state.biometricAvailable,
        lockedOut,
        manualEntry,
        crashPending
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (crashPending != null) return@LifecycleEventObserver
            if (!state.ready || !state.biometricEnabled || !state.biometricAvailable) {
                return@LifecycleEventObserver
            }
            if (state.lockoutUntil > System.currentTimeMillis()) return@LifecycleEventObserver
            if (manualEntry) return@LifecycleEventObserver
            if (SystemClock.elapsedRealtime() - lastPromptAt < BIOMETRIC_RESUME_COOLDOWN_MS) {
                return@LifecycleEventObserver
            }
            scope.launch {
                delay(400)
                if (!biometricInFlight && !manualEntry) openBiometric()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val digits = remember(keypadSeed, state.randomizeKeypad) {
        if (state.randomizeKeypad) (1..9).shuffled(Random(keypadSeed + 17)) else (1..9).toList()
    }

    val transition = rememberInfiniteTransition(label = "lockGlow")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Reverse),
        label = "glow"
    )

    // The master password types through the in-app secure board. The board is a sibling of
    // the screen, so the height tiers below shrink for it instead of the action button hiding
    // behind the keys.
    SecureKeyboardHost(modifier = Modifier.fillMaxSize()) {
        NeonBackground {
            // No scroll container: the IME (PIN flows) and the secure board (master password)
            // shrink this box instead, the column's weighted gaps absorb the loss, and the tier
            // below shrinks the keypad.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val available = maxHeight
                val compact = available < LOCK_COMPACT_BELOW
                val dense = available < LOCK_DENSE_BELOW

                val keySize: Dp = when {
                    dense -> 54.dp
                    compact -> 62.dp
                    else -> 72.dp
                }
                val keyGap: Dp = when {
                    dense -> 8.dp
                    compact -> 12.dp
                    else -> 16.dp
                }
                val keyPad: Dp = if (compact) 8.dp else 11.dp
                val emblemOuter: Dp = when {
                    dense -> 0.dp
                    compact -> 64.dp
                    else -> 104.dp
                }
                val emblemInner: Dp = if (compact) 48.dp else 78.dp
                val emblemIcon: Dp = if (compact) 24.dp else 34.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (compact) 6.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (compact) 30.dp else 34.dp)
                                .background(VaultColor.accent.copy(alpha = 0.12f), VaultShapes.input)
                                .border(1.dp, VaultColor.accent.copy(alpha = 0.3f), VaultShapes.input),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.security,
                                contentDescription = null,
                                tint = VaultColor.accent,
                                modifier = Modifier.size(if (compact) 15.dp else 17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "SECURE WALLET PRO",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = VaultColor.textBody,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            if (!dense) {
                                Text(
                                    text = "ENCRYPTED SESSION",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint,
                                    maxLines = 1
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(VaultColor.accent.copy(alpha = glow), CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "LOCKED",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                                color = VaultColor.accent.copy(alpha = 0.8f)
                            )
                        }
                    }

                    if (emblemOuter > 0.dp) {
                        Spacer(Modifier.weight(if (compact) 0.45f else 0.7f))
                        Box(
                            modifier = Modifier
                                .size(emblemOuter)
                                .align(Alignment.CenterHorizontally),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(emblemOuter)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(VaultColor.accent.copy(alpha = 0.16f * glow), Color.Transparent)
                                        ),
                                        CircleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(emblemInner)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(VaultColor.elevated, VaultColor.surface)
                                        ),
                                        RoundedCornerShape(VaultPaletteController.current.cardCorner.coerceAtMost(26.dp))
                                    )
                                    .border(
                                        1.dp,
                                        VaultColor.accent.copy(alpha = 0.28f),
                                        RoundedCornerShape(VaultPaletteController.current.cardCorner.coerceAtMost(26.dp))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                ThemedIcon(
                                    glyph = if (biometricGate) Glyph.fingerprint else Glyph.lock,
                                    contentDescription = null,
                                    tint = VaultColor.accent,
                                    modifier = Modifier.size(emblemIcon)
                                )
                            }
                        }
                        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        text = "Welcome back.",
                        style = when {
                            dense -> MaterialTheme.typography.titleLarge
                            compact -> MaterialTheme.typography.headlineSmall
                            else -> MaterialTheme.typography.headlineMedium
                        },
                        color = VaultColor.textStrong,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                    Text(
                        text = when {
                            !state.ready -> "Preparing your secure session…"
                            biometricGate -> "Confirm your fingerprint or face to unseal the vault."
                            masterMode -> "Enter your master password to unseal the vault."
                            else -> "Enter your ${state.pinLength}-digit PIN to unseal the vault."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(if (dense) 10.dp else if (compact) 14.dp else 22.dp))

                    if (!state.ready) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (compact) 72.dp else 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 2.4.dp,
                                color = VaultColor.accent
                            )
                        }
                    } else if (biometricGate) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            GradientButton(
                                text = if (biometricInFlight) {
                                    "Waiting for biometrics…"
                                } else {
                                    "Unlock with Face or fingerprint"
                                },
                                icon = Glyph.fingerprint,
                                modifier = Modifier.fillMaxWidth(),
                                loading = biometricInFlight,
                                onClick = { openBiometric() }
                            )
                            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                            Text(
                                text = when {
                                    crashPending != null ->
                                        "Automatic prompt is paused until the report below is cleared. Tap above whenever you are ready."
                                    promptDismissed -> "Prompt dismissed · tap above to try again"
                                    else ->
                                        "BiometricPrompt is armed. Your PIN and master password are never requested automatically."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (promptDismissed || crashPending != null) VaultColor.warning else VaultColor.textFaint,
                                textAlign = TextAlign.Center,
                                maxLines = if (compact) 2 else 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                            Text(
                                text = "Use PIN or master password",
                                style = MaterialTheme.typography.labelLarge,
                                color = VaultColor.accent,
                                modifier = Modifier
                                    .clip(VaultShapes.input)
                                    .clickable {
                                        manualEntry = true
                                        biometricError = null
                                        viewModel.clearError()
                                    }
                                    .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 10.dp)
                            )
                        }
                    } else {
                        if (!masterMode) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                repeat(state.pinLength) { index ->
                                    val filled = index < pin.length
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = if (compact) 4.dp else 5.dp)
                                            .size(if (filled) 13.dp else 12.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (filled) VaultColor.accent else Color.Transparent
                                            )
                                            .border(
                                                1.dp,
                                                if (filled) VaultColor.accent else VaultColor.hairline,
                                                CircleShape
                                            )
                                    )
                                }
                            }
                            Spacer(Modifier.height(if (compact) 4.dp else 9.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (compact) 14.dp else 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 1.8.dp,
                                        color = VaultColor.accent
                                    )
                                }
                            }
                        } else {
                            SecurePasswordField(
                                value = master,
                                onValueChange = { master = it },
                                label = "Master password",
                                onSubmit = {
                                    if (master.isNotBlank() && !state.busy && !lockedOut) {
                                        viewModel.submit(master)
                                    }
                                }
                            )
                            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                            GradientButton(
                                text = if (state.busy) "Verifying…" else "Unseal vault",
                                modifier = Modifier.fillMaxWidth(),
                                loading = state.busy,
                                enabled = master.isNotBlank() && !lockedOut,
                                onClick = { viewModel.submit(master) }
                            )
                        }

                        if (lockedOut) {
                            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ThemedIcon(
                                    glyph = Glyph.errorOutline,
                                    contentDescription = null,
                                    tint = VaultColor.debit,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Too many attempts · retry in ${secondsLeft}s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VaultColor.debit
                                )
                            }
                        } else {
                            val message = biometricError ?: state.error
                            if (message != null) {
                                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ThemedIcon(
                                        glyph = Glyph.errorOutline,
                                        contentDescription = null,
                                        tint = VaultColor.debit,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = VaultColor.debit,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(if (compact) 0.45f else 0.65f))

                    if (state.ready && !biometricGate) {
                        if (!masterMode) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (lockedOut) 0.35f else 1f),
                                verticalArrangement = Arrangement.spacedBy(keyGap)
                            ) {
                                digits.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        row.forEach { number ->
                                            PinKey(
                                                text = number.toString(),
                                                enabled = !lockedOut && !state.busy,
                                                size = keySize,
                                                onClick = {
                                                    if (pin.length < state.pinLength) {
                                                        pin += number.toString()
                                                        if (pin.length == state.pinLength) {
                                                            viewModel.submit(pin)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.padding(horizontal = keyPad)
                                            )
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = keyPad)
                                            .size(keySize),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (state.biometricEnabled && state.biometricAvailable) {
                                            KeyCircle(
                                                enabled = !lockedOut,
                                                onClick = { openBiometric() },
                                                borderColor = VaultColor.accent.copy(alpha = 0.4f)
                                            ) {
                                                ThemedIcon(
                                                    glyph = Glyph.fingerprint,
                                                    contentDescription = "Unlock with biometrics",
                                                    tint = VaultColor.accent,
                                                    modifier = Modifier.size(if (compact) 22.dp else 28.dp)
                                                )
                                            }
                                        }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .padding(horizontal = keyPad)
                                            .size(keySize)
                                            .clip(CircleShape)
                                            .clickable(enabled = !lockedOut && !state.busy) {
                                                if (pin.length < state.pinLength) {
                                                    pin += "0"
                                                    if (pin.length == state.pinLength) {
                                                        viewModel.submit(pin)
                                                    }
                                                }
                                            }
                                            .background(VaultColor.elevated.copy(alpha = 0.62f))
                                            .border(1.dp, VaultColor.hairline, CircleShape),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "0",
                                            style = if (compact) {
                                                MaterialTheme.typography.titleLarge
                                            } else {
                                                MaterialTheme.typography.headlineMedium
                                            },
                                            color = VaultColor.textStrong,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = keyPad)
                                            .size(keySize),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        KeyCircle(
                                            enabled = pin.isNotEmpty() && !lockedOut && !state.busy,
                                            onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
                                        ) {
                                            ThemedIcon(
                                                glyph = Glyph.backspace,
                                                contentDescription = "Delete digit",
                                                tint = if (pin.isNotEmpty()) VaultColor.textBody else VaultColor.textFaint,
                                                modifier = Modifier.size(if (compact) 20.dp else 24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (state.ready && !biometricGate) {
                        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                        Text(
                            text = if (masterMode) "Use PIN instead" else "Use master password",
                            style = MaterialTheme.typography.labelLarge,
                            color = VaultColor.accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .clickable {
                                    masterMode = !masterMode
                                    pin = ""
                                    master = ""
                                    biometricError = null
                                    viewModel.clearError()
                                }
                                .padding(vertical = if (compact) 7.dp else 10.dp)
                        )
                        if (canUseBiometric) {
                            Text(
                                text = "Use biometrics instead",
                                style = MaterialTheme.typography.labelMedium,
                                color = VaultColor.credit,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(VaultShapes.input)
                                    .clickable {
                                        manualEntry = false
                                        biometricError = null
                                        promptDismissed = false
                                        autoPrompted = false
                                        pin = ""
                                        master = ""
                                        viewModel.clearError()
                                    }
                                    .padding(vertical = if (compact) 5.dp else 8.dp)
                            )
                        }
                    }

                    if (!compact) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (CrashLogger.safeRender) {
                                "Safe render mode · effects and capture blocking off after repeated closes"
                            } else {
                                "AES-256-GCM · Keystore sealed · screen capture blocked"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                            color = VaultColor.textFaint.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(VaultColor.elevated.copy(alpha = 0.62f))
            .border(1.dp, VaultColor.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = if (size < 66.dp) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = VaultColor.textStrong,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun KeyCircle(
    enabled: Boolean,
    onClick: () -> Unit,
    borderColor: Color = VaultColor.hairline,
    content: @Composable () -> Unit
) {
    val palette = VaultPaletteController.current
    val shape = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> palette.chipShape
        ThemeDesign.BENTO -> VaultShapes.button
        ThemeDesign.NEO_SKEUOMORPHIC -> VaultShapes.button
        ThemeDesign.CLAYMORPHISM -> VaultShapes.card
        else -> CircleShape
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(VaultColor.elevated.copy(alpha = if (enabled) 0.55f else 0.25f))
            .border(palette.cardBorderWidth.coerceAtLeast(1.dp), borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
