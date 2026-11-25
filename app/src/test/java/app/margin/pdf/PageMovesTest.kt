package app.margin.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageMovesTest {
    @Test
    fun insertAndDeleteShiftLaterPages() {
        assertEquals(2, PageMoves.afterInsert(2, 3))
        assertEquals(4, PageMoves.afterInsert(3, 3))
        assertNull(PageMoves.afterDelete(3, 3))
        assertEquals(2, PageMoves.afterDelete(3, 1))
        assertEquals(0, PageMoves.afterDelete(0, 1))
    }

    @Test
    fun movingAPageMatchesTheNewOrder() {
        for (count in 1..7) for (from in 0 until count) for (to in 0 until count) {
            val order = PageMoves.moveOrder(count, from, to)
            // order[new] = old, so each old page must land where afterMove says.
            for (newIndex in 0 until count) assertEquals("count=$count $from->$to", newIndex, PageMoves.afterMove(order[newIndex], from, to))
        }
        assertArrayEquals(intArrayOf(1, 2, 0, 3), PageMoves.moveOrder(4, 0, 2))
        assertArrayEquals(intArrayOf(0, 3, 1, 2), PageMoves.moveOrder(4, 3, 1))
    }

    @Test
    fun turningMovesCornersClockwise() {
        // A 100 wide, 200 tall page turned clockwise is 200 wide, 100 tall.
        assertEquals(200f to 0f, PageMoves.turnClockwise(0f, 0f, 200f)) // top-left -> top-right
        assertEquals(200f to 100f, PageMoves.turnClockwise(100f, 0f, 200f)) // top-right -> bottom-right
        assertEquals(0f to 0f, PageMoves.turnClockwise(0f, 200f, 200f)) // bottom-left -> top-left
    }

    @Test
    fun bookmarksFollowTheirPages() {
        assertEquals(listOf(0, 3, 6), PageMoves.remapBookmarks(listOf(0, 2, 5)) { PageMoves.afterInsert(it, 1) })
        assertEquals(listOf(0, 4), PageMoves.remapBookmarks(listOf(0, 2, 5)) { PageMoves.afterDelete(it, 2) })
        assertEquals(listOf(1, 2), PageMoves.remapBookmarks(listOf(0, 2)) { PageMoves.afterMove(it, 0, 1) })
    }

    @Test
    fun mergedNames() {
        assertEquals("Merged PDF", PageTools.mergedTitle(emptyList()))
        assertEquals("Notes", PageTools.mergedTitle(listOf("Notes")))
        assertEquals("Notes + 2 more", PageTools.mergedTitle(listOf("Notes", "A", "B")))
    }
}
