package com.securevault.pro.data.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corner bookkeeping for the four points a quadrilateral is made of.
 *
 * The contract every other file relies on: a quad is eight floats in image coordinates,
 * `(x0, y0 … x3, y3)`, ordered so index 0 is the **top-left**, 1 the **top-right**, 2 the
 * **bottom-right** and 3 the **bottom-left** — where "top" means the smaller y in the quad's own
 * frame. That ordering is what lets the homography map 0→(0,0), 1→(w,0), 2→(w,h), 3→(0,h) without
 * mirroring the page, and it is produced from any input permutation by [order], which sorts the
 * four points cyclically by polar angle around their centroid and then rotates the cycle so the
 * topmost-then-leftmost corner leads. Sorting by angle is what makes the result identical for a
 * quad handed over rotated by 90°, 180° or 270° — the rotation moves the corner, and the corner
 * moves with it (asserted in `QuadOrderingTest`).
 */
object QuadGeometry {

    /** Minimum interior angle, in radians, a corner may have before the quad is refused. */
    const val MIN_ANGLE: Double = 0.35

    fun x(quad: FloatArray, corner: Int): Double = quad[corner * 2].toDouble()
    fun y(quad: FloatArray, corner: Int): Double = quad[corner * 2 + 1].toDouble()

    fun centroidX(quad: FloatArray): Double = (x(quad, 0) + x(quad, 1) + x(quad, 2) + x(quad, 3)) / 4.0
    fun centroidY(quad: FloatArray): Double = (y(quad, 0) + y(quad, 1) + y(quad, 2) + y(quad, 3)) / 4.0

    fun side(quad: FloatArray, first: Int, second: Int): Double =
        Contours.distance(x(quad, first), y(quad, first), x(quad, second), y(quad, second))

    fun area(quad: FloatArray): Double = Contours.area(quad)

    /** Signed shoelace area; positive means the cycle reads clockwise on screen (TL→TR→BR→BL). */
    fun signedArea(quad: FloatArray): Double {
        var total = 0.0
        for (index in 0 until 4) {
            val next = (index + 1) % 4
            total += x(quad, index) * y(quad, next) - x(quad, next) * y(quad, index)
        }
        return total / 2.0
    }

    /**
     * Turns any four points into the canonical TL, TR, BR, BL order.
     *
     * Returns a zero-length array when the input is degenerate (three or fewer distinct points),
     * so callers cannot accidentally rectify onto a line.
     */
    fun order(points: FloatArray): FloatArray {
        if (points.size < 8) return FloatArray(0)
        val corners = ArrayList<FloatArray>(4)
        for (index in 0 until 4) corners.add(floatArrayOf(points[index * 2], points[index * 2 + 1]))
        val distinct = ArrayList<FloatArray>(4)
        for (corner in corners) {
            val clash = distinct.firstOrNull {
                abs(it[0] - corner[0]) < 1e-4f && abs(it[1] - corner[1]) < 1e-4f
            }
            if (clash == null) distinct.add(corner)
        }
        if (distinct.size < 4) return FloatArray(0)
        val cx = distinct.sumOf { it[0].toDouble() } / distinct.size
        val cy = distinct.sumOf { it[1].toDouble() } / distinct.size
        val byAngle = distinct.sortedBy { atan2((it[1] - cy).toDouble(), (it[0] - cx).toDouble()) }
        val cycle = ArrayList(byAngle)
        // Cyclic order must run clockwise on screen (positive shoelace in y-down coordinates),
        // otherwise the rectified page comes out mirrored.
        val quad = FloatArray(8)
        for (index in 0 until 4) {
            quad[index * 2] = cycle[index][0]
            quad[index * 2 + 1] = cycle[index][1]
        }
        if (signedArea(quad) < 0.0) {
            val reversed = ArrayList<FloatArray>(4)
            for (index in 3 downTo 0) reversed.add(cycle[index])
            // Reversing a cycle [a,b,c,d] gives [a,d,c,b]: rotate so the leading corner stays put.
            val lead = reversed.removeAt(reversed.size - 1)
            reversed.add(0, lead)
            for (index in 0 until 4) {
                quad[index * 2] = reversed[index][0]
                quad[index * 2 + 1] = reversed[index][1]
            }
        }
        // Canonical start: topmost, then leftmost. Ties broken by x so a level top edge is stable.
        var start = 0
        for (index in 1 until 4) {
            val cyi = quad[index * 2 + 1]
            val syi = quad[start * 2 + 1]
            val cxi = quad[index * 2]
            val sxi = quad[start * 2]
            if (cyi < syi - 1e-4f || (abs(cyi - syi) <= 1e-4f && cxi < sxi)) start = index
        }
        val out = FloatArray(8)
        for (index in 0 until 4) {
            val source = (start + index) % 4
            out[index * 2] = quad[source * 2]
            out[index * 2 + 1] = quad[source * 2 + 1]
        }
        return if (signedArea(out) < 0.0) FloatArray(0) else out
    }

