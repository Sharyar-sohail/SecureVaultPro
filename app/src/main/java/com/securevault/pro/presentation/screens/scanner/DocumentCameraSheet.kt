package com.securevault.pro.presentation.screens.scanner

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import com.securevault.pro.data.scanner.DetectionFailure
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon

/** A camera frame the user accepted: upright JPEG bytes plus the outline it was framed with. */
internal data class LiveCapture(val jpeg: ByteArray, val quad: FloatArray?)

private const val AUTO_STABLE_FRAMES = 7

/**
 * The side length the viewfinder is shrunk to before it is searched.
 *
 * The saved page is analysed at the detector's own 640 px probe; a frame that runs thirty times a
 * second cannot be, or the analysis queue backs up and the overlay lags the phone instead of the
 * paper. 480 px still keeps a corner to within about a texel once it is mapped back.
 */
private const val LIVE_PROBE_LONGEST = 480

/** Confidence an outline needs before it is drawn at all — below this the box would be a guess. */
private const val LIVE_MIN_CONFIDENCE = 0.30f

/** Per-corner confidence and area the shutter is allowed to trust, tighter than the display gate. */
private const val AUTO_MIN_CONFIDENCE = 0.45f
private const val AUTO_MAX_AREA_TRIGGER = 0.90f

/** Milliseconds the outline must have sat still before an automatic capture fires. */
private const val AUTO_STABLE_MILLIS = 700L

/** Normalized movement between two blended outlines that is too small to be worth reposting. */
private const val OVERLAY_DEADBAND = 0.004f

/** Mean luminance under which the torch is offered; a lit room sits well above this. */
private const val DARK_FRAME_LUMA = 78

/** True when the frame is too dim to threshold reliably, so the light is worth turning on. */
private fun isFrameDark(gray: IntArray): Boolean {
    if (gray.isEmpty()) return false
    var total = 0L
    for (value in gray) total += value
    return total / gray.size < DARK_FRAME_LUMA
}

/** True when [next] moved further from [previous] than [deadband] at any corner. */
private fun drifts(next: FloatArray, previous: FloatArray, deadband: Float): Boolean {
    if (next.size != previous.size) return true
    for (index in next.indices) if (kotlin.math.abs(next[index] - previous[index]) > deadband) return true
    return false
}

/** The one line under the title that says what the viewfinder is doing about the page in front of it. */
private fun viewfinderLine(
    cameraError: String?,
    starting: Boolean,
    locked: Boolean,
    confident: Boolean,
    guidance: DetectionFailure
): String = when {
    cameraError != null -> "Camera unavailable"
    starting -> "Waking the camera…"
    locked -> "Capturing…"
    !guidance.rejected && confident -> "Sheet found — hold steady"
    guidance.rejected -> guidance.message
    else -> "Fill the frame with one page"
}

/**
 * In-app viewfinder with live edge detection, so a scan never depends on a third-party
 * camera app answering an ACTION_IMAGE_CAPTURE intent — which silently fails on many
 * Android 11+ devices because of package-visibility rules.
 */
