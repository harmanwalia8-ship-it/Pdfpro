package app.margin.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InkModelTest {
    private val pen = PenStyle(PenType.Ballpoint, 0, 1f)
    private var ids = 100L

    /** A horizontal line at y = 10 from x = 0 to 100, one point per unit. */
    private fun line(id: Long = 1, y: Float = 10f) = InkStroke(
        id, pen,
        FloatArray(101 * InkStroke.STRIDE) { i ->
            when (i % InkStroke.STRIDE) {
                0 -> (i / InkStroke.STRIDE).toFloat()
                1 -> y
                2 -> 0.5f
                else -> (i / InkStroke.STRIDE).toFloat() * 8f
            }
        },
    )

    @Test
    fun areaEraserCutsALineInTwo() {
        val out = EraserMath.eraseArea(listOf(line()), 50f, 10f, 5f) { ids++ }
        assertEquals(2, out.size)
        val (a, b) = out
        assertTrue(a.x(a.count - 1) < 45f)
        assertTrue(b.x(0) > 55f)
        assertEquals(0f, b.pts[3], 0f) // a new piece's time starts at 0
    }

    @Test
    fun areaEraserLeavesUntouchedStrokesAlone() {
        val far = line(id = 2, y = 80f)
        val out = EraserMath.eraseArea(listOf(line(), far), 50f, 10f, 5f) { ids++ }
        assertTrue(out.any { it === far })
    }

    @Test
    fun strokeEraserRemovesTheWholeStroke() {
        val keep = line(id = 2, y = 80f)
        val out = EraserMath.eraseWholeStrokes(listOf(line(), keep), 90f, 11f, 2f)
        assertEquals(listOf(keep), out)
    }

    @Test
    fun sweepDoesNotSkipInkBetweenFarApartPenPositions() {
        // A vertical line at x = 50; the eraser jumps from left to right of it in one move.
        val vertical = InkStroke(
            3, pen,
            FloatArray(41 * InkStroke.STRIDE) { i ->
                when (i % InkStroke.STRIDE) {
                    0 -> 50f
                    1 -> (i / InkStroke.STRIDE).toFloat()
                    else -> 0f
                }
            },
        )
        val out = EraserMath.sweep(listOf(vertical), 20f, 20f, 80f, 20f, 3f, area = false) { ids++ }
        assertTrue(out.isEmpty())
    }

    @Test
    fun undoAndRedo() {
        val h = InkHistory()
        val before = mapOf(0 to emptyList<InkStroke>())
        val after = mapOf(0 to listOf(line()))
        h.record(InkEdit(before, after))
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
        assertSame(before, h.popUndo()!!.before)
        assertTrue(h.canRedo)
        assertSame(after, h.popRedo()!!.after)
        h.popUndo()
        h.record(InkEdit(before, after)) // a new change clears redo
        assertFalse(h.canRedo)
    }
}
