package app.margin.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.margin.ink.InkController
import app.margin.ink.InkStroke
import app.margin.ink.PenType
import app.margin.pdf.PdfDoc
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the PDF as one continuous column of pages, with each page's ink on top.
 * Input (pen and fingers) is handled by [InputRouter]; this only draws.
 *
 * Each page has a full-page image; when zoomed in past that image's resolution, the
 * visible part is re-rendered at full sharpness once you stop moving. Every page draws
 * straight onto the screen-sized viewer, and only the part that's on screen.
 */
@Composable
fun DocumentViewer(doc: PdfDoc, state: ViewerState, ink: InkController?, modifier: Modifier = Modifier) {
    val cache = remember(doc) { PageCache() }
    DisposableEffect(cache) { onDispose { cache.clear() } }
    val density = LocalDensity.current.density
    val pagePaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            setShadowLayer(5f * density, 0f, 1f * density, 0x2E000000)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Margin.colors.desk)
            .clipToBounds()
            .onSizeChanged { state.onViewport(it) },
    ) {
        val range by remember(state) { derivedStateOf { state.visibleRange() } }
        for (i in range) {
            key(i) { PageView(doc, state, ink, cache, i, pagePaint) }
        }
    }
}

/** Largest side of any page image, well inside GPU texture limits (8192 on Adreno 642L). */
private const val MAX_PAGE_IMAGE_PX = 4096f

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
private fun PageView(doc: PdfDoc, state: ViewerState, ink: InkController?, cache: PageCache, index: Int, pagePaint: Paint) {
    val pageSize = state.pageSizes[index]

    // Full-page image: as wide as the page appears on screen (after zooming stops), capped at the screen width.
    val targetWidth by remember(index) {
        derivedStateOf {
            val w = pageSize.width * state.scaleAt(state.settledZoom)
            // A GPU can't draw an image taller or wider than its texture limit (8192 px on your
            // tablet), so very tall pages get a smaller full-page image; the sharp patch covers zoom.
            val maxForHeight = MAX_PAGE_IMAGE_PX * pageSize.width / pageSize.height
            minOf(w, state.viewport.width.toFloat(), maxForHeight, MAX_PAGE_IMAGE_PX).roundToInt().coerceAtLeast(16)
        }
    }
    var base by remember(index) { mutableStateOf(cache[index]) }
    val fade = remember(index) { Animatable(if (base != null) 1f else 0f) }
    val imagePaint = remember(index) { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val inkMatrix = remember(index) { Matrix() }
    val selMatrix = remember(index) { Matrix() }

    LaunchedEffect(index, targetWidth) {
        val have = base
        if (have != null && have.width >= targetWidth * 0.92f && have.width <= targetWidth * 1.6f) return@LaunchedEffect
        val bitmap = doc.render(index, targetWidth / pageSize.width) ?: return@LaunchedEffect
        val image = bitmap.asImageBitmap()
        cache[index] = image
        base = image
        if (fade.value < 1f) fade.animateTo(1f, tween(180, easing = EaseOutSmooth))
    }

    // Sharp patch of the visible area when zoomed past the full-page image's resolution.
    var patch by remember(index) { mutableStateOf<Patch?>(null) }
    LaunchedEffect(index, state.settleTick) {
        val s = state.scaleAt(state.settledZoom)
        val pw = pageSize.width * s
        val ph = pageSize.height * s
        if (pw <= targetWidth * 1.05f) {
            patch = null
            return@LaunchedEffect
        }
        val left = state.pageLeft(index, s) - state.scrollX
        val top = state.pageTop(index, s) - state.scrollY
        val visible = Rect(
            floor(max(0f, -left)).toInt(), floor(max(0f, -top)).toInt(),
            ceil(min(pw, state.viewport.width - left)).toInt(), ceil(min(ph, state.viewport.height - top)).toInt(),
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
            .fillMaxSize()
            .drawBehind {
                val s = state.scale
                val left = state.pageLeft(index, s) - state.scrollX
                val top = state.pageTop(index, s) - state.scrollY
                val w = pageSize.width * s
                val h = pageSize.height * s
                if (left >= size.width || top >= size.height || left + w <= 0f || top + h <= 0f) return@drawBehind

                // White page with a soft shadow (only the on-screen part, so it never gets huge).
                val pad = 16f * density
                drawIntoCanvas {
                    it.nativeCanvas.drawRect(
                        max(left, -pad), max(top, -pad), min(left + w, size.width + pad), min(top + h, size.height + pad),
                        pagePaint,
                    )
                }
                base?.let { drawVisiblePart(it, RectF(left, top, left + w, top + h), fade.value, imagePaint) }
                patch?.let { p ->
                    drawVisiblePart(
                        p.image,
                        RectF(left + p.rectPt.left * s, top + p.rectPt.top * s, left + p.rectPt.right * s, top + p.rectPt.bottom * s),
                        1f,
                        imagePaint,
                    )
                }

                // Ink, stored in page points: scale and move it onto the page, clipped to the paper.
                if (ink != null) {
                    ink.version // redraw when the ink changes
                    val strokes = ink.strokes(index)
                    if (strokes.isNotEmpty()) {
                        inkMatrix.setScale(s, s)
                        inkMatrix.postTranslate(left, top)
                        drawIntoCanvas {
                            val nc = it.nativeCanvas
                            nc.save()
                            nc.clipRect(max(left, 0f), max(top, 0f), min(left + w, size.width), min(top + h, size.height))
                            // Ink's renderer only uses the transform as a quality hint; the canvas
                            // itself must be moved and scaled onto the page.
                            nc.concat(inkMatrix)
                            // A selection being moved or resized is drawn at its live position.
                            val sel = ink.selection?.takeIf { it.page == index }
                            val t = ink.selTransform
                            val moving = sel != null && !t.isIdentity
                            if (sel != null && moving) {
                                selMatrix.setTranslate(-sel.bounds.left, -sel.bounds.top)
                                selMatrix.postScale(t.scale, t.scale)
                                selMatrix.postTranslate(sel.bounds.left + t.dx, sel.bounds.top + t.dy)
                            }
                            fun drawStroke(st: InkStroke) {
                                if (sel != null && moving && st.id in sel.ids) {
                                    nc.save()
                                    nc.concat(selMatrix)
                                    ink.renderer.draw(nc, ink.inkStroke(st), inkMatrix)
                                    nc.restore()
                                } else {
                                    ink.renderer.draw(nc, ink.inkStroke(st), inkMatrix)
                                }
                            }
                            // Highlighter first, so pen ink always sits on top of it.
                            for (st in strokes) if (st.style.type == PenType.Highlighter) drawStroke(st)
                            for (st in strokes) if (st.style.type != PenType.Highlighter) drawStroke(st)
                            nc.restore()
                        }
                    }
                }
            },
    )
}

/**
 * Draws [image] stretched over [dst] (screen px), but only the part that's on screen.
 * Uses float positions so the page glides smoothly instead of snapping pixel by pixel.
 */
private fun DrawScope.drawVisiblePart(image: ImageBitmap, dst: RectF, alpha: Float, paint: Paint) {
    val visL = max(0f, dst.left)
    val visT = max(0f, dst.top)
    val visR = min(size.width, dst.right)
    val visB = min(size.height, dst.bottom)
    if (visR <= visL || visB <= visT || dst.width() <= 0f || dst.height() <= 0f) return
    val sx = image.width / dst.width()
    val sy = image.height / dst.height()
    val srcL = floor((visL - dst.left) * sx).toInt().coerceIn(0, image.width - 1)
    val srcT = floor((visT - dst.top) * sy).toInt().coerceIn(0, image.height - 1)
    val srcR = ceil((visR - dst.left) * sx).toInt().coerceIn(srcL + 1, image.width)
    val srcB = ceil((visB - dst.top) * sy).toInt().coerceIn(srcT + 1, image.height)
    // Map the whole source pixels back to the screen exactly, so the image never shifts while scrolling.
    paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
    drawIntoCanvas {
        it.nativeCanvas.drawBitmap(
            image.asAndroidBitmap(),
            Rect(srcL, srcT, srcR, srcB),
            RectF(dst.left + srcL / sx, dst.top + srcT / sy, dst.left + srcR / sx, dst.top + srcB / sy),
            paint,
        )
    }
}
