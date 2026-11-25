package app.margin.ink

import kotlin.math.max
import kotlin.math.min

/**
 * Something placed on a page besides ink: a typed text box or an image. Its box ([x], [y], [w], [h])
 * is in page points from the page's top-left corner, like ink. No Android types (unit-tested).
 */
sealed interface PageItem {
    val id: Long
    val x: Float
    val y: Float
    val w: Float
    val h: Float
    val bounds: Bounds get() = Bounds(x, y, x + w, y + h)

    /** Moved by ([dx], [dy]) after scaling by [scale] around its top-left corner (a selection drag). */
    fun transformed(t: SelTransform): PageItem
    fun withId(id: Long): PageItem
    fun movedTo(x: Float, y: Float): PageItem
}

/** A typed text box: [size] is the font size in points; [h] follows the text. */
data class TextItem(
    override val id: Long,
    override val x: Float,
    override val y: Float,
    override val w: Float,
    override val h: Float,
    val text: String,
    val size: Float,
    val color: Int,
) : PageItem {
    override fun transformed(t: SelTransform) =
        copy(x = x + t.dx, y = y + t.dy, w = w * t.scale, h = h * t.scale, size = (size * t.scale).coerceIn(4f, 200f))
    override fun withId(id: Long) = copy(id = id)
    override fun movedTo(x: Float, y: Float) = copy(x = x, y = y)
}

/** An image you put on the page; [file] is its name in the PDF's images folder. */
data class ImageItem(
    override val id: Long,
    override val x: Float,
    override val y: Float,
    override val w: Float,
    override val h: Float,
    val file: String,
) : PageItem {
    override fun transformed(t: SelTransform) = copy(x = x + t.dx, y = y + t.dy, w = w * t.scale, h = h * t.scale)
    override fun withId(id: Long) = copy(id = id)
    override fun movedTo(x: Float, y: Float) = copy(x = x, y = y)
}

object ItemMath {
    /** Where an item goes when its page is turned 90° clockwise (page [pageHeight] tall): same size, centre turned. */
    fun turned(item: PageItem, pageHeight: Float): PageItem {
        val cx = item.x + item.w / 2
        val cy = item.y + item.h / 2
        val ncx = pageHeight - cy
        val ncy = cx
        return item.movedTo(ncx - item.w / 2, ncy - item.h / 2)
    }

    /** A box [w] × [h] fitting inside [maxW] × [maxH] with the same shape. */
    fun fit(w: Float, h: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (w <= 0f || h <= 0f) return maxW to maxH
        val k = min(maxW / w, maxH / h)
        return w * k to h * k
    }
}

/** Turns a highlighter stroke drawn over printed text into clean bars over the lines it covers. */
object HighlightSnap {
    /**
     * The horizontal line to look for text along: (left x, middle y, right x) of the stroke [pts]
     * (x, y, pressure, time per point), or null when the stroke isn't a roughly horizontal swipe.
     */
    fun probe(pts: FloatArray): Triple<Float, Float, Float>? {
        val n = pts.size / InkStroke.STRIDE
        if (n < 2) return null
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = pts[i * InkStroke.STRIDE]
            val y = pts[i * InkStroke.STRIDE + 1]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w < 8f || w < h * 1.5f) return null
        return Triple(minX, (minY + maxY) / 2, maxX)
    }

    /**
     * Bars (x, y, pressure, time per point, and the bar's height) over the text [lines] the stroke
     * [pts] covers, or null when it covers none (then it stays as drawn). [size] is the highlighter's size.
     */
    fun bars(pts: FloatArray, size: Float, lines: List<Bounds>): List<Pair<FloatArray, Float>>? {
        val n = pts.size / InkStroke.STRIDE
        if (n < 2 || lines.isEmpty()) return null
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val y = pts[i * InkStroke.STRIDE + 1]
            minY = min(minY, y); maxY = max(maxY, y)
        }
        val reach = size / 2
        // Only lines the stroke actually passes over, merged when they're pieces of the same line.
        val hit = lines.filter { it.width > 0.5f && it.height > 0.5f && it.bottom >= minY - reach && it.top <= maxY + reach }
            .sortedBy { it.top }
        if (hit.isEmpty()) return null
        val merged = ArrayList<Bounds>()
        for (b in hit) {
            val last = merged.lastOrNull()
            val sameLine = last != null && b.top < last.bottom - last.height * 0.4f && b.bottom > last.top + last.height * 0.4f
            if (last != null && sameLine) {
                merged[merged.size - 1] = Bounds(min(last.left, b.left), min(last.top, b.top), max(last.right, b.right), max(last.bottom, b.bottom))
            } else {
                merged += b
            }
        }
        return merged.map { b ->
            val y = (b.top + b.bottom) / 2
            val x0 = b.left - 1f
            val x1 = b.right + 1f
            val steps = max(2, ((x1 - x0) / 6f).toInt() + 1)
            val out = FloatArray(steps * InkStroke.STRIDE)
            for (k in 0 until steps) {
                out[k * InkStroke.STRIDE] = x0 + (x1 - x0) * k / (steps - 1)
                out[k * InkStroke.STRIDE + 1] = y
                out[k * InkStroke.STRIDE + 2] = 1f
                out[k * InkStroke.STRIDE + 3] = k * 6f
            }
            out to b.height * 1.08f
        }
    }
}
