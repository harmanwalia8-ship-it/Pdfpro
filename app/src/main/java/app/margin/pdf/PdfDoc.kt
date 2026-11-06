package app.margin.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
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

class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor?,
    private val doc: Document,
    val pageSizes: List<PageSize>,
    private val origins: List<Pair<Float, Float>>,
) : Closeable {

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
            page(index).run(device, Matrix(scale, scale))
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
            runCatching { pfd?.close() }
        }
    }

    companion object {
        suspend fun open(ctx: Context, uri: Uri): PdfDoc = withContext(Mupdf.dispatcher) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open this file.")
            var keptPfd: ParcelFileDescriptor? = pfd
            try {
                val doc = if (pfd.statSize >= 0) {
                    Document.openDocument(FdStream(pfd), "application/pdf")
                } else {
                    // Some providers hand out a pipe; MuPDF needs random access, so copy it first.
                    pfd.close()
                    keptPfd = null
                    Document.openDocument(copyToCache(ctx, uri).path)
                }
                if (doc.needsPassword()) {
                    doc.destroy()
                    throw PasswordProtectedException()
                }
                val count = doc.countPages()
                val sizes = ArrayList<PageSize>(count)
                val origins = ArrayList<Pair<Float, Float>>(count)
                for (i in 0 until count) {
                    val p = doc.loadPage(i)
                    val b = p.bounds
                    sizes += PageSize(max(1f, b.x1 - b.x0), max(1f, b.y1 - b.y0))
                    origins += b.x0 to b.y0
                    p.destroy()
                }
                PdfDoc(keptPfd, doc, sizes, origins)
            } catch (t: Throwable) {
                runCatching { keptPfd?.close() }
                throw t
            }
        }

        private fun copyToCache(ctx: Context, uri: Uri): File {
            val out = File(ctx.cacheDir, "open/${uri.toString().hashCode()}.pdf").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Can't read this file.")
            return out
        }
    }
}

/** Lets MuPDF read straight from the file Android gave us, without copying it. */
private class FdStream(pfd: ParcelFileDescriptor) : SeekableInputStream {
    private val channel = FileInputStream(pfd.fileDescriptor).channel

    override fun read(buf: ByteArray): Int = channel.read(ByteBuffer.wrap(buf))

    override fun seek(offset: Long, whence: Int): Long {
        val pos = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> channel.position() + offset
            SeekableStream.SEEK_END -> channel.size() + offset
            else -> throw IOException("Bad seek mode $whence")
        }
        channel.position(pos)
        return pos
    }

    override fun position(): Long = channel.position()
}