@OptIn(ExperimentalGetImage::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
internal fun DocumentCameraSheet(
    onDismiss: () -> Unit,
    onCaptured: (LiveCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    val captureRequested = remember { AtomicBoolean(false) }
    val delivered = remember { AtomicBoolean(false) }
    val autoEnabled = remember { AtomicBoolean(true) }
    val autoLightEnabled = remember { AtomicBoolean(true) }

    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(true) }
    var autoCapture by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var autoLight by remember { mutableStateOf(true) }
    var guidance by remember { mutableStateOf(DetectionFailure.NONE) }
    var confident by remember { mutableStateOf(false) }

    LaunchedEffect(autoCapture) { autoEnabled.set(autoCapture) }
    LaunchedEffect(autoLight) { autoLightEnabled.set(autoLight) }
    LaunchedEffect(torch, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torch) }
    }

    DisposableEffect(Unit) {
        disposed.set(false)
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                if (disposed.get()) return@Runnable
                val provider = future.get()
                if (disposed.get()) return@Runnable
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1600, 1200),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val smoother = QuadSmoother()
                var stable = 0
                var settledSince = 0L
                var posted: FloatArray? = null
                var postedReason: DetectionFailure? = null
                var litTorch: Boolean? = null
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    if (disposed.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    var result: LiveCapture? = null
                    try {
                        val media = proxy.image
                        val rotation = proxy.imageInfo.rotationDegrees
                        if (media != null && !delivered.get()) {
                            val frame = lumaFrameOf(media, rotation, LIVE_PROBE_LONGEST)
                            if (frame != null) {
                                val found = detectLiveDocument(frame)
                                val outline = found.quad
                                    ?.takeIf { found.confidence >= LIVE_MIN_CONFIDENCE }
                                    ?.let { quadToNormalized(orderedNormalized(it), frame) }
                                val now = SystemClock.elapsedRealtime()

                                // The torch follows the frame, not a guess about the room, and only
                                // while the user has not taken the light over by hand.
                                val wantTorch = autoLightEnabled.get() && isFrameDark(frame.gray)
                                if (autoLightEnabled.get() && litTorch != wantTorch) {
                                    litTorch = wantTorch
                                    mainExecutor.execute {
                                        if (!disposed.get() && autoLightEnabled.get()) torch = wantTorch
                                    }
                                }

                                if (outline == null) {
                                    smoother.reset()
                                    stable = 0
                                    settledSince = 0L
                                    val reason = found.failure
                                    if (postedReason !== reason || posted != null) {
                                        postedReason = reason
                                        posted = null
                                        mainExecutor.execute {
                                            if (!disposed.get()) {
                                                quad = null
                                                confident = false
                                                guidance = reason
                                            }
                                        }
                                    }
                                } else {
                                    val settled = smoother.isSettledAgainst(outline)
                                    val shown = smoother.blend(outline)
                                    stable = if (settled) stable + 1 else 0
                                    settledSince = if (settled && settledSince == 0L) now else if (!settled) 0L else settledSince
                                    val area = normalizedArea(shown)
                                    val lastShown = posted
                                    if (lastShown == null || drifts(shown, lastShown, OVERLAY_DEADBAND)) {
                                        posted = shown
                                        postedReason = DetectionFailure.NONE
                                        mainExecutor.execute {
                                            if (!disposed.get()) {
                                                quad = shown
                                                confident = true
                                                guidance = if (area > AUTO_MAX_AREA_TRIGGER) {
                                                    DetectionFailure.FRAME_FILLED
                                                } else {
                                                    DetectionFailure.NONE
                                                }
                                                frameSize = IntSize(frame.imageWidth, frame.imageHeight)
                                            }
                                        }
                                    }
                                    val trigger = autoEnabled.get() &&
                                        stable >= AUTO_STABLE_FRAMES &&
                                        found.confidence >= AUTO_MIN_CONFIDENCE &&
                                        area in AUTO_MIN_AREA_TRIGGER..AUTO_MAX_AREA_TRIGGER &&
                                        settledSince > 0L &&
                                        now - settledSince >= AUTO_STABLE_MILLIS
                                    if (trigger && captureRequested.compareAndSet(false, true)) {
                                        mainExecutor.execute {
                                            if (!disposed.get()) {
                                                locked = true
                                                hapticTick(context)
                                            }
                                        }
                                    }
                                }
                            }
                            if (captureRequested.get()) {
                                captureRequested.set(false)
                                val bytes = uprightJpegOf(media, rotation)
                                if (bytes != null && bytes.isNotEmpty()) {
                                    delivered.set(true)
                                    result = LiveCapture(bytes, smoother.current())
                                } else {
                                    mainExecutor.execute {
                                        if (!disposed.get()) {
                                            locked = false
                                            cameraError = "That frame could not be read. Try again."
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        captureRequested.set(false)
                    } finally {
                        proxy.close()
                    }
                    result?.let { capture ->
                        mainExecutor.execute {
                            if (!disposed.get()) onCaptured(capture)
                        }
                    }
                }
                provider.unbindAll()
                // The camera preview is the one surface in this app where a native graphics
                // death has no Java trace at all, so it records its own stage rather than
                // letting the last screen the owner walked through stay on record.
                CrashLogger.noteStage("camera/document preview")
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                mainExecutor.execute {
                    if (!disposed.get()) {
                        starting = false
                        camera = bound
                    }
                }
            } catch (failure: Throwable) {
                CrashLogger.noteEvent(
                    "document camera refused to start: ${(failure.message ?: failure.javaClass.simpleName).take(80)}"
                )
                mainExecutor.execute {
                    if (!disposed.get()) {
                        starting = false
                        cameraError = failure.message ?: "The camera could not be started."
                    }
                }
            }
        }
        future.addListener(listener, mainExecutor)
        onDispose {
            CrashLogger.noteEvent("document camera closed")
            disposed.set(true)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColor.void)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        val overlayQuad = quad
        val uprightWidth =
            (if (frameSize.width > frameSize.height) frameSize.height else frameSize.width).toFloat()
        val uprightHeight =
            (if (frameSize.width > frameSize.height) frameSize.width else frameSize.height).toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (showGrid) {
                gridLines()
            }
            val mapped = overlayQuad?.let {
                normalizedQuadToView(it, uprightWidth, uprightHeight, size.width, size.height, fill = true)
            }
            if (mapped == null) {
                val inset = size.width * 0.09f
                drawRoundRect(
                    color = VaultColor.accent.copy(alpha = 0.6f),
                    topLeft = Offset(inset, size.height * 0.17f),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height * 0.60f),
                    cornerRadius = CornerRadius(26f.dp.toPx(), 26f.dp.toPx()),
                    style = Stroke(width = 2.5f.dp.toPx())
                )
                return@Canvas
            }
            val accent = if (locked) VaultColor.credit else VaultColor.warning
            dimOutside(mapped, accent)
            val polygon = Path().apply {
                moveTo(mapped[0], mapped[1])
                lineTo(mapped[2], mapped[3])
                lineTo(mapped[4], mapped[5])
                lineTo(mapped[6], mapped[7])
                close()
            }
            drawPath(polygon, accent.copy(alpha = 0.10f))
            drawPath(polygon, accent, style = Stroke(width = 3f.dp.toPx(), cap = StrokeCap.Round))
            var index = 0
            while (index < 8) {
                val center = Offset(mapped[index], mapped[index + 1])
                drawCircle(color = accent, radius = 7f.dp.toPx(), center = center)
                drawCircle(
                    color = VaultColor.void.copy(alpha = 0.6f),
                    radius = 3.2f.dp.toPx(),
                    center = center
                )
                index += 2
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = Glyph.close,
                    description = "Close scanner",
                    tint = VaultColor.inkOnScrim,
                    onClick = onDismiss
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan document",
                        style = VaultType.sectionTitle(),
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = viewfinderLine(cameraError, starting, locked, confident, guidance),
                        style = VaultType.micro(),
                        color = when {
                            cameraError != null -> VaultColor.debit
                            locked || confident -> VaultColor.credit
                            else -> VaultColor.textFaint
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIconButton(
                        icon = Glyph.flashOn,
                        description = "Toggle torch",
                        tint = if (torch) VaultColor.warning else VaultColor.inkOnScrim.copy(alpha = 0.75f),
                        onClick = {
                            // Reaching for the light button ends the automatic call.
                            autoLight = false
                            torch = !torch
                        }
                    )
                    RoundIconButton(
                        icon = Glyph.gridOn,
                        description = "Toggle grid",
                        tint = if (showGrid) VaultColor.accent else VaultColor.inkOnScrim.copy(alpha = 0.75f),
                        onClick = { showGrid = !showGrid }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            cameraError?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(VaultColor.debit.copy(alpha = 0.14f))
                        .border(1.dp, VaultColor.debit.copy(alpha = 0.32f), VaultShapes.button)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.errorOutline,
                        contentDescription = null,
                        tint = VaultColor.debit,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(text = message, style = VaultType.caption(), color = VaultColor.textStrong, modifier = Modifier.weight(1f))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(VaultColor.void.copy(alpha = 0.55f))
                .padding(top = 14.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SheetChip(
                    label = "Auto capture",
                    active = autoCapture,
                    accent = VaultColor.credit,
                    icon = Glyph.flashOn,
                    onClick = { autoCapture = !autoCapture }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (autoCapture) {
                        "Shutters the moment the page stops moving"
                    } else {
                        "Tap the button to capture"
                    },
                    style = VaultType.micro(),
                    color = VaultColor.textFaint,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .border(3.dp, if (locked) VaultColor.credit else VaultColor.accent, CircleShape)
                    .background(VaultColor.surfaceLift.copy(alpha = 0.12f))
                    .clickable(
                        enabled = !locked && starting.not() && cameraError == null
                    ) {
                        if (!delivered.get() && captureRequested.compareAndSet(false, true)) {
                            locked = true
                            hapticTick(context)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = VaultColor.accent,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(VaultColor.inkOnScrim)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Corners can be adjusted on the next screen",
                style = VaultType.micro(),
                color = VaultColor.textFaint
            )
        }

        if (starting && cameraError == null) {
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

private const val AUTO_MIN_AREA_TRIGGER = 0.12f

private fun normalizedArea(quad: FloatArray): Float {
    var area = 0f
    for (index in 0 until 4) {
        val next = (index + 1) % 4
        area += quad[index * 2] * quad[next * 2 + 1] - quad[next * 2] * quad[index * 2 + 1]
    }
    return kotlin.math.abs(area) / 2f
}

private fun isStableQuad(previous: FloatArray?, current: FloatArray): Boolean {
    if (previous == null) return false
    for (index in 0 until 8) {
        if (kotlin.math.abs(previous[index] - current[index]) > 0.014f) return false
    }
    return true
}

private fun DrawScope.gridLines() {
    for (third in 1..2) {
        drawLine(
            VaultColor.accent.copy(alpha = 0.22f),
            Offset(size.width * third / 3f, 0f),
            Offset(size.width * third / 3f, size.height),
            1f.dp.toPx()
        )
        drawLine(
            VaultColor.accent.copy(alpha = 0.22f),
            Offset(0f, size.height * third / 3f),
            Offset(size.width, size.height * third / 3f),
            1f.dp.toPx()
        )
    }
}

/** Dims everything but the page, so the sheet reads as the only thing in frame. */
private fun DrawScope.dimOutside(quad: FloatArray, accent: Color) {
    var minX = quad[0]
    var minY = quad[1]
    var maxX = quad[0]
    var maxY = quad[1]
    var index = 2
    while (index < 8) {
        minX = min(minX, quad[index])
        maxX = max(maxX, quad[index])
        minY = min(minY, quad[index + 1])
        maxY = max(maxY, quad[index + 1])
        index += 2
    }
    val veil = VaultColor.void.copy(alpha = 0.5f)
    val bounds = Rect(minX, minY, maxX, maxY)
    if (bounds.top > 0f) drawRect(veil, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, bounds.top))
    if (bounds.bottom < size.height) {
        drawRect(
            veil,
            Offset(0f, bounds.bottom),
            androidx.compose.ui.geometry.Size(size.width, size.height - bounds.bottom)
        )
    }
    if (bounds.left > 0f) {
        drawRect(
            veil,
            Offset(0f, bounds.top),
            androidx.compose.ui.geometry.Size(bounds.left, bounds.height)
        )
    }
    if (bounds.right < size.width) {
        drawRect(
            veil,
            Offset(bounds.right, bounds.top),
            androidx.compose.ui.geometry.Size(size.width - bounds.right, bounds.height)
        )
    }
    drawRect(accent.copy(alpha = 0.04f), bounds.topLeft, bounds.size)
}

@Composable
private fun RoundIconButton(
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

@Composable
private fun SheetChip(
    label: String,
    active: Boolean,
    accent: Color,
    icon: Glyph,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(VaultRadius.pill())
            .background(if (active) accent.copy(alpha = 0.22f) else VaultColor.inkOnScrim.copy(alpha = 0.08f))
            .border(1.dp, if (active) accent else VaultColor.inkOnScrim.copy(alpha = 0.2f), VaultRadius.pill())
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = if (active) accent else VaultColor.inkOnScrim.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = VaultType.micro(),
            color = if (active) accent else VaultColor.inkOnScrim.copy(alpha = 0.8f)
        )
    }
}

private fun hapticTick(context: android.content.Context) {
    runCatching {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
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
