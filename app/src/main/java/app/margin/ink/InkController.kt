package app.margin.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import app.margin.data.Library
import app.margin.pdf.ImageForPdf
import app.margin.pdf.InkForPdf
import app.margin.pdf.ItemForPdf
import app.margin.pdf.PageMoves
import app.margin.pdf.SavedImage
import app.margin.pdf.SavedInk
import app.margin.pdf.SavedItem
import app.margin.pdf.SavedText
import app.margin.pdf.TextForPdf
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
import kotlin.math.max
import kotlin.math.roundToInt

enum class Tool { Pen0, Pen1, Pen2, Highlighter, Eraser, Lasso, Text }
enum class EraserMode { Area, Stroke }

/**
 * Everything you put on the pages of one open PDF: ink, typed text boxes and images; the tools,
 * undo/redo and autosave (to a small file per PDF in app storage).
 */
@Stable
class InkController(ctx: Context, private val docId: String) {
    private val library = Library.get(ctx)
    private val imagesDir = library.imagesDir(docId)

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

    /** Highlighter strokes over printed text become clean bars over the lines. */
    var snapHighlighter by mutableStateOf(true)

    /** Finds the printed text lines along a horizontal line on a page (set by the reader, which has the PDF). */
    var lineFinder: (suspend (page: Int, x0: Float, y: Float, x1: Float) -> List<Bounds>)? = null

    /** Style for the current tool, or null for the eraser, lasso and text tools. */
    val currentStyle: PenStyle?
        get() = when (tool) {
            Tool.Pen0 -> pens[0]
            Tool.Pen1 -> pens[1]
            Tool.Pen2 -> pens[2]
            Tool.Highlighter -> highlighter
            Tool.Eraser, Tool.Lasso, Tool.Text -> null
        }

    /** Picks a tool; anything selected is let go. */
    fun selectTool(t: Tool) {
        tool = t
        clearSelection()
    }

    fun updateCurrentStyle(change: (PenStyle) -> PenStyle) {
        when (tool) {
            Tool.Pen0 -> pens[0] = change(pens[0])
            Tool.Pen1 -> pens[1] = change(pens[1])
            Tool.Pen2 -> pens[2] = change(pens[2])
            Tool.Highlighter -> highlighter = change(highlighter)
            Tool.Eraser, Tool.Lasso, Tool.Text -> Unit
        }
    }

    // ---- Selection: strokes picked with the lasso, or one text box / image ----
    class Selection(val page: Int, val ids: Set<Long>, val bounds: Bounds)
    class ItemRef(val page: Int, val id: Long)

    var selection by mutableStateOf<Selection?>(null)
        private set

    var selectedItem by mutableStateOf<ItemRef?>(null)
        private set

    /** Live move / resize while dragging the selection; applied when the drag ends. */
    var selTransform by mutableStateOf(SelTransform())
        private set

    /** The selected text box or image itself. */
    fun selectedItemValue(): PageItem? = selectedItem?.let { r -> items(r.page).firstOrNull { it.id == r.id } }

    /** What's selected (strokes or an item): its page and box in page points, before any live drag. */
    fun selectionBox(): Pair<Int, Bounds>? {
        selection?.let { return it.page to it.bounds }
        val r = selectedItem ?: return null
        val item = items(r.page).firstOrNull { it.id == r.id } ?: return null
        return r.page to item.bounds
    }

    /** Selects the strokes on [page] that are mostly inside [polygon] (x, y pairs, page points). */
    fun selectInLasso(page: Int, polygon: FloatArray): Boolean {
        selectedItem = null
        val picked = strokes(page).filter { SelectionMath.fractionInside(it, polygon) >= 0.5f }
        selection = if (picked.isEmpty()) null else Selection(page, picked.mapTo(HashSet()) { it.id }, SelectionMath.bounds(picked))
        selTransform = SelTransform()
        return selection != null
    }

