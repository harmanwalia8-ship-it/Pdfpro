package app.margin.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.margin.ink.Bounds
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** MuPDF objects are not thread-safe, so every MuPDF call runs on this one thread. */
object Mupdf {
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "mupdf") }.asCoroutineDispatcher()
}

/** Page size in PDF points (1/72 inch). */
data class PageSize(val width: Float, val height: Float)

class PasswordProtectedException : IOException("This PDF is password-protected.")

/** Ink that Margin wrote into the PDF earlier: points (x, y pairs, page points) and look. */
class SavedInk(val page: Int, val points: FloatArray, val color: Int, val width: Float, val opacity: Float, val subject: String?)

/** A text box or image Margin wrote into the PDF earlier: its box in page points (from the page's corner). */
sealed class SavedItem(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

class SavedText(page: Int, x: Float, y: Float, w: Float, h: Float, val text: String, val size: Float, val color: Int) :
    SavedItem(page, x, y, w, h)

/** An image; [nth] counts Margin's images on its page, to find it again when it's imported. */
class SavedImage(page: Int, x: Float, y: Float, w: Float, h: Float, val nth: Int) : SavedItem(page, x, y, w, h)

/** A text box or image as it goes into the PDF (page points, from the page's corner). */
sealed class ItemForPdf(val x: Float, val y: Float, val w: Float, val h: Float)

class TextForPdf(x: Float, y: Float, w: Float, h: Float, val text: String, val size: Float, val color: Int) : ItemForPdf(x, y, w, h)

class ImageForPdf(x: Float, y: Float, w: Float, h: Float, val file: File) : ItemForPdf(x, y, w, h)

/** One entry of the PDF's own table of contents; [page] is -1 when it doesn't point into this PDF. */
class OutlineEntry(val title: String, val page: Int, val depth: Int)

/** What a newly added page looks like. */
enum class PageTemplate(val label: String) {
    Blank("Blank"),
    Lined("Lined"),
    Grid("Grid"),
    Dotted("Dotted"),
    /** Cue column on the left, notes on the right, summary at the bottom. */
    Cornell("Cornell"),
    /** Music staves. */
    Music("Music"),
    /** A schedule column and a to-do list with checkboxes. */
    Planner("Planner"),
}

class PdfDoc private constructor(
    private val doc: Document,
    sizes: List<PageSize>,
    origins: List<Pair<Float, Float>>,
    /** Margin's ink annotations found in the file when it was opened. */
    val savedInk: List<SavedInk>,
    /** Margin's text boxes and images found in the file when it was opened. */
    val savedItems: List<SavedItem>,
) : Closeable {

    /** Page sizes (PDF points), in page order. Changes when pages are added, deleted or turned. */
    @Volatile var pageSizes: List<PageSize> = sizes
        private set
    private var origins: List<Pair<Float, Float>> = origins

    /** Pages that have (or had) Margin's annotations (ink, text, images); each save rewrites these. */
    private val markedPages = HashSet<Int>().apply {
        savedInk.forEach { add(it.page) }
        savedItems.forEach { add(it.page) }
    }

    val pageCount: Int get() = pageSizes.size

    /** Recently used pages stay loaded; older ones are freed. */
    private val pages = object : LinkedHashMap<Int, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>): Boolean {
            val drop = size > 8
            if (drop) eldest.value.destroy()
            return drop
        }
    }

    @Volatile private var closed = false

    private fun page(index: Int): Page = pages.getOrPut(index) { doc.loadPage(index) }

    /**
     * Renders page [index] at [scale] pixels per PDF point.
     * [region] is a part of the whole scaled page, in pixels; null renders the whole page.
     */
    suspend fun render(index: Int, scale: Float, region: Rect? = null): Bitmap? = withContext(Mupdf.dispatcher) {
        ensureActive()
        if (closed || index !in pageSizes.indices) return@withContext null
        val size = pageSizes[index]
        val full = Rect(0, 0, max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
        val r = region?.let { Rect(it).apply { if (!intersect(full)) return@withContext null } } ?: full
        val bitmap = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val (x0, y0) = origins[index]
        val device = AndroidDrawDevice(bitmap, (x0 * scale).roundToInt() + r.left, (y0 * scale).roundToInt() + r.top)
        try {
            drawWithoutMarginInk(page(index), device, Matrix(scale, scale))
            device.close()
        } finally {
            device.destroy()
        }
        bitmap
    }

    /** Small preview of a page, [widthPx] wide. */
    suspend fun thumbnail(index: Int, widthPx: Int): Bitmap? =
        pageSizes.getOrNull(index)?.let { render(index, minOf(widthPx / it.width, 4f * widthPx / it.height)) }

    // ---- Finding things ----

    /** Every place [needle] appears on page [index] (ignoring case): one or more boxes per match, in page points. */
    suspend fun search(index: Int, needle: String): List<List<Bounds>> = withContext(Mupdf.dispatcher) {
        ensureActive()
        if (closed || index !in pageSizes.indices || needle.isBlank()) return@withContext emptyList()
        val (ox, oy) = origins[index]
        page(index).search(needle, StructuredText.SEARCH_IGNORE_CASE).orEmpty().map { hit ->
            hit.map { q ->
                val r = q.toRect()
                Bounds(r.x0 - ox, r.y0 - oy, r.x1 - ox, r.y1 - oy)
            }
        }
    }

    /** The PDF's own table of contents, flattened (with its depth), or empty if it has none. */
    suspend fun outline(): List<OutlineEntry> = withContext(Mupdf.dispatcher) {
        val out = ArrayList<OutlineEntry>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, depth: Int) {
            items?.forEach { o ->
                val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrDefault(-1)
                out += OutlineEntry(o.title?.trim().orEmpty().ifEmpty { "Untitled" }, page, depth)
                if (out.size < 5_000) walk(o.down, depth + 1)
            }
        }
        if (!closed) runCatching { walk(doc.loadOutline(), 0) }
        out
    }

    /**
     * The printed text lines between ([x0], [y]) and ([x1], [y]) on page [index], as boxes in page
     * points (for snapping a highlighter stroke onto them). Empty where there's no text.
     */
    suspend fun textLines(index: Int, x0: Float, y: Float, x1: Float): List<Bounds> = withContext(Mupdf.dispatcher) {
        if (closed || index !in pageSizes.indices) return@withContext emptyList()
        val (ox, oy) = origins[index]
        val text = page(index).toStructuredText()
        try {
            text.highlight(Point(x0 + ox, y + oy), Point(x1 + ox, y + oy)).orEmpty().map { q ->
                val r = q.toRect()
                Bounds(r.x0 - ox, r.y0 - oy, r.x1 - ox, r.y1 - oy)
            }
        } finally {
            text.destroy()
        }
    }

    // ---- Changing pages ----

    /**
     * Adds a [template] page after page [after], the same size as that page.
     * Returns false if this isn't an editable PDF.
     */
    suspend fun insertPage(after: Int, template: PageTemplate): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        val ref = pageSizes.getOrNull(after) ?: pageSizes.last()
        val pageObj = pdf.addPage(
            com.artifex.mupdf.fitz.Rect(0f, 0f, ref.width, ref.height), 0, pdf.newDictionary(),
            templateContent(template, ref.width, ref.height),
        )
        dropLoadedPages()
        pdf.insertPage(after + 1, pageObj)
        // Annotations belong to their page, so pages after the new one moved down by one.
        remapMarked { PageMoves.afterInsert(it, after + 1) }
        reloadPageInfo()
        true
    }

    /** Deletes page [index] (never the last remaining page). Returns false if it can't. */
    suspend fun deletePage(index: Int): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        if (pageCount <= 1 || index !in pageSizes.indices) return@withContext false
        dropLoadedPages()
        pdf.deletePage(index)
        remapMarked { PageMoves.afterDelete(it, index) }
        reloadPageInfo()
        true
    }

    /** Moves page [from] to position [to]; its annotations go with it. */
    suspend fun movePage(from: Int, to: Int): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        if (from !in pageSizes.indices || to !in pageSizes.indices || from == to) return@withContext false
        dropLoadedPages()
        pdf.rearrangePages(PageMoves.moveOrder(pageCount, from, to))
        remapMarked { PageMoves.afterMove(it, from, to) }
        reloadPageInfo()
        true
    }

    /** Turns page [index] a quarter turn clockwise. */
    suspend fun turnPage(index: Int): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        if (index !in pageSizes.indices) return@withContext false
        val obj = pdf.findPage(index)
        val now = inherited(obj, "Rotate")?.asInteger() ?: 0
        dropLoadedPages()
        obj.put("Rotate", ((now + 90) % 360 + 360) % 360)
        reloadPageInfo()
        true
    }

    /**
     * Puts a copy of page [index] right after it. The copy shares the page's printed content;
     * Margin's own notes on it are copied by the ink layer and written in with the next save.
     */
    suspend fun duplicatePage(index: Int): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        if (index !in pageSizes.indices) return@withContext false
        val src = pdf.findPage(index)
        val copy = pdf.newDictionary()
        copy.put("Type", pdf.newName("Page"))
        for (key in PAGE_KEYS) inherited(src, key)?.let { copy.put(key, it) }
        val ref = pdf.addObject(copy)
        dropLoadedPages()
        pdf.insertPage(index + 1, ref)
        remapMarked { PageMoves.afterInsert(it, index + 1) }
        reloadPageInfo()
        true
    }

    private fun remapMarked(change: (Int) -> Int?) {
        val moved = markedPages.mapNotNull(change)
        markedPages.clear()
        markedPages.addAll(moved)
    }

    private fun dropLoadedPages() {
        pages.values.forEach { it.destroy() }
        pages.clear()
    }

    private fun reloadPageInfo() {
        val count = doc.countPages()
        val sizes = ArrayList<PageSize>(count)
        val orig = ArrayList<Pair<Float, Float>>(count)
        for (i in 0 until count) {
            val b = page(i).bounds
            sizes += PageSize(max(1f, b.x1 - b.x0), max(1f, b.y1 - b.y0))
            orig += b.x0 to b.y0
        }
        origins = orig
        pageSizes = sizes
    }

    override fun close() {
        closed = true
        CoroutineScope(Mupdf.dispatcher).launch {
            pages.values.forEach { it.destroy() }
            pages.clear()
            doc.destroy()
        }
    }

    // ---- Saving ----

    /**
     * Writes [strokes] and [items] (per page, in page points) into the PDF as annotations (ink, text,
     * images), replacing Margin's previous ones, and saves the whole document to [out]. Returns false
     * if this isn't a writable PDF.
     */
    suspend fun saveWithInk(
        strokes: Map<Int, List<InkForPdf>>,
        items: Map<Int, List<ItemForPdf>>,
        out: File,
    ): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        val touched = HashSet(markedPages).apply {
            addAll(strokes.filterValues { it.isNotEmpty() }.keys)
            addAll(items.filterValues { it.isNotEmpty() }.keys)
        }
        pdf.beginOperation("Margin notes")
        try {
            for (i in touched) {
                if (i !in pageSizes.indices) continue
                val page = page(i) as? PDFPage ?: continue
                page.annotations?.filter { isMargin(it) }?.forEach { page.deleteAnnotation(it) }
                val (x0, y0) = origins[i]
                for (s in strokes[i].orEmpty()) {
                    if (s.points.size < 4) continue
                    val a = page.createAnnotation(PDFAnnotation.TYPE_INK)
                    a.author = MARGIN
                    a.subject = s.subject
                    a.color = rgb(s.color)
                    a.opacity = s.opacity
                    a.borderWidth = s.width
                    a.inkList = arrayOf(Array(s.points.size / 2) { k -> Point(s.points[2 * k] + x0, s.points[2 * k + 1] + y0) })
                    a.update()
                }
                for (item in items[i].orEmpty()) {
                    val rect = com.artifex.mupdf.fitz.Rect(item.x + x0, item.y + y0, item.x + item.w + x0, item.y + item.h + y0)
                    when (item) {
                        is TextForPdf -> {
                            if (item.text.isBlank()) continue
                            val a = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
                            a.author = MARGIN
                            a.contents = item.text
                            a.setDefaultAppearance("Helv", item.size, rgb(item.color))
                            a.rect = rect
                            a.borderWidth = 0f
                            a.update()
                        }
                        is ImageForPdf -> {
                            if (!item.file.exists()) continue
                            val a = page.createAnnotation(PDFAnnotation.TYPE_STAMP)
                            a.author = MARGIN
                            a.rect = rect
                            a.setStampImage(Image(item.file.path))
                            a.update()
                        }
                    }
                }
                page.update()
                markedPages += i
            }
        } finally {
            pdf.endOperation()
        }
        out.parentFile?.mkdirs()
        pdf.save(out.path, "garbage")
        true
    }

    /** Draws the image [item] (found in the PDF when it was opened) into a PNG file [out]. */
    suspend fun renderSavedImage(item: SavedImage, out: File): Boolean = withContext(Mupdf.dispatcher) {
        if (closed || item.page !in pageSizes.indices) return@withContext false
        val page = page(item.page) as? PDFPage ?: return@withContext false
        val annot = page.annotations
            ?.filter { isMargin(it) && it.type == PDFAnnotation.TYPE_STAMP }
            ?.getOrNull(item.nth) ?: return@withContext false
        runCatching {
            val r = annot.rect
            val scale = min(3f, 2048f / max(1f, max(r.x1 - r.x0, r.y1 - r.y0)))
            val bitmap = Bitmap.createBitmap(
                max(1, ((r.x1 - r.x0) * scale).roundToInt()), max(1, ((r.y1 - r.y0) * scale).roundToInt()), Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.TRANSPARENT)
            val device = AndroidDrawDevice(bitmap, (r.x0 * scale).roundToInt(), (r.y0 * scale).roundToInt())
            try {
                annot.run(device, Matrix(scale, scale), null)
                device.close()
            } finally {
                device.destroy()
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
    }

    companion object {
        const val MARGIN = "Margin"

        /** A4 in PDF points: the page size of new notebooks. */
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f

        /** What a page needs to look the same; the rest (annotations, links, parent) isn't copied. */
        internal val PAGE_KEYS = listOf("MediaBox", "CropBox", "BleedBox", "TrimBox", "ArtBox", "Rotate", "UserUnit", "Resources", "Contents", "Group")

        /** A page attribute, looked up through the page tree where pages inherit it (MediaBox, Rotate...). */
        internal fun inherited(page: PDFObject, key: String): PDFObject? {
            var node: PDFObject? = page
            repeat(32) {
                val n = node ?: return null
                if (n.isNull) return null
                val v = n.get(key)
                if (!v.isNull) return v
                node = n.get("Parent")
            }
            return null
        }

        private fun rgb(color: Int) = floatArrayOf(
            ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f,
        )

        private fun colorOf(c: FloatArray?): Int = when {
            c == null || c.isEmpty() -> 0xFF000000.toInt()
            c.size >= 3 -> (0xFF shl 24) or ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()
            else -> (c[0] * 255).toInt().let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        }

        /**
         * Writes a new one-page PDF with [template] paper to [out] (a notebook to write in).
         * More pages are added in the reader, like in any PDF.
         */
        suspend fun createBlank(out: File, template: PageTemplate, width: Float = A4_WIDTH, height: Float = A4_HEIGHT) =
            withContext(Mupdf.dispatcher) {
                val pdf = PDFDocument()
                try {
                    val page = pdf.addPage(
                        com.artifex.mupdf.fitz.Rect(0f, 0f, width, height), 0, pdf.newDictionary(),
                        templateContent(template, width, height),
                    )
                    pdf.insertPage(0, page)
                    out.parentFile?.mkdirs()
                    val part = File(out.parentFile, out.name + ".part")
                    try {
                        pdf.save(part.path, "garbage")
                        if (!part.renameTo(out)) throw IOException("Can't save the new notebook")
                    } finally {
                        part.delete()
                    }
                } finally {
                    pdf.destroy()
                }
            }

        /** The drawing commands for a new page (PDF coordinates: origin bottom-left). */
        private fun templateContent(t: PageTemplate, w: Float, h: Float): String = buildString {
            when (t) {
                PageTemplate.Blank -> Unit
                PageTemplate.Lined -> {
                    append("0.76 0.82 0.94 RG 0.6 w\n")
                    var y = h - 72f
                    while (y > 40f) {
                        append("36 $y m ${w - 36f} $y l S\n")
                        y -= 24f
                    }
                }
                PageTemplate.Grid -> {
                    append("0.87 0.86 0.84 RG 0.4 w\n")
                    var x = 20f
                    while (x < w) {
                        append("$x 0 m $x $h l S\n")
                        x += 20f
                    }
                    var y = 20f
                    while (y < h) {
                        append("0 $y m $w $y l S\n")
                        y += 20f
                    }
                }
                PageTemplate.Dotted -> {
                    append("0.72 0.70 0.67 rg\n")
                    var y = 20f
                    while (y < h) {
                        var x = 20f
                        while (x < w) {
                            append("${x - 0.7f} ${y - 0.7f} 1.4 1.4 re f\n")
                            x += 20f
                        }
                        y += 20f
                    }
                }
                PageTemplate.Cornell -> {
                    val top = h - 72f
                    val summary = h * 0.2f
                    val cue = 36f + (w - 72f) * 0.3f
                    append("0.76 0.82 0.94 RG 0.6 w\n")
                    var y = top - 24f
                    while (y > summary + 12f) {
                        append("$cue $y m ${w - 36f} $y l S\n")
                        y -= 24f
                    }
                    append("0.55 0.62 0.80 RG 0.9 w\n")
                    append("36 $top m ${w - 36f} $top l S\n")
                    append("36 $summary m ${w - 36f} $summary l S\n")
                    append("0.86 0.55 0.55 RG 0.9 w\n")
                    append("$cue $top m $cue $summary l S\n")
                }
                PageTemplate.Music -> {
                    append("0.55 0.55 0.55 RG 0.5 w\n")
                    var top = h - 60f
                    while (top - 28f > 40f) {
                        for (j in 0 until 5) {
                            val y = top - j * 7f
                            append("42 $y m ${w - 42f} $y l S\n")
                        }
                        top -= 64f
                    }
                }
                PageTemplate.Planner -> {
                    val head = h - 84f
                    val split = 36f + (w - 72f) * 0.62f
                    append("0.55 0.62 0.80 RG 1 w\n")
                    append("36 $head m ${w - 36f} $head l S\n")
                    append("0.76 0.82 0.94 RG 0.6 w\n")
                    var y = head - 30f
                    while (y > 40f) {
                        append("36 $y m $split $y l S\n")
                        append("${split + 34f} $y m ${w - 36f} $y l S\n")
                        append("${split + 18f} ${y + 8f} 10 10 re S\n")
                        y -= 30f
                    }
                    append("84 $head m 84 ${y + 30f} l S\n")
                }
            }
        }

        /** Margin's own annotations: its ink, text boxes and images (written by Margin, drawn by its ink layer). */
        private fun isMargin(a: PDFAnnotation): Boolean =
            a.type in MARGIN_TYPES && runCatching { a.hasAuthor() && a.author == MARGIN }.getOrDefault(false)

        private val MARGIN_TYPES = setOf(PDFAnnotation.TYPE_INK, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_STAMP)

        /** Draws [page] except Margin's own notes, which the ink layer draws (editable) on top. MuPDF thread only. */
        internal fun drawWithoutMarginInk(page: Page, device: Device, ctm: Matrix) {
            if (page is PDFPage) {
                page.runPageContents(device, ctm, null)
                page.annotations?.forEach { a -> if (!isMargin(a)) a.run(device, ctm, null) }
                page.runPageWidgets(device, ctm, null)
            } else {
                page.run(device, ctm)
            }
        }

        /**
         * Opens [file] (Margin's copy of the PDF, see PdfStore). Saving replaces that file with a
         * new one, which is safe while it's open: MuPDF keeps reading the file it opened.
         */
        suspend fun open(file: File): PdfDoc = withContext(Mupdf.dispatcher) {
            val doc = Document.openDocument(file.path)
            try {
                if (doc.needsPassword()) throw PasswordProtectedException()
                val count = doc.countPages()
                if (count == 0) throw IOException("This PDF has no pages.")
                val sizes = ArrayList<PageSize>(count)
                val origins = ArrayList<Pair<Float, Float>>(count)
                val saved = ArrayList<SavedInk>()
                val items = ArrayList<SavedItem>()
                for (i in 0 until count) {
                    val p = doc.loadPage(i)
                    val b = p.bounds
                    sizes += PageSize(max(1f, b.x1 - b.x0), max(1f, b.y1 - b.y0))
                    origins += b.x0 to b.y0
                    if (p is PDFPage) {
                        var images = 0
                        p.annotations?.filter { isMargin(it) }?.forEach { a ->
                            when (a.type) {
                                PDFAnnotation.TYPE_INK -> saved += readInk(i, a, b.x0, b.y0)
                                PDFAnnotation.TYPE_FREE_TEXT -> readText(i, a, b.x0, b.y0)?.let { items += it }
                                PDFAnnotation.TYPE_STAMP -> {
                                    val r = a.rect
                                    items += SavedImage(i, r.x0 - b.x0, r.y0 - b.y0, r.x1 - r.x0, r.y1 - r.y0, images++)
                                }
                            }
                        }
                    }
                    p.destroy()
                }
                PdfDoc(doc, sizes, origins, saved, items)
            } catch (t: Throwable) {
                doc.destroy()
                throw t
            }
        }

        private fun readInk(page: Int, a: PDFAnnotation, x0: Float, y0: Float): List<SavedInk> {
            val color = colorOf(a.color)
            val subject = runCatching { if (a.hasSubject()) a.subject else null }.getOrNull()
            return a.inkList.orEmpty().map { stroke ->
                val pts = FloatArray(stroke.size * 2)
                stroke.forEachIndexed { k, pt ->
                    pts[2 * k] = pt.x - x0
                    pts[2 * k + 1] = pt.y - y0
                }
                SavedInk(page, pts, color, a.borderWidth, a.opacity, subject)
            }
        }

        private fun readText(page: Int, a: PDFAnnotation, x0: Float, y0: Float): SavedText? = runCatching {
            val r = a.rect
            val da = if (a.hasDefaultAppearance()) a.defaultAppearance else null
            SavedText(
                page, r.x0 - x0, r.y0 - y0, r.x1 - r.x0, r.y1 - r.y0,
                a.contents.orEmpty(), da?.size?.takeIf { it > 0f } ?: 12f, colorOf(da?.color),
            )
        }.getOrNull()
    }
}

/** One stroke as it goes into the PDF: x, y pairs in page points, and its look. */
class InkForPdf(val points: FloatArray, val color: Int, val width: Float, val opacity: Float, val subject: String)
