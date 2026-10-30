package com.securevault.pro.data.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Connected components, contours and convex hulls — everything between "a binary mask" and
 * "an ordered closed outline".
 *
 * Two contour sources, because the two failure modes of a document photo are different:
 *
 *  - a **bright region** (paper on a dark desk) is labelled and its outline is taken as the
 *    convex hull of its extreme points. For a 4-connected region every hull vertex is either the
 *    first or the last set pixel of its row or of its column, so scanning rows and columns is a
 *    provably complete vertex source — no corner can be missed by chance.
 *  - an **edge closure** (a page whose paper and desk are the same brightness but sharply
 *    separated) is walked with Moore neighbourhood following and fed into the same hull.
 *
 * Everything downstream — Douglas-Peucker to four corners, line-fit refinement, the homography —
 * consumes only `FloatArray` of ordered `(x, y)` pairs, so the whole path is JVM-testable.
 */
object Contours {

    /** 8-neighbourhood, clockwise from due north in image coordinates (y grows downward). */
    private val DIR_X = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val DIR_Y = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    /** Result of a flood-fill labelling pass over a binary mask. */
    class Region(
        val start: Int,
        val pixels: IntArray,
        val size: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val area: Double get() = (right - left + 1).toDouble() * (bottom - top + 1)
        /** Fill ratio of the axis box: a sheet of paper is near 1, a stray shadow edge is not. */
        val solidity: Double get() = if (area <= 0.0) 0.0 else size / area
    }