    /** The top-most text box or image at (x, y) on [page], in page points. */
    fun itemAt(page: Int, x: Float, y: Float): PageItem? = items(page).lastOrNull { it.bounds.contains(x, y) }

    fun selectItem(page: Int, id: Long) {
        selection = null
        selTransform = SelTransform()
        selectedItem = ItemRef(page, id)
    }

    fun clearSelection() {
        if (selection != null) selection = null
        if (selectedItem != null) selectedItem = null
        if (!selTransform.isIdentity) selTransform = SelTransform()
    }

    fun previewSelection(t: SelTransform) {
        selTransform = t
    }

    /** Ends a move / resize: the strokes or item take their new place (one undo step). */
    fun applySelectionTransform() {
        val t = selTransform
        selTransform = SelTransform()
        if (t.isIdentity) return
        selection?.let { sel ->
            val after = strokes(sel.page).map { s ->
                if (s.id in sel.ids) SelectionMath.transform(s, sel.bounds.left, sel.bounds.top, t) else s
            }
            commit(mapOf(sel.page to after))
            selection = Selection(sel.page, sel.ids, SelectionMath.bounds(after.filter { it.id in sel.ids }))
            return
        }
        val r = selectedItem ?: return
        commitItems(mapOf(r.page to items(r.page).map { if (it.id == r.id) it.transformed(t) else it }))
    }

    fun deleteSelection() {
        selection?.let { sel ->
            commit(mapOf(sel.page to strokes(sel.page).filter { it.id !in sel.ids }))
            selection = null
            return
        }
        val r = selectedItem ?: return
        commitItems(mapOf(r.page to items(r.page).filter { it.id != r.id }))
        selectedItem = null
    }

    /** Copies the selection a little down and to the right, and selects the copy. */
    fun duplicateSelection() {
        selection?.let { sel ->
            val copies = strokes(sel.page).filter { it.id in sel.ids }.map { s ->
                val moved = SelectionMath.transform(s, 0f, 0f, SelTransform(12f, 12f, 1f))
                InkStroke(nextId++, moved.style, moved.pts)
            }
            commit(mapOf(sel.page to strokes(sel.page) + copies))
            selection = Selection(sel.page, copies.mapTo(HashSet()) { it.id }, SelectionMath.bounds(copies))
            return
        }
        val r = selectedItem ?: return
        val item = selectedItemValue() ?: return
        var copy = item.transformed(SelTransform(12f, 12f, 1f)).withId(nextId++)
        if (copy is ImageItem) copy = copy.copy(file = copyImageFile(copy.file) ?: return)
        commitItems(mapOf(r.page to items(r.page) + copy))
        selectItem(r.page, copy.id)
    }

    fun recolorSelection(color: Int) {
        selection?.let { sel ->
            val after = strokes(sel.page).map { s ->
                if (s.id in sel.ids && s.style.color != color) InkStroke(s.id, s.style.copy(color = color), s.pts) else s
            }
            commit(mapOf(sel.page to after))
            return
        }
        val r = selectedItem ?: return
        commitItems(mapOf(r.page to items(r.page).map { if (it.id == r.id && it is TextItem) it.copy(color = color) else it }))
    }

    // ---- Ink ----
    private val pages = HashMap<Int, List<InkStroke>>()
    private val items = HashMap<Int, List<PageItem>>()
    private val history = InkHistory()
    private var nextId = 1L

    /** Bumped on every change, so pages redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped when something only needs drawing again (an image finished loading). */
    var redraw by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun strokes(page: Int): List<InkStroke> = pages[page].orEmpty()
    fun items(page: Int): List<PageItem> = items[page].orEmpty()

    /** Has this page any of your ink, text or images? */
    fun hasNotes(page: Int): Boolean = strokes(page).isNotEmpty() || items(page).isNotEmpty()

    val isEmpty: Boolean get() = pages.values.all { it.isEmpty() } && items.values.all { it.isEmpty() }

    /** Ink changed since it was last written into the PDF file. */
    var pdfDirty by mutableStateOf(false)
        private set

