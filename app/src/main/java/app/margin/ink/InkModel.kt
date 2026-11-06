package app.margin.ink

import kotlin.math.hypot
import kotlin.math.max

/** The pens in the tray. Highlighter is its own tool but shares the stroke model. */
enum class PenType { Ballpoint, Fountain, Pencil, Marker, Highlighter }

/** Look of a stroke. [size] is in PDF points, so ink zooms with the page. */
data class PenStyle(val type: PenType, val color: Int, val size: Float)

/**
 * One finished stroke on a page, in page coordinates (PDF points).
 * [pts] holds x, y, pressure, time-in-ms for each input point.
 */
class InkStroke(val id: Long, val style: PenStyle, val pts: FloatArray) {
    val count: Int get() = pts.size / STRIDE
    fun x(i: Int) = pts[i * STRIDE]
    fun y(i: Int) = pts[i * STRIDE + 1]

    /** The rendered Jetpack Ink stroke, built once and reused for drawing. */
    @Volatile var rendered: Any? = null

    /** Rough reach of the ink around its centre line, used for erasing. */
    val halfWidth: Float get() = style.size / 2f

    companion object {
        const val STRIDE = 4
    }
}

/** Erasing math, kept free of Android so it can be unit-tested. */
object EraserMath {
    /** Stroke eraser: removes every stroke that passes within [r] of (x, y). */
    fun eraseWholeStrokes(strokes: List<InkStroke>, x: Float, y: Float, r: Float): List<InkStroke> =
        strokes.filterNot { s -> hits(s, x, y, r) }

    /**
     * Area eraser: cuts away the parts of strokes within [r] of (x, y). What's left of a
     * stroke becomes one or more new strokes (with new ids from [newId]).
     */
    fun eraseArea(strokes: List<InkStroke>, x: Float, y: Float, r: Float, newId: () -> Long): List<InkStroke> {
        val out = ArrayList<InkStroke>(strokes.size)
        for (s in strokes) {
            if (!hits(s, x, y, r)) {
                out += s
                continue
            }
            val reach = r + s.halfWidth
            val rr = reach * reach
            var start = -1
            for (i in 0..s.count) {
                val inside = i == s.count || dist2(s, i, x, y) <= rr
                if (!inside && start < 0) start = i
                if (inside && start >= 0) {
                    if (i - start >= 2) out += piece(s, start, i, newId())
                    start = -1
                }
            }
        }
        return out
    }

    /** Erases along the segment from (x0, y0) to (x1, y1), so fast pen moves don't skip ink. */
    fun sweep(
        strokes: List<InkStroke>, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, area: Boolean, newId: () -> Long,
    ): List<InkStroke> {
        val steps = max(1, (hypot(x1 - x0, y1 - y0) / max(r * 0.5f, 0.5f)).toInt())
        var list = strokes
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            list = if (area) eraseArea(list, x, y, r, newId) else eraseWholeStrokes(list, x, y, r)
        }
        return list
    }

    private fun hits(s: InkStroke, x: Float, y: Float, r: Float): Boolean {
        val reach = r + s.halfWidth
        val rr = reach * reach
        for (i in 0 until s.count) if (dist2(s, i, x, y) <= rr) return true
        return false
    }

    private fun dist2(s: InkStroke, i: Int, x: Float, y: Float): Float {
        val dx = s.x(i) - x
        val dy = s.y(i) - y
        return dx * dx + dy * dy
    }

    private fun piece(s: InkStroke, from: Int, to: Int, id: Long): InkStroke {
        val pts = s.pts.copyOfRange(from * InkStroke.STRIDE, to * InkStroke.STRIDE)
        val t0 = pts[3]
        for (i in 3 until pts.size step InkStroke.STRIDE) pts[i] -= t0 // times start at 0 again
        return InkStroke(id, s.style, pts)
    }
}

/** One undoable change: the strokes of some pages before and after. */
class InkEdit(val before: Map<Int, List<InkStroke>>, val after: Map<Int, List<InkStroke>>)

/** Undo / redo stack for one document. */
class InkHistory(private val limit: Int = 200) {
    private val undo = ArrayDeque<InkEdit>()
    private val redo = ArrayDeque<InkEdit>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun record(edit: InkEdit) {
        undo.addLast(edit)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun popUndo(): InkEdit? = undo.removeLastOrNull()?.also { redo.addLast(it) }
    fun popRedo(): InkEdit? = redo.removeLastOrNull()?.also { undo.addLast(it) }
}
