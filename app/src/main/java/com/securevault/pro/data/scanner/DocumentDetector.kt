package com.securevault.pro.data.scanner

import kotlin.math.max
import kotlin.math.min

/** Why a quad was refused. Surfaced to the user, never swallowed. */
enum class DetectionFailure(val message: String) {
    NONE(""),
    TOO_SMALL("The page sits too close to the edge of the frame to crop safely."),
    NO_CONTRAST("The page and the surface under it are the same brightness, so there is no outline to follow."),
    FRAME_FILLED("The sheet already fills the photo, so there is nothing left to crop."),
    NOT_QUADRILATERAL("The outline found is not a four-sided sheet — it may be a shadow or a hand."),
    BLURRY("An outline is there, but its edges are too soft to straighten onto."),
    DEGENERATE_TRANSFORM("The straightening maths refused this shape. Keeping the full frame.");

    val rejected: Boolean get() = this != NONE
}

/** Which of the two detectors produced the outline. */
enum class DetectionMethod { LUMINANCE_REGION, EDGE_CLOSURE, NONE }

/**
 * A quad plus everything that justifies it.
 *
 * [confidence] is a measured number, not a label: the mean Sobel response along the four sides
 * (how sharp the outline is) mixed with the squareness of the corners and, for the luminance
 * method, the solidity of the region inside them. It is what the viewfinder shows as "hold
 * steady" and what the still path uses to decide whether to crop at all, so a low-confidence quad
 * is reported rather than quietly dropped.
 */
data class DetectionResult(
    val quad: FloatArray,
    val method: DetectionMethod,
    val confidence: Double,
    val edgeStrength: Double,
    val squareness: Double,
    val solidity: Double,
    val areaFraction: Double,
    val hull: FloatArray,
    val failure: DetectionFailure = DetectionFailure.NONE
) {
    val accepted: Boolean get() = quad.size == 8 && failure == DetectionFailure.NONE

    fun withFailure(reason: DetectionFailure): DetectionResult =
        if (accepted && reason.rejected) copy(quad = FloatArray(0), hull = FloatArray(0), failure = reason)
        else copy(failure = reason)

    companion object {
        /** The lowest confidence the auto-crop will act on; below it, full frame + a reason. */
        const val MIN_CONFIDENCE: Double = 0.22

        fun rejected(reason: DetectionFailure, method: DetectionMethod = DetectionMethod.NONE) =
            DetectionResult(
                quad = FloatArray(0),
                method = method,
                confidence = 0.0,
                edgeStrength = 0.0,
                squareness = 0.0,
                solidity = 0.0,
                areaFraction = 0.0,
                hull = FloatArray(0),
                failure = reason
            )
    }
}

/** One detector's answer: either a quad or the reason it has none. */
private class Attempt(val result: DetectionResult?, val failure: DetectionFailure)

/**
 * The edge detector proper: a luminance plane in, a justified quadrilateral out.
 *
 * The pipeline, in order, with the reason each stage is there:
 *
 *  1. **Smooth** — two 3x3 binomial passes. Sensor noise at ISO 3200 otherwise seeds a hundred
 *     gradient pixels per square centimetre and the contour walks the noise instead of the page.
 *  2. **Two masks in parallel** — an Otsu luminance split (paper vs desk) and a Sobel gradient
 *     threshold (any hard boundary at all). A white page on a white desk has no luminance split but
 *     a strong edge; a shaded page on a dark desk has the opposite. Running only one of them is why
 *     most naive scanners "lose" half their shots.
 *  3. **Region / contour** — largest 4-connected component for the luminance mask, Moore-traced
 *     boundary for the edge mask, both reduced to a convex hull.
 *  4. **Douglas-Peucker to four corners**, then **total-least-squares edge fits with outlier
 *     rejection** to place each corner where the two fitted edges actually intersect.
 *  5. **Gate and score** — [QuadGeometry.isUsable] plus a measured confidence. If nothing passes, a
 *     [DetectionFailure] comes back with the reason and the caller tells the user instead of
 *     inventing corners.
 */
object DocumentDetector {

    /** The size the detector wants its probe buffer at: 640 px long side is ~1 % of a page. */
    const val PROBE_LONGEST: Int = 640

