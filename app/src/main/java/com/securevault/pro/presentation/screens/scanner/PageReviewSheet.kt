package com.securevault.pro.presentation.screens.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GradientButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon

/**
 * The "is this the page?" step. The four corners are draggable, the look is picked here,
 * and nothing is sealed until Use page — which makes an auto-detected edge a suggestion
 * the user can overrule instead of a silent crop of the wrong rectangle.
 */
@Composable
internal fun PageReviewSheet(
    capture: LiveCapture,
    position: Int,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var handles by remember { mutableStateOf(capture.quad?.copyOf() ?: insetQuad()) }
    var cropEnabled by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(ScanFilter.Enhanced) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var ready by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(capture) {
        val bitmap = withContext(Dispatchers.Default) { normalizedBitmap(capture.jpeg, 1600) }
        if (bitmap == null) {
            failure = "That photo could not be opened. Retake the page."
        } else {
            source = bitmap
        }
        ready = true
    }

    // Debounced, so dragging a corner does not queue a perspective warp per pixel moved.
    LaunchedEffect(handles, cropEnabled, filter, ready) {
        val bitmap = source
        if (!ready || bitmap == null) return@LaunchedEffect
        delay(150)
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                val base = cropAndFilter(bitmap, handles, cropEnabled, filter, 900)
                base
            }.getOrNull()
        }
        if (rendered != null) {
            preview?.takeIf { it !== rendered }?.recycle()
            preview = rendered
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            preview?.recycle()
            source?.recycle()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColor.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(VaultColor.accent.copy(alpha = 0.14f))
                    .border(1.dp, VaultColor.accent.copy(alpha = 0.3f), CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.close,
                    contentDescription = "Discard page",
                    tint = VaultColor.accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Page $position",
                    style = VaultType.cardTitle(),
                    color = VaultColor.textStrong
                )
                Text(
                    text = failure ?: "Drag a corner to fix the crop, then pick a look",
                    style = VaultType.micro(),
                    color = if (failure != null) VaultColor.warning else VaultColor.textFaint
                )
            }
            GhostButton(text = "Retake", icon = Glyph.refresh, accent = VaultColor.warning, onClick = onRetake)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(VaultShapes.card)
                .background(VaultColor.void.copy(alpha = 0.55f))
                .border(1.dp, VaultColor.accent.copy(alpha = 0.18f), VaultShapes.card),
            contentAlignment = Alignment.Center
        ) {
            val shown = preview ?: source
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Scanned page preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            }
            if (shown != null && cropEnabled) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(shown) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val metrics = fitMetrics(
                                    shown.width.toFloat(),
                                    shown.height.toFloat(),
                                    size.width.toFloat(),
                                    size.height.toFloat()
                                ) ?: return@detectDragGestures
                                val corner = nearestCorner(handles, change.position, metrics)
                                val next = handles.copyOf()
                                next[corner * 2] = (next[corner * 2] + dragAmount.x / metrics.scale)
                                    .coerceIn(0f, 1f)
                                next[corner * 2 + 1] = (next[corner * 2 + 1] + dragAmount.y / metrics.scale)
                                    .coerceIn(0f, 1f)
                                handles = next
                            }
                        }
                ) {
                    val metrics = fitMetrics(
                        shown.width.toFloat(),
                        shown.height.toFloat(),
                        size.width,
                        size.height
                    )
                    if (metrics != null) {
                        drawCorners(quadInView(handles, metrics, shown.width, shown.height))
                    }
                }
            }
            if (!ready || working) {
                CircularProgressIndicator(
                    color = VaultColor.accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScanFilter.entries.forEach { option ->
                ReviewChip(
                    label = option.label,
                    active = filter == option,
                    accent = if (option == ScanFilter.Greyscale) VaultColor.accent else VaultColor.credit,
                    icon = Glyph.autoFixHigh,
                    onClick = { filter = option }
                )
            }
            Spacer(Modifier.width(2.dp))
            ReviewChip(
                label = if (cropEnabled) "Auto crop" else "Full frame",
                active = cropEnabled,
                accent = VaultColor.warning,
                icon = Glyph.cropFree,
                onClick = { cropEnabled = !cropEnabled }
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Discard",
                modifier = Modifier.weight(1f),
                enabled = !working,
                onClick = onDismiss
            )
            GradientButton(
                text = "Use page",
                modifier = Modifier.weight(1.5f),
                enabled = ready && !working && failure == null && source != null,
                onClick = {
                    val bitmap = source ?: return@GradientButton
                    working = true
                    val quad = handles.copyOf()
                    val chosen = filter
                    val crop = cropEnabled
                    scope.launch {
                        val jpeg = withContext(Dispatchers.Default) {
                            runCatching {
                                val base = if (crop) {
                                    perspectiveCrop(
                                        bitmap,
                                        normalizedToPixels(quad, bitmap.width.toFloat(), bitmap.height.toFloat())
                                    ) ?: bitmap
                                } else {
                                    bitmap
                                }
                                val output = applyScanFilter(base, chosen)
                                val bytes = toJpeg(output, 90)
                                if (output !== base) output.recycle()
                                if (base !== bitmap) base.recycle()
                                bytes
                            }.getOrNull()
                        }
                        working = false
                        if (jpeg != null && jpeg.isNotEmpty()) {
                            onConfirm(jpeg)
                        } else {
                            failure = "The page could not be prepared. Try Retake."
                        }
                    }
                }
            )
        }
    }
}

