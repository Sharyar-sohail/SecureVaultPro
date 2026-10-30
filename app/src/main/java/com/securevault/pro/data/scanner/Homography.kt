package com.securevault.pro.data.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The 3x3 projective transform, solved from four point pairs, plus its inverse.
 *
 * A rectified scan is exactly one homography: the four corners of the photographed sheet map to
 * the four corners of a rectangle, and every other pixel follows. `Matrix.setPolyToPoly` can do
 * the same thing on Android, but it cannot be tested off-device, it gives no residual measure and
 * it cannot be inverted — and the inverse is the only correct way to resample, because you must
 * ask "which source pixel does *this output* pixel come from", never "where did this source pixel
 * go" (forward mapping leaves holes).
 *
 * The solve is the direct linear transform on the 8x8 system with h9 pinned to 1, after Hartley
 * normalisation (translate centroids to the origin, scale so the mean radius is √2). Without the
 * normalisation the entries of A span 1..~6 000 000 on a 2400 px page and the elimination loses
 * the low bits; with it the corner residual on a synthetic quad is < 1e-3 px, which is what
 * `HomographySolveTest` asserts.
 */
object Homography {

    /** A transform is 9 floats in row-major order: [h0 h1 h2 / h3 h4 h5 / h6 h7 h8]. */
    const val SIZE = 9

    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Maps `(x, y)` through [matrix]; returns null when the point lands on the line at infinity. */
    fun mapPoint(matrix: FloatArray, x: Float, y: Float): FloatArray? {
        val denominator = matrix[6] * x + matrix[7] * y + matrix[8]
        if (abs(denominator) < 1e-12f) return null
        val nx = matrix[0] * x + matrix[1] * y + matrix[2]
        val ny = matrix[3] * x + matrix[4] * y + matrix[5]
        return floatArrayOf(nx / denominator, ny / denominator)
    }

    /** Mean reprojection error, in pixels, of [source] through [matrix] onto [destination]. */
    fun residual(matrix: FloatArray, source: FloatArray, destination: FloatArray): Double {
        val count = minOf(source.size / 2, destination.size / 2)
        if (count == 0) return Double.POSITIVE_INFINITY
        var total = 0.0
        for (index in 0 until count) {
            val mapped = mapPoint(matrix, source[index * 2], source[index * 2 + 1]) ?: return Double.POSITIVE_INFINITY
            val dx = mapped[0] - destination[index * 2]
            val dy = mapped[1] - destination[index * 2 + 1]
            total += sqrt((dx * dx + dy * dy).toDouble())
        }
        return total / count
    }

    /**
     * Solves `source → destination` for any number of point pairs (>= 4).
     *
     * The normal equations (AᵀA·h = Aᵀb) of the direct linear transform are used rather than a
     * square system, so a five- or six-point correspondence — the API keeps this open for a future
     * marker-based mode — resolves as a genuine least-squares fit instead of an exact one.
     *
     * Returns null instead of garbage when the pairs are degenerate: four points of a quad that
     * are collinear, duplicated or so thin that the normalised system is singular produce a zero
     * pivot here, and the caller then falls back to full-frame rather than warping onto a line.
     */
    fun solve(source: FloatArray, destination: FloatArray): FloatArray? {
        val count = minOf(source.size / 2, destination.size / 2)
        if (count < 4) return null
        val sourceNorm = Normalization.of(source, count)
        val destinationNorm = Normalization.of(destination, count)
        if (sourceNorm == null || destinationNorm == null) return null
        val equations = count * 2
        val design = Array(equations) { DoubleArray(8) }
        val target = DoubleArray(equations)
        for (index in 0 until count) {
            val sx = sourceNorm.forwardX(source[index * 2], source[index * 2 + 1])
            val sy = sourceNorm.forwardY(source[index * 2], source[index * 2 + 1])
            val dx = destinationNorm.forwardX(destination[index * 2], destination[index * 2 + 1])
            val dy = destinationNorm.forwardY(destination[index * 2], destination[index * 2 + 1])
            val row = index * 2
            design[row][0] = sx; design[row][1] = sy; design[row][2] = 1.0
            design[row][6] = -dx * sx; design[row][7] = -dx * sy
            target[row] = dx
            val next = row + 1
            design[next][3] = sx; design[next][4] = sy; design[next][5] = 1.0
            design[next][6] = -dy * sx; design[next][7] = -dy * sy
            target[next] = dy
        }
        val normal = Array(8) { row -> DoubleArray(8).also { column ->
            for (k in 0 until 8) {
                var total = 0.0
                for (equation in 0 until equations) {
                    total += design[equation][row] * design[equation][k]
                }
                column[k] = total
            }
        } }
        val product = DoubleArray(8)
        for (row in 0 until 8) {
            var total = 0.0
            for (equation in 0 until equations) total += design[equation][row] * target[equation]
            product[row] = total
        }
        val solution = solveLinearSystem(normal, product) ?: return null
        val normalised = floatArrayOf(
            solution[0].toFloat(), solution[1].toFloat(), solution[2].toFloat(),
            solution[3].toFloat(), solution[4].toFloat(), solution[5].toFloat(),
            solution[6].toFloat(), solution[7].toFloat(), 1f
        )
        // H = Tdestination^-1 * Hnormalised * Tsource
        return normaliseScale(
            multiply(inverse(destinationNorm.matrix) ?: return null, multiply(normalised, sourceNorm.matrix))
        )
    }

