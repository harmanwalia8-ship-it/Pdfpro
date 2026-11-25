package app.margin.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import app.margin.data.Doc
import app.margin.data.Library
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes new PDFs: a blank notebook to write in, or some of a PDF's pages (keep a range like
 * "pages 1–200", or split it into parts of N pages). New PDFs live in Margin's storage and go
 * into the library; a PDF they came from stays as it is. Page ranges are 0-based and inclusive.
 */
object PageTools {
    /** Makes a new notebook (one page of [paper]; add more in the reader) and puts it at the top of the library. */
    suspend fun createNotes(ctx: Context, title: String, paper: PageTemplate): Doc {
        val id = Library.newId()
        PdfDoc.createBlank(PdfStore.copyFile(ctx, id), paper)
        val doc = Doc(
            id = id, uri = null, title = title.trim().ifEmpty { "Notes" }, pageCount = 1,
            lastOpened = 0, added = System.currentTimeMillis(),
        )
        Library.get(ctx).addMade(listOf(doc))
        return doc
    }

    /** At most this many PDFs from one split, so a slip of the finger can't flood the library. */
    const val MAX_PARTS = 100

    /** What the page tools will make, or why they can't. */
    sealed interface Plan {
        data class Ok(val ranges: List<IntRange>) : Plan
        data class Problem(val message: String) : Plan
    }

    /** Keep pages [from]..[to], as typed by the user (1-based). */
    fun keepPlan(from: String, to: String, pageCount: Int): Plan {
        val a = from.trim().toIntOrNull()
        val b = to.trim().toIntOrNull()
        return when {
            a == null || b == null -> Plan.Problem("Enter the first and last page")
            a < 1 || b > pageCount || a > pageCount || b < 1 -> Plan.Problem("Pages go from 1 to $pageCount")
            a > b -> Plan.Problem("The first page can't come after the last page")
            a == 1 && b == pageCount -> Plan.Problem("That's every page. Choose fewer pages to keep.")
            else -> Plan.Ok(listOf(a - 1..b - 1))
        }
    }

    /** Split into parts of [every] pages, as typed by the user. */
    fun splitPlan(every: String, pageCount: Int): Plan {
        val n = every.trim().toIntOrNull()
        return when {
            n == null || n < 1 -> Plan.Problem("Enter how many pages go in each PDF")
            n >= pageCount -> Plan.Problem("Use fewer than $pageCount pages per PDF")
            else -> {
                val ranges = splitRanges(pageCount, n)
                if (ranges.size > MAX_PARTS) {
                    Plan.Problem("That makes ${ranges.size} PDFs; the most is $MAX_PARTS. Put more pages in each.")
                } else {
                    Plan.Ok(ranges)
                }
            }
        }
    }

    /** [pageCount] pages in parts of [every]; the last part has whatever is left. */
    fun splitRanges(pageCount: Int, every: Int): List<IntRange> =
        (0 until pageCount step every).map { first -> first..min(first + every, pageCount) - 1 }

    /** "Notes (pages 1–50)", or "Notes (page 7)" for a single page. */
    fun titleFor(title: String, range: IntRange): String =
        if (range.first == range.last) "$title (page ${range.first + 1})"
        else "$title (pages ${range.first + 1}–${range.last + 1})"

    /** "1–50", "51–100", "101": for showing a plan. */
    fun label(range: IntRange): String =
        if (range.first == range.last) "${range.first + 1}" else "${range.first + 1}–${range.last + 1}"

    /** "Makes a new PDF with pages 1–200 (200 pages)." / "Makes 12 PDFs: pages 1–50, 51–100, …, 551–600." */
    fun describe(ranges: List<IntRange>): String {
        if (ranges.size == 1) {
            val r = ranges.single()
            val n = r.last - r.first + 1
            return if (n == 1) "Makes a new PDF with page ${label(r)}." else "Makes a new PDF with pages ${label(r)} ($n pages)."
        }
        val shown = if (ranges.size <= 4) ranges.map(::label) else ranges.take(2).map(::label) + "…" + label(ranges.last())
        return "Makes ${ranges.size} PDFs: pages ${shown.joinToString(", ")}."
    }

    /**
     * Makes a new PDF for each of [ranges] of [source] (Margin's up-to-date copy of [from]) and puts
     * them at the top of the library. [onProgress] gets how many are done. Returns the new PDFs.
     * If anything fails, nothing is added and no files are left behind.
     */
    suspend fun extract(
        ctx: Context,
        from: Doc,
        source: File,
        ranges: List<IntRange>,
        onProgress: (done: Int) -> Unit = {},
    ): List<Doc> {
        val library = Library.get(ctx)
        val made = ArrayList<Doc>()
        val now = System.currentTimeMillis()
        try {
            ranges.forEachIndexed { k, range ->
                val id = Library.newId()
                made += Doc(
                    id = id, uri = null, title = titleFor(from.title, range), pageCount = range.last - range.first + 1,
                    lastOpened = 0, added = now - k, // keeps the parts in order in the library
                )
                // One part per MuPDF call, so the reader can still render pages in between.
                withContext(Mupdf.dispatcher) {
                    writePart(source, range, PdfStore.copyFile(ctx, id), library.thumbFile(id))
                }
                onProgress(k + 1)
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                made.forEach {
                    PdfStore.copyFile(ctx, it.id).delete()
                    library.thumbFile(it.id).delete()
                }
            }
            throw t
        }
        library.addMade(made)
        return made
    }

    /** Writes pages [range] of [source] to [out] (plus a cover thumbnail). MuPDF thread only. */
    private fun writePart(source: File, range: IntRange, out: File, thumb: File) {
        val doc = Document.openDocument(source.path)
        try {
            if (doc.needsPassword()) throw PasswordProtectedException()
            val pdf = doc as? PDFDocument ?: throw IOException("Not a PDF")
            if (range.first < 0 || range.last >= pdf.countPages()) throw IOException("The PDF has fewer pages than expected")
            // Keeps these pages (with their annotations, so your ink comes along) and drops the rest,
            // including bookmarks and links that pointed to them.
            pdf.rearrangePages(IntArray(range.last - range.first + 1) { range.first + it })
            out.parentFile?.mkdirs()
            val part = File(out.parentFile, out.name + ".part")
            try {
                pdf.save(part.path, "garbage")
                if (!part.renameTo(out)) throw IOException("Can't save the new PDF")
            } finally {
                part.delete()
            }
            try {
                saveThumb(pdf, thumb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No cover yet; the reader makes one when the PDF is first opened.
            }
        } finally {
            doc.destroy()
        }
    }

    /** Same cover the reader makes on first open: page 1, 360 px wide, without Margin's ink. */
    private fun saveThumb(doc: Document, file: File) {
        val page = doc.loadPage(0)
        try {
            val b = page.bounds
            val w = max(1f, b.x1 - b.x0)
            val h = max(1f, b.y1 - b.y0)
            val scale = min(360f / w, 4f * 360f / h)
            val bitmap = Bitmap.createBitmap(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val device = AndroidDrawDevice(bitmap, (b.x0 * scale).roundToInt(), (b.y0 * scale).roundToInt())
            try {
                PdfDoc.drawWithoutMarginInk(page, device, Matrix(scale, scale))
                device.close()
            } finally {
                device.destroy()
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            page.destroy()
        }
    }
}
