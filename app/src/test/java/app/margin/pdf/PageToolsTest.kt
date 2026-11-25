package app.margin.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageToolsTest {
    private fun ok(plan: PageTools.Plan): List<IntRange> {
        assertTrue("expected a plan, got $plan", plan is PageTools.Plan.Ok)
        return (plan as PageTools.Plan.Ok).ranges
    }

    private fun problem(plan: PageTools.Plan) = assertTrue("expected a problem, got $plan", plan is PageTools.Plan.Problem)

    @Test
    fun keepsTheTypedPages() {
        assertEquals(listOf(0..199), ok(PageTools.keepPlan("1", "200", 600)))
        assertEquals(listOf(6..6), ok(PageTools.keepPlan(" 7 ", "7", 10)))
        assertEquals(listOf(9..9), ok(PageTools.keepPlan("10", "10", 10)))
    }

    @Test
    fun keepRejectsPagesThatDontExist() {
        problem(PageTools.keepPlan("", "5", 10))
        problem(PageTools.keepPlan("0", "5", 10))
        problem(PageTools.keepPlan("3", "11", 10))
        problem(PageTools.keepPlan("11", "12", 10))
        problem(PageTools.keepPlan("6", "5", 10)) // backwards
        problem(PageTools.keepPlan("1", "10", 10)) // every page: nothing to extract
    }

    @Test
    fun splitsIntoEvenPartsWithTheRestLast() {
        assertEquals(listOf(0..49, 50..99, 100..119), ok(PageTools.splitPlan("50", 120)))
        assertEquals(listOf(0..1, 2..3, 4..5), ok(PageTools.splitPlan("2", 6)))
        assertEquals(listOf(0..0, 1..1, 2..2), ok(PageTools.splitPlan("1", 3)))
    }

    @Test
    fun splitCoversEveryPageExactlyOnce() {
        for (count in 1..60) {
            for (every in 1..count) {
                val pages = PageTools.splitRanges(count, every).flatMap { it.toList() }
                assertEquals("count=$count every=$every", (0 until count).toList(), pages)
            }
        }
    }

    @Test
    fun splitRejectsNonsenseAndFloods() {
        problem(PageTools.splitPlan("", 10))
        problem(PageTools.splitPlan("0", 10))
        problem(PageTools.splitPlan("10", 10)) // one part = the whole PDF
        problem(PageTools.splitPlan("1", PageTools.MAX_PARTS + 1))
        ok(PageTools.splitPlan("1", PageTools.MAX_PARTS))
    }

    @Test
    fun namesAndDescriptions() {
        assertEquals("Notes (pages 1–50)", PageTools.titleFor("Notes", 0..49))
        assertEquals("Notes (page 7)", PageTools.titleFor("Notes", 6..6))
        assertEquals("Makes a new PDF with pages 1–200 (200 pages).", PageTools.describe(listOf(0..199)))
        assertEquals("Makes a new PDF with page 3.", PageTools.describe(listOf(2..2)))
        assertEquals("Makes 3 PDFs: pages 1–2, 3–4, 5.", PageTools.describe(listOf(0..1, 2..3, 4..4)))
        assertEquals(
            "Makes 12 PDFs: pages 1–50, 51–100, …, 551–600.",
            PageTools.describe(PageTools.splitRanges(600, 50)),
        )
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Notes_ week 3.pdf", pdfFileName("Notes: week 3"))
        assertEquals("a_b_c.pdf", pdfFileName("a/b\\c"))
        assertEquals("PDF.pdf", pdfFileName("  "))
        assertEquals("Book (pages 1–50).pdf", pdfFileName("Book (pages 1–50)"))
    }
}
