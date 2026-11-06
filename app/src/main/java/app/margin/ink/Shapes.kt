package app.margin.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A box in page points. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Move / resize applied to a selection: scale around its top-left corner, then move. */
data class SelTransform(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f) {
    val isIdentity get() = dx == 0f && dy == 0f && scale == 1f
}

/** Lasso selection math (no Android types, so it can be unit-tested). */
object SelectionMath {
    /** Ray casting: is (x, y) inside the polygon [poly] (x, y pairs)? */
    fun inside(x: Float, y: Float, poly: FloatArray): Boolean {
        var c = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]
            val yi = poly[2 * i + 1]
            val xj = poly[2 * j]
            val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Share of a stroke's points inside the lasso (sampled, so long strokes stay fast). */
    fun fractionInside(s: InkStroke, poly: FloatArray): Float {
        if (s.count == 0 || poly.size < 6) return 0f
        val step = max(1, s.count / 64)
        var hit = 0
        var total = 0
        var i = 0
        while (i < s.count) {
            if (inside(s.x(i), s.y(i), poly)) hit++
            total++
            i += step
        }
        return hit.toFloat() / total
    }

    /** Box around the strokes, including their ink width. */
    fun bounds(strokes: List<InkStroke>): Bounds {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (s in strokes) {
            val w = s.halfWidth
            for (i in 0 until s.count) {
                l = min(l, s.x(i) - w)
                t = min(t, s.y(i) - w)
                r = max(r, s.x(i) + w)
                b = max(b, s.y(i) + w)
            }
        }
        return Bounds(l, t, r, b)
    }

    /** The stroke moved / resized by [t] around the anchor (ax, ay). Keeps its id. */
    fun transform(s: InkStroke, ax: Float, ay: Float, t: SelTransform): InkStroke {
        val pts = s.pts.copyOf()
        for (i in 0 until s.count) {
            val o = i * InkStroke.STRIDE
            pts[o] = ax + (pts[o] - ax) * t.scale + t.dx
            pts[o + 1] = ay + (pts[o + 1] - ay) * t.scale + t.dy
        }
        return InkStroke(s.id, s.style.copy(size = s.style.size * t.scale), pts)
    }
}

/**
 * Hold-to-snap: when you hold the pen still at the end of a stroke, a stroke that looks like
 * a line or a simple shape becomes a clean one. Handwriting doesn't fit any shape, so it stays.
 */
object ShapeSnap {
    /**
     * [xy] = the stroke as x, y pairs (page points). Returns the clean shape as x, y pairs
     * (with points every couple of points, so the eraser can cut it), or null when it
     * doesn't look like a line, an angle, a triangle, a rectangle or an ellipse.
     */
    fun snap(xy: FloatArray): FloatArray? {
        val n = xy.size / 2
        if (n < 2) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in 0 until n) {
            minX = min(minX, xy[2 * i]); maxX = max(maxX, xy[2 * i])
            minY = min(minY, xy[2 * i + 1]); maxY = max(maxY, xy[2 * i + 1])
            if (i > 0) length += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 8f) return null // too small to be a shape (about 3 mm)
        val gap = hypot(xy[0] - xy[2 * n - 2], xy[1] - xy[2 * n - 1])
        val closed = gap < 0.2f * diag && length > 2f * diag
        val simple = douglasPeucker(xy, max(1.5f, 0.06f * diag))

        if (!closed) {
            // A straight line, or straight segments with one or two corners (an angle, a "Z").
            return if (simple.size / 2 in 2..4) densify(simple, closed = false) else null
        }

