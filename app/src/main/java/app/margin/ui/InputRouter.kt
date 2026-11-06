package app.margin.ui

import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.margin.BuildConfig
import app.margin.ink.Bounds
import app.margin.ink.EraserMode
import app.margin.ink.InkController
import app.margin.ink.PenStyle
import app.margin.ink.SelTransform
import app.margin.ink.ShapeSnap
import app.margin.ink.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * All touches on the document come here as raw MotionEvents:
 *  - the S Pen writes, erases (eraser tool, or while its side button is held) or lassoes;
 *    holding it still at the end of a stroke snaps the stroke into a clean line or shape;
 *  - a selection can be moved (drag inside it) or resized (drag its corner) with pen or finger;
 *  - fingers scroll and zoom: one finger scrolls, two fingers pinch;
 *    with the zoom locked, only two fingers scroll and nothing zooms;
 *  - palm rejection: fingers are ignored while the pen touches or hovers (and shortly after),
 *    and very large contacts (a palm) never count.
 */
class InputRouter(
    private val state: ViewerState,
    private val ink: InkController,
    private val strokes: InProgressStrokesView,
    private val host: View,
    private val scope: CoroutineScope,
    private val density: Float,
    /** Called on every new touch; returns true when it used the touch (e.g. to close a panel). */
    private val onDownAnywhere: () -> Boolean,
) {
    /** Where the pen is (hovering or erasing), for the eraser and pen cursors. Null when away. */
    var penAt by mutableStateOf<Offset?>(null)
        private set

    /** The lasso being drawn, in screen pixels; [lassoVersion] changes as it grows. */
    val lasso = ArrayList<Offset>()
    var lassoVersion by mutableIntStateOf(0)
        private set

    /** True while the selection is being moved or resized (its action bar hides meanwhile). */
    var dragging by mutableStateOf(false)
        private set

    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(host.context).scaledMaximumFlingVelocity.toFloat()
    private val decay = splineBasedDecay<Float>(Density(density))

    /** When the pen was last touching or hovering (device uptime). Starts as "never". */
    private var lastPenAt = Long.MIN_VALUE / 4
    private var swallow = false

    init {
        strokes.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.forEach { (id, stroke) ->
                    val p = pending.remove(id) ?: return@forEach
                    val pts = toPagePoints(stroke, p.viewToPage)
                    log("finished page=${p.page} points=${pts.size / 4}")
                    if (pts.size >= 4) ink.addFinished(p.page, p.style, pts)
                }
                // Our page layer draws them from the next frame on; then the live layer can let go.
                this@InputRouter.strokes.postOnAnimation { this@InputRouter.strokes.removeFinishedStrokes(strokes.keys) }
            }
        })
    }

    /** Pen seen hovering (from Compose, which gets hover events). */
    fun onPenHover(at: Offset?) {
        lastPenAt = SystemClock.uptimeMillis()
        if (penPointer < 0) penAt = at
    }

    fun onTouch(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            swallow = onDownAnywhere()
            if (swallow) return true
        }
        if (swallow) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallow = false
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                log("down tool=${ev.getToolType(i)} major=${ev.getTouchMajor(i)} pen=$penPointer")
                if (isPen(ev, i)) penDown(ev, i) else fingerDown(ev, i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penPointer >= 0) penMove(ev)
                if (fingerDrag >= 0) fingerDragMove(ev) else if (nav.isNotEmpty()) fingersMove(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                when (id) {
                    penPointer -> penUp(ev)
                    fingerDrag -> endDrag()
                    else -> fingerUp(ev, id, last = action == MotionEvent.ACTION_UP)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                log("cancel event")
                penCancel(ev)
                if (fingerDrag >= 0) endDrag()
                endNavigation(fling = false)
            }
        }
        return true
    }

    private fun isPen(ev: MotionEvent, i: Int): Boolean {
        val t = ev.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.d("MarginInput", msg)
    }

    /** Runs an Ink call; a failure ends the stroke cleanly (and is logged) instead of crashing. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w("MarginInput", "$what failed", t)
        }
    }

    // ---------------- Pen ----------------
    private enum class Mode { None, Write, Erase, Lasso, Drag }

    private var mode = Mode.None
    private var penPointer = -1
    private var strokeId: InProgressStrokeId? = null
    private var erasePage = -1
    private var lastErase = Offset.Zero
    private var moves = 0

    private class Pending(val page: Int, val style: PenStyle, val viewToPage: Matrix)
    private val pending = HashMap<InProgressStrokeId, Pending>()

    private fun penDown(ev: MotionEvent, i: Int) {
        if (penPointer >= 0) return
        endNavigation(fling = false) // the pen wins over a resting palm
        penPointer = ev.getPointerId(i)
        lastPenAt = SystemClock.uptimeMillis()
        val at = Offset(ev.getX(i), ev.getY(i))
        val eraserButton = ev.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        mode = when {
            eraserButton || ink.tool == Tool.Eraser -> Mode.Erase
            ink.tool == Tool.Lasso -> Mode.Lasso
            else -> Mode.Write
        }
        // While writing, the ink itself shows where the pen is; the cursor is for hovering and erasing.
        penAt = if (mode == Mode.Erase) at else null
        when (mode) {
            Mode.Erase -> {
                ink.clearSelection()
                ink.beginErase()
                erasePage = -1
                eraseTo(at)
            }
            Mode.Lasso -> lassoDown(at)
            else -> writeDown(ev, at)
        }
    }

    private fun writeDown(ev: MotionEvent, at: Offset) {
        ink.clearSelection()
        val style = ink.currentStyle ?: return
        val page = pageAt(at) ?: return
        host.requestUnbufferedDispatch(ev)
        try {
            // The live stroke is drawn in screen pixels, so its brush is scaled to the current zoom.
            val id = strokes.startStroke(ev, penPointer, ink.brush(style, state.scale))
            val toPage = viewToPage(page)
            strokeId = id
            pending[id] = Pending(page, style, toPage)
            live.clear()
            addLive(at, toPage)
            holdAnchor = at
            scheduleHold()
            log("pen down page=$page at=$at scale=${state.scale}")
        } catch (t: Throwable) {
            Log.w("MarginInput", "startStroke failed", t)
        }
    }

    private fun penMove(ev: MotionEvent) {
        val i = ev.findPointerIndex(penPointer)
        if (++moves % 40 == 1) log("pen move #$moves mode=$mode")
        if (i < 0) return
        lastPenAt = SystemClock.uptimeMillis()
        val at = Offset(ev.getX(i), ev.getY(i))
        when (mode) {
            Mode.Erase -> {
                penAt = at
                for (h in 0 until ev.historySize) eraseTo(Offset(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h)))
                eraseTo(at)
            }
            Mode.Write -> {
                val id = strokeId ?: return // snapped into a shape: the rest of this touch is ignored
                safely("addToStroke") { strokes.addToStroke(ev, penPointer, id) }
                val p = pending[id] ?: return
                for (h in 0 until ev.historySize) addLive(Offset(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h)), p.viewToPage)
                addLive(at, p.viewToPage)
                if ((at - holdAnchor).getDistance() > 4f * density) {
                    holdAnchor = at
                    scheduleHold()
                }
            }
            Mode.Lasso -> if (lassoPage >= 0 && (lasso.isEmpty() || (at - lasso.last()).getDistance() > 2f)) {
                lasso += at
                lassoVersion++
            }
            Mode.Drag -> updateDrag(at)
            Mode.None -> Unit
        }
    }

    private fun penUp(ev: MotionEvent) {
        lastPenAt = SystemClock.uptimeMillis()
        log("pen up after $moves moves, mode=$mode")
        moves = 0
        host.removeCallbacks(holdCheck)
        when (mode) {
            Mode.Erase -> ink.endErase()
            Mode.Write -> strokeId?.let { id ->
                safely("finishStroke") {
                    strokes.finishStroke(ev, penPointer, id)
                    strokes.requestHandoff()
                }
            }
            Mode.Lasso -> finishLasso()
            Mode.Drag -> endDrag()
            Mode.None -> Unit
        }
        strokeId = null
        penPointer = -1
        mode = Mode.None
        penAt = null
    }

    private fun penCancel(ev: MotionEvent) {
        if (penPointer < 0) return
        log("pen cancel after $moves moves")
        moves = 0
        host.removeCallbacks(holdCheck)
        when (mode) {
            Mode.Erase -> ink.endErase()
            Mode.Write -> strokeId?.let {
                safely("cancelStroke") { strokes.cancelStroke(it, ev) }
                pending.remove(it)
            }
            Mode.Lasso -> {
                lasso.clear()
                lassoPage = -1
                lassoVersion++
            }
            Mode.Drag -> {
                ink.previewSelection(SelTransform())
                drag = Drag.None
                dragging = false
            }
            Mode.None -> Unit
        }
        strokeId = null
        penPointer = -1
        mode = Mode.None
        penAt = null
    }

    /** The finished live stroke is in screen pixels; store it in page points. */
    private fun toPagePoints(stroke: Stroke, viewToPage: Matrix): FloatArray {
        val inputs = stroke.inputs
        val n = inputs.size
        val out = FloatArray(n * 4)
        val xy = FloatArray(2)
        val tmp = StrokeInput()
        for (i in 0 until n) {
            inputs.populate(i, tmp)
            xy[0] = tmp.x
            xy[1] = tmp.y
            viewToPage.mapPoints(xy)
            out[i * 4] = xy[0]
            out[i * 4 + 1] = xy[1]
            out[i * 4 + 2] = tmp.pressure
            out[i * 4 + 3] = tmp.elapsedTimeMillis.toFloat()
        }
        return out
    }

    private fun eraseTo(at: Offset) {
        val page = pageAt(at) ?: return
        val s = state.scale
        val x = (at.x - (state.pageLeft(page) - state.scrollX)) / s
        val y = (at.y - (state.pageTop(page) - state.scrollY)) / s
        val radiusPx = if (ink.eraserMode == EraserMode.Area) ink.eraserSizeDp * density / 2f else 8f * density
        if (page != erasePage) {
            erasePage = page
            lastErase = Offset(x, y)
        }
        ink.eraseAlong(page, lastErase.x, lastErase.y, x, y, radiusPx / s)
        lastErase = Offset(x, y)
    }

    /** The page under a point in this view, or null over the gaps and the desk. */
    private fun pageAt(at: Offset): Int? {
        val y = state.scrollY + at.y
        val page = state.pageAt(y)
        val top = state.pageTop(page)
        val left = state.pageLeft(page)
        val x = state.scrollX + at.x
        val inside = y >= top && y <= top + state.pageHeight(page) && x >= left && x <= left + state.pageWidth(page)
        return if (inside) page else null
    }

    /** Screen (this view) to page points, for strokes stored in page coordinates. */
    private fun viewToPage(page: Int): Matrix = Matrix().apply {
        setTranslate(-(state.pageLeft(page) - state.scrollX), -(state.pageTop(page) - state.scrollY))
        postScale(1f / state.scale, 1f / state.scale)
    }

    // ---------------- Hold to snap ----------------
    /** The stroke being written, as x, y pairs in page points (for shape snapping). */
    private val live = ArrayList<Float>()
    private val tmpXY = FloatArray(2)
    private var holdAnchor = Offset.Zero
    private val holdCheck = Runnable { trySnap() }

    private fun addLive(at: Offset, toPage: Matrix) {
        tmpXY[0] = at.x
        tmpXY[1] = at.y
        toPage.mapPoints(tmpXY)
        live += tmpXY[0]
        live += tmpXY[1]
    }

    private fun scheduleHold() {
        host.removeCallbacks(holdCheck)
        host.postDelayed(holdCheck, HOLD_MS)
    }

    /** The pen has been still for a moment: if the stroke looks like a shape, make it a clean one. */
    private fun trySnap() {
        if (mode != Mode.Write) return
        val id = strokeId ?: return
        val p = pending[id] ?: return
        var length = 0f
        for (k in 2 until live.size step 2) length += hypot(live[k] - live[k - 2], live[k + 1] - live[k - 1])
        if (length * state.scale < 40f * density) return // a dot or a comma, not a shape
        val shape = ShapeSnap.snap(live.toFloatArray()) ?: return
        safely("cancelStroke") { strokes.cancelStroke(id) }
        pending.remove(id)
        strokeId = null
        val n = shape.size / 2
        val pts = FloatArray(n * 4)
        for (k in 0 until n) {
            pts[4 * k] = shape[2 * k]
            pts[4 * k + 1] = shape[2 * k + 1]
            pts[4 * k + 2] = 0.6f
            pts[4 * k + 3] = k * 4f
        }
        ink.addFinished(p.page, p.style, pts)
        host.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
        log("snapped to a shape with $n points")
    }

    // ---------------- Lasso and selection ----------------
    private enum class Drag { None, Move, Resize }

    private var lassoPage = -1
    private var drag = Drag.None
    private var dragStart = Offset.Zero
    private var fingerDrag = -1 // pointer id of a finger moving the selection

    /** The selection's box on screen (with the live move / resize applied), or null. */
    fun selectionOnScreen(): Bounds? {
        val sel = ink.selection ?: return null
        val t = ink.selTransform
        val s = state.scale
        val ox = state.pageLeft(sel.page) - state.scrollX
        val oy = state.pageTop(sel.page) - state.scrollY
        val b = sel.bounds
        val pad = 6f * density
        return Bounds(
            ox + (b.left + t.dx) * s - pad,
            oy + (b.top + t.dy) * s - pad,
            ox + (b.left + b.width * t.scale + t.dx) * s + pad,
            oy + (b.top + b.height * t.scale + t.dy) * s + pad,
        )
    }

    private fun hitSelection(at: Offset): Drag {
        val box = selectionOnScreen() ?: return Drag.None
        if (hypot(at.x - box.right, at.y - box.bottom) < 28f * density) return Drag.Resize
        return if (box.contains(at.x, at.y)) Drag.Move else Drag.None
    }

    private fun lassoDown(at: Offset) {
        val hit = hitSelection(at)
        if (hit != Drag.None) {
            mode = Mode.Drag
            startDrag(hit, at)
            return
        }
        ink.clearSelection()
        lassoPage = pageAt(at) ?: -1
        lasso.clear()
        if (lassoPage >= 0) lasso += at
        lassoVersion++
    }

    private fun finishLasso() {
        val page = lassoPage
        if (page >= 0 && lasso.size >= 3) {
            val poly = FloatArray(lasso.size * 2)
            lasso.forEachIndexed { k, o ->
                poly[2 * k] = o.x
                poly[2 * k + 1] = o.y
            }
            viewToPage(page).mapPoints(poly)
            val picked = ink.selectInLasso(page, poly)
            log("lasso on page $page: ${if (picked) ink.selection?.ids?.size else 0} strokes")
        }
        lasso.clear()
        lassoPage = -1
        lassoVersion++
    }

    private fun startDrag(kind: Drag, at: Offset) {
        drag = kind
        dragStart = at
        dragging = true
    }

    private fun updateDrag(at: Offset) {
        val sel = ink.selection ?: return
        val s = state.scale
        val dx = (at.x - dragStart.x) / s
        val dy = (at.y - dragStart.y) / s
        when (drag) {
            Drag.Move -> ink.previewSelection(SelTransform(dx, dy, 1f))
            Drag.Resize -> {
                val w = sel.bounds.width.coerceAtLeast(1f)
                val h = sel.bounds.height.coerceAtLeast(1f)
                // One scale for both sides, keeping the dragged corner under the pen (along the diagonal).
                val k = (((w + dx) * w + (h + dy) * h) / (w * w + h * h)).coerceIn(0.2f, 8f)
                ink.previewSelection(SelTransform(0f, 0f, k))
            }
            Drag.None -> Unit
        }
    }

    private fun endDrag() {
        if (drag != Drag.None) ink.applySelectionTransform()
        drag = Drag.None
        fingerDrag = -1
        dragging = false
    }

    private fun fingerDragMove(ev: MotionEvent) {
        val i = ev.findPointerIndex(fingerDrag)
        if (i >= 0) updateDrag(Offset(ev.getX(i), ev.getY(i)))
    }

    // ---------------- Fingers ----------------
    private val nav = LinkedHashMap<Int, Offset>()
    private var centroid = Offset.Zero
    private var span = 0f
    private var travel = 0f
    private var panning = false
    private var pinched = false
    private var tapStart = 0L
    private var tapAt = Offset.Zero
    private var lastTapUp = 0L
    private var lastTapAt = Offset.Zero
    private var fling: Job? = null
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()

    private fun fingerDown(ev: MotionEvent, i: Int) {
        val now = ev.eventTime
        val palm = ev.getTouchMajor(i) > 56f * density
        // Palm rejection: ignore fingers while the pen is touching, hovering or just lifted.
        if (penPointer >= 0 || SystemClock.uptimeMillis() - lastPenAt < 400 || palm) return
        val at = Offset(ev.getX(i), ev.getY(i))
        if (fingerDrag >= 0) return // one finger is moving the selection; others wait
        if (nav.isEmpty()) {
            val hit = hitSelection(at)
            if (hit != Drag.None) {
                fling?.cancel()
                fingerDrag = ev.getPointerId(i)
                startDrag(hit, at)
                return
            }
        }
        fling?.cancel()
        if (nav.isEmpty()) {
            tapStart = now
            tapAt = at
            travel = 0f
            panning = false
            pinched = false
            samples.clear()
        }
        nav[ev.getPointerId(i)] = at
        rebase()
    }

    private fun fingersMove(ev: MotionEvent) {
        for (id in nav.keys.toList()) {
            val i = ev.findPointerIndex(id)
            if (i >= 0) nav[id] = Offset(ev.getX(i), ev.getY(i))
        }
        val c = centroidOf()
        val d = c - centroid
        val locked = state.zoomLocked
        val twoPlus = nav.size >= 2
        val newSpan = if (twoPlus) spanOf(c) else 0f
        // A pinch barely moves the fingers' midpoint, so the spread counts as movement too.
        travel += d.getDistance() + if (twoPlus && span > 0f) abs(newSpan - span) else 0f
        if (!panning && travel > slop) panning = true
        if (panning && (!locked || twoPlus)) {
            if (!locked && twoPlus && span > 0f && newSpan > 0f) {
                state.zoomBy(newSpan / span, c)
                span = newSpan
                pinched = true
            }
            state.scrollBy(-d.x, -d.y)
            samples.addLast(Triple(ev.eventTime, c.x, c.y))
            while (samples.size > 2 && ev.eventTime - samples.first().first > 100) samples.removeFirst()
        }
        centroid = c
    }

    private fun fingerUp(ev: MotionEvent, id: Int, last: Boolean) {
        if (nav.remove(id) == null) return
        if (nav.isNotEmpty() && !last) {
            rebase()
            return
        }
        val now = ev.eventTime
        if (!panning && now - tapStart < 250) {
            onTap(tapAt, now)
            nav.clear()
            return
        }
        endNavigation(fling = true)
    }

    private fun onTap(at: Offset, now: Long) {
        if (ink.selection != null) {
            ink.clearSelection() // a tap outside the selection lets it go
            return
        }
        val isDouble = now - lastTapUp < 300 && (at - lastTapAt).getDistance() < 48f * density
        if (isDouble && !state.zoomLocked) {
            lastTapUp = 0L
            scope.launch { state.animateZoomTo(if (state.zoom < 1.5f) 2.2f else 1f, at) }
        } else {
            lastTapUp = now
            lastTapAt = at
        }
    }

    private fun endNavigation(fling: Boolean) {
        if (nav.isEmpty() && !panning) return
        nav.clear()
        val (vx, vy) = velocity()
        val speed = min(hypot(vx, vy), maxFling)
        val canFling = fling && panning && (!pinched || state.zoomLocked) && speed > 250f
        panning = false
        if (!canFling) {
            state.settle()
            return
        }
        val norm = hypot(vx, vy)
        val dx = -vx / norm
        val dy = -vy / norm
        this.fling = scope.launch {
            var lastV = 0f
            AnimationState(0f, speed).animateDecay(decay) {
                val step = value - lastV
                lastV = value
                if (!state.scrollBy(dx * step, dy * step)) cancelAnimation()
            }
            state.settle()
        }
    }

    private fun velocity(): Pair<Float, Float> {
        if (samples.size < 2) return 0f to 0f
        val a = samples.first()
        val b = samples.last()
        val dt = (b.first - a.first).coerceAtLeast(1L) / 1000f
        return (b.second - a.second) / dt to (b.third - a.third) / dt
    }

    private fun rebase() {
        centroid = centroidOf()
        span = spanOf(centroid)
        samples.clear()
    }

    private fun centroidOf(): Offset {
        var x = 0f
        var y = 0f
        nav.values.forEach { x += it.x; y += it.y }
        return if (nav.isEmpty()) Offset.Zero else Offset(x / nav.size, y / nav.size)
    }

    private fun spanOf(c: Offset): Float =
        if (nav.size < 2) 0f else nav.values.sumOf { (it - c).getDistance().toDouble() }.toFloat() / nav.size

    companion object {
        /** How long the pen must rest at the end of a stroke to snap it into a shape. */
        const val HOLD_MS = 600L
    }
}
