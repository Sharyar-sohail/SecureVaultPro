package com.securevault.pro.data.scanner

import kotlin.math.max
import kotlin.math.min

/** The looks a page can be finished with. Every one of them is a real pixel operation. */
enum class ScanLook(val label: String, val description: String) {
    ORIGINAL("Original", "Straightened, untouched pixels"),
    ENHANCE("Enhance", "White balance, level stretch, light sharpen"),
    BLACK_WHITE("B & W", "Two levels only: ink black, paper white"),
    GREYSCALE("Greyscale", "BT.601 luminance, no colour"),
    MAGIC("Magic colour", "White point balanced, chroma lifted"),
    SHARPEN("Crisp", "Unsharp mask over the original, no levels touched");

    companion object {
        fun apply(source: ArgbPlane, look: ScanLook): ArgbPlane = when (look) {
            ORIGINAL -> source
            ENHANCE -> Filters.scanEnhance(source)
            BLACK_WHITE -> Filters.blackWhite(source)
            GREYSCALE -> Filters.greyscale(source)
            MAGIC -> Filters.magicColour(source)
            SHARPEN -> Filters.unsharp(source, radius = 3, amount = 0.95)
        }
    }
}

/**
 * What came out of one page pass.
 *
 * [plane] is always returned — a page the detector refused still gets filtered, only uncropped —
 * and [failure] carries the sentence the UI shows about it, so "we cropped it" is never implied
 * when we did not.
 */
class ScanOutcome(
    val plane: ArgbPlane,
    val looked: ScanLook,
    val cropped: Boolean,
    val detection: DetectionResult,
    val failure: DetectionFailure,
    val outputWidth: Int,
    val outputHeight: Int
) {
    /** What the user should be told, or null when nothing went wrong. */
    val notice: String? get() = failure.takeIf { it.rejected }?.message
}

/**
 * The page pipeline: detect, rectify, finish.
 *
 * Pure Kotlin, no `android.*`, no bitmap, no context. It takes the ARGB buffer a capture decoded
 * into and hands back the buffer a PDF page or a preview is drawn from. Both the still-capture and
 * the live viewfinder paths sit on top of this, so an edge that looks like a page on the screen
 * rectifies into the same page afterwards — the single most common way a scanner of this class
 * ends up cropping somewhere other than where it told the user it would.
 */
object DocumentPipeline {