    fun markWrittenToPdf(atVersion: Int) {
        if (version == atVersion) pdfDirty = false
    }

    /** The PDF itself changed (e.g. a page was added): save it even if no ink changed. */
    fun markDirty() {
        version++
        pdfDirty = true
    }

    /** The ink as it goes into the PDF file: x, y pairs per stroke, plus color, width, opacity. */
    fun forPdf(): Map<Int, List<InkForPdf>> = pages.mapValues { (_, list) ->
        list.map { s ->
            val xy = FloatArray(s.count * 2)
            for (i in 0 until s.count) {
                xy[2 * i] = s.x(i)
                xy[2 * i + 1] = s.y(i)
            }
            val hl = s.style.type == PenType.Highlighter
            InkForPdf(xy, s.style.color, s.style.size, if (hl) 0.45f else 1f, "Margin ${s.style.type.name}")
        }
    }

    /** Text boxes and images as they go into the PDF file. */
    fun itemsForPdf(): Map<Int, List<ItemForPdf>> = items.mapValues { (_, list) ->
        list.map { item ->
            when (item) {
                is TextItem -> TextForPdf(item.x, item.y, item.w, item.h, item.text, item.size, item.color)
                is ImageItem -> ImageForPdf(item.x, item.y, item.w, item.h, File(imagesDir, item.file))
            }
        }
    }

    /**
     * Notes found in the PDF itself (written by Margin before), used when Margin has none saved:
     * e.g. after reinstalling, or in a PDF made from another one (split, merge). [renderImage] draws
     * an image from the PDF into a file.
     */
    suspend fun importSaved(saved: List<SavedInk>, savedItems: List<SavedItem>, renderImage: suspend (SavedImage, File) -> Boolean) {
        if ((saved.isEmpty() && savedItems.isEmpty()) || !isEmpty) return
        saved.forEach { si ->
            val type = PenType.entries.firstOrNull { si.subject == "Margin ${it.name}" }
                ?: if (si.opacity < 0.9f) PenType.Highlighter else PenType.Ballpoint
            val n = si.points.size / 2
            val pts = FloatArray(n * InkStroke.STRIDE)
            for (i in 0 until n) {
                pts[i * 4] = si.points[2 * i]
                pts[i * 4 + 1] = si.points[2 * i + 1]
                pts[i * 4 + 2] = -1f // pressure isn't stored in PDF ink
                pts[i * 4 + 3] = i * 8f
            }
            pages[si.page] = strokes(si.page) + InkStroke(nextId++, PenStyle(type, si.color, si.width), pts)
        }
        for (si in savedItems) {
            val item: PageItem = when (si) {
                is SavedText -> TextItem(nextId++, si.x, si.y, si.w, si.h, si.text, si.size, si.color)
                is SavedImage -> {
                    val id = nextId++
                    val name = "img-$id.png"
                    if (!renderImage(si, File(imagesDir, name))) continue
                    ImageItem(id, si.x, si.y, si.w, si.h, name)
                }
            }
            items[si.page] = items(si.page) + item
        }
        version++
        scheduleSave()
    }

    // ---- Drawing ----
    val textures = StockTextureBitmapStore(ctx.resources)

    /** Emulators' software graphics can't draw Ink's GPU meshes, so they use plain paths. */
    val renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create(isEmulator, textures)
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

    // ---- Text boxes ----
    private val layouts = HashMap<Long, Pair<TextItem, StaticLayout>>()

    /** The text box laid out for drawing (in page points). */
    fun textLayout(t: TextItem): StaticLayout {
        layouts[t.id]?.let { (key, layout) -> if (key == t) return layout }
        return makeLayout(t.text, t.w, t.size, t.color).also { layouts[t.id] = t to it }
    }

    private fun makeLayout(text: String, w: Float, size: Float, color: Int): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            this.color = color
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, w.roundToInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun textHeight(text: String, w: Float, size: Float): Float =
        max(size * 1.3f, makeLayout(text.ifEmpty { " " }, w, size, 0).height.toFloat())