    /** Frames smaller than this carry too few texels for a contour to mean anything. */
    const val MIN_PROBE_SIDE: Int = 48

    fun detect(source: LumaPlane, probeLongest: Int = PROBE_LONGEST): DetectionResult {
        val longest = max(source.width, source.height)
        if (longest < MIN_PROBE_SIDE) return DetectionResult.rejected(DetectionFailure.TOO_SMALL)
        val scale = probeLongest.toFloat() / longest
        val probe = source.resized(
            max(MIN_PROBE_SIDE, (source.width * scale).toInt()),
            max(MIN_PROBE_SIDE, (source.height * scale).toInt())
        )
        val width = probe.width
        val height = probe.height
        val grey = probe.smooth3x3(passes = 2)
        val gradient = EdgeMap.sobel(grey)
        val frameArea = width.toDouble() * height.toDouble()

        val attempts = listOf(
            tryLuminanceRegion(grey, gradient, width, height, frameArea),
            tryEdgeClosure(grey, gradient, width, height, frameArea)
        )
        val best = attempts.mapNotNull { it.result }.sortedByDescending { it.confidence }.firstOrNull()
        if (best == null) {
            // Prefer the most specific reason the two strategies gave.
            val reason = attempts.firstOrNull { it.failure.rejected }?.failure ?: DetectionFailure.NO_CONTRAST
            return DetectionResult.rejected(reason)
        }
        return if (best.confidence < DetectionResult.MIN_CONFIDENCE) {
            best.copy(quad = FloatArray(0), failure = DetectionFailure.BLURRY)
        } else {
            best
        }
    }

    /** Runs the detector on a colour buffer, in that buffer's own pixel coordinates. */
    fun detectArgb(source: ArgbPlane, probeLongest: Int = PROBE_LONGEST): DetectionResult =
        detect(LumaPlane.of(source), probeLongest)

    private fun tryLuminanceRegion(
        grey: LumaPlane,
        gradient: LumaPlane,
        width: Int,
        height: Int,
        frameArea: Double
    ): Attempt {
        val hist = Thresholds.histogram(grey.pixels)
        if (Thresholds.otsuSeparation(hist) < 0.15) {
            return Attempt(null, DetectionFailure.NO_CONTRAST)
        }
        val threshold = Thresholds.otsu(hist)
        val bright = BooleanArray(grey.size)
        var brightCount = 0
        for (index in grey.pixels.indices) {
            if (grey.pixels[index] > threshold) {
                bright[index] = true
                brightCount += 1
            }
        }
        val fraction = brightCount.toDouble() / max(1, grey.size)
        if (fraction > 0.985) return Attempt(null, DetectionFailure.FRAME_FILLED)
        if (fraction < 0.04) return Attempt(null, DetectionFailure.TOO_SMALL)
        val region = Contours.largestRegion(bright, width, height)
            ?: return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        if (Contours.coverage(region, width, height) < 0.05) {
            return Attempt(null, DetectionFailure.TOO_SMALL)
        }
        val outline = Contours.regionOutline(region, width, height)
        return score(
            outline, gradient, width, height, frameArea,
            solidity = region.solidity, method = DetectionMethod.LUMINANCE_REGION
        )
    }

    private fun tryEdgeClosure(
        grey: LumaPlane,
        gradient: LumaPlane,
        width: Int,
        height: Int,
        frameArea: Double
    ): Attempt {
        val hist = Thresholds.histogram(gradient.pixels)
        val threshold = Thresholds.gradientThreshold(hist)
        if (Thresholds.percentile(hist, 0.995) < threshold) {
            return Attempt(null, DetectionFailure.BLURRY)
        }
        val mask = EdgeMap.close(EdgeMap.edges(gradient, threshold), width, height, radius = 1)
        // Seed on the brightest edge pixel inside the inner 60 % of the frame: the frame border is
        // where the *lens* vignette lives, and a contour started there traces the vignette.
        var seed = -1
        var bestValue = -1
        var y = height / 5
        while (y < height - height / 5) {
            var x = width / 5
            while (x < width - width / 5) {
                if (mask[y * width + x] && grey[x, y] > bestValue) {
                    bestValue = grey[x, y]
                    seed = y * width + x
                }
                x += 3
            }
            y += 3
        }
        if (seed < 0) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        val traced = Contours.traceBoundary(mask, width, height, seed)
            ?: return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        val hull = Contours.convexHull(traced)
        if (hull.size < 8) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        // Solidity is meaningless for a contour that has no interior, so the closure path scores on
        // edge sharpness and corner squareness alone.
        return score(
            hull, gradient, width, height, frameArea,
            solidity = 0.0, method = DetectionMethod.EDGE_CLOSURE
        )
    }

