package app.margin.ui

import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.margin.pdf.PdfDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the PDF as one continuous column of pages.
 * One finger scrolls (with fling), two fingers pinch-zoom, double-tap toggles zoom.
 * Each page shows a full-page image; when zoomed in further than that image's resolution,
 * the visible part is re-rendered at full sharpness once you stop moving.
 */
@Composable
fun DocumentViewer(doc: PdfDoc, state: ViewerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val cache = remember(doc) { PageCache() }
    DisposableEffect(cache) { onDispose { cache.clear() } }

    Box(
        modifier
            .fillMaxSize()
            .background(Margin.colors.desk)
            .clipToBounds()
            .onSizeChanged { state.onViewport(it) }
            .pointerInput(state) {
                detectTapGestures(onDoubleTap = { p ->
                    scope.launch { state.animateZoomTo(if (state.zoom < 1.5f) 2.2f else 1f, p) }
                })
            }
            .pointerInput(state) { viewerGestures(state, scope) },
    ) {
        val range by remember(state) { derivedStateOf { state.visibleRange() } }
        for (i in range) {
            key(i) { PageView(doc, state, cache, i) }
        }
    }
}

private class Patch(val image: ImageBitmap, val rectPt: RectF)

private class PageCache {
    private val lru = object : LruCache<Int, ImageBitmap>(192 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }

    operator fun get(i: Int): ImageBitmap? = lru.get(i)
    operator fun set(i: Int, b: ImageBitmap) {
        lru.put(i, b)
    }

    fun clear() = lru.evictAll()
}

@Composable
private fun PageView(doc: PdfDoc, state: ViewerState, cache: PageCache, index: Int) {
    val size = state.pageSizes[index]

    // Full-page image: as wide as the page appears on screen (after zooming stops), capped at the screen width.
    val targetWidth by remember(index) {
        derivedStateOf {
            val w = size.width * state.scaleAt(state.settledZoom)
            min(w, state.viewport.width.toFloat()).roundToInt().coerceAtLeast(64)
        }
    }
    var base by remember(index) { mutableStateOf(cache[index]) }
    val fade = remember(index) { Animatable(if (base != null) 1f else 0f) }

    LaunchedEffect(index, targetWidth) {
        val have = base
        if (have != null && have.width >= targetWidth * 0.92f && have.width <= targetWidth * 1.6f) return@LaunchedEffect
        val bitmap = doc.render(index, targetWidth / size.width) ?: return@LaunchedEffect
        val image = bitmap.asImageBitmap()
        cache[index] = image
        base = image
        if (fade.value < 1f) fade.animateTo(1f, tween(180, easing = EaseOutSmooth))
    }

    // Sharp patch of the visible area when zoomed past the full-page image's resolution.
    var patch by remember(index) { mutableStateOf<Patch?>(null) }
    LaunchedEffect(index, state.settleTick) {
        val s = state.scaleAt(state.settledZoom)
        val pw = size.width * s
        val ph = size.height * s
        if (pw <= targetWidth * 1.05f) {
            patch = null
            return@LaunchedEffect
        }
        val left = state.pageLeft(index, s) - state.scrollX
        val top = state.pageTop(index, s) - state.scrollY
        val visible = Rect(
            max(0f, -left).toInt(), max(0f, -top).toInt(),
            min(pw, state.viewport.width - left).toInt(), min(ph, state.viewport.height - top).toInt(),
        )
        if (visible.isEmpty) return@LaunchedEffect
        delay(50) // a quick follow-up gesture cancels this render
        val bitmap = doc.render(index, s, visible) ?: return@LaunchedEffect
        patch = Patch(
            bitmap.asImageBitmap(),
            RectF(visible.left / s, visible.top / s, visible.right / s, visible.bottom / s),
        )
    }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (state.pageLeft(index) - state.scrollX).roundToInt(),
                    (state.pageTop(index) - state.scrollY).roundToInt(),
                )
            }
            .layout { measurable, _ ->
                val w = state.pageWidth(index).roundToInt().coerceAtLeast(1)
                val h = state.pageHeight(index).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .shadow(2.dp, RectangleShape, clip = false)
            .drawBehind {
                drawRect(Color.White)
                val dst = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                base?.let {
                    drawImage(it, dstSize = dst, alpha = fade.value, filterQuality = FilterQuality.Medium)
                }
                patch?.let { p ->
                    val k = this.size.width / size.width
                    drawImage(
                        p.image,
                        dstOffset = IntOffset((p.rectPt.left * k).roundToInt(), (p.rectPt.top * k).roundToInt()),
                        dstSize = IntSize((p.rectPt.width() * k).roundToInt(), (p.rectPt.height() * k).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            },
    )
}

private suspend fun PointerInputScope.viewerGestures(state: ViewerState, scope: CoroutineScope) {
    val decay = splineBasedDecay<Float>(this)
    var fling: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fling?.cancel()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var pinched = false
        var pastSlop = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) pinched = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (!pastSlop) {
                travel += pan
                pastSlop = travel.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.01f
            }
            if (pastSlop) {
                if (zoom != 1f) {
                    val c = event.calculateCentroid(useCurrent = true)
                    if (c != Offset.Unspecified) state.zoomBy(zoom, c)
                }
                state.scrollBy(-pan.x, -pan.y)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (pressed == 1) {
                event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
            } else {
                tracker.resetTracking()
            }
        } while (event.changes.any { it.pressed })

        val v = tracker.calculateVelocity()
        val speed = hypot(v.x, v.y)
        if (pastSlop && !pinched && speed > 250f) {
            val dx = -v.x / speed
            val dy = -v.y / speed
            fling = scope.launch {
                var last = 0f
                AnimationState(0f, speed).animateDecay(decay) {
                    val step = value - last
                    last = value
                    if (!state.scrollBy(dx * step, dy * step)) cancelAnimation()
                }
                state.settle()
            }
        } else {
            state.settle()
        }
    }
}