    /** Adds a text box at ([x], [y]) on [page], [w] wide, and selects it. */
    fun addText(page: Int, x: Float, y: Float, w: Float, text: String, size: Float, color: Int) {
        if (text.isBlank()) return
        val item = TextItem(nextId++, x, y, w, textHeight(text, w, size), text, size, color)
        commitItems(mapOf(page to items(page) + item))
        selectItem(page, item.id)
    }

    /** Changes a text box's words, size or color (its height follows). */
    fun updateText(page: Int, id: Long, text: String, size: Float, color: Int) {
        val old = items(page).firstOrNull { it.id == id } as? TextItem ?: return
        if (text.isBlank()) {
            commitItems(mapOf(page to items(page).filter { it.id != id }))
            if (selectedItem?.id == id) selectedItem = null
            return
        }
        val k = size / old.size
        val w = (old.w * k).coerceAtLeast(24f)
        val updated = old.copy(text = text, size = size, color = color, w = w, h = textHeight(text, w, size))
        commitItems(mapOf(page to items(page).map { if (it.id == id) updated else it }))
    }

    // ---- Images ----
    private val bitmaps = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()

    /** The picture for [item], or null while it loads (the page redraws when it's ready). */
    fun bitmap(item: ImageItem): Bitmap? {
        bitmaps[item.file]?.let { return it }
        if (loading.add(item.file)) {
            io.launch {
                val bm = decode(File(imagesDir, item.file), 2048)
                withContext(Dispatchers.Main) {
                    loading.remove(item.file)
                    if (bm != null) {
                        bitmaps[item.file] = bm
                        redraw++
                    }
                }
            }
        }
        return null
    }

    /**
     * Adds a picture (already saved as [file] in this PDF's images folder, [pxW] × [pxH] pixels) to
     * [page], fitted inside the box ([x], [y], [maxW], [maxH]), and selects it.
     */
    fun addImage(page: Int, file: String, pxW: Int, pxH: Int, x: Float, y: Float, maxW: Float, maxH: Float) {
        val (w, h) = ItemMath.fit(pxW.toFloat(), pxH.toFloat(), maxW, maxH)
        val item = ImageItem(nextId++, x + (maxW - w) / 2, y + (maxH - h) / 2, w, h, file)
        commitItems(mapOf(page to items(page) + item))
        selectItem(page, item.id)
    }

    /** Where pictures for this PDF are kept. */
    fun imageFile(name: String): File = File(imagesDir, name).also { it.parentFile?.mkdirs() }

    private fun copyImageFile(name: String): String? = runCatching {
        val copy = "img-${System.nanoTime()}.${name.substringAfterLast('.', "png")}"
        File(imagesDir, name).copyTo(File(imagesDir, copy))
        copy
    }.getOrNull()

    // ---- Changes ----
    /** A pen stroke the user just finished: x, y, pressure, time per point, in page points. */
    fun addFinished(page: Int, style: PenStyle, pts: FloatArray) {
        commit(mapOf(page to strokes(page) + InkStroke(nextId++, style, pts)))
    }

