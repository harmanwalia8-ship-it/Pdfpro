package app.margin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import app.margin.data.Library
import app.margin.ink.EraserMode
import app.margin.ink.InkController
import app.margin.ink.Tool
import app.margin.pdf.PasswordProtectedException
import app.margin.pdf.PdfDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

@Composable
fun ReaderScreen(docId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Margin.colors
    val library = remember { Library.get(ctx) }
    val entry = remember(docId) { library.get(docId) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    var pdf by remember(docId) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(docId) { mutableStateOf<String?>(null) }
    val ink = remember(docId) { InkController(ctx, docId) }

    LaunchedEffect(docId) {
        if (entry == null) {
            error = "This file is no longer in your library."
            return@LaunchedEffect
        }
        library.markOpened(docId)
        ink.load()
        try {
            pdf = PdfDoc.open(ctx, Uri.parse(entry.uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordProtectedException) {
            error = "This PDF is password-protected. Opening protected PDFs isn't supported yet."
        } catch (e: SecurityException) {
            error = "Margin no longer has access to this file. Open it again from Files."
        } catch (e: FileNotFoundException) {
            error = "This file was moved or deleted."
        } catch (e: Exception) {
            error = "Couldn't open this PDF."
        }
    }
    DisposableEffect(pdf) {
        val doc = pdf
        onDispose { doc?.close() }
    }
    DisposableEffect(ink) { onDispose { ink.flush() } }

    val state = remember(pdf) {
        pdf?.let { ViewerState(it.pageSizes, density, entry?.lastPage ?: 0, entry?.zoom ?: 1f, entry?.zoomLocked ?: false) }
    }

    // First open: remember the page count and save a cover thumbnail for the library.
    LaunchedEffect(pdf) {
        val doc = pdf ?: return@LaunchedEffect
        library.update(docId) { it.copy(pageCount = doc.pageCount) }
        val thumb = library.thumbFile(docId)
        if (!thumb.exists()) {
            val bm = doc.thumbnail(0, 360) ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { thumb.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
    // Remember where you were, how far you'd zoomed, and whether the zoom was locked.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        snapshotFlow { Triple(s.currentPage, s.settledZoom, s.zoomLocked) }.distinctUntilChanged().collectLatest { (p, z, l) ->
            delay(500)
            library.update(docId) { it.copy(lastPage = p, zoom = z, zoomLocked = l) }
        }
    }

    var showPages by rememberSaveable(docId) { mutableStateOf(false) }
    var panel by remember { mutableStateOf<TrayPanel?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2200)
            hint = null
        }
    }
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = showPages && panel == null) { showPages = false }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ReaderTopBar(
            title = entry?.title.orEmpty(),
            state = state,
            pagesOpen = showPages,
            onBack = onBack,
            onPages = { showPages = !showPages },
            onToggleLock = {
                state?.let { s ->
                    s.zoomLocked = !s.zoomLocked
                    hint = if (s.zoomLocked) "Zoom locked · scroll with two fingers" else "Zoom unlocked"
                }
            },
            onShare = { entry?.let { share(ctx, Uri.parse(it.uri), it.title) } },
        )
        Box(Modifier.fillMaxWidth().weight(1f).background(c.desk)) {
            val doc = pdf
            val s = state
            // The pages slide right to make room for the panel (a pure move, no re-render).
            val shift by animateDpAsState(if (showPages) 116.dp else 0.dp, tween(300, easing = EaseOutSmooth), label = "shift")
            when {
                error != null -> Message(error.orEmpty(), onBack, Modifier.align(Alignment.Center))
                doc == null || s == null -> Loading(Modifier.align(Alignment.Center))
                else -> {
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift.toPx() }) {
                        DocumentViewer(doc, s, ink)
                        key(s) {
                            InkSurface(s, ink, onDownAnywhere = {
                                if (panel != null) {
                                    panel = null
                                    true
                                } else false
                            })
                        }
                    }
                    PagePill(s, Modifier.align(Alignment.TopCenter))
                    PenTray(
                        ink = ink,
                        panel = panel,
                        onPanel = { panel = it },
                        onClearPage = {
                            ink.clearPage(s.currentPage)
                            panel = null
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = hint != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.92f),
                exit = fadeOut(tween(300)),
            ) {
                Text(
                    hint.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color(0xE61C1B1A), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showPages && doc != null && s != null,
                enter = slideInHorizontally(tween(300, easing = EaseOutSmooth)) { -it } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(250, easing = EaseOutSmooth)) { -it } + fadeOut(tween(200)),
            ) {
                if (doc != null && s != null) {
                    PagesPanel(doc, s, onPick = { i -> scope.launch { s.animateToPage(i) } }, onClose = { showPages = false })
                }
            }
        }
    }
}

