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
import com.securevault.pro.data.scanner.DetectionFailure
import com.securevault.pro.data.scanner.DocumentDetector
import com.securevault.pro.data.scanner.Filters
import com.securevault.pro.data.scanner.LumaPlane
import com.securevault.pro.data.scanner.QuadGeometry
import com.securevault.pro.data.scanner.ScanBitmaps
import com.securevault.pro.data.scanner.ScanLook
import com.securevault.pro.data.scanner.ScanPageProvider
import com.securevault.pro.data.scanner.ScanPdfWriter
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

/** The page finish the pure core uses for the same choice the chips show. */
private fun lookOf(filter: ScanFilter): ScanLook = when (filter) {
    ScanFilter.Enhanced -> ScanLook.ENHANCE
    ScanFilter.Original -> ScanLook.ORIGINAL
    ScanFilter.Greyscale -> ScanLook.BLACK_WHITE
}

/**
 * What one viewfinder frame had to say about the sheet inside it.
 *
 * [quad] is in the frame's own pixels, corner order top-left, top-right, bottom-right, bottom-left,
 * or null when the detector refused — in which case [failure] is the sentence the viewfinder shows
 * instead of a box. [confidence] is measured (edge sharpness, corner squareness, region solidity),
 * which is what lets "hold steady" mean *the outline stopped moving* rather than *a shape appeared*.
 */
internal class LiveDetection(
    val quad: FloatArray?,
    val confidence: Float,
    val failure: DetectionFailure
)

/**
 * The live detector: the same maths the saved page runs, on the camera's own luminance plane.
 *
 * Two masks are consulted — the paper/desk brightness split *and* the gradient closure — so a white
 * sheet on a white desk (no brightness split, hard edges) and a shaded sheet on a dark desk (the
 * opposite) are both findable. The old viewfinder path read brightness only, which is why it lost
 * the outline under a ceiling light.
 */
internal fun detectLiveDocument(frame: LumaFrame): LiveDetection {
    val longest = max(frame.width, frame.height)
    if (longest < 48) return LiveDetection(null, 0f, DetectionFailure.TOO_SMALL)
    val result = try {
        DocumentDetector.detect(LumaPlane(frame.width, frame.height, frame.gray), longest)
    } catch (_: Throwable) {
        return LiveDetection(null, 0f, DetectionFailure.NO_CONTRAST)
    }
    val quad = result.quad.takeIf { it.size == 8 && result.accepted }
    return LiveDetection(quad, result.confidence.toFloat(), if (quad == null) result.failure else DetectionFailure.NONE)
}

/**
 * Glides the outline from one frame to the next instead of teleporting it.
 *
 * A raw per-frame quad moves 1-3 texels on a still page because the contour of a real edge is never
 * identical twice, and the eye reads that as a live box that never settles — so the box the user was
 * told to hold steady is the one that never looks steady. Small moves are damped, a big move is
 * taken as a real change of position and adopted at once, and anything under the deadband is dropped
 * so the box stops moving at all when the phone stops moving.
 */
internal class QuadSmoother {

    private var smoothed: FloatArray? = null

    /** The last value handed out, so a caller can skip a redundant state write. */
    fun current(): FloatArray? = smoothed

    fun reset() {
        smoothed = null
    }

    /** Blends [next] (normalized corners) into the running outline and returns what to draw. */
    fun blend(next: FloatArray): FloatArray {
        val previous = smoothed
        if (previous == null || previous.size != 8 || next.size != 8) {
            smoothed = next.copyOf()
            return smoothed!!
        }
        var drift = 0f
        for (index in 0 until 8) drift = max(drift, abs(previous[index] - next[index]))
        smoothed = when {
            drift < DEADBAND -> previous
            drift > TELEPORT -> next.copyOf()
            else -> FloatArray(8).also { out ->
                for (index in 0 until 8) {
                    out[index] = previous[index] * (1f - ALPHA) + next[index] * ALPHA
                }
            }
        }
        return smoothed!!
    }

