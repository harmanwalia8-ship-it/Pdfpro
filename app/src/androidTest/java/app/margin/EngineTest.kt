package app.margin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.margin.data.Doc
import app.margin.data.Library
import app.margin.ink.HighlightSnap
import app.margin.pdf.ImageForPdf
import app.margin.pdf.InkForPdf
import app.margin.pdf.PageTools
import app.margin.pdf.PdfDoc
import app.margin.pdf.PdfStore
import app.margin.pdf.SavedImage
import app.margin.pdf.SavedText
import app.margin.pdf.TextForPdf
import app.margin.pdf.Mupdf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Margin's PDF engine on a real (emulated) device, without the screens: search, the text lines the
 * highlighter snaps to, moving / turning / duplicating pages, text boxes and pictures written into
 * the PDF and read back, and merging. Leaves the library as it found it.
 */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "engine-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun searchAndTextLines() = runBlocking {
        val doc = PdfDoc.open(samplePdf(File(dir, "search.pdf"), 4, "S"))
        try {
            val hits = doc.search(2, "S chapter 3")
            assertEquals(1, hits.size)
            assertTrue("the title is near the top", hits[0].first().top in 40f..110f)
            assertTrue(doc.search(0, "S chapter 3").isEmpty())
            assertEquals("ignores case", 1, doc.search(2, "s CHAPTER 3").size)
            assertEquals(38, doc.search(1, "quick brown fox").size)
            assertTrue("this PDF has no table of contents", doc.outline().isEmpty())

            // The first body line sits on y = 132 (its letters roughly 123..135).
            val lines = doc.textLines(0, 56f, 129f, 400f)
            assertTrue("found the text line under a highlighter stroke", lines.isNotEmpty())
            val stroke = floatArrayOf(56f, 129f, 0.5f, 0f, 200f, 131f, 0.5f, 8f, 400f, 128f, 0.5f, 16f)
            val bars = HighlightSnap.bars(stroke, 12f, lines)
            assertNotNull(bars)
            for ((pts, h) in bars!!) {
                assertTrue("bar sits on the line (y=${pts[1]})", pts[1] in 118f..138f)
                assertTrue("bar is as tall as the letters ($h)", h in 6f..20f)
            }
        } finally {
            doc.close()
        }
    }

    @Test
    fun pagesMoveTurnDuplicateAndStaySoAfterSaving() = runBlocking {
        val doc = PdfDoc.open(samplePdf(File(dir, "pages.pdf"), 3, "P"))
        assertTrue(doc.movePage(0, 2)) // now: 2, 3, 1
        assertEquals(1, doc.search(2, "P chapter 1").size)
        assertTrue(doc.duplicatePage(0)) // now: 2, 2, 3, 1
        assertEquals(4, doc.pageCount)
        assertEquals(1, doc.search(1, "P chapter 2").size)
        val upright = doc.pageSizes[3]
        assertTrue(doc.turnPage(3))
        assertEquals("turned sideways", upright.height, doc.pageSizes[3].width, 0.5f)
        val out = File(dir, "pages-saved.pdf")
        assertTrue(doc.saveWithInk(emptyMap(), emptyMap(), out))
        doc.close()

        assertEquals("every page is a proper page object", listOf(true, true, true, true), pagesAreReferences(out))
        val again = PdfDoc.open(out)
        try {
            assertEquals(4, again.pageCount)
            assertEquals(1, again.search(0, "P chapter 2").size)
            assertEquals(1, again.search(1, "P chapter 2").size)
            assertEquals(1, again.search(2, "P chapter 3").size)
            assertEquals(1, again.search(3, "P chapter 1").size)
            assertEquals(upright.height, again.pageSizes[3].width, 0.5f)
        } finally {
            again.close()
        }
    }

    @Test
    fun textBoxesAndPicturesGoIntoThePdfAndComeBack() = runBlocking {
        val png = File(dir, "picture.png")
        png.outputStream().use { Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val doc = PdfDoc.open(samplePdf(File(dir, "items.pdf"), 1, "T"))
        val out = File(dir, "items-saved.pdf")
        val ink = mapOf(0 to listOf(InkForPdf(floatArrayOf(60f, 300f, 200f, 320f), Color.BLUE, 2f, 1f, "Margin Ballpoint")))
        val items = mapOf(
            0 to listOf(
                TextForPdf(60f, 420f, 220f, 30f, "Hello from Margin", 14f, Color.BLACK),
                ImageForPdf(300f, 420f, 120f, 60f, png),
            ),
        )
        assertTrue(doc.saveWithInk(ink, items, out))
        doc.close()

        val again = PdfDoc.open(out)
        try {
            assertEquals(1, again.savedInk.size)
            val text = again.savedItems.filterIsInstance<SavedText>().single()
            assertEquals("Hello from Margin", text.text)
            assertEquals(14f, text.size, 0.5f)
            assertEquals(60f, text.x, 1f)
            val img = again.savedItems.filterIsInstance<SavedImage>().single()
            assertEquals(120f, img.w, 1f)
            assertEquals(60f, img.h, 1f)
            val back = File(dir, "picture-back.png")
            assertTrue(again.renderSavedImage(img, back))
            assertTrue(back.length() > 0)
        } finally {
            again.close()
        }
    }

    @Test
    fun mergingKeepsPagesNotesAndAddsContents() = runBlocking {
        val library = Library.get(ctx)
        val docA = library.add(Uri.fromFile(samplePdf(File(dir, "Merge A.pdf"), 2, "A")))
        val docB = library.add(Uri.fromFile(samplePdf(File(dir, "Merge B.pdf"), 3, "B")))
        var merged: Doc? = null
        try {
            // Ink in A's copy, as saving in the reader leaves it.
            val copyA = PdfStore.prepare(ctx, docA.id).file
            PdfStore.prepare(ctx, docB.id)
            val inked = File(dir, "a-inked.pdf")
            PdfDoc.open(copyA).let { d ->
                d.saveWithInk(mapOf(1 to listOf(InkForPdf(floatArrayOf(60f, 300f, 200f, 320f), Color.BLUE, 2f, 1f, "Margin Ballpoint"))), emptyMap(), inked)
                d.close()
            }
            inked.copyTo(copyA, overwrite = true)
            library.update(docA.id) { it.copy(copyAhead = true, edited = true) }

            merged = PageTools.merge(ctx, listOf(library.get(docA.id)!!, library.get(docB.id)!!), "Merged test")
            assertEquals(5, merged.pageCount)
            assertEquals("every page is a proper page object", List(5) { true }, pagesAreReferences(PdfStore.copyFile(ctx, merged.id)))
            val m = PdfDoc.open(PdfStore.copyFile(ctx, merged.id))
            try {
                assertEquals(5, m.pageCount)
                assertEquals(1, m.search(0, "A chapter 1").size)
                assertEquals(1, m.search(2, "B chapter 1").size)
                assertEquals(1, m.search(4, "B chapter 3").size)
                assertTrue("A's ink came along", m.savedInk.any { it.page == 1 })
                val contents = m.outline()
                val seen = "contents: " + contents.joinToString { "${it.title} -> page ${it.page}" } +
                    " (links: " + withContext(Mupdf.dispatcher) { rawContents(PdfStore.copyFile(ctx, merged.id)) } + ")"
                assertEquals(seen, listOf("Merge A", "Merge B"), contents.map { it.title })
                assertEquals(seen, listOf(0, 2), contents.map { it.page })
            } finally {
                m.close()
            }
        } finally {
            PdfStore.delete(ctx, docA)
            PdfStore.delete(ctx, docB)
            merged?.let { PdfStore.delete(ctx, it) }
        }
    }

    /** For each page of [file]: is it in the page tree as a reference to a page object (as it must be)? */
    private suspend fun pagesAreReferences(file: File): List<Boolean> = withContext(Mupdf.dispatcher) {
        val d = com.artifex.mupdf.fitz.Document.openDocument(file.path) as com.artifex.mupdf.fitz.PDFDocument
        try {
            List(d.countPages()) { d.findPage(it).isIndirect }
        } finally {
            d.destroy()
        }
    }

    /** The merged PDF's table of contents as MuPDF reads it (title=link), to explain a failure. MuPDF thread. */
    private fun rawContents(file: File): String = runCatching {
        val d = com.artifex.mupdf.fitz.Document.openDocument(file.path)
        try {
            d.loadOutline().orEmpty().joinToString { "${it.title}=${it.uri}" }
        } finally {
            d.destroy()
        }
    }.getOrElse { "unreadable: $it" }

    /** An A4 PDF made with Android's own PDF writer: "<tag> chapter N" and 38 lines of text per page. */
    private fun samplePdf(file: File, pages: Int, tag: String): File {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 26f; isAntiAlias = true; color = Color.BLACK }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true; color = Color.DKGRAY }
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val c = page.canvas
            c.drawText("$tag chapter ${i + 1}", 56f, 92f, title)
            var y = 132f
            repeat(38) { line ->
                c.drawText("Line ${line + 1}. The quick brown fox jumps over the lazy dog, page ${i + 1}.", 56f, y, body)
                y += 16f
            }
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
