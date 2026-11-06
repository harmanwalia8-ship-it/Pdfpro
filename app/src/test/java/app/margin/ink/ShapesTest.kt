package app.margin.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class ShapesTest {
    /** A small, repeatable wobble like a hand-drawn line. */
    private fun wobble(i: Int) = (sin(i * 1.7) * 1.2).toFloat()

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int): FloatArray =
        FloatArray(n * 2) { k ->
            val i = k / 2
            val t = i.toFloat() / (n - 1)
            if (k % 2 == 0) x0 + (x1 - x0) * t else y0 + (y1 - y0) * t + wobble(i)
        }

    @Test
    fun wobblyLineBecomesStraight() {
        val out = ShapeSnap.snap(line(10f, 50f, 210f, 80f, 120))
        assertNotNull(out)
        out!!
        val n = out.size / 2
        val (ax, ay, bx, by) = listOf(out[0], out[1], out[2 * n - 2], out[2 * n - 1])
        for (i in 0 until n) {
            // distance of every point from the line through the ends
            val d = abs((by - ay) * out[2 * i] - (bx - ax) * out[2 * i + 1] + bx * ay - by * ax) / hypot(by - ay, bx - ax)
            assertTrue("point $i is ${d}pt off the line", d < 0.01f)
        }
    }

    @Test
    fun roughRectangleBecomesExact() {
        // Drawn clockwise starting in the middle of the top side.
        val pts = ArrayList<Float>()
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0 until 30) {
                val t = i / 30f
                pts += x0 + (x1 - x0) * t + wobble(pts.size) * 0.5f
                pts += y0 + (y1 - y0) * t + wobble(pts.size + 7) * 0.5f
            }
        }
        seg(100f, 20f, 200f, 20f); seg(200f, 20f, 200f, 120f); seg(200f, 120f, 20f, 120f)
        seg(20f, 120f, 20f, 20f); seg(20f, 20f, 98f, 21f)
        val out = ShapeSnap.snap(pts.toFloatArray())
        assertNotNull(out)
        out!!
        for (i in 0 until out.size / 2) {
            val x = out[2 * i]
            val y = out[2 * i + 1]
            val onEdge = min(min(abs(x - 20f), abs(x - 200f)), min(abs(y - 20f), abs(y - 120f)))
            assertTrue("point ($x, $y) is not on the rectangle", onEdge < 2.5f)
        }
        assertEquals(5, ShapeSnap.douglasPeucker(out, 1f).size / 2) // four corners, back to the start
    }

    @Test
    fun roughCircleBecomesRound() {
        val n = 90
        val xy = FloatArray(n * 2) { k ->
            val a = 2 * PI * (k / 2) / (n - 1)
            val r = 50 + wobble(k / 2) * 1.5f
            if (k % 2 == 0) (150 + r * cos(a)).toFloat() else (150 + r * sin(a)).toFloat()
        }
        val out = ShapeSnap.snap(xy)
        assertNotNull(out)
        out!!
        val radii = (0 until out.size / 2).map { hypot(out[2 * it] - 150f, out[2 * it + 1] - 150f) }
        assertTrue("not round: ${radii.min()}..${radii.max()}", radii.max() - radii.min() < 2f)
    }

    @Test
    fun triangleKeepsThreeCorners() {
        val pts = ArrayList<Float>()
        val v = listOf(50f to 20f, 140f to 160f, 10f to 150f, 50f to 21f)
        for (s in 0 until 3) {
            val (x0, y0) = v[s]
            val (x1, y1) = v[s + 1]
            for (i in 0 until 40) {
                pts += x0 + (x1 - x0) * i / 40f
                pts += y0 + (y1 - y0) * i / 40f + wobble(pts.size) * 0.6f
            }
        }
        pts += 51f; pts += 22f
        val out = ShapeSnap.snap(pts.toFloatArray())
        assertNotNull(out)
        assertEquals(4, ShapeSnap.douglasPeucker(out!!, 1f).size / 2) // v0, v1, v2, back to v0
    }

    @Test
    fun handwritingStaysAsIs() {
        // "mmmm": many bumps, nothing like a line or shape
        val n = 200
        val xy = FloatArray(n * 2) { k ->
            val i = k / 2
            if (k % 2 == 0) i * 1.5f else (abs(sin(i * 0.25)) * 30).toFloat()
        }
        assertNull(ShapeSnap.snap(xy))
    }

    @Test
    fun tinyMarksStayAsIs() {
        assertNull(ShapeSnap.snap(line(0f, 0f, 4f, 3f, 20)))
    }

    @Test
    fun lassoSelectsStrokesMostlyInside() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertTrue(SelectionMath.inside(50f, 50f, square))
        assertFalse(SelectionMath.inside(150f, 50f, square))
        val pen = PenStyle(PenType.Ballpoint, 0, 2f)
        fun strokeFrom(x0: Float, x1: Float) = InkStroke(1, pen, FloatArray(11 * 4) { k ->
            when (k % 4) { 0 -> x0 + (x1 - x0) * (k / 4) / 10f; 1 -> 50f; else -> 0f }
        })
        assertEquals(1f, SelectionMath.fractionInside(strokeFrom(10f, 90f), square), 0.001f)
        assertTrue(SelectionMath.fractionInside(strokeFrom(60f, 160f), square) < 0.5f)
    }

    @Test
    fun moveAndResizeAroundTheCorner() {
        val pen = PenStyle(PenType.Ballpoint, 0, 2f)
        val s = InkStroke(7, pen, floatArrayOf(10f, 10f, 0.5f, 0f, 30f, 20f, 0.5f, 8f))
        val t = SelectionMath.transform(s, 10f, 10f, SelTransform(dx = 5f, dy = -5f, scale = 2f))
        assertEquals(7L, t.id)
        assertEquals(15f, t.x(0), 0.001f); assertEquals(5f, t.y(0), 0.001f)
        assertEquals(55f, t.x(1), 0.001f); assertEquals(25f, t.y(1), 0.001f)
        assertEquals(4f, t.style.size, 0.001f)
        assertEquals(0.5f, t.pts[2], 0f) // pressure untouched
    }
}