/** Crop, filter and downscale for the on-screen preview; recycles what it replaces. */
private fun cropAndFilter(
    bitmap: Bitmap,
    quad: FloatArray,
    crop: Boolean,
    filter: ScanFilter,
    maxDimension: Int
): Bitmap {
    val base = if (crop) {
        perspectiveCrop(bitmap, normalizedToPixels(quad, bitmap.width.toFloat(), bitmap.height.toFloat()))
            ?: bitmap
    } else {
        bitmap
    }
    val output = applyScanFilter(base, filter)
    val display = thumbnailOf(output, maxDimension)
    if (output !== base && output !== display) output.recycle()
    if (base !== bitmap && base !== output) base.recycle()
    return display
}

private class FitMetrics(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun fitMetrics(
    imageWidth: Float,
    imageHeight: Float,
    viewWidth: Float,
    viewHeight: Float
): FitMetrics? {
    if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return null
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    return FitMetrics(
        scale = scale,
        offsetX = (viewWidth - imageWidth * scale) / 2f,
        offsetY = (viewHeight - imageHeight * scale) / 2f
    )
}

private fun quadInView(handles: FloatArray, metrics: FitMetrics, imageWidth: Int, imageHeight: Int): FloatArray {
    val points = FloatArray(8)
    for (corner in 0 until 4) {
        points[corner * 2] = metrics.offsetX + handles[corner * 2] * imageWidth * metrics.scale
        points[corner * 2 + 1] = metrics.offsetY + handles[corner * 2 + 1] * imageHeight * metrics.scale
    }
    return points
}

private fun nearestCorner(handles: FloatArray, position: Offset, metrics: FitMetrics): Int {
    val x = (position.x - metrics.offsetX) / metrics.scale
    val y = (position.y - metrics.offsetY) / metrics.scale
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (corner in 0 until 4) {
        val distance = abs(x - handles[corner * 2]) + abs(y - handles[corner * 2 + 1])
        if (distance < bestDistance) {
            bestDistance = distance
            best = corner
        }
    }
    return best
}

@Composable
private fun ReviewChip(
    label: String,
    active: Boolean,
    accent: Color,
    icon: Glyph,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(VaultRadius.pill())
            .background(if (active) accent.copy(alpha = 0.18f) else VaultColor.background.copy(alpha = 0.6f))
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.5f) else VaultColor.inkOnScrim.copy(alpha = 0.12f),
                VaultRadius.pill()
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = if (active) accent else VaultColor.textBody,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = VaultType.micro().copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
            color = if (active) accent else VaultColor.textBody
        )
    }
}

private fun DrawScope.drawCorners(points: FloatArray) {
    val polygon = Path().apply {
        moveTo(points[0], points[1])
        lineTo(points[2], points[3])
        lineTo(points[4], points[5])
        lineTo(points[6], points[7])
        close()
    }
    drawPath(polygon, VaultColor.credit.copy(alpha = 0.10f))
    drawPath(polygon, VaultColor.credit, style = Stroke(width = 2.4f.dp.toPx(), cap = StrokeCap.Round))
    for (index in 0 until 8 step 2) {
        val center = Offset(points[index], points[index + 1])
        drawCircle(color = VaultColor.credit, radius = 13f.dp.toPx(), center = center)
        drawCircle(color = VaultColor.background.copy(alpha = 0.9f), radius = 6f.dp.toPx(), center = center)
        drawCircle(color = VaultColor.credit, radius = 3f.dp.toPx(), center = center)
    }
}
