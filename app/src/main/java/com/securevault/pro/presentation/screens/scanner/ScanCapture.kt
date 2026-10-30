package com.securevault.pro.presentation.screens.scanner

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera hands the analyser a YUV_420_888 frame in sensor orientation. These
 * helpers are the only place that knows how to turn it into something the rest of
 * the scanner (which works on upright JPEG bytes) can consume, and how to move a
 * detected quad between buffer, image, upright and on-screen coordinate spaces.
 */
internal data class LumaFrame(
    val gray: IntArray,
    val width: Int,
    val height: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int
) {
    val scaleX: Float get() = imageWidth.toFloat() / width
    val scaleY: Float get() = imageHeight.toFloat() / height
}

/**
 * Reads the luminance plane, stride-corrected, downsampled so the longest side is
 * close to [targetLongest]. Runs on the analysis thread, allocates no bitmaps.
 */
internal fun lumaFrameOf(image: Image, rotationDegrees: Int, targetLongest: Int): LumaFrame? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer ?: return null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val sourceWidth = image.width
    val sourceHeight = image.height
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val usableColumns = minOf(sourceWidth, (buffer.limit() / pixelStride).coerceAtLeast(1))
    val usableRows = minOf(sourceHeight, (buffer.limit() / rowStride).coerceAtLeast(1))
    if (usableColumns < 32 || usableRows < 32) return null

    val ratio = targetLongest.toFloat() / max(usableColumns, usableRows)
    val width = (usableColumns * ratio).toInt().coerceIn(32, 1280)
    val height = (usableRows * ratio).toInt().coerceIn(32, 1280)
    val gray = IntArray(width * height)
    val columnStep = usableColumns.toFloat() / width
    val rowStep = usableRows.toFloat() / height
    var index = 0
    for (row in 0 until height) {
        val sourceRow = (row * rowStep).toInt().coerceAtMost(usableRows - 1)
        val rowStart = sourceRow * rowStride
        for (column in 0 until width) {
            val sourceColumn = (column * columnStep).toInt().coerceAtMost(usableColumns - 1)
            val position = rowStart + sourceColumn * pixelStride
            gray[index] = if (position in 0 until buffer.limit()) {
                buffer.get(position).toInt() and 0xFF
            } else {
                128
            }
            index += 1
        }
    }
    return LumaFrame(gray, width, height, sourceWidth, sourceHeight, rotationDegrees)
}

/** Copies an arbitrary-stride YUV_420_888 frame into the tightly packed NV21 layout. */
private fun nv21Of(image: Image): ByteArray? {
    if (image.planes.size < 3) return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val out = ByteArray(width * height + 2 * chromaWidth * chromaHeight)

    var offset = 0
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride.coerceAtLeast(1)
    for (row in 0 until height) {
        val rowStart = row * yRowStride
        for (column in 0 until width) {
            val position = rowStart + column * yPixelStride
            out[offset] = if (position < yBuffer.limit()) yBuffer.get(position) else 0.toByte()
            offset += 1
        }
    }

    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride.coerceAtLeast(1)
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride.coerceAtLeast(1)
    var chroma = width * height
    for (row in 0 until chromaHeight) {
        for (column in 0 until chromaWidth) {
            val vPosition = row * vRowStride + column * vPixelStride
            val uPosition = row * uRowStride + column * uPixelStride
            out[chroma] = if (vPosition < vBuffer.limit()) vBuffer.get(vPosition) else 128.toByte()
            out[chroma + 1] = if (uPosition < uBuffer.limit()) uBuffer.get(uPosition) else 128.toByte()
            chroma += 2
        }
    }
    return out
}

/**
 * JPEG bytes for a camera frame, already rotated upright so the rest of the
 * pipeline never has to care about sensor orientation or EXIF.
 */
internal fun uprightJpegOf(image: Image, rotationDegrees: Int, quality: Int = 92): ByteArray? {
    val nv21 = nv21Of(image) ?: return null
    val out = ByteArrayOutputStream()
    val encoded = runCatching {
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
    }.getOrDefault(false)
    if (!encoded || out.size() <= 0) return null
    val landscape = out.toByteArray()
    val normalized = normalizedRotation(rotationDegrees)
    if (normalized == 0) return landscape
    val bitmap = normalizedBitmap(landscape, 2400) ?: return landscape
    return try {
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        val upright = android.graphics.Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        val bytes = if (upright === bitmap) landscape else toJpeg(upright, quality)
        if (upright !== bitmap) upright.recycle()
        bytes
    } finally {
        bitmap.recycle()
    }
}