    /**
     * Convex within [tolerance] pixels of slack.
     *
     * A page corner that came out concave is always a contour artefact — a finger, a staple, a
     * shadow — and rectifying onto it folds the image inside-out, so it is rejected outright.
     */
    fun isConvex(quad: FloatArray, tolerance: Double = 0.5): Boolean {
        if (quad.size < 8) return false
        var sign = 0.0
        for (index in 0 until 4) {
            val b = (index + 1) % 4
            val c = (index + 2) % 4
            val cross = (x(quad, b) - x(quad, index)) * (y(quad, c) - y(quad, b)) -
                (y(quad, b) - y(quad, index)) * (x(quad, c) - x(quad, b))
            if (abs(cross) <= tolerance) continue
            if (sign == 0.0) sign = cross else if (sign * cross < 0.0) return false
        }
        return true
    }

    /** Interior angle at [corner], in radians. */
    fun angleAt(quad: FloatArray, corner: Int): Double {
        val previous = (corner + 3) % 4
        val next = (corner + 1) % 4
        val a1 = atan2(y(quad, next) - y(quad, corner), x(quad, next) - x(quad, corner))
        val a2 = atan2(y(quad, previous) - y(quad, corner), x(quad, previous) - x(quad, corner))
        var delta = abs(a1 - a2)
        if (delta > Math.PI) delta = 2 * Math.PI - delta
        return delta
    }

    fun anglesUsable(quad: FloatArray): Boolean =
        (0 until 4).all { angleAt(quad, it) in MIN_ANGLE..(Math.PI - MIN_ANGLE / 2) }

    /**
     * Is this quad worth cropping to?
     *
     * Encodes the honest-degradation rules: a minimum side so a speck never becomes a page, an
     * area band so the outline is inside the frame rather than the frame itself (an
     * "auto-detected" sheet filling 100 % of the photo means the detector found the *photo*, and
     * cropping to it is a no-op the user should not be sold as a success), convexity and angle
     * sanity.
     */
    fun isUsable(
        quad: FloatArray,
        frameWidth: Double,
        frameHeight: Double,
        minSide: Double = 24.0,
        minAreaFraction: Double = 0.10,
        maxAreaFraction: Double = 0.985
    ): Boolean {
        if (quad.size < 8) return false
        if (quad.any { it.isNaN() || it.isInfinite() }) return false
        if (!isConvex(quad)) return false
        if (!anglesUsable(quad)) return false
        val sides = doubleArrayOf(
            side(quad, 0, 1), side(quad, 1, 2), side(quad, 2, 3), side(quad, 3, 0)
        )
        if (sides.any { it < minSide }) return false
        val longest = sides.maxOrNull() ?: 0.0
        val shortest = sides.minOrNull() ?: 1.0
        if (longest / max(1e-6, shortest) > 12.0) return false
        val frame = frameWidth * frameHeight
        if (frame <= 0.0) return false
        val fraction = area(quad) / frame
        if (fraction < minAreaFraction || fraction > maxAreaFraction) return false
        // Every corner inside the frame plus a small margin: a hull vertex that escaped the frame
        // came from a broken contour and would warp a transparent wedge into the page.
        val margin = 2.0
        for (index in 0 until 4) {
            if (x(quad, index) < -margin || x(quad, index) > frameWidth + margin) return false
            if (y(quad, index) < -margin || y(quad, index) > frameHeight + margin) return false
        }
        return true
    }

    /**
     * Least-squares line through a point set (total-least-squares via the covariance eigenvector).
     *
     * Returned as `(point, direction)` so [refine] can intersect two of them. Vertical and
     * horizontal edges are handled the same way — an `y = mx + c` fit would fail on the vertical
     * side of a page, which is exactly the side a rotated phone photo has most of.
     */
    class Line(val px: Double, val py: Double, val dx: Double, val dy: Double) {
        fun angle(): Double = atan2(dy, dx)
    }

