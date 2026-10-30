package com.securevault.pro.presentation.screens.stego

import android.annotation.SuppressLint
import android.media.Image
import android.util.Size
import androidx.camera.core.Camera
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.presentation.theme.VaultColor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * The camera the reveal screen looks through, and the one job it has: turn a live sensor frame into
 * an upright [LumaPlane] the codec can read.
 *
 * Two decisions in here are worth stating, because both look like shortcuts and neither is:
 *
 *  * **The analysis frame is the decode input.** A 12 MP still would be the obvious choice, but the
 *    mark is laid out on a *normalised* lattice, so a 900-sample frame carries exactly as much of it
 *    as a 4000-sample one, and the bounded frame is what keeps the analyser thread inside a fixed
 *    budget at 30 frames a second. Resolution buys capacity in the *encoder*, which writes the
 *    carrier it was given; a reader only needs enough samples that each lattice cell lands on more
 *    than one band cell.
 *  * **Box averaging, not point sampling.** The `crypto/stego` band is a grid of 4x4 pixel means, so
 *    decimating a frame by picking one pixel per output sample aliases the photo's own grain into
 *    that same channel — the channel the chips live in. Averaging the box a sample covers is a few
 *    extra `ByteBuffer.get` calls per frame and removes a bias that costs real bits after a print.
 *
 * The composable owns no verdict and no state beyond the camera itself: it calls [onFrame] on its
 * own executor, and the screen decides what a frame means. If CAMERA is refused, this is simply
 * never composed — the reveal screen falls back to the photo picker, which is why that fallback is
 * a first-class path here and not an error page.
 */
@OptIn(ExperimentalGetImage::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
internal fun StegoViewfinder(
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    torch: Boolean = false,
    targetLongest: Int = PROBE_LONGEST,
    onCameraError: (String?) -> Unit = {},
    onFrame: (LumaPlane) -> Unit
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
    val skip = remember { AtomicBoolean(paused) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(paused) { skip.set(paused) }
    LaunchedEffect(torch, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torch) }
    }

    DisposableEffect(Unit) {
        disposed.set(false)
        skip.set(paused)
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            if (disposed.get()) return@Runnable
            try {
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
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    if (disposed.get() || skip.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val frame = proxy.image?.let { media ->
                        uprightPlaneOf(media, proxy.imageInfo.rotationDegrees, targetLongest)
                    }
                    proxy.close()
                    if (!disposed.get() && frame != null) onFrame(frame)
                }
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                mainExecutor.execute {
                    if (!disposed.get()) {
                        camera = bound
                        onCameraError(null)
                    }
                }
            } catch (failure: Throwable) {
                mainExecutor.execute {
                    if (!disposed.get()) {
                        onCameraError(
                            failure.message ?: "The camera could not be started. Pick a photo instead."
                        )
                    }
                }
            }
        }
        future.addListener(listener, mainExecutor)
        onDispose {
            disposed.set(true)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.background(VaultColor.void)
    )
}

/** A viewfinder frame this size already puts 225 band cells across a 96-side lattice. */
internal const val PROBE_LONGEST = 900

/**
 * An upright luminance plane from one camera frame, or null when the frame is not usable yet.
 *
 * The Y plane of a `YUV_420_888` frame has a row stride wider than its width and, on some sensors, a
 * pixel stride of 2; both are honoured here, and a row or column that runs past the buffer limit is
 * dropped rather than guessed at, so a short buffer yields a smaller honest plane instead of a
 * corrupted one.
 */
internal fun uprightPlaneOf(image: Image, rotationDegrees: Int, targetLongest: Int): LumaPlane? {
    val raw = boxPlaneOf(image, targetLongest) ?: return null
    val quarterTurns = normalized(rotationDegrees) / 90
    return if (quarterTurns == 0) raw else StegoPlanes.rotated(raw, quarterTurns)
}

private fun boxPlaneOf(image: Image, targetLongest: Int): LumaPlane? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer ?: return null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val sourceWidth = image.width
    val sourceHeight = image.height
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val columns = min(sourceWidth, (buffer.limit() / pixelStride).coerceAtLeast(1))
    val rows = min(sourceHeight, (buffer.limit() / rowStride).coerceAtLeast(1))
    if (columns < 64 || rows < 64) return null
    val ratio = targetLongest.toFloat() / max(columns, rows)
    val width = (columns * ratio).toInt().coerceIn(64, targetLongest)
    val height = (rows * ratio).toInt().coerceIn(64, targetLongest)
    val columnSpan = columns.toFloat() / width
    val rowSpan = rows.toFloat() / height
    val limit = buffer.limit()
    val samples = IntArray(width * height)
    for (y in 0 until height) {
        val fromRow = (y * rowSpan).toInt().coerceAtMost(rows - 1)
        val toRow = max(fromRow + 1, ((y + 1) * rowSpan).toInt()).coerceAtMost(rows)
        for (x in 0 until width) {
            val fromColumn = (x * columnSpan).toInt().coerceAtMost(columns - 1)
            val toColumn = max(fromColumn + 1, ((x + 1) * columnSpan).toInt()).coerceAtMost(columns)
            var total = 0L
            var count = 0
            var row = fromRow
            while (row < toRow) {
                val start = row * rowStride
                var column = fromColumn
                while (column < toColumn) {
                    val position = start + column * pixelStride
                    if (position in 0 until limit) {
                        total += buffer.get(position).toInt() and 0xFF
                        count += 1
                    }
                    column += 1
                }
                row += 1
            }
            samples[y * width + x] =
                if (count == 0) 128 else ((total + count / 2) / count).toInt().coerceIn(0, 255)
        }
    }
    return LumaPlane(width, height, samples)
}

/** CameraX reports 0/90/180/270 clockwise to upright; anything else is folded back into that set. */
private fun normalized(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360
