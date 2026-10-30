package com.securevault.pro.presentation.screens.auth

import android.Manifest
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    title: String = "Scan authenticator",
    subtitle: String = "Point at a TOTP QR code",
    accepts: (String) -> Boolean = { it.startsWith("otpauth://", ignoreCase = true) },
    acceptedLabel: String = "Authenticator link found",
    rejectedLabel: String = "That QR code is not an authenticator link",
    guidance: List<Triple<Glyph, String, Color>> = emptyList()
) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!permission.status.isGranted) permission.launchPermissionRequest()
    }

    GlassScaffold(
        title = title,
        subtitle = subtitle,
        onBack = onBack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (permission.status.isGranted) {
                CameraQrScanner(
                    onScanned = onScanned,
                    accepts = accepts,
                    acceptedLabel = acceptedLabel,
                    rejectedLabel = rejectedLabel,
                    guidance = guidance
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(VaultShapes.card)
                            .background(VaultColor.accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedIcon(
                            glyph = Glyph.cameraAlt,
                            contentDescription = null,
                            tint = VaultColor.accent,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "Camera access needed",
                        style = MaterialTheme.typography.titleLarge,
                        color = VaultColor.textStrong,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (permission.status.shouldShowRationale) {
                            "Secure Wallet Pro only uses the camera to read authenticator QR codes. Frames are analysed on-device and never stored."
                        } else {
                            "Grant camera access, or enter the Base32 setup key manually."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textBody,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    GhostButton(
                        text = "Grant camera access",
                        icon = Glyph.cameraAlt,
                        accent = VaultColor.accent,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { permission.launchPermissionRequest() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraQrScanner(
    onScanned: (String) -> Unit,
    accepts: (String) -> Boolean,
    acceptedLabel: String,
    rejectedLabel: String,
    guidance: List<Triple<Glyph, String, Color>>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var codesSeen by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        disposed.set(false)
        val future = ProcessCameraProvider.getInstance(context)
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
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    if (disposed.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        if (!streaming) {
                            ContextCompat.getMainExecutor(context).execute {
                                if (!disposed.get()) streaming = true
                            }
                        }
                        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val value = codes.asSequence()
                                    .mapNotNull { it.rawValue }
                                    .firstOrNull { it.isNotBlank() }
                                if (value == null) return@addOnSuccessListener
                                ContextCompat.getMainExecutor(context).execute {
                                    if (disposed.get()) return@execute
                                    if (!accepts(value)) {
                                        if (!handled.get()) {
                                            feedback = rejectedLabel
                                            codesSeen = 0
                                        }
                                        return@execute
                                    }
                                    codesSeen += 1
                                    // Two hits on the same payload: the code is steady, not a mis-read.
                                    if (handled.compareAndSet(false, true)) {
                                        feedback = acceptedLabel
                                        onScanned(value)
                                    }
                                }
                            }
                            .addOnFailureListener { failure ->
                                ContextCompat.getMainExecutor(context).execute {
                                    if (!disposed.get() && !handled.get()) {
                                        feedback = "Reading failed: " +
                                            (failure.message?.take(60) ?: "on-device scanner error")
                                    }
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } catch (failure: Throwable) {
                        proxy.close()
                        ContextCompat.getMainExecutor(context).execute {
                            if (!disposed.get() && !handled.get()) {
                                cameraError = failure.message ?: "The camera frame could not be read"
                            }
                        }
                    }
                }
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                ContextCompat.getMainExecutor(context).execute {
                    if (!disposed.get()) camera = bound
                }
            } catch (e: Throwable) {
                ContextCompat.getMainExecutor(context).execute {
                    cameraError = e.message ?: "The camera could not be started"
                }
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed.set(true)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
            runCatching { scanner.close() }
        }
    }

    LaunchedEffect(torch, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torch) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VaultColor.void.copy(alpha = 0.28f))
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScannerChip(
                label = if (torch) "Torch on" else "Torch",
                icon = Glyph.flashOn,
                active = torch,
                onClick = { torch = !torch }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .border(
                        2.dp,
                        if (codesSeen > 0) VaultColor.credit else if (streaming) VaultColor.accent else VaultColor.warning,
                        VaultShapes.card
                    )
                    .clip(VaultShapes.card)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .clip(VaultRadius.pill())
                        .background(VaultColor.background.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = when {
                            cameraError != null -> "CAMERA ERROR"
                            !streaming -> "STARTING CAMERA…"
                            else -> "ALIGN QR CODE WITHIN FRAME"
                        },
                        style = VaultType.micro().copy(fontWeight = FontWeight.Bold),
                        color = when {
                            cameraError != null -> VaultColor.debit
                            codesSeen > 0 -> VaultColor.credit
                            else -> VaultColor.accentInk
                        }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = VaultColor.accent,
                borderColor = VaultColor.accent.copy(alpha = 0.25f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
            ) {
                Column {
                    if (guidance.isEmpty()) {
                        RowHint(
                            icon = Glyph.qrCodeScanner,
                            text = "Open the service's security settings and choose “Set up authenticator”.",
                            color = VaultColor.accent
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        guidance.forEachIndexed { index, (icon, text, color) ->
                            RowHint(icon = icon, text = text, color = color)
                            if (index < guidance.lastIndex) Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    RowHint(
                        icon = Glyph.cameraAlt,
                        text = "Hold 20–30 cm back, fill the box, and turn the screen you are " +
                            "scanning up bright. Analysis stays on this device.",
                        color = VaultColor.textBody
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            cameraError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.debit,
                    textAlign = TextAlign.Center
                )
            }
            feedback?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.contains("not", ignoreCase = true) ||
                        it.contains("failed", ignoreCase = true)
                    ) {
                        VaultColor.warning
                    } else {
                        VaultColor.credit
                    },
                    textAlign = TextAlign.Center
                )
            }
            if (streaming && codesSeen == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Camera is streaming — no QR read yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    textAlign = TextAlign.Center
                )
            }
        }
        Text(
            text = "on-device ML Kit · frames are discarded immediately",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        )
    }
}

@Composable
private fun ScannerChip(
    label: String,
    icon: Glyph,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(VaultRadius.pill())
            .background(VaultColor.background.copy(alpha = 0.72f))
            .border(
                1.dp,
                if (active) VaultColor.warning else VaultColor.inkOnScrim.copy(alpha = 0.2f),
                VaultRadius.pill()
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = if (active) VaultColor.warning else VaultColor.inkOnScrim.copy(alpha = 0.8f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = VaultType.micro(),
            color = if (active) VaultColor.warning else VaultColor.inkOnScrim.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun RowHint(
    icon: Glyph,
    text: String,
    color: Color
) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textBody,
            lineHeight = 16.sp
        )
    }
}