    /** Hull -> four corners -> line-fit refinement -> gate -> score. */
    private fun score(
        hull: FloatArray,
        gradient: LumaPlane,
        width: Int,
        height: Int,
        frameArea: Double,
        solidity: Double,
        method: DetectionMethod
    ): Attempt {
        if (hull.size < 8) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        val approximated = Contours.toVertexCount(hull, 4)
        if (approximated.size < 8) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        val ordered = QuadGeometry.order(approximated)
        if (ordered.size < 8) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        val refined = QuadGeometry.refine(ordered, hull, width.toDouble(), height.toDouble())
        val quad = QuadGeometry.order(refined)
        if (quad.size < 8) return Attempt(null, DetectionFailure.NOT_QUADRILATERAL)
        if (!QuadGeometry.isUsable(quad, width.toDouble(), height.toDouble())) {
            val area = QuadGeometry.area(quad) / max(1.0, frameArea)
            val reason = when {
                area > 0.985 -> DetectionFailure.FRAME_FILLED
                area < 0.06 -> DetectionFailure.TOO_SMALL
                else -> DetectionFailure.NOT_QUADRILATERAL
            }
            return Attempt(null, reason)
        }
        val edge = QuadGeometry.edgeStrength(quad, gradient)
        val squareness = QuadGeometry.squareness(quad)
        val area = QuadGeometry.area(quad) / max(1.0, frameArea)
        val confidence = when (method) {
            DetectionMethod.LUMINANCE_REGION ->
                0.50 * edge + 0.22 * squareness + 0.28 * solidity.coerceIn(0.0, 1.0)
            DetectionMethod.NONE -> 0.0
            else -> 0.62 * edge + 0.38 * squareness
        }
        val result = DetectionResult(
            quad = quad,
            method = method,
            confidence = confidence,
            edgeStrength = edge,
            squareness = squareness,
            solidity = solidity,
            areaFraction = area,
            hull = hull
        )
        return Attempt(result, DetectionFailure.NONE)
    }

    /**
     * Scales a quad produced on the probe buffer back up to the full-resolution frame the pixels
     * live in. Aspect is preserved per axis because the probe resize was per-axis nearest.
     */
    fun scaleQuad(quad: FloatArray, probeWidth: Int, probeHeight: Int, width: Int, height: Int): FloatArray {
        val out = FloatArray(quad.size)
        val scaleX = width.toFloat() / max(1, probeWidth)
        val scaleY = height.toFloat() / max(1, probeHeight)
        for (index in 0 until quad.size / 2) {
            out[index * 2] = quad[index * 2] * scaleX
            out[index * 2 + 1] = quad[index * 2 + 1] * scaleY
        }
        return out
    }

    /** Convenience: the probe size the detector would have used for a frame of this shape. */
    fun probeSizeFor(width: Int, height: Int, probeLongest: Int = PROBE_LONGEST): IntArray {
        val longest = max(width, height)
        val scale = probeLongest.toFloat() / longest
        return intArrayOf(
            max(MIN_PROBE_SIDE, (width * scale).toInt()),
            max(MIN_PROBE_SIDE, (height * scale).toInt())
        )
    }

    /** True when the detector found something worth cropping, used by the live overlay. */
    fun usable(result: DetectionResult): Boolean = result.accepted

    /**
     * The message shown when a page is *not* auto-cropped. Kept here so the UI cannot invent a
     * friendlier lie about what the maths actually refused.
     */
    fun fallbackMessage(reason: DetectionFailure): String = when (reason) {
        DetectionFailure.NONE -> ""
        else -> reason.message
    }

    /** Clamp helper kept next to the scorer that uses it. */
    private fun unit(value: Double): Double = min(1.0, max(0.0, value))
}
