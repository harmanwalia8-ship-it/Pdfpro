package app.margin.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
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

class PdfDoc private constructor(
    private val work: File,
    private val doc: Document,
    val pageSizes: List<PageSize>,
    private val origins: List<Pair<Float, Float>>,
    /** Margin's ink annotations found in the file when it was opened. */
    val savedInk: List<SavedInk>,
) : Closeable {

    /** Pages that have (or had) Margin ink annotations; each save rewrites these. */
    private val inkedPages = savedInk.mapTo(HashSet()) { it.page }

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
        if (closed) return@withContext null
        val size = pageSizes[index]
        val full = Rect(0, 0, max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
        val r = region?.let { Rect(it).apply { if (!intersect(full)) return@withContext null } } ?: full
        val bitmap = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val (x0, y0) = origins[index]
        val device = AndroidDrawDevice(bitmap, (x0 * scale).roundToInt() + r.left, (y0 * scale).roundToInt() + r.top)
        try {
            val page = page(index)
            val ctm = Matrix(scale, scale)
            if (page is PDFPage) {
                // Margin's own ink is drawn (editable) by the ink layer, so skip it here.
                page.runPageContents(device, ctm, null)
                page.annotations?.forEach { a -> if (!isMargin(a)) a.run(device, ctm, null) }
                page.runPageWidgets(device, ctm, null)
            } else {
                page.run(device, ctm)
            }
            device.close()
        } finally {
            device.destroy()
        }
        bitmap
    }

    /** Small preview of a page, [widthPx] wide. */
    suspend fun thumbnail(index: Int, widthPx: Int): Bitmap? =
        render(index, widthPx / pageSizes[index].width)

    override fun close() {
        closed = true
        CoroutineScope(Mupdf.dispatcher).launch {
            pages.values.forEach { it.destroy() }
            pages.clear()
            doc.destroy()
            work.delete()
        }
    }

    /**
     * Writes [strokes] (per page, in page points) into the PDF as ink annotations, replacing
     * Margin's previous ones, and saves the whole document to [out]. Returns false if this
     * isn't a writable PDF.
     */
    suspend fun saveWithInk(strokes: Map<Int, List<InkForPdf>>, out: File): Boolean = withContext(Mupdf.dispatcher) {
        val pdf = doc as? PDFDocument ?: return@withContext false
        val touched = HashSet(inkedPages).apply { addAll(strokes.filterValues { it.isNotEmpty() }.keys) }
        pdf.beginOperation("Margin ink")
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
                    a.color = floatArrayOf(
                        ((s.color shr 16) and 0xFF) / 255f, ((s.color shr 8) and 0xFF) / 255f, (s.color and 0xFF) / 255f,
                    )
                    a.opacity = s.opacity
                    a.borderWidth = s.width
                    a.inkList = arrayOf(Array(s.points.size / 2) { k -> Point(s.points[2 * k] + x0, s.points[2 * k + 1] + y0) })
                    a.update()
                }
                page.update()
                inkedPages += i
            }
        } finally {
            pdf.endOperation()
        }
        out.parentFile?.mkdirs()
        pdf.save(out.path, "garbage")
        true
    }

    companion object {
        const val MARGIN = "Margin"

        private fun isMargin(a: PDFAnnotation): Boolean =
            a.type == PDFAnnotation.TYPE_INK && runCatching { a.hasAuthor() && a.author == MARGIN }.getOrDefault(false)

        /**
         * Opens a private working copy of the PDF. Viewing the copy lets Margin safely
         * rewrite the original file (with your ink) while you keep reading.
         */
        suspend fun open(ctx: Context, uri: Uri, workName: String): PdfDoc = withContext(Mupdf.dispatcher) {
            val work = File(ctx.cacheDir, "work/$workName.pdf").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(uri)?.use { input -> work.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Can't read this file.")
            try {
                val doc = Document.openDocument(work.path)
                if (doc.needsPassword()) {
                    doc.destroy()
                    throw PasswordProtectedException()
                }
                val count = doc.countPages()
                if (count == 0) {
                    doc.destroy()
                    throw IOException("This PDF has no pages.")
                }
                val sizes = ArrayList<PageSize>(count)
                val origins = ArrayList<Pair<Float, Float>>(count)
                val saved = ArrayList<SavedInk>()
                for (i in 0 until count) {
                    val p = doc.loadPage(i)
                    val b = p.bounds
                    sizes += PageSize(max(1f, b.x1 - b.x0), max(1f, b.y1 - b.y0))
                    origins += b.x0 to b.y0
                    if (p is PDFPage) {
                        p.annotations?.filter { isMargin(it) }?.forEach { a -> saved += readInk(i, a, b.x0, b.y0) }
                    }
                    p.destroy()
                }
                PdfDoc(work, doc, sizes, origins, saved)
            } catch (t: Throwable) {
                work.delete()
                throw t
            }
        }

        private fun readInk(page: Int, a: PDFAnnotation, x0: Float, y0: Float): List<SavedInk> {
            val c = a.color ?: floatArrayOf(0f, 0f, 0f)
            val color = if (c.size >= 3) {
                (0xFF shl 24) or ((c[0] * 255).toInt() shl 16) or ((c[1] * 255).toInt() shl 8) or (c[2] * 255).toInt()
            } else 0xFF000000.toInt()
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
    }
}

/** One stroke as it goes into the PDF: x, y pairs in page points, and its look. */
class InkForPdf(val points: FloatArray, val color: Int, val width: Float, val opacity: Float, val subject: String)