        var poly = simple.copyOf(simple.size - 2) // the last point is back at the start
        poly = removeStraightCorners(poly)
        when (poly.size / 2) {
            3 -> return densify(poly, closed = true)
            4 -> return densify(squareUp(poly), closed = true)
        }
        return ellipse(xy, minX, minY, maxX, maxY)
    }

    private fun ellipse(xy: FloatArray, minX: Float, minY: Float, maxX: Float, maxY: Float): FloatArray? {
        val n = xy.size / 2
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        var rx = (maxX - minX) / 2f
        var ry = (maxY - minY) / 2f
        if (rx < 2f || ry < 2f) return null
        var err = 0f
        for (i in 0 until n) err += abs(hypot((xy[2 * i] - cx) / rx, (xy[2 * i + 1] - cy) / ry) - 1f)
        if (err / n > 0.15f) return null // not round enough
        if (abs(rx - ry) < 0.12f * max(rx, ry)) {
            val r = (rx + ry) / 2f
            rx = r
            ry = r
        }
        // Start where the pen started, so the stroke keeps its direction.
        val a0 = atan2((xy[1] - cy) / ry, (xy[0] - cx) / rx)
        val count = max(48, (PI * (rx + ry) / 2f).toInt())
        val out = FloatArray((count + 1) * 2)
        for (k in 0..count) {
            val a = a0 + 2 * PI * k / count
            out[2 * k] = cx + rx * cos(a).toFloat()
            out[2 * k + 1] = cy + ry * sin(a).toFloat()
        }
        return out
    }

    /** Douglas–Peucker: keeps only the points that matter for the shape. */
    fun douglasPeucker(xy: FloatArray, eps: Float): FloatArray {
        val n = xy.size / 2
        if (n <= 2) return xy.copyOf()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0f
            var idx = -1
            for (i in a + 1 until b) {
                val d = distToSegment(xy, i, a, b)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > eps) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) {
            out += xy[2 * i]
            out += xy[2 * i + 1]
        }
        return out.toFloatArray()
    }

    private fun distToSegment(xy: FloatArray, i: Int, a: Int, b: Int): Float {
        val px = xy[2 * i]
        val py = xy[2 * i + 1]
        val ax = xy[2 * a]
        val ay = xy[2 * a + 1]
        val bx = xy[2 * b]
        val by = xy[2 * b + 1]
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Drops corners of a closed polygon that are nearly straight (e.g. where the pen started mid-side). */
    private fun removeStraightCorners(poly: FloatArray): FloatArray {
        var p = poly
        var changed = true
        while (changed && p.size / 2 > 3) {
            changed = false
            val n = p.size / 2
            for (i in 0 until n) {
                val a = (i + n - 1) % n
                val c = (i + 1) % n
                val ux = p[2 * i] - p[2 * a]
                val uy = p[2 * i + 1] - p[2 * a + 1]
                val vx = p[2 * c] - p[2 * i]
                val vy = p[2 * c + 1] - p[2 * i + 1]
                val lu = hypot(ux, uy)
                val lv = hypot(vx, vy)
                val turn = if (lu < 1e-3f || lv < 1e-3f) 0.0 else acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f).toDouble())
                if (turn < Math.toRadians(25.0)) {
                    p = p.copyOfRange(0, 2 * i) + p.copyOfRange(2 * i + 2, p.size)
                    changed = true
                    break
                }
            }
        }
        return p
    }

    /** A four-cornered shape whose sides are nearly level and upright becomes an exact rectangle. */
    private fun squareUp(quad: FloatArray): FloatArray {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ang = Math.toDegrees(atan2(abs(quad[2 * j + 1] - quad[2 * i + 1]), abs(quad[2 * j] - quad[2 * i])).toDouble())
            if (ang > 12.0 && ang < 78.0) return quad // tilted on purpose: keep the shape
        }
        val l = minOf(quad[0], quad[2], quad[4], quad[6])
        val r = maxOf(quad[0], quad[2], quad[4], quad[6])
        val t = minOf(quad[1], quad[3], quad[5], quad[7])
        val b = maxOf(quad[1], quad[3], quad[5], quad[7])
        // Start at the corner nearest the pen's first point.
        val corners = floatArrayOf(l, t, r, t, r, b, l, b)
        val first = (0 until 4).minBy { hypot(corners[2 * it] - quad[0], corners[2 * it + 1] - quad[1]) }
        return FloatArray(8) { k -> corners[(2 * first + k) % 8] }
    }

    /** Points every ~2 page points along the segments (and back to the start when [closed]). */
    fun densify(poly: FloatArray, closed: Boolean, step: Float = 2f): FloatArray {
        val n = poly.size / 2
        val out = ArrayList<Float>()
        val segs = if (closed) n else n - 1
        for (s in 0 until segs) {
            val a = s
            val b = (s + 1) % n
            val ax = poly[2 * a]
            val ay = poly[2 * a + 1]
            val dx = poly[2 * b] - ax
            val dy = poly[2 * b + 1] - ay
            val k = max(1, (hypot(dx, dy) / step).toInt())
            for (j in 0 until k) {
                out += ax + dx * j / k
                out += ay + dy * j / k
            }
        }
        val end = if (closed) 0 else n - 1
        out += poly[2 * end]
        out += poly[2 * end + 1]
        return out.toFloatArray()
    }
}