    /** True when the outline has sat still long enough to shutter on — the anti false-trigger gate. */
    fun isSettledAgainst(sample: FloatArray): Boolean {
        val previous = smoothed ?: return false
        if (previous.size != 8 || sample.size != 8) return false
        for (index in 0 until 8) if (abs(previous[index] - sample[index]) > SETTLE) return false
        return true
    }

    private companion object {
        const val ALPHA = 0.4f
        const val DEADBAND = 0.004f
        const val TELEPORT = 0.12f
        const val SETTLE = 0.008f
    }
}

/** Canonical corners of a normalized outline, so a rotated phone never yields a shifted start corner. */
internal fun orderedNormalized(quad: FloatArray): FloatArray {
    if (quad.size != 8) return quad
    val ordered = try {
        QuadGeometry.order(quad)
    } catch (_: Throwable) {
        FloatArray(0)
    }
    return if (ordered.size == 8) ordered else quad
}

/**
 * Bytes of heap the runtime says this process can still take, used to size a page buffer.
 *
 * The scanner has killed the process before by insisting on a 2400 px page while three other
 * buffers were live, so a page is now decoded to what the heap can actually hold.
 */
internal fun pageDimensionCap(requested: Int): Int {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    val budget = runCatching { ScanBitmaps.safeDimension(requested, max(8L * 1024L * 1024L, available)) }
        .getOrDefault(requested)
    return budget.coerceIn(minOf(640, requested), requested)
}

/**
 * The page pass, in one place: rectify onto [overrideQuad] when the viewfinder or the review sheet
 * already approved corners, otherwise let the core find its own outline, then finish with [filter].
 *
 * This runs on the pure core (bilinear homography, white page background beyond the sheet, measured
 * white balance and level stretch) rather than a canvas colour matrix, because a colour matrix can
 * neither undo perspective nor flatten a shadow. [overrideQuad] is trusted, so the page that comes
 * out is the page the user was shown. Any page the core cannot afford or will not crop falls back
 * to the canvas path, so a tight heap costs sharpness rather than the scan.
 */
internal fun finishPage(
    source: Bitmap,
    overrideQuad: FloatArray?,
    crop: Boolean,
    filter: ScanFilter,
    maxDimension: Int
): Bitmap? {
    if (source.isRecycled) return null
    val cap = pageDimensionCap(maxDimension)
    val pixels = source.width.toLong() * source.height.toLong()
    if (crop && pixels <= MAX_CORE_PIXELS) {
        val trusted = overrideQuad?.takeIf { it.size == 8 }?.let {
            normalizedToPixels(orderedNormalized(it), source.width.toFloat(), source.height.toFloat())
        }
        val outcome = runCatching {
            ScanBitmaps.process(
                source = source,
                look = lookOf(filter),
                autoCrop = true,
                overrideQuad = trusted,
                maxDimension = cap
            )
        }.getOrNull()
        if (outcome != null) {
            val result = outcome.first
            // Identity means the core refused to crop: hand back the upright page, nothing re-run.
            if (result === source) return withinCap(source, cap)
            return result
        }
    }
    return finishLegacy(source, overrideQuad, crop, filter, cap)
}

/** Returns the page resized to [cap], recycling the input, or the input itself when it already fits. */
private fun withinCap(source: Bitmap, cap: Int): Bitmap {
    if (max(source.width, source.height) <= cap) return source
    val scaled = thumbnailOf(source, cap)
    if (scaled !== source) source.recycle()
    return scaled
}

/** The canvas path: the fallback when the pure core cannot run on this heap. */
private fun finishLegacy(
    source: Bitmap,
    overrideQuad: FloatArray?,
    crop: Boolean,
    filter: ScanFilter,
    maxDimension: Int
): Bitmap? {
    var cropped: Bitmap? = null
    return try {
        val quad = if (crop) {
            overrideQuad?.takeIf { it.size == 8 && isTrustworthy(it, source) }
                ?: detectDocumentQuad(source)
        } else {
            null
        }
        if (quad != null) cropped = perspectiveCrop(source, quad)
        val base = cropped ?: source
        val output = applyScanFilter(base, filter)
        var result = if (output !== base) {
            if (base !== source) base.recycle()
            output
        } else {
            base
        }
        if (max(result.width, result.height) > maxDimension) {
            val scaled = thumbnailOf(result, maxDimension)
            if (scaled !== result && result !== source) result.recycle()
            result = scaled
        }
        if (result !== source) source.recycle()
        result
    } catch (_: Throwable) {
        cropped?.recycle()
        if (source.isRecycled) null else source
    }
}