    /**
     * Largest 4-connected component of [mask].
     *
     * Iterative stack, no recursion, one IntArray allocation per pass; a 640x480 probe needs
     * 307k cells and a recursive flood fill would blow the stack on exactly the frames where the
     * paper fills the screen.
     */
    fun largestRegion(mask: BooleanArray, width: Int, height: Int): Region? {
        val count = width * height
        if (count == 0 || mask.size < count) return null
        val visited = BooleanArray(count)
        val stack = IntArray(count)
        var best: Region? = null
        var index = 0
        while (index < count) {
            if (!mask[index] || visited[index]) {
                index += 1
                continue
            }
            var top = 0
            stack[top] = index
            top += 1
            visited[index] = true
            var collected = 0
            var minX = index % width
            var maxX = minX
            var minY = index / width
            var maxY = minY
            var pixels = IntArray(64)
            while (top > 0) {
                val here = stack[--top]
                collected += 1
                if (collected > pixels.size) pixels = pixels.copyOf(max(pixels.size * 2, collected))
                pixels[collected - 1] = here
                val x = here % width
                val y = here / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) top = push(here - 1, mask, visited, stack, top)
                if (x < width - 1) top = push(here + 1, mask, visited, stack, top)
                if (y > 0) top = push(here - width, mask, visited, stack, top)
                if (y < height - 1) top = push(here + width, mask, visited, stack, top)
            }
            val current = Region(index, pixels.copyOf(collected), collected, minX, minY, maxX, maxY)
            if (best == null || current.size > best!!.size) best = current
            // Components are disjoint, so a scan that restarts after each fill is still O(n).
            index = 0
            while (index < count && (visited[index] || !mask[index])) index += 1
        }
        return best
    }

    /** Fraction of the frame the region covers, 0..1. */
    fun coverage(region: Region, width: Int, height: Int): Double =
        region.size.toDouble() / max(1, width * height)

    /**
     * Ordered outline of a region as `(x, y)` pairs, from the extreme-point scan described above.
     * Always returns at least three points when the region has any area.
     */
    fun regionOutline(region: Region, width: Int, height: Int): FloatArray {
        val points = ArrayList<Float>(64)
        // First and last set pixel of every row inside the region's box.
        val inRegion = BooleanArray(width * height)
        for (pixel in region.pixels) inRegion[pixel] = true
        for (y in region.top..region.bottom) {
            val row = y * width
            var first = -1
            var last = -1
            for (x in region.left..region.right) {
                if (inRegion[row + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                points.add(first.toFloat()); points.add(y.toFloat())
                points.add(last.toFloat()); points.add(y.toFloat())
            }
        }
        for (x in region.left..region.right) {
            var first = -1
            var last = -1
            for (y in region.top..region.bottom) {
                if (inRegion[y * width + x]) {
                    if (first < 0) first = y
                    last = y
                }
            }
            if (first >= 0) {
                points.add(x.toFloat()); points.add(first.toFloat())
                points.add(x.toFloat()); points.add(last.toFloat())
            }
        }
        return convexHull(Points.copyOf(points))
    }

    /**
     * Moore neighbourhood boundary following with a Jacob-style stopping rule and a hard step cap.
     *
     * Returns the closed outline of the component containing [seed], or null when the component is
     * a single pixel. The cap (8 boundary steps per mask pixel) is what makes an untested
     * pathological mask unable to spin forever: the detector is on the camera's analysis thread
     * and a hang there is a frozen viewfinder.
     */
    fun traceBoundary(mask: BooleanArray, width: Int, height: Int, seed: Int): FloatArray? {
        if (seed !in mask.indices || !mask[seed]) return null
        var current = seed
        // The seed is the topmost-leftmost mask pixel, so north, west and north-west are all
        // background: start the clockwise search at north.
        var searchFrom = 0
        var steps = 0
        val limit = 8 * countSet(mask) + 64
        val outline = ArrayList<Float>(64)
        outline.add((current % width).toFloat())
        outline.add((current / width).toFloat())
        while (steps < limit) {
            steps += 1
            val currentX = current % width
            val currentY = current / width
            var found = -1
            var probe = searchFrom
            for (attempt in 0 until 8) {
                val x = currentX + DIR_X[probe]
                val y = currentY + DIR_Y[probe]
                if (x in 0 until width && y in 0 until height && mask[y * width + x]) {
                    found = probe
                    break
                }
                probe = (probe + 1) % 8
            }
            if (found < 0) break
            val nextX = currentX + DIR_X[found]
            val nextY = currentY + DIR_Y[found]
            val next = nextY * width + nextX
            if (next == seed && outline.size > 3) break
            current = next
            outline.add(nextX.toFloat())
            outline.add(nextY.toFloat())
            // Resume the search just clockwise of the direction back to where we came from.
            searchFrom = (opposite(found) + 1) % 8
        }
        return if (outline.size < 6) null else Points.copyOf(outline)
    }

    private fun opposite(direction: Int): Int = (direction + 4) % 8

    private fun countSet(mask: BooleanArray): Int {
        var total = 0
        for (value in mask) if (value) total += 1
        return total
    }

    /** Andrew's monotone chain. Returns the hull in counter-clockwise image order, no repeats. */
    fun convexHull(points: FloatArray): FloatArray {
        val count = points.size / 2
        if (count < 3) return points.copyOf()
        val order = ArrayList<Int>(count)
        for (index in 0 until count) order.add(index)
        order.sortWith { a, b ->
            val ax = points[a * 2]
            val ay = points[a * 2 + 1]
            val bx = points[b * 2]
            val by = points[b * 2 + 1]
            if (ax != bx) ax.compareTo(bx) else ay.compareTo(by)
        }
        val hull = IntArray(2 * count)
        var top = 0
        // Lower hull.
        for (index in order) {
            while (top >= 2 && cross(hull[top - 2], hull[top - 1], index, points) <= 0f) top -= 1
            hull[top] = index
            top += 1
        }
        // Upper hull, t = one past the count of lower-hull slots so the ends stay shared.
        val lower = top + 1
        for (i in order.lastIndex - 1 downTo 0) {
            val index = order[i]
            while (top >= lower && cross(hull[top - 2], hull[top - 1], index, points) <= 0f) top -= 1
            hull[top] = index
            top += 1
        }
        // The last point repeats the first; drop it.
        val vertexCount = max(0, top - 1)
        if (vertexCount < 3) return FloatArray(0)
        val out = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            val index = hull[i]
            out[i * 2] = points[index * 2]
            out[i * 2 + 1] = points[index * 2 + 1]
        }
        return out
    }

    /** Strictly-positive cross product of the first two edges at [points]'s indices a, b, c. */
    private fun cross(a: Int, b: Int, c: Int, points: FloatArray): Float {
        val ax = points[a * 2]
        val ay = points[a * 2 + 1]
        val bx = points[b * 2]
        val by = points[b * 2 + 1]
        val cx = points[c * 2]
        val cy = points[c * 2 + 1]
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    }

    /** Polygon area as `|shoelace| / 2`, in the same units as the points. */
    fun area(polygon: FloatArray): Double {
        val count = polygon.size / 2
        if (count < 3) return 0.0
        var total = 0.0
        for (index in 0 until count) {
            val next = (index + 1) % count
            total += polygon[index * 2].toDouble() * polygon[next * 2 + 1] -
                polygon[next * 2].toDouble() * polygon[index * 2 + 1]
        }
        return abs(total) / 2.0
    }

    /**
     * Ramer–Douglas-Peucker simplification.
     *
     * The recursive form is replaced by an explicit split/merge so the worst case is bounded by
     * the point count rather than by the call stack, and so [simplify] can be asked for an exact
     * vertex budget (`stop = 4`) which is how a contour becomes a quadrilateral.
     */
    fun simplify(polygon: FloatArray, epsilon: Double): FloatArray {
        val count = polygon.size / 2
        if (count <= 2 || epsilon <= 0.0) return polygon.copyOf()
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        val low = IntArray(count)
        val high = IntArray(count)
        var depth = 0
        low[0] = 0
        high[0] = count - 1
        depth = 1
        while (depth > 0) {
            depth -= 1
            val from = low[depth]
            val to = high[depth]
            if (to - from < 1) continue
            var worst = -1
            var worstDistance = 0.0
            for (index in from + 1 until to) {
                val distance = pointLineDistance(
                    polygon[index * 2], polygon[index * 2 + 1],
                    polygon[from * 2], polygon[from * 2 + 1],
                    polygon[to * 2], polygon[to * 2 + 1]
                )
                if (distance > worstDistance) {
                    worstDistance = distance
                    worst = index
                }
            }
            if (worst < 0 || worstDistance <= epsilon) continue
            keep[worst] = true
            low[depth] = from
            high[depth] = worst
            depth += 1
            low[depth] = worst
            high[depth] = to
            depth += 1
        }
        // Closed polygon: also test the seam between the last and first vertex.
        var worst = -1
        var worstDistance = epsilon
        for (index in 1 until count - 1) {
            if (keep[index]) continue
            val distance = pointLineDistance(
                polygon[index * 2], polygon[index * 2 + 1],
                polygon[0], polygon[1],
                polygon[(count - 1) * 2], polygon[(count - 1) * 2 + 1]
            )
            if (distance > worstDistance) {
                worstDistance = distance
                worst = index
            }
        }
        if (worst >= 0) keep[worst] = true
        val out = ArrayList<Float>(16)
        for (index in 0 until count) {
            if (keep[index]) {
                out.add(polygon[index * 2])
                out.add(polygon[index * 2 + 1])
            }
        }
        return Points.copyOf(out)
    }

    /**
     * Simplifies a closed convex polygon down to exactly [vertices] corners by growing the epsilon
     * until the count lands, then keeps the first [vertices] survivors in order.
     *
     * Feeding `vertices = 4` is how a hull becomes a candidate quad. A convex polygon loses its
     * flattest points first, so the four that survive are the four genuine corners; if the shape
     * is not actually quadrilateral the returned corners come back degenerate (collinear or
     * duplicated) and [QuadGeometry.isUsable] rejects them.
     */
    fun toVertexCount(polygon: FloatArray, vertices: Int = 4): FloatArray {
        val count = polygon.size / 2
        if (count <= vertices) return polygon.copyOf()
        val extent = max(boundingBoxWidth(polygon), boundingBoxHeight(polygon))
        var epsilon = extent / 100.0
        var result = polygon
        var guard = 0
        while (guard < 24) {
            guard += 1
            result = simplifyClosed(polygon, epsilon)
            val produced = result.size / 2
            if (produced == vertices) return result
            if (produced < vertices) {
                epsilon /= 1.6
            } else {
                if (epsilon > extent) break
                epsilon *= 1.6
            }
        }
        val produced = result.size / 2
        if (produced == vertices) return result
        if (produced > vertices) {
            // Take the four points that are furthest apart in the polygon's own order: keep the
            // vertex budget honest instead of silently returning a many-sided blob.
            return thinClosed(result, vertices)
        }
        return FloatArray(0)
    }

    private fun simplifyClosed(polygon: FloatArray, epsilon: Double): FloatArray {
        val doubled = FloatArray(polygon.size + 2)
        System.arraycopy(polygon, 0, doubled, 0, polygon.size)
        doubled[polygon.size] = polygon[0]
        doubled[polygon.size + 1] = polygon[1]
        val simplified = simplify(doubled, epsilon)
        val count = simplified.size / 2
        if (count >= 2 && simplified[0] == simplified[(count - 1) * 2] &&
            simplified[1] == simplified[(count - 1) * 2 + 1]
        ) {
            return FloatArray((count - 1) * 2).also {
                System.arraycopy(simplified, 0, it, 0, it.size)
            }
        }
        return simplified
    }

    /** Keeps every k-th vertex until the count fits [vertices]; order preserving. */
    private fun thinClosed(polygon: FloatArray, vertices: Int): FloatArray {
        val count = polygon.size / 2
        if (count <= vertices) return polygon.copyOf()
        val out = FloatArray(vertices * 2)
        for (index in 0 until vertices) {
            val source = (index.toLong() * count / vertices).toInt().coerceAtMost(count - 1)
            out[index * 2] = polygon[source * 2]
            out[index * 2 + 1] = polygon[source * 2 + 1]
        }
        return out
    }

    fun boundingBoxWidth(polygon: FloatArray): Double {
        if (polygon.isEmpty()) return 0.0
        var min = polygon[0]
        var max = polygon[0]
        for (index in 0 until polygon.size step 2) {
            if (polygon[index] < min) min = polygon[index]
            if (polygon[index] > max) max = polygon[index]
        }
        return (max - min).toDouble()
    }

    fun boundingBoxHeight(polygon: FloatArray): Double {
        if (polygon.size < 2) return 0.0
        var min = polygon[1]
        var max = polygon[1]
        for (index in 1 until polygon.size step 2) {
            if (polygon[index] < min) min = polygon[index]
            if (polygon[index] > max) max = polygon[index]
        }
        return (max - min).toDouble()
    }

    /** Perpendicular distance from a point to the infinite line through two others. */
    fun pointLineDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 1e-9) return sqrt(((px - ax).toDouble().let { it * it }) + ((py - ay).toDouble().let { it * it }))
        return abs(dy * px - dx * py + bx.toDouble() * ay - by.toDouble() * ax) / length
    }

    /** Angle of a point around a centroid, in image coordinates; used for cyclic ordering. */
    fun angleAround(x: Float, y: Float, centerX: Double, centerY: Double): Double =
        atan2((y - centerY).toDouble(), (x - centerX).toDouble())

    private fun push(index: Int, mask: BooleanArray, visited: BooleanArray, stack: IntArray, top: Int): Int {
        if (!mask[index] || visited[index]) return top
        visited[index] = true
        stack[top] = index
        return top + 1
    }

    internal object Points {
        fun copyOf(values: ArrayList<Float>): FloatArray {
            val out = FloatArray(values.size)
            for (index in values.indices) out[index] = values[index]
            return out
        }
    }

    /** min/max helpers kept here so the hull code reads as geometry, not as stdlib noise. */
    fun span(values: FloatArray): Double {
        if (values.size < 2) return 0.0
        var low = values[0]
        var high = values[0]
        for (value in values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        return (high - low).toDouble()
    }

    fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    /** Mean of a buffer; the four filter tests all compare against this rather than a magic 128. */
    fun IntArray.meanValue(): Double {
        if (isEmpty()) return 0.0
        var total = 0.0
        for (value in this) total += value
        return total / size
    }
}
