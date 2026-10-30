package com.securevault.pro.presentation.screens.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CaptureTarget(val file: File, val uri: Uri)

internal fun newCaptureTarget(context: Context): CaptureTarget {
    val dir = File(context.cacheDir, "secure_share").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    return CaptureTarget(file, uri)
}

internal fun scanFileName(prefix: String, extension: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "scan_${prefix}_$stamp.$extension"
}

internal fun scanDisplayDate(): String =
    SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date())

internal fun normalizedBitmap(bytes: ByteArray, maxDimension: Int = 2200): Bitmap? {
    val decoded = decodeDownsampled(bytes, maxDimension * 2) ?: return null
    val oriented = applyExifOrientation(decoded, readExifOrientation(bytes))
    val longest = max(oriented.width, oriented.height)
    if (longest <= maxDimension) return oriented
    val scale = maxDimension.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        oriented,
        (oriented.width * scale).toInt().coerceAtLeast(1),
        (oriented.height * scale).toInt().coerceAtLeast(1),
        true
    )
    if (scaled !== oriented) oriented.recycle()
    return scaled
}

private const val DETECTION_PROBE = 640

internal enum class ScanFilter(val label: String) {
    Enhanced("Enhance"),
    Original("Original"),
    Greyscale("B & W")
}

internal fun applyScanFilter(source: Bitmap, filter: ScanFilter): Bitmap = when (filter) {
    ScanFilter.Enhanced -> enhanceDocument(source)
    ScanFilter.Original -> source
    ScanFilter.Greyscale -> greyscaleDocument(source)
}

/**
 * Decodes a capture, finds the sheet of paper, rectifies it with a perspective
 * transform and lifts contrast / white balance. Falls back to the upright
 * original when no confident document outline is found.
 */
internal fun prepareDocument(bytes: ByteArray, maxDimension: Int = 2200): Bitmap? =
    prepareDocument(bytes, maxDimension, null, ScanFilter.Enhanced)

/**
 * Same pipeline, but the live viewfinder can hand down the quad it already found
 * (in [overrideQuad], bitmap coordinates) and the filter the user picked, so a page
 * is never re-detected differently from the way it was framed on screen.
 */
internal fun prepareDocument(
    bytes: ByteArray,
    maxDimension: Int,
    overrideQuad: FloatArray?,
    filter: ScanFilter
): Bitmap? {
    val source = normalizedBitmap(bytes, maxDimension) ?: return null
    var cropped: Bitmap? = null
    return try {
        val quad = overrideQuad?.takeIf { it.size == 8 && isTrustworthy(it, source) }
            ?: detectDocumentQuad(source)
        if (quad != null) cropped = perspectiveCrop(source, quad)
        val base = cropped ?: source
        val output = applyScanFilter(base, filter)
        if (output !== base) {
            if (base !== source) base.recycle()
            source.recycle()
            output
        } else if (base !== source) {
            source.recycle()
            base
        } else {
            source
        }
    } catch (_: Throwable) {
        cropped?.recycle()
        if (source.isRecycled) null else source
    }
}

/** True when the quad really outlines a sheet inside this bitmap, so it may be cropped. */
internal fun isTrustworthy(quad: FloatArray, source: Bitmap): Boolean =
    trustworthy(quad, source.width.toFloat(), source.height.toFloat(), min(source.width, source.height))

internal fun detectDocumentQuad(source: Bitmap): FloatArray? {
    val longest = max(source.width, source.height)
    if (longest < 200) return null
    val scale = DETECTION_PROBE.toFloat() / longest
    val width = (source.width * scale).roundToInt().coerceAtLeast(48)
    val height = (source.height * scale).roundToInt().coerceAtLeast(48)
    val probe = Bitmap.createScaledBitmap(source, width, height, true)
    val gray = try {
        grayOf(probe, width, height)
    } finally {
        if (probe !== source) probe.recycle()
    }
    val quad = quadFromGray(gray, width, height) ?: return null
    val scaleX = source.width.toFloat() / width
    val scaleY = source.height.toFloat() / height
    return FloatArray(8).also { scaled ->
        for (corner in 0 until 4) {
            scaled[corner * 2] = quad[corner * 2] * scaleX
            scaled[corner * 2 + 1] = quad[corner * 2 + 1] * scaleY
        }
    }
}