    /** Fits a line to `(x, y)` pairs; null when fewer than two points or a zero-length direction. */
    fun fitLine(points: FloatArray, from: Int, to: Int): Line? {
        val count = to - from
        if (count < 2) return null
        var sumX = 0.0
        var sumY = 0.0
        for (index in from until to) {
            sumX += points[index * 2]
            sumY += points[index * 2 + 1]
        }
        val meanX = sumX / count
        val meanY = sumY / count
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (index in from until to) {
            val dx = points[index * 2] - meanX
            val dy = points[index * 2 + 1] - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        // Principal eigenvector of the 2x2 covariance — closed form, no iteration.
        val trace = sxx + syy
        val determinant = sxx * syy - sxy * sxy
        val discriminant = max(0.0, trace * trace / 4.0 - determinant)
        val lambda = trace / 2.0 + sqrt(discriminant)
        var dx = sxy
        var dy = lambda - sxx
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) {
            dx = lambda - syy
            dy = sxy
        }
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return null
        return Line(meanX, meanY, dx, dy)
    }

    /** Intersection of two lines; null when parallel. */
    fun intersect(a: Line, b: Line): FloatArray? {
        val denominator = a.dx * b.dy - a.dy * b.dx
        if (abs(denominator) < 1e-9) return null
        val t = ((b.px - a.px) * b.dy - (b.py - a.py) * b.dx) / denominator
        return floatArrayOf((a.px + t * a.dx).toFloat(), (a.py + t * a.dy).toFloat())
    }

    /**
     * Corner refinement by total-least-squares edge fits with outlier rejection.
     *
     * For each of the four sides the hull vertices that lie within [band] of that side are
     * collected, points further than 1.5 px from the fitted line are dropped, the line is re-fit
     * and adjacent lines are intersected to place the corner on the *fitted* edges instead of on a
     * single quantised hull vertex. This is the difference between a corner that lands on the last
     * white pixel of a contour and one that lands where the two page edges actually meet, and it
     * is what makes a 3-4 px perspective error collapse to under 1 px on a synthetic quad.
     */
    fun refine(quad: FloatArray, hull: FloatArray, frameWidth: Double, frameHeight: Double): FloatArray {
        if (quad.size < 8 || hull.size < 8) return quad
        val band = max(8.0, min(frameWidth, frameHeight) * 0.12)
        val snap = max(2.0, min(frameWidth, frameHeight) * 0.02)
        val lines = arrayOfNulls<Line>(4)
        for (edge in 0 until 4) {
            val next = (edge + 1) % 4
            val ax = x(quad, edge)
            val ay = y(quad, edge)
            val bx = x(quad, next)
            val by = y(quad, next)
            val near = ArrayList<Float>(hull.size)
            val vertexCount = hull.size / 2
            for (index in 0 until vertexCount) {
                val distance = Contours.pointLineDistance(
                    hull[index * 2], hull[index * 2 + 1],
                    ax.toFloat(), ay.toFloat(), bx.toFloat(), by.toFloat()
                )
                if (distance <= snap) {
                    near.add(hull[index * 2])
                    near.add(hull[index * 2 + 1])
                }
            }
            if (near.size < 6) {
                lines[edge] = Line(ax, ay, bx - ax, by - ay)
                continue
            }
            val points = Contours.Points.copyOf(near)
            val ordered = orderAlongLine(points, ax, ay, bx, by)
            val initial = fitLine(ordered, 0, ordered.size / 2)
            var fit: Line? = initial
            if (initial != null) {
                val kept = ArrayList<Float>(ordered.size)
                for (index in 0 until ordered.size / 2) {
                    val distance = Contours.pointLineDistance(
                        ordered[index * 2], ordered[index * 2 + 1],
                        initial.px.toFloat(), initial.py.toFloat(),
                        (initial.px + initial.dx).toFloat(), (initial.py + initial.dy).toFloat()
                    )
                    if (distance <= max(1.5, snap / 3.0)) {
                        kept.add(ordered[index * 2])
                        kept.add(ordered[index * 2 + 1])
                    }
                }
                if (kept.size >= 6) {
                    fit = fitLine(Contours.Points.copyOf(kept), 0, kept.size / 2) ?: initial
                }
            }
            lines[edge] = fit ?: Line(ax, ay, bx - ax, by - ay)
        }
        var lowX = Double.MAX_VALUE
        var highX = -Double.MAX_VALUE
        var lowY = Double.MAX_VALUE
        var highY = -Double.MAX_VALUE
        for (index in 0 until 4) {
            lowX = min(lowX, x(quad, index))
            highX = max(highX, x(quad, index))
            lowY = min(lowY, y(quad, index))
            highY = max(highY, y(quad, index))
        }
        val corners = FloatArray(8)
        for (corner in 0 until 4) {
            val left = lines[(corner + 3) % 4]
            val right = lines[corner]
            val point = if (left != null && right != null) intersect(left, right) else null
            if (point != null && point[0].isFinite() && point[1].isFinite()) {
                corners[corner * 2] = clamp(
                    point[0], (lowX - band).toFloat(), (highX + band).toFloat()
                )
                corners[corner * 2 + 1] = clamp(
                    point[1], (lowY - band).toFloat(), (highY + band).toFloat()
                )
            } else {
                corners[corner * 2] = quad[corner * 2]
                corners[corner * 2 + 1] = quad[corner * 2 + 1]
            }
        }
        val ordered = order(corners)
        val frameArea = max(1.0, frameWidth * frameHeight)
        return if (ordered.size == 8 && isConvex(ordered) && area(ordered) / frameArea in 0.05..0.99) {
            ordered
        } else {
            quad
        }
    }

