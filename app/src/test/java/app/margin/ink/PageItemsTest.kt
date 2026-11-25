package app.margin.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageItemsTest {
    @Test
    fun resizingATextBoxScalesItsLetters() {
        val t = TextItem(1, 10f, 20f, 100f, 30f, "Hi", 12f, 0)
        val big = t.transformed(SelTransform(5f, -5f, 2f)) as TextItem
        assertEquals(15f, big.x)
        assertEquals(15f, big.y)
        assertEquals(200f, big.w)
        assertEquals(60f, big.h)
        assertEquals(24f, big.size)
    }

    @Test
    fun turnedItemsKeepTheirSizeAndFollowTheirSpot() {
        // Page 100 wide, 200 tall. An item centred at (20, 40) goes to (200 - 40, 20) = (160, 20).
        val img = ImageItem(1, 10f, 30f, 20f, 20f, "a.png")
        val t = ItemMath.turned(img, 200f)
        assertEquals(20f, t.w)
        assertEquals(150f, t.x)
        assertEquals(10f, t.y)
    }

    @Test
    fun picturesFitTheirBoxWithoutStretching() {
        val (w, h) = ItemMath.fit(4000f, 3000f, 300f, 300f)
        assertEquals(300f, w, 0.01f)
        assertEquals(225f, h, 0.01f)
    }

    private fun stroke(vararg xy: Float): FloatArray {
        val n = xy.size / 2
        return FloatArray(n * 4).also { p ->
            for (i in 0 until n) {
                p[4 * i] = xy[2 * i]
                p[4 * i + 1] = xy[2 * i + 1]
                p[4 * i + 2] = 0.5f
                p[4 * i + 3] = i * 8f
            }
        }
    }

    @Test
    fun onlyHorizontalSwipesLookForText() {
        assertNotNull(HighlightSnap.probe(stroke(10f, 100f, 60f, 103f, 120f, 99f)))
        assertNull(HighlightSnap.probe(stroke(10f, 100f, 14f, 160f))) // down the margin
        assertNull(HighlightSnap.probe(stroke(10f, 100f, 12f, 100f))) // a dot
    }

    @Test
    fun snappedBarsCoverTheLinesUnderTheStroke() {
        val pts = stroke(50f, 104f, 120f, 106f, 200f, 103f)
        val lines = listOf(
            Bounds(40f, 98f, 120f, 110f), // two pieces of the line under the stroke...
            Bounds(118f, 98.5f, 260f, 110f),
            Bounds(40f, 160f, 260f, 172f), // ...and a line far below it
        )
        val bars = HighlightSnap.bars(pts, 12f, lines)
        assertNotNull(bars)
        assertEquals(1, bars!!.size)
        val (bar, height) = bars[0]
        val ys = (0 until bar.size / 4).map { bar[4 * it + 1] }.toSet()
        assertEquals("a bar is perfectly straight", 1, ys.size)
        assertTrue(ys.first() in 98f..110f)
        assertTrue(bar[0] <= 40f && bar[bar.size - 4] >= 260f)
        assertTrue(height in 11f..14f)
    }

    @Test
    fun noTextMeansTheStrokeStaysAsDrawn() {
        assertNull(HighlightSnap.bars(stroke(50f, 104f, 200f, 103f), 12f, emptyList()))
        assertNull(HighlightSnap.bars(stroke(50f, 104f, 200f, 103f), 12f, listOf(Bounds(40f, 300f, 260f, 312f))))
    }
}
