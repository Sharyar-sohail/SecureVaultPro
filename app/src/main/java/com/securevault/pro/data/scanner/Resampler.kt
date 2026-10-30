package com.securevault.pro.data.scanner

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Bilinear resampling and the rectification that uses it.
 *
 * Inverse mapping only: for each output pixel the *inverse* homography is applied to find where
 * in the source it came from, then four source texels are interpolated. Forward mapping (push
 * source pixels to destinations) leaves pinholes wherever the scale shrinks and double-writes
 * wherever it grows, which on a page is exactly the region the user is about to read.
 *
 * Sampling weights are the textbook `((1-fx)(1-fy), fx(1-fy), …)` on the *unpremultiplied* ARGB
 * ints that [android.graphics.Bitmap.getPixels] returns; alpha is interpolated with the same
 * weights so a transparent wedge stays transparent. Boundary rule: coordinates are clamped to the
 * texture, so a pixel that maps just outside the sheet reads the edge texel (replicate) rather
 * than black — but a pixel that maps outside the *quad's own bounding box* by more than
 * [EDGE_TOLERANCE_PX] is filled with the page background instead, so a cropped sheet never
 * inherits the desk it was photographed on.
 */
object Resampler {

    /** How far outside the source a destination pixel may reach before it counts as background. */
    const val EDGE_TOLERANCE_PX: Float = 0.5f

    /**
     * Bilinear sample at fractional coordinates.
     *
     * Exact integers return the texel itself, and a coordinate of `width - 1` returns the last
     * column, which is what `BilinearResampleTest` asserts at all four edges.
     */
    fun sampleBilinear(plane: ArgbPlane, x: Float, y: Float): Int {
        val fx = clamp(x, 0f, (plane.width - 1).toFloat()).toDouble()
        val fy = clamp(y, 0f, (plane.height - 1).toFloat()).toDouble()
        val x0 = floor(fx).toInt().coerceIn(0, plane.width - 1)
        val y0 = floor(fy).toInt().coerceIn(0, plane.height - 1)
        val x1 = min(x0 + 1, plane.width - 1)
        val y1 = min(y0 + 1, plane.height - 1)
        val wx = fx - x0
        val wy = fy - y0
        val a = plane.pixels[y0 * plane.width + x0]
        val b = plane.pixels[y0 * plane.width + x1]
        val c = plane.pixels[y1 * plane.width + x0]
        val d = plane.pixels[y1 * plane.width + x1]
        val top = 1.0 - wx
        val bottom = wx
        fun channel(shift: Int): Int {
            val value = ((a shr shift) and 0xFF) * top * (1.0 - wy) +
                ((b shr shift) and 0xFF) * bottom * (1.0 - wy) +
                ((c shr shift) and 0xFF) * top * wy +
                ((d shr shift) and 0xFF) * bottom * wy
            return clampChannel(value + 0.5)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * Warps [source] through an already-inverted homography.
     *
     * `inverse` must map *destination* coordinates back to *source* coordinates — pass
     * [Homography.inverse] of [Homography.quadToRectangle]. Rows are written one at a time into a
     * freshly allocated buffer, so peak memory is one destination plus one source plane: this is
     * the function the 40-page session calls once per page, never once per page pair.
     */
    fun warp(
        source: ArgbPlane,
        inverse: FloatArray,
        width: Int,
        height: Int,
        background: Int = ArgbPlane.pack(255, 255, 255, 255)
    ): ArgbPlane? {
        if (width < 1 || height < 1 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION) return null
        val out = IntArray(width * height)
        val left = -EDGE_TOLERANCE_PX
        val top = -EDGE_TOLERANCE_PX
        val right = source.width - 1f + EDGE_TOLERANCE_PX
        val bottom = source.height - 1f + EDGE_TOLERANCE_PX
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val px = x + 0.5f
                val py = y + 0.5f
                val divisor = inverse[6] * px + inverse[7] * py + inverse[8]
                if (abs(divisor) < 1e-9f) {
                    out[row + x] = background
                    continue
                }
                val sx = (inverse[0] * px + inverse[1] * py + inverse[2]) / divisor - 0.5f
                val sy = (inverse[3] * px + inverse[4] * py + inverse[5]) / divisor - 0.5f
                out[row + x] = if (sx < left || sx > right || sy < top || sy > bottom) {
                    background
                } else {
                    sampleBilinear(source, sx, sy)
                }
            }
        }
        return ArgbPlane(width, height, out)
    }

    /**
     * Rectifies a photographed sheet.
     *
     * Sizes the output from the quad's own side lengths (so a landscape page does not come out
     * portrait), caps the longest side at [maxDimension] to keep a 12 MP capture from becoming a
     * 12 MP bitmap, solves the homography, inverts it and resamples. Any step that cannot be
     * trusted returns null and the caller keeps the full frame — see [DocumentPipeline].
     */
    fun rectify(
        source: ArgbPlane,
        quad: FloatArray,
        maxDimension: Int = 2400,
        background: Int = ArgbPlane.pack(255, 255, 255, 255)
    ): RectifiedPage? {
        val ordered = QuadGeometry.order(quad)
        if (ordered.size < 8) return null
        val size = Homography.destinationSize(ordered, min(maxDimension, MAX_OUTPUT_DIMENSION)) ?: return null
        val width = size[0]
        val height = size[1]
        val destination = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        val forward = Homography.solve(ordered, destination) ?: return null
        val inverse = Homography.inverse(forward) ?: return null
        // The transform has to actually put the corners where they belong before a single pixel is
        // resampled: on a normalised 4-point solve the residual is ~1e-4 px, so anything above a
        // quarter pixel means the correspondence was degenerate and the warp would smear the page.
        if (Homography.residual(forward, ordered, destination) > 0.25) return null
        val warped = warp(source, inverse, width, height, background) ?: return null
        return RectifiedPage(warped, ordered, width, height)
    }

    const val MAX_OUTPUT_DIMENSION: Int = 4096
}

/** A rectified page: the pixels, the corners they came from and the output size. */
class RectifiedPage(
    val plane: ArgbPlane,
    val quad: FloatArray,
    val width: Int,
    val height: Int
)