    /**
     * A highlighter stroke the user just finished: over printed text (and with snapping on) it becomes
     * clean bars over the lines it covers; otherwise it's kept as drawn.
     */
    suspend fun finishHighlight(page: Int, style: PenStyle, pts: FloatArray) {
        val finder = lineFinder
        val probe = if (snapHighlighter && finder != null) HighlightSnap.probe(pts) else null
        val bars = probe?.let { (x0, y, x1) ->
            runCatching { finder?.invoke(page, x0, y, x1) }.getOrNull()?.let { HighlightSnap.bars(pts, style.size, it) }
        }
        if (bars == null) {
            addFinished(page, style, pts)
        } else {
            commit(mapOf(page to strokes(page) + bars.map { (p, h) -> InkStroke(nextId++, style.copy(size = h), p) }))
        }
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

    // ---- Pages changing (undo history is reset: its steps refer to page numbers that changed) ----

    /** A page was added at [at]: notes on later pages move down one. */
    fun onPageInserted(at: Int) = remapPages { PageMoves.afterInsert(it, at) }

    /** Page [at] was deleted, with its notes: notes on later pages move up one. */
    fun onPageDeleted(at: Int) {
        pages.remove(at)
        items.remove(at)
        remapPages { PageMoves.afterDelete(it, at) }
    }

    /** Page [from] moved to [to]. */
    fun onPageMoved(from: Int, to: Int) = remapPages { PageMoves.afterMove(it, from, to) }

    /** Page [index] was turned a quarter clockwise; it was [oldHeight] points tall. */
    fun onPageTurned(index: Int, oldHeight: Float) {
        pages[index]?.let { list ->
            pages[index] = list.map { s ->
                val p = s.pts.copyOf()
                for (i in 0 until s.count) {
                    val (x, y) = PageMoves.turnClockwise(s.x(i), s.y(i), oldHeight)
                    p[i * InkStroke.STRIDE] = x
                    p[i * InkStroke.STRIDE + 1] = y
                }
                InkStroke(s.id, s.style, p)
            }
        }
        items[index]?.let { list -> items[index] = list.map { ItemMath.turned(it, oldHeight) } }
        remapPages { it }
    }

    /** Page [index] was copied to [index] + 1: the copy gets copies of its notes. */
    fun onPageDuplicated(index: Int) {
        val strokesCopy = strokes(index).map { InkStroke(nextId++, it.style, it.pts.copyOf()) }
        val itemsCopy = items(index).mapNotNull { item ->
            val c = item.withId(nextId++)
            if (c is ImageItem) copyImageFile(c.file)?.let { c.copy(file = it) } else c
        }
        remapPages { PageMoves.afterInsert(it, index + 1) }
        if (strokesCopy.isNotEmpty()) pages[index + 1] = strokesCopy
        if (itemsCopy.isNotEmpty()) items[index + 1] = itemsCopy
        changed()
    }

    private fun remapPages(move: (Int) -> Int?) {
        val old = HashMap(pages)
        pages.clear()
        old.forEach { (i, list) -> move(i)?.let { pages[it] = list } }
        val oldItems = HashMap(items)
        items.clear()
        oldItems.forEach { (i, list) -> move(i)?.let { items[it] = list } }
        history.clear()
        clearSelection()
        changed()
    }

    fun clearPage(page: Int) {
        if (strokes(page).isNotEmpty()) commit(mapOf(page to emptyList()))
    }

    fun undo() {
        val e = history.popUndo() ?: return
        clearSelection()
        e.before.forEach { (p, l) -> pages[p] = l }
        e.itemsBefore.forEach { (p, l) -> items[p] = l }
        changed()
    }

    fun redo() {
        val e = history.popRedo() ?: return
        clearSelection()
        e.after.forEach { (p, l) -> pages[p] = l }
        e.itemsAfter.forEach { (p, l) -> items[p] = l }
        changed()
    }

    private fun commit(after: Map<Int, List<InkStroke>>) {
        val before = after.keys.associateWith { strokes(it) }
        after.forEach { (p, l) -> pages[p] = l }
        history.record(InkEdit(before, after))
        changed()
    }

    private fun commitItems(after: Map<Int, List<PageItem>>) {
        val before = after.keys.associateWith { items(it) }
        after.forEach { (p, l) -> items[p] = l }
        history.record(InkEdit(emptyMap(), emptyMap(), before, after))
        changed()
    }

    private fun changed() {
        version++
        pdfDirty = true
        canUndo = history.canUndo
        canRedo = history.canRedo
        scheduleSave()
    }

    // ---- Autosave ----
    private val file = library.inkFile(docId)
    private var saveJob: Job? = null

    private fun scheduleSave() {
        val snapshot = HashMap(pages)
        val itemsSnapshot = HashMap(items)
        saveJob?.cancel()
        saveJob = io.launch {
            delay(500)
            write(snapshot, itemsSnapshot)
        }
    }

    /** Saves right away (e.g. when the PDF is closed). */
    fun flush() {
        saveJob?.cancel()
        val snapshot = HashMap(pages)
        val itemsSnapshot = HashMap(items)
        io.launch { write(snapshot, itemsSnapshot) }
    }

    suspend fun load() {
        val (loaded, loadedItems) = withContext(Dispatchers.IO) { read() }
        loaded.forEach { (p, l) -> pages[p] = l }
        loadedItems.forEach { (p, l) -> items[p] = l }
        nextId = max(
            loaded.values.flatten().maxOfOrNull { it.id } ?: 0L,
            loadedItems.values.flatten().maxOfOrNull { it.id } ?: 0L,
        ) + 1
        version++
        // Pictures no text box or undo step can reach any more (e.g. deleted before the PDF was last closed).
        // Recent ones are left alone: the last session may still be finishing its save.
        val used = loadedItems.values.flatten().filterIsInstance<ImageItem>().mapTo(HashSet()) { it.file }
        val old = System.currentTimeMillis() - 60_000
        withContext(Dispatchers.IO) {
            imagesDir.listFiles()?.forEach { if (it.name !in used && it.lastModified() < old) it.delete() }
        }
    }

    private fun write(data: Map<Int, List<InkStroke>>, itemData: Map<Int, List<PageItem>>) {
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
        val itemsJson = JSONObject()
        itemData.forEach { (page, list) ->
            if (list.isEmpty()) return@forEach
            val arr = JSONArray()
            list.forEach { item ->
                val o = JSONObject().put("id", item.id)
                    .put("x", item.x.toDouble()).put("y", item.y.toDouble()).put("w", item.w.toDouble()).put("h", item.h.toDouble())
                when (item) {
                    is TextItem -> o.put("k", "text").put("text", item.text).put("s", item.size.toDouble()).put("c", item.color)
                    is ImageItem -> o.put("k", "image").put("f", item.file)
                }
                arr.put(o)
            }
            itemsJson.put(page.toString(), arr)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("v", 1).put("pages", pagesJson).put("items", itemsJson).toString())
        tmp.renameTo(file)
    }

    private fun read(): Pair<Map<Int, List<InkStroke>>, Map<Int, List<PageItem>>> = runCatching {
        val root = JSONObject(file.readText())
        val pagesJson = root.getJSONObject("pages")
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
        val itemsOut = HashMap<Int, List<PageItem>>()
        root.optJSONObject("items")?.let { itemsJson ->
            itemsJson.keys().forEach { key ->
                val arr = itemsJson.getJSONArray(key)
                itemsOut[key.toInt()] = List(arr.length()) { i -> arr.getJSONObject(i) }.mapNotNull { o ->
                    val id = o.getLong("id")
                    val x = o.getDouble("x").toFloat()
                    val y = o.getDouble("y").toFloat()
                    val w = o.getDouble("w").toFloat()
                    val h = o.getDouble("h").toFloat()
                    when (o.optString("k")) {
                        "text" -> TextItem(id, x, y, w, h, o.getString("text"), o.getDouble("s").toFloat(), o.getInt("c"))
                        "image" -> ImageItem(id, x, y, w, h, o.getString("f"))
                        else -> null
                    }
                }
            }
        }
        out to itemsOut
    }.getOrDefault(emptyMap<Int, List<InkStroke>>() to emptyMap())

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
        val isEmulator: Boolean = android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.PRODUCT.contains("sdk")

        /** One PDF point in centimetres: tells brushes the real size of a stroke unit. */
        const val PT_CM = 2.54f / 72f

        /** Saving outlives the reader screen, so closing a PDF never loses the last strokes. */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Decodes an image file, shrunk so its longest side is at most [maxSide] pixels. */
        fun decode(file: File, maxSide: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
