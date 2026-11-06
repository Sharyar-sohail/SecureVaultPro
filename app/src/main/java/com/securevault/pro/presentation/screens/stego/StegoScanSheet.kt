package com.securevault.pro.presentation.screens.stego

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The viewfinder behind "Scan a printed photo": the physical page goes in through the lens, and what
 * leaves through [onCapture] is the same [LumaPlane] a picked file becomes.
 *
 * That is the whole design. The sheet owns the camera, the torch and the live "is there a mark in
 * frame" hint — and nothing else: no decode, no passphrase, no verdict. The locked frame is handed to
 * the view model, joins the carrier list, and is read by the reveal pipeline that already exists, so
 * a print and a file can never diverge in what they report.
 *
 * The hint comes from [StegoCodec.probe], which answers only "does this frame carry our directory" —
 * cheap enough to run on a live frame and, deliberately, not a decode. Closing this sheet at any
 * point costs nothing: the carrier list is only ever touched by the shutter.
 */
@Composable
internal fun StegoScanSheet(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onCapture: (LumaPlane) -> Unit
) {
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val codec = remember { StegoCodec.production() }
    val newest = remember { AtomicReference<LumaPlane?>(null) }
    val delivered = remember { AtomicBoolean(false) }
    val closed = remember { AtomicBoolean(false) }
    val lastProbeAt = remember { AtomicLong(0L) }

    var waiting by remember { mutableStateOf(true) }
    var markInFrame by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        closed.set(false)
        onDispose { closed.set(true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColor.void)
    ) {
        StegoViewfinder(
            modifier = Modifier.fillMaxSize(),
            paused = locked,
            torch = torch,
            onCameraError = { failure -> cameraError = failure },
            onFrame = { plane ->
                newest.set(plane)
                val elapsed = SystemClock.elapsedRealtime()
                val due = !delivered.get() && elapsed - lastProbeAt.get() >= PROBE_EVERY_MS
                if (due) {
                    lastProbeAt.set(elapsed)
                    val located = runCatching { codec.probe(plane) != null }.getOrDefault(false)
                    mainExecutor.execute {
                        if (!closed.get()) {
                            waiting = false
                            markInFrame = located
                        }
                    }
                }
            }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawFrameGuide(markInFrame || locked)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScrimButton(
                    icon = Glyph.close,
                    description = "Close the scanner",
                    tint = VaultColor.inkOnScrim,
                    onClick = onDismiss
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan a printed photo",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = when {
                            cameraError != null -> "Camera unavailable"
                            locked -> "Frame locked"
                            markInFrame -> "Mark found — hold steady"
                            waiting -> "Waking the camera…"
                            else -> "Fill the frame with the page"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            cameraError != null -> VaultColor.debit
                            locked || markInFrame -> VaultColor.credit
                            else -> VaultColor.textFaint
                        }
                    )
                }
                ScrimButton(
                    icon = Glyph.flashOn,
                    description = "Toggle the light",
                    tint = if (torch) VaultColor.warning else VaultColor.inkOnScrim.copy(alpha = 0.75f),
                    onClick = { torch = !torch }
                )
            }
            cameraError?.let { failure ->
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(VaultColor.debit.copy(alpha = 0.14f))
                        .border(1.dp, VaultColor.debit.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.errorOutline,
                        contentDescription = null,
                        tint = VaultColor.debit,
                        size = 18.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textStrong,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(
                        text = "Add a photo",
                        icon = Glyph.photoLibrary,
                        accent = VaultColor.decorative,
                        onClick = onPickPhoto
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(VaultColor.void.copy(alpha = 0.55f))
                .padding(top = 14.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Lay the page flat, keep it inside the frame, and tap to lock one frame.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                modifier = Modifier.padding(horizontal = 26.dp)
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(3.dp, if (locked) VaultColor.credit else VaultColor.accent, CircleShape)
                    .background(VaultColor.surfaceLift.copy(alpha = 0.12f))
                    .clickable(enabled = !waiting && !locked && cameraError == null) {
                        val frame = newest.get()
                        if (frame != null && delivered.compareAndSet(false, true)) {
                            locked = true
                            buzz(context)
                            onCapture(frame)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(VaultColor.inkOnScrim)
                )
            }
        }

        if (waiting && cameraError == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VaultColor.void.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = VaultColor.accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

/**
 * A directory probe costs a real sweep of the lattice candidates, so it runs three times a second
 * rather than once per frame; the preview stays smooth and the analyser thread keeps its budget.
 */
private const val PROBE_EVERY_MS = 320L

/** The page window, plus the line the mark is read across. Turns green when a directory answers. */
private fun DrawScope.drawFrameGuide(located: Boolean) {
    val accent = if (located) VaultColor.credit else VaultColor.accent
    val inset = size.width * 0.07f
    val top = size.height * 0.15f
    val bottom = size.height * 0.66f
    drawRoundRect(
        color = accent.copy(alpha = 0.7f),
        topLeft = Offset(inset, top),
        size = Size(size.width - inset * 2f, bottom - top),
        cornerRadius = CornerRadius(24f.dp.toPx(), 24f.dp.toPx()),
        style = Stroke(width = 2.5f.dp.toPx())
    )
    val middle = (top + bottom) / 2f
    drawLine(
        color = accent.copy(alpha = 0.3f),
        start = Offset(inset, middle),
        end = Offset(size.width - inset, middle),
        strokeWidth = 1.5f.dp.toPx()
    )
}

@Composable
private fun ScrimButton(
    icon: Glyph,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(VaultColor.void.copy(alpha = 0.5f))
            .border(1.dp, VaultColor.hairlineOnScrim.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun buzz(context: Context) {
    runCatching {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }
        vibrator?.takeIf { it.hasVibrator() }?.vibrate(
            android.os.VibrationEffect.createOneShot(24, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}