    /** Points sorted along a direction so [fitLine]'s index range is a contiguous arc. */
    private fun orderAlongLine(points: FloatArray, ax: Double, ay: Double, bx: Double, by: Double): FloatArray {
        val count = points.size / 2
        val dx = bx - ax
        val dy = by - ay
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 1e-9) return points
        val projection = DoubleArray(count)
        val order = ArrayList<Int>(count)
        for (index in 0 until count) {
            projection[index] = ((points[index * 2] - ax) * dx + (points[index * 2 + 1] - ay) * dy) / length
            order.add(index)
        }
        order.sortBy { projection[it] }
        val out = FloatArray(points.size)
        for (index in 0 until count) {
            val source = order[index]
            out[index * 2] = points[source * 2]
            out[index * 2 + 1] = points[source * 2 + 1]
        }
        return out
    }

    /**
     * Sharpness of the outline: mean gradient magnitude sampled along the four sides.
     *
     * Reported as the detector's confidence rather than as a hidden bonus — a quad whose edges sit
     * on flat grey scores near zero and the pipeline falls back to full frame and *says so*.
     */
    fun edgeStrength(quad: FloatArray, gradient: LumaPlane, samples: Int = 64): Double {
        var total = 0.0
        var hits = 0
        for (edge in 0 until 4) {
            val next = (edge + 1) % 4
            for (step in 0..samples) {
                val fraction = step.toDouble() / samples
                val fx = x(quad, edge) + (x(quad, next) - x(quad, edge)) * fraction
                val fy = y(quad, edge) + (y(quad, next) - y(quad, edge)) * fraction
                val px = fx.toInt()
                val py = fy.toInt()
                if (px !in 0 until gradient.width || py !in 0 until gradient.height) continue
                var best = 0
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        val value = gradient.sampleClamped(px + ox, py + oy)
                        if (value > best) best = value
                    }
                }
                total += best
                hits += 1
            }
        }
        return if (hits == 0) 0.0 else total / hits / 255.0
    }

    /** How close to axis-aligned the quad is, 0..1 — 1 when it is a rectangle, lower when skewed. */
    fun squareness(quad: FloatArray): Double {
        var worst = 0.0
        for (corner in 0 until 4) {
            worst = max(worst, abs(angleAt(quad, corner) - Math.PI / 2.0))
        }
        return (1.0 - worst / (Math.PI / 2.0)).coerceIn(0.0, 1.0)
    }

    /** Rotates a quad about a point, clockwise on screen; used by the ordering test. */
    fun rotate(quad: FloatArray, degrees: Double, centerX: Double, centerY: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        val cos = cos(radians)
        val sin = sin(radians)
        val out = FloatArray(quad.size)
        for (index in 0 until quad.size / 2) {
            val dx = quad[index * 2] - centerX
            val dy = quad[index * 2 + 1] - centerY
            out[index * 2] = (centerX + dx * cos - dy * sin).toFloat()
            out[index * 2 + 1] = (centerY + dx * sin + dy * cos).toFloat()
        }
        return out
    }
}
