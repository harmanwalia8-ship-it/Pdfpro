package app.margin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.margin.pdf.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateTest {
    private val a4 = PageSize(595f, 842f)
    private val density = 2f

    private fun state(pages: Int = 20, initialPage: Int = 0) =
        ViewerState(List(pages) { a4 }, density, initialPage).apply { onViewport(IntSize(2560, 1500)) }

    @Test
    fun opensAtTheRememberedPage() {
        val s = state(initialPage = 7)
        // Page 7's top edge sits just below the top of the screen, and it's the current page.
        assertEquals(7, s.pageAt(s.scrollY + 12f * density + 1f))
        assertEquals(7, s.currentPage)
        assertEquals(0f, state(initialPage = 0).scrollY, 0.001f)
    }

    @Test
    fun pageAtFindsEveryPageTop() {
        val s = state()
        for (i in 0 until s.pageCount) {
            assertEquals(i, s.pageAt(s.pageTop(i) + 1f))
        }
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val s = state()
        s.scrollBy(0f, 5000f)
        val focus = Offset(1300f, 700f)
        val page = s.pageAt(s.scrollY + focus.y)
        val localBefore = (s.scrollY + focus.y - s.pageTop(page)) / s.scale
        val xBefore = (s.scrollX + focus.x - s.pageLeft(page)) / s.scale
        s.zoomBy(2.5f, focus)
        val localAfter = (s.scrollY + focus.y - s.pageTop(page)) / s.scale
        val xAfter = (s.scrollX + focus.x - s.pageLeft(page)) / s.scale
        assertEquals(localBefore, localAfter, 0.5f)
        assertEquals(xBefore, xAfter, 0.5f)
    }

    @Test
    fun zoomStaysWithinLimits() {
        val s = state()
        s.zoomBy(100f, Offset(10f, 10f))
        assertEquals(s.maxZoom, s.zoom, 0.0001f)
        s.zoomBy(0.0001f, Offset(10f, 10f))
        assertEquals(s.minZoom, s.zoom, 0.0001f)
    }

    @Test
    fun scrollingStopsAtTheEnds() {
        val s = state(pages = 3)
        assertTrue(!s.scrollBy(0f, -100f))
        s.scrollBy(0f, 1e9f)
        val bottom = s.scrollY
        assertTrue(!s.scrollBy(0f, 50f))
        assertEquals(bottom, s.contentHeight() - s.viewport.height, 0.5f)
    }

    @Test
    fun rotationKeepsTheSamePage() {
        val s = state()
        s.scrollBy(0f, s.pageTop(12) - s.scrollY + 40f)
        val before = s.pageAt(s.scrollY)
        s.onViewport(IntSize(1500, 2560))
        assertEquals(before, s.pageAt(s.scrollY))
    }
}