    /**
     * Runs the whole page pass.
     *
     * @param source the upright page pixels, full resolution.
     * @param look the finish to apply *after* rectification, never before: sharpening a skewed page
     *   sharpens the interpolation artefacts of the skew.
     * @param autoCrop false keeps the full frame (the "Full frame" chip).
     * @param overrideQuad corners the user already approved, in [source] pixels. Trusted as given:
     *   an explicit quad is never second-guessed by the detector, only re-ordered.
     * @param maxDimension the longest output side; 2400 is ~300 dpi on A4 and ~1.5 MB of pixels.
     */
    fun process(
        source: ArgbPlane,
        look: ScanLook = ScanLook.ENHANCE,
        autoCrop: Boolean = true,
        overrideQuad: FloatArray? = null,
        maxDimension: Int = 2400
    ): ScanOutcome {
        val width = source.width.toDouble()
        val height = source.height.toDouble()
        if (!autoCrop) {
            return ScanOutcome(
                plane = ScanLook.apply(source, look),
                looked = look,
                cropped = false,
                detection = DetectionResult.rejected(DetectionFailure.NONE),
                failure = DetectionFailure.NONE,
                outputWidth = source.width,
                outputHeight = source.height
            )
        }
        val manual = overrideQuad?.takeIf { it.size == 8 }?.let { QuadGeometry.order(it) }
        var quad = manual
        var detection: DetectionResult
        if (manual != null && QuadGeometry.isUsable(manual, width, height)) {
            detection = DetectionResult(
                quad = manual,
                method = DetectionMethod.NONE,
                confidence = 1.0,
                edgeStrength = 1.0,
                squareness = QuadGeometry.squareness(manual),
                solidity = 1.0,
                areaFraction = QuadGeometry.area(manual) / max(1.0, width * height),
                hull = manual
            )
        } else {
            // Detect on a probe buffer, then lift the corners back into full-resolution pixels.
            val probe = probeOf(source)
            val raw = DocumentDetector.detect(probe)
            detection = if (raw.accepted) {
                val scaled = DocumentDetector.scaleQuad(
                    raw.quad, probe.width, probe.height, source.width, source.height
                )
                raw.copy(quad = scaled)
            } else {
                raw
            }
            quad = detection.quad.takeIf { it.size == 8 && QuadGeometry.isUsable(it, width, height) }
        }
        if (quad == null) {
            val reason = if (manual != null) DetectionFailure.NOT_QUADRILATERAL else detection.failure
            return ScanOutcome(
                plane = ScanLook.apply(source, look),
                looked = look,
                cropped = false,
                detection = detection,
                failure = reason,
                outputWidth = source.width,
                outputHeight = source.height
            )
        }
        val rectified = Resampler.rectify(source, quad, maxDimension)
        if (rectified == null) {
            return ScanOutcome(
                plane = ScanLook.apply(source, look),
                looked = look,
                cropped = false,
                detection = detection,
                failure = DetectionFailure.DEGENERATE_TRANSFORM,
                outputWidth = source.width,
                outputHeight = source.height
            )
        }
        return ScanOutcome(
            plane = ScanLook.apply(rectified.plane, look),
            looked = look,
            cropped = true,
            detection = detection,
            failure = DetectionFailure.NONE,
            outputWidth = rectified.width,
            outputHeight = rectified.height
        )
    }

    /**
     * Rectifies onto an approved quad without touching detection — the review sheet's "use these
     * corners" path, where the user has already overruled the auto-crop.
     */
    fun rectifyOnly(
        source: ArgbPlane,
        quad: FloatArray,
        look: ScanLook,
        maxDimension: Int = 2400
    ): ScanOutcome? {
        val ordered = QuadGeometry.order(quad).takeIf { it.size == 8 } ?: return null
        val rectified = Resampler.rectify(source, ordered, maxDimension) ?: return null
        return ScanOutcome(
            plane = ScanLook.apply(rectified.plane, look),
            looked = look,
            cropped = true,
            detection = DetectionResult(
                quad = ordered,
                method = DetectionMethod.NONE,
                confidence = 1.0,
                edgeStrength = 1.0,
                squareness = QuadGeometry.squareness(ordered),
                solidity = 1.0,
                areaFraction = QuadGeometry.area(ordered) /
                    max(1.0, source.width.toDouble() * source.height.toDouble()),
                hull = ordered
            ),
            failure = DetectionFailure.NONE,
            outputWidth = rectified.width,
            outputHeight = rectified.height
        )
    }

    /**
     * The probe the detector runs on.
     *
     * Strided nearest sampling rather than [LumaPlane.resized] on the full plane: taking a 640-px
     * probe out of a 2400-px page costs one pass over the small buffer instead of a copy of the big
     * one, and the detector only ever reads luminance from it.
     */
    private fun probeOf(source: ArgbPlane): LumaPlane {
        val size = DocumentDetector.probeSizeFor(source.width, source.height)
        val width = min(size[0], source.width)
        val height = min(size[1], source.height)
        val out = IntArray(width * height)
        val stepX = source.width.toFloat() / width
        val stepY = source.height.toFloat() / height
        for (y in 0 until height) {
            val sourceY = min(source.height - 1, (y * stepY).toInt())
            val sourceRow = sourceY * source.width
            val row = y * width
            for (x in 0 until width) {
                val sourceX = min(source.width - 1, (x * stepX).toInt())
                val color = source.pixels[sourceRow + sourceX]
                out[row + x] = lumaOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            }
        }
        return LumaPlane(width, height, out)
    }
}
