package com.securevault.pro.presentation.screens.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun VoiceRecorderScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onLock: () -> Unit,
    viewModel: VoiceRecorderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var startAfterGrant by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (result && startAfterGrant) viewModel.startRecording()
        startAfterGrant = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (state.recording) {
            viewModel.cancelRecording()
        }
    }

    fun requestRecording() {
        if (granted) {
            viewModel.startRecording()
        } else {
            startAfterGrant = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    GlassScaffold(
        title = "Voice memos",
        subtitle = "Sealed audio · microphone stays on-device",
        onBack = onBack,
        actions = {
            GlassIconButton(
                icon = Glyph.folder,
                contentDescription = "Open file vault",
                onClick = onOpenFiles,
                tint = VaultColor.accent
            )
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.accent
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 4.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(accent = VaultColor.credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(VaultShapes.button)
                                .background(VaultColor.credit.copy(alpha = 0.12f))
                                .border(1.dp, VaultColor.credit.copy(alpha = 0.3f), VaultShapes.button),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.mic,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Encrypted audio vault",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Voice memos are sealed with AES-256-GCM before they touch disk and play only inside the vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagChip("MIC ONLY", accent = VaultColor.credit)
                        TagChip("NO UPLOAD", accent = VaultColor.accent)
                        TagChip("LOCAL CIPHERTEXT", accent = VaultColor.warning)
                    }
                }
            }

            if (state.decoy) {
                item {
                    GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.lock,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Restricted session · recording and playback are disabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                    }
                }
            }

            if (!granted && !state.decoy) {
                item {
                    GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.mic,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Microphone access is required to capture a memo. Audio never leaves this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                modifier = Modifier.weight(1f),
                                lineHeight = 17.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GradientButton(
                                text = "Allow microphone",
                                icon = Glyph.mic,
                                modifier = Modifier.weight(1f),
                                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            )
                            GhostButton(
                                text = "Settings",
                                modifier = Modifier.weight(0.5f),
                                onClick = { openAppSettings(context) }
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(accent = if (state.recording) VaultColor.debit else VaultColor.credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                state.saving -> "Sealing memo"
                                state.recording -> "Recording"
                                else -> "Ready"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = if (state.recording) VaultColor.debit else VaultColor.textFaint,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.recording) {
                            TagChip("LIVE", accent = VaultColor.debit)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = elapsedLabel(state.elapsedMs),
                        style = VaultType.hero().copy(fontFamily = FontFamily.Monospace),
                        color = if (state.recording) VaultColor.debit else VaultColor.textStrong
                    )
                    Spacer(Modifier.height(8.dp))
                    Waveform(
                        amplitude = if (state.recording) state.amplitude else 0f,
                        active = state.recording
                    )
                    Spacer(Modifier.height(14.dp))
                    when {
                        state.saving -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = VaultColor.credit
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Encrypting and storing the memo…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody
                                )
                            }
                        }
                        state.recording -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GradientButton(
                                    text = "Stop & seal",
                                    icon = Glyph.stop,
                                    modifier = Modifier.weight(1f),
                                    onClick = viewModel::stopAndSave
                                )
                                GhostButton(
                                    text = "Discard",
                                    icon = Glyph.delete,
                                    accent = VaultColor.debit,
                                    modifier = Modifier.weight(0.55f),
                                    onClick = viewModel::cancelRecording
                                )
                            }
                        }
                        else -> {
                            GradientButton(
                                text = "Record memo",
                                icon = Glyph.mic,
                                enabled = !state.decoy,
                                onClick = ::requestRecording
                            )
                        }
                    }
                    state.error?.let { error ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.errorOutline,
                                contentDescription = null,
                                tint = VaultColor.debit,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.debit,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "DISMISS",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint,
                                modifier = Modifier
                                    .clip(VaultShapes.chip)
                                    .clickable(onClick = viewModel::clearError)
                                    .padding(horizontal = 5.dp, vertical = 3.dp)
                            )
                        }
                    }
                    state.savedName?.let { name ->
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .background(VaultColor.credit.copy(alpha = 0.09f))
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThemedIcon(
                                glyph = Glyph.checkCircle,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Sealed as $name",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.credit,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "OK",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.accent,
                                modifier = Modifier
                                    .clip(VaultShapes.chip)
                                    .clickable(onClick = viewModel::dismissSaved)
                                    .padding(horizontal = 5.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Sealed memos",
                    actionLabel = "${state.memos.size} files",
                    onAction = onOpenFiles
                )
            }

            if (state.memos.isEmpty()) {
                item {
                    EmptyState(
                        icon = Glyph.musicNote,
                        title = "No voice memos yet",
                        message = "Record a memo above. Each clip is encrypted immediately and appears only in this vault."
                    )
                }
            } else {
                items(state.memos.size, key = { state.memos[it].id }) { index ->
                    val memo = state.memos[index]
                    MemoRow(memo = memo, onOpen = { onOpenFile(memo.id) })
                }
            }

            item {
                GlassCard(accent = VaultColor.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.openInNew,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Playback happens in the in-app secure viewer. Recording stops and the draft is discarded if the app leaves the foreground.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Waveform(amplitude: Float, active: Boolean) {
    val palette = VaultPaletteController.current
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing)
        ),
        label = "phase"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(VaultShapes.input)
            .background(palette.surfaceShade.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(26) { index ->
            val value = if (active) {
                4f + amplitude * 40f * (0.2f + 0.8f * abs(sin(phase + index * 0.55f)))
            } else {
                4f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(value.dp)
                    .clip(VaultShapes.tag)
                    .background(
                        if (active) {
                            Brush.verticalGradient(listOf(VaultColor.debit, VaultColor.warning))
                        } else {
                            Brush.verticalGradient(listOf(VaultColor.textFaint.copy(alpha = 0.25f), VaultColor.textFaint.copy(alpha = 0.25f)))
                        }
                    )
            )
        }
    }
}

@Composable
private fun MemoRow(memo: VaultFile, onOpen: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(VaultColor.credit.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.musicNote,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = memo.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(memo.size)} · ${formatShortDate(memo.uploadedAt)} · sealed",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TagChip("PLAY", accent = VaultColor.credit)
        }
    }
}

private fun elapsedLabel(ms: Long): String {
    val total = (ms / 1_000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", total / 60L, total % 60L)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