private fun grayOf(probe: Bitmap, width: Int, height: Int): IntArray {
    val pixels = IntArray(width * height)
    probe.getPixels(pixels, 0, width, 0, 0, width, height)
    val gray = IntArray(pixels.size)
    for (index in pixels.indices) {
        val color = pixels[index]
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        gray[index] = (red * 77 + green * 150 + blue * 29) shr 8
    }
    return gray
}

/**
 * Works purely on a luminance buffer, so the live viewfinder (which only has the
 * camera's Y plane) and the still-image path run exactly the same maths.
 *
 * Returns the sheet corners in buffer coordinates, or null when the shape is too
 * small, too concave or too close to the frame edge to trust as an auto-crop.
 */
internal fun quadFromGray(gray: IntArray, width: Int, height: Int): FloatArray? {
    val count = width * height
    if (count == 0 || gray.size < count) return null
    smoothGray(gray, width, height)

    val threshold = otsuThreshold(gray)
    var bright = 0
    for (value in gray) if (value > threshold) bright += 1
    if (bright < count / 8 || bright > count * 15 / 16) return null

    val mask = BooleanArray(count)
    for (index in 0 until count) mask[index] = gray[index] > threshold

    val visited = BooleanArray(count)
    val stack = IntArray(count)
    var bestStart = -1
    var bestSize = 0
    for (start in 0 until count) {
        if (!mask[start] || visited[start]) continue
        var top = 0
        stack[top] = start
        top += 1
        visited[start] = true
        var size = 0
        while (top > 0) {
            val index = stack[--top]
            size += 1
            val x = index % width
            val y = index / width
            if (x > 0) top = push(index - 1, mask, visited, stack, top)
            if (x < width - 1) top = push(index + 1, mask, visited, stack, top)
            if (y > 0) top = push(index - width, mask, visited, stack, top)
            if (y < height - 1) top = push(index + width, mask, visited, stack, top)
        }
        if (size > bestSize) {
            bestSize = size
            bestStart = start
        }
    }
    if (bestStart < 0 || bestSize < count * 0.10f) return null

    val seen = BooleanArray(count)
    var top = 0
    stack[top] = bestStart
    top += 1
    seen[bestStart] = true
    var topLeft = bestStart
    var topRight = bestStart
    var bottomRight = bestStart
    var bottomLeft = bestStart
    var minSum = Int.MAX_VALUE
    var maxSum = Int.MIN_VALUE
    var maxDiff = Int.MIN_VALUE
    var minDiff = Int.MAX_VALUE
    while (top > 0) {
        val index = stack[--top]
        val x = index % width
        val y = index / width
        val sum = x + y
        val diff = x - y
        if (sum < minSum) {
            minSum = sum
            topLeft = index
        }
        if (sum > maxSum) {
            maxSum = sum
            bottomRight = index
        }
        if (diff > maxDiff) {
            maxDiff = diff
            topRight = index
        }
        if (diff < minDiff) {
            minDiff = diff
            bottomLeft = index
        }
        if (x > 0) top = push(index - 1, mask, seen, stack, top)
        if (x < width - 1) top = push(index + 1, mask, seen, stack, top)
        if (y > 0) top = push(index - width, mask, seen, stack, top)
        if (y < height - 1) top = push(index + width, mask, seen, stack, top)
    }

    val quad = floatArrayOf(
        (topLeft % width).toFloat(), (topLeft / width).toFloat(),
        (topRight % width).toFloat(), (topRight / width).toFloat(),
        (bottomRight % width).toFloat(), (bottomRight / width).toFloat(),
        (bottomLeft % width).toFloat(), (bottomLeft / width).toFloat()
    )
    return if (trustworthy(quad, width.toFloat(), height.toFloat(), min(width, height))) quad else null
}

private fun trustworthy(quad: FloatArray, frameWidth: Float, frameHeight: Float, minSide: Int): Boolean {
    val limit = minSide * 0.16f
    val sides = floatArrayOf(
        distance(quad, 0, 1),
        distance(quad, 1, 2),
        distance(quad, 2, 3),
        distance(quad, 3, 0)
    )
    if (sides.any { it < limit }) return false
    val area = polygonArea(quad)
    val frame = frameWidth * frameHeight
    if (area < frame * 0.10f || area > frame * 0.985f) return false
    return isConvex(quad)
}

private fun push(
    index: Int,
    mask: BooleanArray,
    visited: BooleanArray,
    stack: IntArray,
    depth: Int
): Int {
    if (!mask[index] || visited[index]) return depth
    visited[index] = true
    stack[depth] = index
    return depth + 1
}

