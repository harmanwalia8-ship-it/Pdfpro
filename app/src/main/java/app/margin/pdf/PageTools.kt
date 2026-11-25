package app.margin.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import app.margin.data.Doc
import app.margin.data.Library
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
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
    /**
     * Makes a new notebook (one A4 page of [paper], upright or [landscape]) and puts it at the top of the
     * library. Writing on its last page adds the next one, with the same paper.
     */
    suspend fun createNotes(ctx: Context, title: String, paper: PageTemplate, landscape: Boolean = false): Doc {
        val id = Library.newId()
        val (w, h) = if (landscape) PdfDoc.A4_HEIGHT to PdfDoc.A4_WIDTH else PdfDoc.A4_WIDTH to PdfDoc.A4_HEIGHT
        PdfDoc.createBlank(PdfStore.copyFile(ctx, id), paper, w, h)
        val doc = Doc(
            id = id, uri = null, title = title.trim().ifEmpty { "Notes" }, pageCount = 1,
            lastOpened = 0, added = System.currentTimeMillis(), paper = paper.name,
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

    /** "Notes + 2 more": the suggested name for joining PDFs called [titles]. */
    fun mergedTitle(titles: List<String>): String = when (titles.size) {
        0 -> "Merged PDF"
        1 -> titles[0]
        else -> "${titles[0]} + ${titles.size - 1} more"
    }

    /**
     * Joins [docs] (in this order, from Margin's up-to-date copies) into one new PDF called [title],
     * with a contents entry where each one starts, and puts it at the top of the library. Your ink,
     * text and pictures come along. [onProgress] gets how many PDFs are in so far.
     */
    suspend fun merge(ctx: Context, docs: List<Doc>, title: String, onProgress: (Int) -> Unit = {}): Doc {
        val library = Library.get(ctx)
        val sources = docs.map { PdfStore.prepare(ctx, it.id).file }
        val id = Library.newId()
        val out = PdfStore.copyFile(ctx, id)
        val pages = try {
            withContext(Mupdf.dispatcher) {
                writeMerged(sources, docs.map { it.title }, out, library.thumbFile(id), onProgress)
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                out.delete()
                library.thumbFile(id).delete()
            }
            throw t
        }
        val doc = Doc(
            id = id, uri = null, title = title.trim().ifEmpty { mergedTitle(docs.map { it.title }) }, pageCount = pages,
            lastOpened = 0, added = System.currentTimeMillis(),
        )
        library.addMade(listOf(doc))
        return doc
    }

    /** MuPDF thread only. Returns the number of pages written. */
    private fun writeMerged(sources: List<File>, titles: List<String>, out: File, thumb: File, onProgress: (Int) -> Unit): Int {
        val merged = PDFDocument()
        try {
            val starts = ArrayList<Int>()
            sources.forEachIndexed { k, file ->
                val src = Document.openDocument(file.path)
                try {
                    if (src.needsPassword()) throw PasswordProtectedException()
                    val pdf = src as? PDFDocument ?: throw IOException("Not a PDF")
                    starts += merged.countPages()
                    val map = merged.newPDFGraftMap()
                    try {
                        for (i in 0 until pdf.countPages()) copyPage(merged, map, pdf, i)
                    } finally {
                        map.destroy()
                    }
                } finally {
                    src.destroy()
                }
                onProgress(k + 1)
            }
            // Where each PDF starts, as the merged PDF's table of contents.
            runCatching {
                val contents = merged.outlineIterator()
                titles.forEachIndexed { k, t -> contents.insert(t, "#page=${starts[k] + 1}", false) }
                contents.destroy()
            }
            out.parentFile?.mkdirs()
            val part = File(out.parentFile, out.name + ".part")
            try {
                merged.save(part.path, "garbage")
                if (!part.renameTo(out)) throw IOException("Can't save the merged PDF")
            } finally {
                part.delete()
            }
            runCatching { saveThumb(merged, thumb) }
            return merged.countPages()
        } finally {
            merged.destroy()
        }
    }

    /**
     * Copies page [i] of [src] to the end of [dst]: its look and its notes (ink, text, stamps, highlights).
     * Links, form fields and pop-ups are left out: they point at pages and fields that don't come along.
     * [src] is changed in memory only (it's never saved).
     */
    private fun copyPage(dst: PDFDocument, map: PDFGraftMap, src: PDFDocument, i: Int) {
        val page = src.findPage(i)
        val copy = dst.newDictionary()
        copy.put("Type", dst.newName("Page"))
        for (key in PdfDoc.PAGE_KEYS) PdfDoc.inherited(page, key)?.let { copy.put(key, map.graftObject(it)) }
        val annots = page.get("Annots")
        if (!annots.isNull && annots.isArray) {
            val keep = dst.newArray()
            for (k in 0 until annots.size()) {
                val a = annots.get(k)
                if (a.isNull || !a.isDictionary) continue
                val sub = a.get("Subtype")
                val type = if (sub.isName) sub.asName() else ""
                if (type == "Link" || type == "Widget" || type == "Popup") continue
                // These point back into the old PDF (its page, other annotations, form fields).
                for (key in listOf("P", "Popup", "IRT", "Parent")) a.delete(key)
                keep.push(map.graftObject(a))
            }
            if (keep.size() > 0) copy.put("Annots", keep)
        }
        dst.insertPage(dst.countPages(), dst.addObject(copy))
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
