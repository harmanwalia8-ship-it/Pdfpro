package app.margin.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.margin.pdf.PageSize
import kotlin.math.max
import kotlin.math.min

/**
 * Where we are in the document: zoom and scroll position, plus the page layout math.
 * Pages are stacked vertically; all pages share one scale (pixels per PDF point), so
 * mixed page sizes keep their real proportions.
 */
@Stable
class ViewerState(
    val pageSizes: List<PageSize>,
    private val density: Float,
    private val initialPage: Int,
) {
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set

    /** Bumped whenever scrolling or zooming comes to rest. Sharp re-renders wait for it. */
    var settleTick by mutableIntStateOf(0)
        private set
    var settledZoom by mutableFloatStateOf(1f)
        private set

    val minZoom = 0.5f
    val maxZoom = 6f

    private val gap = 16f * density
    private val padTop = 24f * density
    private val padBottom = 128f * density // keeps the last page clear of floating tools
    private val sideMargin = 24f * density
    private val maxBaseWidth = 900f * density // default page width on a wide landscape screen
    private val maxPageWidthPt = pageSizes.maxOf { it.width }

    /** prefix[i] = total height (pt) of pages before page i. */
    private val prefix = FloatArray(pageSizes.size + 1).also { p ->
        pageSizes.forEachIndexed { i, s -> p[i + 1] = p[i] + s.height }
    }

    val pageCount: Int get() = pageSizes.size

    /** Pixels per PDF point at zoom [z]. Zoom 1 = page fits the screen width (up to a comfortable max). */
    fun scaleAt(z: Float): Float {
        val base = min(viewport.width - 2 * sideMargin, maxBaseWidth).coerceAtLeast(120f)
        return base * z / maxPageWidthPt
    }

    val scale: Float get() = scaleAt(zoom)

    fun pageTop(i: Int, s: Float = scale): Float = padTop + prefix[i] * s + i * gap
    fun pageWidth(i: Int, s: Float = scale): Float = pageSizes[i].width * s
    fun pageHeight(i: Int, s: Float = scale): Float = pageSizes[i].height * s
    fun contentWidth(s: Float = scale): Float = max(viewport.width.toFloat(), maxPageWidthPt * s + 2 * sideMargin)
    fun contentHeight(s: Float = scale): Float =
        padTop + prefix[pageCount] * s + (pageCount - 1) * gap + padBottom
    fun pageLeft(i: Int, s: Float = scale): Float = (contentWidth(s) - pageWidth(i, s)) / 2

    /** The page at content height [y] (the page above, when [y] falls in a gap). */
    fun pageAt(y: Float, s: Float = scale): Int {
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageTop(mid, s) <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Page in the middle of the screen. */
    val currentPage: Int get() = pageAt(scrollY + viewport.height / 2f)

    /** Pages on screen, plus half a screen either way so scrolling never shows blank pages. */
    fun visibleRange(): IntRange {
        if (viewport.height == 0 || pageCount == 0) return IntRange.EMPTY
        val extra = viewport.height * 0.5f
        val first = pageAt(scrollY - extra)
        val last = pageAt(scrollY + viewport.height + extra)
        return first..last
    }

    fun onViewport(size: IntSize) {
        if (size == viewport || size.width == 0) return
        val first = viewport.width == 0
        // Keep the same spot of the same page on screen across rotation / split screen.
        val anchorPage = if (first) initialPage.coerceIn(0, pageCount - 1) else pageAt(scrollY)
        val anchorLocal = if (first) 0f else (scrollY - pageTop(anchorPage)) / scale
        viewport = size
        scrollY = when {
            first && anchorPage == 0 -> 0f
            first -> clampY(pageTop(anchorPage) - 12f * density)
            else -> clampY(pageTop(anchorPage) + anchorLocal * scale)
        }
        scrollX = clampX(scrollX)
        settle()
    }

    /** Scrolls by a pixel delta; returns false when already at the edge. */
    fun scrollBy(dx: Float, dy: Float): Boolean {
        val nx = clampX(scrollX + dx)
        val ny = clampY(scrollY + dy)
        val moved = nx != scrollX || ny != scrollY
        scrollX = nx
        scrollY = ny
        return moved
    }

    /** Zooms by [factor], keeping the point under [focus] (screen px) where it is. */
    fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return
        val oldS = scale
        val newS = scaleAt(newZoom)
        val fromCenterX = scrollX + focus.x - contentWidth(oldS) / 2
        val y = scrollY + focus.y
        val page = pageAt(y, oldS)
        val local = (y - pageTop(page, oldS)) / oldS
        zoom = newZoom
        scrollX = clampX(contentWidth(newS) / 2 + fromCenterX * (newS / oldS) - focus.x)
        scrollY = clampY(pageTop(page, newS) + local * newS - focus.y)
    }

    fun settle() {
        settledZoom = zoom
        settleTick++
    }

    suspend fun animateZoomTo(target: Float, focus: Offset) {
        animate(zoom, target.coerceIn(minZoom, maxZoom), animationSpec = tween(280, easing = EaseOutSmooth)) { v, _ ->
            zoomBy(v / zoom, focus)
        }
        settle()
    }

    suspend fun animateToPage(i: Int) {
        val target = clampY(pageTop(i.coerceIn(0, pageCount - 1)) - 12f * density)
        animate(scrollY, target, animationSpec = tween(380, easing = EaseOutSmooth)) { v, _ -> scrollY = v }
        settle()
    }

    private fun clampX(v: Float) = v.coerceIn(0f, max(0f, contentWidth() - viewport.width))
    private fun clampY(v: Float) = v.coerceIn(0f, max(0f, contentHeight() - viewport.height))
}
