package app.margin.pdf

/**
 * How page numbers (0-based) change when pages are added, deleted, moved or duplicated, and how a
 * point moves when a page is turned. Used for ink, text boxes, images and bookmarks. No Android types.
 */
object PageMoves {
    /** A page was inserted at [at]. */
    fun afterInsert(page: Int, at: Int): Int = if (page >= at) page + 1 else page

    /** Page [at] was deleted; null for the deleted page itself. */
    fun afterDelete(page: Int, at: Int): Int? = when {
        page == at -> null
        page > at -> page - 1
        else -> page
    }

    /** Page [from] was moved to position [to] (its index afterwards); the pages in between shift by one. */
    fun afterMove(page: Int, from: Int, to: Int): Int = when {
        page == from -> to
        from < to && page in (from + 1)..to -> page - 1
        to < from && page in to until from -> page + 1
        else -> page
    }

    /** The new page order (old indices, in their new places) for moving [from] to [to]. */
    fun moveOrder(count: Int, from: Int, to: Int): IntArray {
        val order = (0 until count).toMutableList()
        order.add(to, order.removeAt(from))
        return order.toIntArray()
    }

    /**
     * Where a point (x, y) of a page [w] wide and [h] tall ends up when the page is turned 90° clockwise
     * (the turned page is [h] wide and [w] tall).
     */
    fun turnClockwise(x: Float, y: Float, h: Float): Pair<Float, Float> = (h - y) to x

    /** Bookmarks (sorted, no repeats) after [change] maps each page to its new number, or null if it's gone. */
    fun remapBookmarks(bookmarks: List<Int>, change: (Int) -> Int?): List<Int> =
        bookmarks.mapNotNull(change).distinct().sorted()
}
