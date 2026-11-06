package app.margin.ink

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.StockTextureBitmapStore
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Tool { Pen0, Pen1, Pen2, Highlighter, Eraser }
enum class EraserMode { Area, Stroke }

/**
 * Everything about the ink of one open PDF: the tools, the strokes on each page,
 * undo/redo and autosave (to a small file per PDF in app storage).
 */
@Stable
class InkController(ctx: Context, docId: String) {
    // ---- Tools (observed by the pen tray) ----
    var tool by mutableStateOf(Tool.Pen0)
    val pens = mutableStateListOf(
        PenStyle(PenType.Fountain, 0xFF1F3A93.toInt(), 1.6f),
        PenStyle(PenType.Ballpoint, 0xFF1C1B1A.toInt(), 1.0f),
        PenStyle(PenType.Pencil, 0xFF6D5D52.toInt(), 1.4f),
    )
    var highlighter by mutableStateOf(PenStyle(PenType.Highlighter, 0xFFFFD54A.toInt(), 12f))
    var eraserMode by mutableStateOf(EraserMode.Area)
    var eraserSizeDp by mutableFloatStateOf(32f)

    /** Style for the current tool, or null when erasing. */
    val currentStyle: PenStyle?
        get() = when (tool) {
            Tool.Pen0 -> pens[0]
            Tool.Pen1 -> pens[1]
            Tool.Pen2 -> pens[2]
            Tool.Highlighter -> highlighter
            Tool.Eraser -> null
        }

    fun updateCurrentStyle(change: (PenStyle) -> PenStyle) {
        when (tool) {
            Tool.Pen0 -> pens[0] = change(pens[0])
            Tool.Pen1 -> pens[1] = change(pens[1])
            Tool.Pen2 -> pens[2] = change(pens[2])
            Tool.Highlighter -> highlighter = change(highlighter)
            Tool.Eraser -> Unit
        }
    }

    // ---- Ink ----
    private val pages = HashMap<Int, List<InkStroke>>()
    private val history = InkHistory()
    private var nextId = 1L

    /** Bumped on every change, so pages redraw. */
    var version by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun strokes(page: Int): List<InkStroke> = pages[page].orEmpty()

    // ---- Drawing ----
    val textures = StockTextureBitmapStore(ctx.resources)
    val renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create(textures)
    private val families = HashMap<PenType, BrushFamily>()

    /** Brush for [style]; [scale] multiplies the size (screen pixels per page point for live ink). */
    fun brush(style: PenStyle, scale: Float = 1f): Brush {
        val family = families.getOrPut(style.type) {
            when (style.type) {
                PenType.Ballpoint, PenType.Marker -> StockBrushes.marker()
                PenType.Fountain -> StockBrushes.pressurePen()
                PenType.Pencil -> StockBrushes.pencilUnstable
                PenType.Highlighter -> StockBrushes.highlighter()
            }
        }
        val color = if (style.type == PenType.Highlighter) (style.color and 0x00FFFFFF) or 0x73000000 else style.color
        return Brush.createWithColorIntArgb(family, color, style.size * scale, 0.05f * scale)
    }

    /** The drawable stroke for [s], built on first use. */
    fun inkStroke(s: InkStroke): Stroke =
        (s.rendered as? Stroke) ?: buildStroke(s).also { s.rendered = it }

    private fun buildStroke(s: InkStroke): Stroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0 until s.count) {
            val o = i * InkStroke.STRIDE
            batch.add(InputToolType.STYLUS, s.pts[o], s.pts[o + 1], s.pts[o + 3].toLong(), PT_CM, s.pts[o + 2])
        }
        return Stroke(brush(s.style), batch)
    }

    // ---- Changes ----
    /** A pen stroke the user just finished: x, y, pressure, time per point, in page points. */
    fun addFinished(page: Int, style: PenStyle, pts: FloatArray) {
        commit(mapOf(page to strokes(page) + InkStroke(nextId++, style, pts)))
    }

    private var eraseBefore: HashMap<Int, List<InkStroke>>? = null

    fun beginErase() {
        eraseBefore = HashMap()
    }

    /** Erases along a pen movement on [page], in page points; [radius] in points. */
    fun eraseAlong(page: Int, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val before = strokes(page)
        if (before.isEmpty()) return
        val after = EraserMath.sweep(before, x0, y0, x1, y1, radius, eraserMode == EraserMode.Area) { nextId++ }
        if (after.size == before.size && after.indices.all { after[it] === before[it] }) return
        eraseBefore?.let { if (page !in it) it[page] = before }
        pages[page] = after
        version++
    }

    fun endErase() {
        val before = eraseBefore ?: return
        eraseBefore = null
        if (before.isEmpty()) return
        history.record(InkEdit(before, before.keys.associateWith { strokes(it) }))
        changed()
    }

    fun clearPage(page: Int) {
        if (strokes(page).isNotEmpty()) commit(mapOf(page to emptyList()))
    }

    fun undo() {
        val e = history.popUndo() ?: return
        e.before.forEach { (p, l) -> pages[p] = l }
        changed()
    }

    fun redo() {
        val e = history.popRedo() ?: return
        e.after.forEach { (p, l) -> pages[p] = l }
        changed()
    }

    private fun commit(after: Map<Int, List<InkStroke>>) {
        val before = after.keys.associateWith { strokes(it) }
        after.forEach { (p, l) -> pages[p] = l }
        history.record(InkEdit(before, after))
        changed()
    }

    private fun changed() {
        version++
        canUndo = history.canUndo
        canRedo = history.canRedo
        scheduleSave()
    }

    // ---- Autosave ----
    private val file = File(ctx.filesDir, "ink/$docId.json")
    private var saveJob: Job? = null

    private fun scheduleSave() {
        val snapshot = HashMap(pages)
        saveJob?.cancel()
        saveJob = io.launch {
            delay(500)
            write(snapshot)
        }
    }

    /** Saves right away (e.g. when the PDF is closed). */
    fun flush() {
        saveJob?.cancel()
        val snapshot = HashMap(pages)
        io.launch { write(snapshot) }
    }

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { read() }
        loaded.forEach { (p, l) -> pages[p] = l }
        nextId = (loaded.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
        version++
    }

    private fun write(data: Map<Int, List<InkStroke>>) {
        val pagesJson = JSONObject()
        data.forEach { (page, list) ->
            if (list.isEmpty()) return@forEach
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id).put("t", s.style.type.name).put("c", s.style.color).put("s", s.style.size.toDouble())
                        .put("p", encode(s.pts)),
                )
            }
            pagesJson.put(page.toString(), arr)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("v", 1).put("pages", pagesJson).toString())
        tmp.renameTo(file)
    }

    private fun read(): Map<Int, List<InkStroke>> = runCatching {
        val pagesJson = JSONObject(file.readText()).getJSONObject("pages")
        val out = HashMap<Int, List<InkStroke>>()
        pagesJson.keys().forEach { key ->
            val arr = pagesJson.getJSONArray(key)
            out[key.toInt()] = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                InkStroke(
                    o.getLong("id"),
                    PenStyle(PenType.valueOf(o.getString("t")), o.getInt("c"), o.getDouble("s").toFloat()),
                    decode(o.getString("p")),
                )
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun encode(pts: FloatArray): String {
        val buf = ByteBuffer.allocate(pts.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(pts)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun decode(s: String): FloatArray {
        val buf = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    companion object {
        /** One PDF point in centimetres: tells brushes the real size of a stroke unit. */
        const val PT_CM = 2.54f / 72f

        /** Saving outlives the reader screen, so closing a PDF never loses the last strokes. */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