    /** Convenience: the transform that rectifies [quad] (image coordinates) onto a w x h rectangle. */
    fun quadToRectangle(quad: FloatArray, width: Int, height: Int): FloatArray? {
        if (quad.size < 8 || width < 2 || height < 2) return null
        val destination = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        return solve(quad, destination)
    }

    /** Inverse of a 3x3; null when singular (i.e. the transform collapsed the plane). */
    fun inverse(matrix: FloatArray): FloatArray? {
        if (matrix.size < SIZE) return null
        val a = matrix[0]; val b = matrix[1]; val c = matrix[2]
        val d = matrix[3]; val e = matrix[4]; val f = matrix[5]
        val g = matrix[6]; val h = matrix[7]; val i = matrix[8]
        val cofactor00 = e * i - f * h
        val cofactor01 = -(d * i - f * g)
        val cofactor02 = d * h - e * g
        val determinant = a * cofactor00 + b * cofactor01 + c * cofactor02
        if (abs(determinant) < 1e-12f) return null
        val scale = 1f / determinant
        return floatArrayOf(
            scale * cofactor00,
            scale * -(b * i - c * h),
            scale * (b * f - c * e),
            scale * cofactor01,
            scale * (a * i - c * g),
            scale * -(a * f - c * d),
            scale * cofactor02,
            scale * -(a * h - b * g),
            scale * (a * e - b * d)
        )
    }

    fun multiply(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray(SIZE)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                var total = 0.0
                for (k in 0 until 3) {
                    total += first[row * 3 + k].toDouble() * second[k * 3 + column].toDouble()
                }
                out[row * 3 + column] = total.toFloat()
            }
        }
        return out
    }

    /** Divides everything by h8 so the projective terms stay comparable across solves. */
    private fun normaliseScale(matrix: FloatArray): FloatArray {
        val pivot = matrix[8]
        if (abs(pivot) < 1e-12f) return matrix
        return FloatArray(SIZE) { matrix[it] / pivot }
    }

    /**
     * Gaussian elimination with partial pivoting on a square system.
     *
     * A pivot below 1e-11 is treated as "this point set is degenerate", not as "divide anyway".
     */
    private fun solveLinearSystem(coefficient: Array<DoubleArray>, constants: DoubleArray): DoubleArray? {
        val n = constants.size
        if (coefficient.size != n) return null
        val work = Array(n) { row -> coefficient[row].copyOf(n + 1) }
        for (row in 0 until n) {
            work[row][n] = constants[row]
        }
        for (column in 0 until n) {
            var pivot = column
            var best = abs(work[column][column])
            for (search in column + 1 until n) {
                val value = abs(work[search][column])
                if (value > best) {
                    best = value
                    pivot = search
                }
            }
            if (best < 1e-11) return null
            if (pivot != column) {
                val swap = work[column]
                work[column] = work[pivot]
                work[pivot] = swap
            }
            val diagonal = work[column][column]
            for (search in column + 1 until n) {
                val factor = work[search][column] / diagonal
                if (factor == 0.0) continue
                for (k in column..n) {
                    work[search][k] -= factor * work[column][k]
                }
            }
        }
        val solution = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var total = work[row][n]
            for (k in row + 1 until n) total -= work[row][k] * solution[k]
            solution[row] = total / work[row][row]
        }
        return if (solution.all { it.isFinite() }) solution else null
    }

    /**
     * Hartley normalisation: shift to the centroid, scale to a mean radius of √2.
     * [matrix] is the forward normaliser, so the inverse is what maps back.
     */
    class Normalization private constructor(
        val shiftX: Double,
        val shiftY: Double,
        val scale: Double,
        val matrix: FloatArray
    ) {
        fun forwardX(x: Float, y: Float): Double = (x - shiftX) * scale
        fun forwardY(x: Float, y: Float): Double = (y - shiftY) * scale

        companion object {
            fun of(points: FloatArray, count: Int): Normalization? {
                if (count < 2) return null
                var sumX = 0.0
                var sumY = 0.0
                for (index in 0 until count) {
                    sumX += points[index * 2]
                    sumY += points[index * 2 + 1]
                }
                val centroidX = sumX / count
                val centroidY = sumY / count
                var radius = 0.0
                for (index in 0 until count) {
                    val dx = points[index * 2] - centroidX
                    val dy = points[index * 2 + 1] - centroidY
                    radius += sqrt(dx * dx + dy * dy)
                }
                radius /= count
                if (radius < 1e-6) return null
                val scale = sqrt(2.0) / radius
                return Normalization(
                    centroidX,
                    centroidY,
                    scale,
                    floatArrayOf(
                        scale.toFloat(), 0f, (-centroidX * scale).toFloat(),
                        0f, scale.toFloat(), (-centroidY * scale).toFloat(),
                        0f, 0f, 1f
                    )
                )
            }
        }
    }

    /** Longest/best side pair of a quad, used to size the rectangled output. */
    fun destinationSize(quad: FloatArray, maxDimension: Int = 2400): IntArray? {
        if (quad.size < 8) return null
        val top = QuadGeometry.side(quad, 0, 1)
        val right = QuadGeometry.side(quad, 1, 2)
        val bottom = QuadGeometry.side(quad, 2, 3)
        val left = QuadGeometry.side(quad, 3, 0)
        if (top <= 0 || right <= 0 || bottom <= 0 || left <= 0) return null
        val longest = max(max(top, bottom), max(left, right))
        val fit = if (longest > maxDimension) maxDimension / longest else 1.0
        val width = (max(top, bottom) * fit).toInt()
        val height = (max(left, right) * fit).toInt()
        if (width < 16 || height < 16) return null
        return intArrayOf(width, height)
    }
}