/**
 * The live ink layer (Jetpack Ink draws the stroke under the pen with very low latency),
 * which also receives every touch on the document and hands it to [InputRouter].
 * Draws the eraser ring / pen dot where the S Pen hovers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InkSurface(state: ViewerState, ink: InkController, onDownAnywhere: () -> Boolean) {
    val scope = rememberCoroutineScope()
    val host = LocalView.current
    val density = LocalDensity.current.density
    val downAnywhere by rememberUpdatedState(onDownAnywhere)
    var router by remember { mutableStateOf<InputRouter?>(null) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val pen = e.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser } ?: continue
                        if (!pen.pressed) router?.onPenHover(if (e.type == PointerEventType.Exit) null else pen.position)
                    }
                }
            }
            // Every touch on the document (pen, fingers, palm) goes to the router as a raw MotionEvent.
            .pointerInteropFilter { ev -> router?.onTouch(ev) ?: false },
    ) {
        AndroidView(
            factory = { context ->
                InProgressStrokesView(context).apply {
                    textureBitmapStore = ink.textures
                    eagerInit()
                    router = InputRouter(state, ink, this, host, scope, density) { downAnywhere() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val accent = Margin.colors.accent
        Canvas(Modifier.fillMaxSize()) {
            val at = router?.penAt ?: return@Canvas
            if (ink.tool == Tool.Eraser) {
                val r = if (ink.eraserMode == EraserMode.Area) ink.eraserSizeDp.dp.toPx() / 2f else 8.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.35f), r, at)
                drawCircle(Color(0xFF57524D), r, at, style = Stroke(1.5.dp.toPx()))
            } else {
                val style = ink.currentStyle ?: return@Canvas
                val r = (style.size * state.scale / 2f).coerceAtLeast(2.5.dp.toPx())
                drawCircle(Color(style.color).copy(alpha = 0.55f), r, at)
                drawCircle(accent.copy(alpha = 0.0f), r, at)
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    state: ViewerState?,
    pagesOpen: Boolean,
    onBack: () -> Unit,
    onPages: () -> Unit,
    onToggleLock: () -> Unit,
    onShare: () -> Unit,
) {
    val c = Margin.colors
    val page by remember(state) { derivedStateOf { state?.currentPage ?: 0 } }
    val locked = state?.zoomLocked == true
    Column(Modifier.background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconBtn(MIcons.Back, "Back to library", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state == null) "Opening…" else "Page ${page + 1} of ${state.pageCount}",
                    color = c.textMuted, fontSize = 12.sp,
                )
            }
            IconBtn(
                if (locked) MIcons.Lock else MIcons.Unlock,
                if (locked) "Unlock zoom" else "Lock zoom",
                onToggleLock, active = locked, enabled = state != null,
            )
            IconBtn(MIcons.Pages, if (pagesOpen) "Hide pages" else "Show pages", onPages, active = pagesOpen, enabled = state != null)
            IconBtn(MIcons.Share, "Share", onShare)
        }
        HorizontalDivider(thickness = 1.dp, color = c.line)
    }
}

/** "3 / 24": shows while you scroll, fades away when you stop. */
@Composable
private fun PagePill(state: ViewerState, modifier: Modifier = Modifier) {
    val page by remember(state) { derivedStateOf { state.currentPage } }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.scrollY to state.zoom }.collectLatest {
            visible = true
            delay(1200)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = 12.dp),
        enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.92f),
        exit = fadeOut(tween(350)),
    ) {
        Text(
            "${page + 1} / ${state.pageCount}",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Color(0xE61C1B1A), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun PagesPanel(doc: PdfDoc, state: ViewerState, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val c = Margin.colors
    val current by remember(state) { derivedStateOf { state.currentPage } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val thumbs = remember(doc) {
        object : LruCache<Int, ImageBitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
        }
    }
    LaunchedEffect(current) {
        if (list.layoutInfo.visibleItemsInfo.none { it.index == current }) {
            list.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }
    Column(Modifier.width(232.dp).fillMaxHeight().shadow(12.dp).background(c.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(" · ${doc.pageCount}", color = c.textMuted, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            IconBtn(MIcons.Close, "Close pages", onClose)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(doc.pageCount, key = { it }) { i ->
                PageThumb(doc, i, thumbs, selected = i == current, onClick = { onPick(i) })
            }
        }
    }
}

@Composable
private fun PageThumb(doc: PdfDoc, index: Int, cache: LruCache<Int, ImageBitmap>, selected: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    val size = doc.pageSizes[index]
    var image by remember(index) { mutableStateOf(cache.get(index)) }
    LaunchedEffect(index) {
        if (image == null) {
            doc.thumbnail(index, 240)?.asImageBitmap()?.let {
                cache.put(index, it)
                image = it
            }
        }
    }
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "ring")
    val shape = RoundedCornerShape(4.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .width(120.dp)
                .aspectRatio(size.width / size.height)
                .shadow(2.dp, shape)
                .clip(shape)
                .background(Color.White)
                .border(2.5.dp, c.accent.copy(alpha = ring), shape)
                .clickable(onClickLabel = "Go to page ${index + 1}", onClick = onClick),
        ) {
            image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
        Text(
            "${index + 1}",
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) c.accent else c.textMuted,
        )
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250) // no spinner flash for quick opens
        show = true
    }
    if (show) CircularProgressIndicator(modifier.size(36.dp), color = Margin.colors.accent, strokeWidth = 2.5.dp)
}

@Composable
private fun Message(text: String, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(32.dp).width(420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Margin.colors.text, fontSize = 16.sp, textAlign = TextAlign.Center)
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back to library") }
    }
}

private fun share(ctx: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, title)) }
}