private fun smoothGray(gray: IntArray, width: Int, height: Int) {
    val temp = IntArray(gray.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val left = gray[row + max(0, x - 1)]
            val center = gray[row + x]
            val right = gray[row + min(width - 1, x + 1)]
            temp[row + x] = (left + center * 2 + right) / 4
        }
    }
    for (y in 0 until height) {
        val up = max(0, y - 1) * width
        val row = y * width
        val down = min(height - 1, y + 1) * width
        for (x in 0 until width) {
            gray[row + x] = (temp[up + x] + temp[row + x] * 2 + temp[down + x]) / 4
        }
    }
}

private fun otsuThreshold(gray: IntArray): Int {
    val histogram = IntArray(256)
    for (value in gray) histogram[value.coerceIn(0, 255)] += 1
    val total = gray.size.toDouble()
    var sum = 0.0
    for (value in 0..255) sum += value * histogram[value]
    var backgroundWeight = 0
    var backgroundSum = 0.0
    var best = 0.0
    var threshold = 127
    for (value in 0..255) {
        backgroundWeight += histogram[value]
        if (backgroundWeight == 0) continue
        val foregroundWeight = total - backgroundWeight
        if (foregroundWeight <= 0.0) break
        backgroundSum += value * histogram[value]
        val backgroundMean = backgroundSum / backgroundWeight
        val foregroundMean = (sum - backgroundSum) / foregroundWeight
        val between = backgroundWeight * foregroundWeight *
            (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
        if (between > best) {
            best = between
            threshold = value
        }
    }
    return threshold
}

internal fun perspectiveCrop(source: Bitmap, quad: FloatArray): Bitmap? {
    val top = distance(quad, 0, 1)
    val right = distance(quad, 1, 2)
    val bottom = distance(quad, 2, 3)
    val left = distance(quad, 3, 0)
    val cap = 2600f
    val longest = max(max(top, bottom), max(left, right))
    val fit = if (longest > cap) cap / longest else 1f
    val outWidth = (max(top, bottom) * fit).roundToInt()
    val outHeight = (max(left, right) * fit).roundToInt()
    if (outWidth < 64 || outHeight < 64) return null
    val matrix = Matrix()
    val destination = floatArrayOf(
        0f, 0f,
        outWidth.toFloat(), 0f,
        outWidth.toFloat(), outHeight.toFloat(),
        0f, outHeight.toFloat()
    )
    if (!matrix.setPolyToPoly(quad, 0, destination, 0, 4)) return null
    return runCatching {
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        output
    }.getOrNull()
}

private fun enhanceDocument(source: Bitmap): Bitmap =    transferDocument(source) { ColorStats.from(it).toColorMatrix() }

private fun greyscaleDocument(source: Bitmap): Bitmap = transferDocument(source) {
    val matrix = ColorStats.from(source).toColorMatrix()
    val luminance = ColorMatrix()
    luminance.setSaturation(0f)
    matrix.postConcat(luminance)
    // Push the mid-tones apart so a grey page reads like a clean photocopy.
    val contrast = ColorMatrix(
        floatArrayOf(
            1.28f, 0f, 0f, 0f, -36f,
            0f, 1.28f, 0f, 0f, -36f,
            0f, 0f, 1.28f, 0f, -36f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    matrix.postConcat(contrast)
    matrix
}

private fun transferDocument(
    source: Bitmap,
    matrixFor: (Bitmap) -> ColorMatrix
): Bitmap {
    if (source.width < 16 || source.height < 16) return source
    return runCatching {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrixFor(source))
        canvas.drawBitmap(source, 0f, 0f, paint)
        output
    }.getOrDefault(source)
}

private class ColorStats(
    private val low: Int,
    private val high: Int,
    private val gainRed: Float,
    private val gainGreen: Float,
    private val gainBlue: Float
) {
    fun toColorMatrix(): ColorMatrix {
        val range = (high - low).coerceAtLeast(1)
        val scale = 255f / range
        val contrast = 1.07f
        val lift = 2f
        fun channel(gain: Float): Pair<Float, Float> {
            val multiplier = (scale * gain * contrast).coerceIn(0.4f, 3.4f)
            val offset = -low * scale * gain * contrast + (-128f * contrast + 128f + lift)
            return multiplier to offset
        }
        val (redScale, redOffset) = channel(gainRed)
        val (greenScale, greenOffset) = channel(gainGreen)
        val (blueScale, blueOffset) = channel(gainBlue)
        return ColorMatrix().apply {
            setScale(redScale, greenScale, blueScale, 1f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, redOffset,
                        0f, 1f, 0f, 0f, greenOffset,
                        0f, 0f, 1f, 0f, blueOffset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    companion object {
        fun from(bitmap: Bitmap): ColorStats {
            val width = 240
            val height = (bitmap.height * (width.toFloat() / bitmap.width))
                .roundToInt().coerceAtLeast(1)
            val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val pixels = IntArray(width * height)
            try {
                sample.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                if (sample !== bitmap) sample.recycle()
            }
            val histogram = IntArray(256)
            var sumRed = 0L
            var sumGreen = 0L
            var sumBlue = 0L
            for (color in pixels) {
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                sumRed += red
                sumGreen += green
                sumBlue += blue
                histogram[(red * 77 + green * 150 + blue * 29) shr 8] += 1
            }
            val total = pixels.size.toFloat().coerceAtLeast(1f)
            fun percentile(fraction: Float): Int {
                val target = (total * fraction).toInt()
                var accumulated = 0
                for (value in 0..255) {
                    accumulated += histogram[value]
                    if (accumulated >= target) return value
                }
                return 255
            }
            var low = percentile(0.02f)
            var high = percentile(0.98f)
            if (high - low < 24) {
                low = 0
                high = 255
            }
            val meanRed = (sumRed / total).coerceAtLeast(1f)
            val meanGreen = (sumGreen / total).coerceAtLeast(1f)
            val meanBlue = (sumBlue / total).coerceAtLeast(1f)
            val mean = (meanRed + meanGreen + meanBlue) / 3f
            fun gain(value: Float): Float = (mean / value).coerceIn(0.84f, 1.18f)
            return ColorStats(low, high, gain(meanRed), gain(meanGreen), gain(meanBlue))
        }
    }
}

private fun distance(points: FloatArray, first: Int, second: Int): Float {
    val dx = points[second * 2] - points[first * 2]
    val dy = points[second * 2 + 1] - points[first * 2 + 1]
    return sqrt(dx * dx + dy * dy)
}

private fun polygonArea(points: FloatArray): Float {
    var area = 0f
    for (index in 0 until 4) {
        val next = (index + 1) % 4
        area += points[index * 2] * points[next * 2 + 1] -
            points[next * 2] * points[index * 2 + 1]
    }
    return abs(area) / 2f
}

private fun isConvex(points: FloatArray): Boolean {
    var sign = 0
    for (index in 0 until 4) {
        val a = index
        val b = (index + 1) % 4
        val c = (index + 2) % 4
        val cross = (points[b * 2] - points[a * 2]) * (points[c * 2 + 1] - points[b * 2 + 1]) -
            (points[b * 2 + 1] - points[a * 2 + 1]) * (points[c * 2] - points[b * 2])
        val current = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
        if (current == 0) continue
        if (sign == 0) sign = current else if (sign != current) return false
    }
    return sign != 0
}

internal fun thumbnailOf(bitmap: Bitmap, maxDimension: Int = 640): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    )
}

internal fun toJpeg(bitmap: Bitmap, quality: Int = 88): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

internal suspend fun recognizeText(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text.trim())
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    } finally {
        recognizer.close()
    }
}

internal fun buildPdf(pages: List<ByteArray>): ByteArray {
    val document = android.graphics.pdf.PdfDocument()
    try {
        pages.forEachIndexed { index, jpeg ->
            val bitmap = normalizedBitmap(jpeg, 1800)
                ?: throw IllegalStateException("Page ${index + 1} could not be decoded.")
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                595,
                842,
                index + 1
            ).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            val availableWidth = 595f - 48f
            val availableHeight = 842f - 48f
            val scale = minOf(
                availableWidth / bitmap.width,
                availableHeight / bitmap.height
            )
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (595f - drawWidth) / 2f
            val top = (842f - drawHeight) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                null
            )
            document.finishPage(page)
            bitmap.recycle()
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        return out.toByteArray()
    } finally {
        document.close()
    }
}

private fun decodeDownsampled(bytes: ByteArray, targetMax: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetMax) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(bytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { transformed ->
                if (transformed !== bitmap) bitmap.recycle()
            }
    }.getOrDefault(bitmap)
}