/**
 * Corner outline of the sheet as fractions (0..1) of the *upright* frame. Carrying the
 * quad normalized means the same value survives a downscale to 2400 px, a preview
 * overlay and a hand-dragged review handle without three different scale factors.
 */
internal fun quadToNormalized(quad: FloatArray, frame: LumaFrame): FloatArray? {
    val upright = uprightSizeOf(frame) ?: return null
    val mapped = FloatArray(8)
    for (corner in 0 until 4) {
        val point = rotatePoint(
            quad[corner * 2] * frame.scaleX,
            quad[corner * 2 + 1] * frame.scaleY,
            frame.imageWidth.toFloat(),
            frame.imageHeight.toFloat(),
            normalizedRotation(frame.rotationDegrees)
        )
        mapped[corner * 2] = (point[0] / upright[0]).coerceIn(0f, 1f)
        mapped[corner * 2 + 1] = (point[1] / upright[1]).coerceIn(0f, 1f)
    }
    return mapped
}

private fun rotatePoint(x: Float, y: Float, width: Float, height: Float, rotationDegrees: Int): FloatArray =
    when (rotationDegrees) {
        90 -> floatArrayOf(height - y, x)
        180 -> floatArrayOf(width - x, height - y)
        270 -> floatArrayOf(y, width - x)
        else -> floatArrayOf(x, y)
    }

internal fun normalizedRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360

private fun uprightSizeOf(frame: LumaFrame): FloatArray? {
    val rotation = normalizedRotation(frame.rotationDegrees)
    val width = if (rotation == 90 || rotation == 270) frame.imageHeight else frame.imageWidth
    val height = if (rotation == 90 || rotation == 270) frame.imageWidth else frame.imageHeight
    if (width <= 0 || height <= 0) return null
    return floatArrayOf(width.toFloat(), height.toFloat())
}

/**
 * Maps a normalized quad onto an on-screen box. The camera preview uses FILL_CENTER
 * for the upright frame, the review sheet uses FIT_CENTER over the captured bitmap,
 * so both can share one function.
 */
internal fun normalizedQuadToView(
    quad: FloatArray,
    uprightWidth: Float,
    uprightHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    fill: Boolean
): FloatArray? {
    if (viewWidth <= 1f || viewHeight <= 1f || uprightWidth <= 0f || uprightHeight <= 0f) return null
    val scale = if (fill) {
        maxOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
    } else {
        minOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
    }
    val offsetX = (viewWidth - uprightWidth * scale) / 2f
    val offsetY = (viewHeight - uprightHeight * scale) / 2f
    val mapped = FloatArray(8)
    for (corner in 0 until 4) {
        mapped[corner * 2] = offsetX + quad[corner * 2] * uprightWidth * scale
        mapped[corner * 2 + 1] = offsetY + quad[corner * 2 + 1] * uprightHeight * scale
    }
    return mapped
}

/** Inverse of [normalizedQuadToView] with [fill] = false: a dragged handle back to a fraction. */
internal fun viewToNormalized(
    viewX: Float,
    viewY: Float,
    uprightWidth: Float,
    uprightHeight: Float,
    viewWidth: Float,
    viewHeight: Float
): FloatArray? {
    if (uprightWidth <= 0f || uprightHeight <= 0f) return null
    val scale = minOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
    if (scale <= 0f) return null
    val offsetX = (viewWidth - uprightWidth * scale) / 2f
    val offsetY = (viewHeight - uprightHeight * scale) / 2f
    return floatArrayOf(
        ((viewX - offsetX) / scale / uprightWidth).coerceIn(0f, 1f),
        ((viewY - offsetY) / scale / uprightHeight).coerceIn(0f, 1f)
    )
}

/** A sensible starting outline when nothing was detected: most of the frame, inset. */
internal fun insetQuad(fraction: Float = 0.035f): FloatArray = floatArrayOf(
    fraction, fraction,
    1f - fraction, fraction,
    1f - fraction, 1f - fraction,
    fraction, 1f - fraction
)

/** Scales a normalized quad into pixel coordinates of a concrete bitmap. */
internal fun normalizedToPixels(quad: FloatArray, width: Float, height: Float): FloatArray {
    val mapped = FloatArray(8)
    for (corner in 0 until 4) {
        mapped[corner * 2] = quad[corner * 2] * width
        mapped[corner * 2 + 1] = quad[corner * 2 + 1] * height
    }
    return mapped
}