private const val MAX_CORE_PIXELS = 6_000_000L

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
    return finishPage(source, overrideQuad, crop = true, filter = filter, maxDimension = maxDimension)
}

/**
 * The page as text recognition wants it: upright, straightened, and no colour games in between —
 * the model reads edges, and a stretched contrast curve moves them.
 */
internal fun prepareForText(bytes: ByteArray, maxDimension: Int = 2000): Bitmap? {
    val source = normalizedBitmap(bytes, maxDimension) ?: return null
    return finishPage(source, null, crop = true, filter = ScanFilter.Original, maxDimension = maxDimension)
}

/**
 * A two-level copy of a page: ink black, paper white, with the threshold allowed to wander across
 * the sheet so an uneven lamp or a curled corner still binarises. This is the image to hand the
 * text model when the first read of a page came back empty.
 */
internal fun binarisedCopyForText(source: Bitmap): Bitmap? {
    if (source.isRecycled) return null
    return runCatching {
        val plane = ScanBitmaps.argbOf(source)
        ScanBitmaps.toBitmap(Filters.blackWhite(plane, adaptiveWhenSlopeAbove = 12.0))
    }.getOrNull()
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

/**
 * Reads a page twice when the first read found hardly any letters: once as photographed, then again
 * as a flat two-level copy with the threshold allowed to wander across the sheet.
 *
 * A page under a ceiling lamp has a bright half and a shadowed half, and one global contrast curve
 * cannot serve both — the model then returns a handful of characters from the lit side and nothing
 * from the rest. Binarising locally is what recovers those lines, so the better of the two reads
 * wins. Whichever read produced more actual letters is the one kept.
 */
internal suspend fun recognizeTextWithRetry(page: Bitmap): String {
    val first = runCatching { recognizeText(page) }.getOrDefault("")
    if (readableChars(first) >= MIN_USEFUL_CHARS) return first
    val flattened = binarisedCopyForText(page)
    val second = if (flattened == null) {
        ""
    } else {
        runCatching { recognizeText(flattened) }.getOrDefault("")
    }
    if (flattened != null && flattened !== page) flattened.recycle()
    return if (readableChars(second) > readableChars(first)) second else first
}

private fun readableChars(text: String): Int = text.count { it.isLetterOrDigit() }

private const val MIN_USEFUL_CHARS = 12

/**
 * The sealed PDF: A4 in whichever orientation each page actually is, one page decoded at a time.
 *
 * Goes through [ScanPdfWriter] rather than drawing every page into one document at a fixed portrait
 * sheet, so a landscape receipt stops arriving shrunk inside two grey bars and a long session never
 * holds more than a single decoded page.
 */
internal fun buildPdf(pages: List<ByteArray>): ByteArray {
    if (pages.isEmpty()) throw IllegalArgumentException("There are no pages to write.")
    val provider = object : ScanPageProvider {
        override val pageCount: Int = pages.size

        override fun acquire(index: Int): Bitmap? = runCatching {
            normalizedBitmap(pages[index], ScanPdfWriter.pagePixelCap())
        }.getOrNull()

        override fun release(index: Int, bitmap: Bitmap) {
            runCatching { bitmap.recycle() }
        }
    }
    val out = ByteArrayOutputStream()
    val (written, failure) = ScanPdfWriter.compose(provider, out)
    if (failure != null) throw IllegalStateException(failure.message)
    if (written == 0) throw IllegalStateException("No pages could be read.")
    return out.toByteArray()
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
