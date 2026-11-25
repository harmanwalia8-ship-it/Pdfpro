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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
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
import androidx.core.content.FileProvider
import app.margin.pdf.PasswordProtectedException
import app.margin.pdf.PdfDoc
import app.margin.pdf.PdfStore
import app.margin.pdf.PageTemplate
import app.margin.pdf.pdfFileName
import app.margin.pdf.PageMoves
import app.margin.ink.ImageItem
import app.margin.ink.TextItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    val docs by library.docs.collectAsStateWithLifecycle()
    val live = docs.firstOrNull { it.id == docId } ?: entry
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    var pdf by remember(docId) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(docId) { mutableStateOf<String?>(null) }
    val ink = remember(docId) { InkController(ctx, docId) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        val h = hint ?: return@LaunchedEffect
        delay(if (h.length > 40) 4000 else 2200)
        hint = null
    }

    LaunchedEffect(docId) {
        if (entry == null) {
            error = "This file is no longer in your library."
            return@LaunchedEffect
        }
        library.markOpened(docId)
        ink.load()
        try {
            // Margin reads its own copy (refreshed from the original when it can), so the PDF
            // still opens after the original is deleted.
            val kept = PdfStore.prepare(ctx, docId)
            if (kept.originalMissing) hint = "Can't find the original file · showing Margin's copy"
            val doc = PdfDoc.open(kept.file)
            // e.g. after reinstalling, or in a PDF made from another one: the notes are still in the PDF
            ink.importSaved(doc.savedInk, doc.savedItems) { item, file -> doc.renderSavedImage(item, file) }
            ink.lineFinder = { page, x0, y, x1 -> doc.textLines(page, x0, y, x1) }
            pdf = doc
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
    // ---- Saving your notes into the PDF file itself (Margin's copy; your original file is never changed) ----
    var saveState by remember(docId) { mutableStateOf<PdfStore.Result?>(null) }
    var saving by remember(docId) { mutableStateOf(false) }
    suspend fun saveIntoFile() {
        val doc = pdf ?: return
        if (!ink.pdfDirty || saving) return
        val v = ink.version
        saving = true
        val r = PdfStore.save(ctx, docId, doc, ink.forPdf(), ink.itemsForPdf())
        saving = false
        saveState = r
        if (r == PdfStore.Result.Saved) ink.markWrittenToPdf(v)
    }
    // After 20 quiet seconds...
    LaunchedEffect(ink.version, pdf) {
        if (pdf != null && ink.pdfDirty) {
            delay(20_000)
            saveIntoFile()
        }
    }
    // ...when the app goes to the background...
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, pdf) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) appScope.launch { saveIntoFile() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // ...and when you leave this PDF (then close it).
    DisposableEffect(pdf) {
        val doc = pdf
        onDispose {
            ink.flush()
            appScope.launch {
                if (doc != null && ink.pdfDirty) PdfStore.save(ctx, docId, doc, ink.forPdf(), ink.itemsForPdf())
                doc?.close()
            }
        }
    }

    // Changing pages rebuilds the page layout; [start] carries the view over to it.
    var pagesVersion by remember(docId) { mutableIntStateOf(0) }
    val start = remember(docId) { StartView(entry?.lastPage ?: 0, entry?.zoom ?: 1f, entry?.zoomLocked ?: false) }
    val state = remember(pdf, pagesVersion) {
        pdf?.let { ViewerState(it.pageSizes, density, start.page, start.zoom, start.locked) }
    }
    val search = remember(pdf, pagesVersion) {
        val doc = pdf
        val st = state
        if (doc == null || st == null) null else SearchState(doc, scope) { hit ->
            val b = hit.boxes.firstOrNull()
            scope.launch { st.animateToSpot(hit.page, b?.left ?: 0f, b?.top ?: 0f) }
        }
    }

    suspend fun refreshCover(doc: PdfDoc) {
        val bm = doc.thumbnail(0, 360) ?: return
        withContext(Dispatchers.IO) { library.thumbFile(docId).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        forgetThumb(docId)
    }

    // First open: remember the page count and save a cover thumbnail for the library.
    LaunchedEffect(pdf) {
        val doc = pdf ?: return@LaunchedEffect
        library.update(docId) { it.copy(pageCount = doc.pageCount) }
        if (!library.thumbFile(docId).exists()) refreshCover(doc)
    }
    // Remember where you were, how far you'd zoomed, and whether the zoom was locked.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        snapshotFlow { Triple(s.currentPage, s.settledZoom, s.zoomLocked) }.distinctUntilChanged().collectLatest { (p, z, l) ->
            delay(500)
            library.update(docId) { it.copy(lastPage = p, zoom = z, zoomLocked = l) }
        }
    }
    // A notebook grows as you write: notes on its last page add a fresh page after it, with the same paper.
    LaunchedEffect(ink.version, pdf, state) {
        val doc = pdf ?: return@LaunchedEffect
        val s = state ?: return@LaunchedEffect
        val paper = live?.paper?.let { p -> PageTemplate.entries.firstOrNull { it.name == p } } ?: return@LaunchedEffect
        val last = doc.pageCount - 1
        if (!ink.hasNotes(last)) return@LaunchedEffect
        delay(1200) // after a pause in writing, so the page appears without interrupting you
        if (doc.insertPage(last, paper)) {
            s.updatePages(doc.pageSizes)
            ink.markDirty()
            library.update(docId) { it.copy(pageCount = doc.pageCount) }
        }
    }

    var showPages by rememberSaveable(docId) { mutableStateOf(false) }
    var sideTab by rememberSaveable(docId) { mutableStateOf(SideTab.Pages) }
    var showPageTools by remember(docId) { mutableStateOf(false) }
    var panel by remember { mutableStateOf<TrayPanel?>(null) }
    var textEdit by remember { mutableStateOf<TextEdit?>(null) }

    /**
     * Changes pages in the PDF itself, moves your notes and bookmarks with their pages
     * ([marks] maps an old page number to its new one, or null if it's gone), then saves.
     */
    fun editPages(goTo: Int, edit: suspend (PdfDoc) -> Boolean, moveNotes: () -> Unit, marks: (Int) -> Int?) {
        val doc = pdf ?: return
        val s = state ?: return
        scope.launch {
            if (!edit(doc)) {
                hint = "This file's pages can't be changed"
                return@launch
            }
            moveNotes()
            start.page = goTo.coerceIn(0, doc.pageCount - 1)
            start.zoom = s.zoom
            start.locked = s.zoomLocked
            pagesVersion++
            library.update(docId) { it.copy(pageCount = doc.pageCount, bookmarks = PageMoves.remapBookmarks(it.bookmarks, marks)) }
            saveIntoFile()
            refreshCover(doc)
        }
    }

    // Pictures: picked with Android's photo picker (Margin reads only the one you pick).
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val s = state
        if (uri == null || s == null) return@rememberLauncherForActivityResult
        scope.launch {
            val pic = importPicture(ctx, uri, ink)
            if (pic == null) {
                hint = "Couldn't add that picture"
                return@launch
            }
            // In the middle of the part of the page you're looking at.
            val page = s.currentPage
            val pw = s.pageSizes[page].width
            val ph = s.pageSizes[page].height
            val top = ((s.scrollY - s.pageTop(page)) / s.scale).coerceIn(0f, ph)
            val bottom = ((s.scrollY + s.viewport.height - s.pageTop(page)) / s.scale).coerceIn(top, ph)
            val boxH = ((bottom - top) * 0.6f).coerceAtLeast(40f)
            ink.addImage(page, pic.name, pic.width, pic.height, pw * 0.2f, top + (bottom - top - boxH) / 2, pw * 0.6f, boxH)
            hint = "Drag to move it · drag its corner to resize"
        }
    }

    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = showPages && panel == null) { showPages = false }
    BackHandler(enabled = search?.open == true && !showPages && panel == null) { search?.close() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        val s0 = state
        val currentPage by remember(s0) { derivedStateOf { s0?.currentPage ?: 0 } }
        val bookmarked = live?.bookmarks?.contains(currentPage) == true
        ReaderTopBar(
            title = live?.title.orEmpty(),
            state = state,
            search = search,
            status = when {
                saving -> "Saving…"
                ink.pdfDirty && saveState == PdfStore.Result.ReadOnly -> "Can't save into this file; notes kept in Margin"
                ink.pdfDirty && saveState == PdfStore.Result.Failed -> "Not saved yet"
                ink.pdfDirty -> null
                saveState == PdfStore.Result.Saved -> "Saved"
                else -> null
            },
            pagesOpen = showPages,
            bookmarked = bookmarked,
            onBack = onBack,
            onSearch = { search?.open = true },
            onBookmark = {
                val p = currentPage
                library.update(docId) { d ->
                    d.copy(bookmarks = if (p in d.bookmarks) d.bookmarks - p else (d.bookmarks + p).sorted())
                }
                hint = if (bookmarked) "Bookmark removed" else "Bookmarked page ${p + 1}"
            },
            onPages = { showPages = !showPages },
            onPageTools = { showPageTools = true },
            onToggleLock = {
                state?.let { s ->
                    s.zoomLocked = !s.zoomLocked
                    hint = if (s.zoomLocked) "Zoom locked · scroll with two fingers" else "Zoom unlocked"
                }
            },
            onShare = {
                live?.let { e ->
                    scope.launch {
                        saveIntoFile() // share the file with your latest notes in it
                        share(ctx, PdfStore.copyFile(ctx, docId), e.title)
                    }
                }
            },
        )
        val toolsFor = pdf
        if (showPageTools && live != null && toolsFor != null) {
            PageToolsDialog(
                doc = live,
                pageCount = toolsFor.pageCount,
                source = {
                    saveIntoFile() // the new PDFs get your latest notes and page changes
                    PdfStore.copyFile(ctx, docId)
                },
                onMade = { made ->
                    showPageTools = false
                    hint = if (made.size == 1) "Made a new PDF · it's in your library" else "Made ${made.size} PDFs · they're in your library"
                },
                onDismiss = { showPageTools = false },
            )
        }
        textEdit?.let { t ->
            TextBoxDialog(
                existing = t.existing,
                onDone = { text, size, color ->
                    textEdit = null
                    val existing = t.existing
                    if (existing != null) {
                        ink.updateText(t.page, existing.id, text, size, color)
                    } else {
                        val pw = state?.pageSizes?.getOrNull(t.page)?.width ?: 595f
                        val x = t.x.coerceAtMost(pw - 98f).coerceAtLeast(6f)
                        ink.addText(t.page, x, t.y - size * 0.6f, (pw - x - 18f).coerceIn(80f, 280f), text, size, color)
                    }
                },
                onDelete = t.existing?.let { e -> { textEdit = null; ink.updateText(t.page, e.id, "", e.size, e.color) } },
                onDismiss = { textEdit = null },
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(c.desk)) {
            val doc = pdf
            val s = state
            // The pages slide right to make room for the panel (a pure move, no re-render).
            val shift by animateDpAsState(if (showPages) SidePanelWidth / 2 else 0.dp, tween(300, easing = EaseOutSmooth), label = "shift")
            when {
                error != null -> Message(error.orEmpty(), onBack, Modifier.align(Alignment.Center))
                doc == null || s == null -> Loading(Modifier.align(Alignment.Center))
                else -> {
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift.toPx() }) {
                        key(s) {
                            DocumentViewer(doc, s, ink, search)
                            var router by remember { mutableStateOf<InputRouter?>(null) }
                            InkSurface(
                                s, ink,
                                onRouter = { router = it },
                                onDownAnywhere = {
                                    if (panel != null) {
                                        panel = null
                                        true
                                    } else false
                                },
                                onTextTap = { page, x, y, existing -> textEdit = TextEdit(page, x, y, existing) },
                            )
                            router?.let { r ->
                                SelectionBar(r, ink, onEditText = { page, item -> textEdit = TextEdit(page, item.x, item.y, item) })
                            }
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
                        onInsertImage = {
                            runCatching { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
                    SidePanel(
                        doc, s, ink, pagesVersion,
                        bookmarks = live?.bookmarks.orEmpty(),
                        tab = sideTab,
                        onTab = { sideTab = it },
                        onClose = { showPages = false },
                        actions = PageActions(
                            onPick = { i -> scope.launch { s.animateToPage(i) } },
                            onAdd = { after, template ->
                                editPages(after + 1, { it.insertPage(after, template) }, { ink.onPageInserted(after + 1) }) {
                                    PageMoves.afterInsert(it, after + 1)
                                }
                            },
                            onDelete = { i ->
                                val goTo = if (i >= doc.pageCount - 1) i - 1 else i
                                editPages(goTo, { it.deletePage(i) }, { ink.onPageDeleted(i) }) { PageMoves.afterDelete(it, i) }
                            },
                            onDuplicate = { i ->
                                editPages(i + 1, { it.duplicatePage(i) }, { ink.onPageDuplicated(i) }) { PageMoves.afterInsert(it, i + 1) }
                            },
                            onTurn = { i ->
                                val oldHeight = doc.pageSizes[i].height
                                editPages(i, { it.turnPage(i) }, { ink.onPageTurned(i, oldHeight) }) { it }
                            },
                            onMove = { from, to ->
                                editPages(to, { it.movePage(from, to) }, { ink.onPageMoved(from, to) }) { PageMoves.afterMove(it, from, to) }
                            },
                            onRemoveBookmark = { p -> library.update(docId) { d -> d.copy(bookmarks = d.bookmarks - p) } },
                        ),
                    )
                }
            }
        }
    }
}

/** The text tool touched [page] at ([x], [y]): edit [existing], or add a new text box there. */
private class TextEdit(val page: Int, val x: Float, val y: Float, val existing: TextItem?)

/** A picture saved into the PDF's images folder, and its size in pixels. */
private class Picture(val name: String, val width: Int, val height: Int)

/**
 * Copies the picture at [uri] into this PDF's images folder (shrunk to at most 2048 px a side),
 * so it stays even if the photo is deleted.
 */
private suspend fun importPicture(ctx: Context, uri: Uri, ink: InkController): Picture? = withContext(Dispatchers.IO) {
    runCatching {
        val name = "img-${System.currentTimeMillis()}"
        val raw = ink.imageFile("$name.src")
        ctx.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
        val bm = InkController.decode(raw, 2048)
        raw.delete()
        bm ?: return@runCatching null
        val png = bm.hasAlpha()
        val file = ink.imageFile(if (png) "$name.png" else "$name.jpg")
        file.outputStream().use { bm.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
        Picture(file.name, bm.width, bm.height)
    }.getOrNull()
}

/**
 * The live ink layer (Jetpack Ink draws the stroke under the pen with very low latency),
 * which also receives every touch on the document and hands it to [InputRouter].
 * Draws the eraser ring / pen dot where the S Pen hovers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InkSurface(
    state: ViewerState,
    ink: InkController,
    onRouter: (InputRouter) -> Unit,
    onDownAnywhere: () -> Boolean,
    onTextTap: (page: Int, x: Float, y: Float, existing: TextItem?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val host = LocalView.current
    val density = LocalDensity.current.density
    val downAnywhere by rememberUpdatedState(onDownAnywhere)
    val textTap by rememberUpdatedState(onTextTap)
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
                    // Emulators can't do low-latency front-buffer drawing or GPU meshes; real tablets keep both.
                    if (InkController.isEmulator) {
                        @Suppress("DEPRECATION")
                        useHighLatencyRenderHelper = true
                        rendererFactory = { ink.renderer }
                    }
                    eagerInit()
                    val r = InputRouter(
                        state, ink, this, host, scope, density,
                        onDownAnywhere = { downAnywhere() },
                        onTextTap = { page, x, y, existing -> textTap(page, x, y, existing) },
                    )
                    router = r
                    onRouter(r)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val accent = Margin.colors.accent
        // Own layer: moving the cursor redraws only the cursor, not the pages under it.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val r = router ?: return@Canvas
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            // The lasso being drawn
            r.lassoVersion
            if (r.lasso.size > 1) {
                val path = Path().apply {
                    moveTo(r.lasso[0].x, r.lasso[0].y)
                    for (k in 1 until r.lasso.size) lineTo(r.lasso[k].x, r.lasso[k].y)
                }
                drawPath(path, accent.copy(alpha = 0.06f))
                drawPath(path, accent, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            }
            // The selection: dashed box with a round resize handle at its bottom-right corner
            r.selectionOnScreen()?.let { b ->
                val corner = CornerRadius(8.dp.toPx())
                drawRoundRect(accent.copy(alpha = 0.05f), Offset(b.left, b.top), Size(b.width, b.height), corner)
                drawRoundRect(accent, Offset(b.left, b.top), Size(b.width, b.height), corner, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
                drawCircle(Color.White, 9.dp.toPx(), Offset(b.right, b.bottom))
                drawCircle(accent, 9.dp.toPx(), Offset(b.right, b.bottom), style = Stroke(2.dp.toPx()))
            }
            val at = r.penAt ?: return@Canvas
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

/**
 * Actions for what's selected, just above it: lassoed ink (recolor, duplicate, delete), a text box
 * (edit, recolor, duplicate, delete) or a picture (duplicate, delete).
 */
@Composable
private fun SelectionBar(router: InputRouter, ink: InkController, onEditText: (page: Int, TextItem) -> Unit) {
    val c = Margin.colors
    var colors by remember { mutableStateOf(false) }
    LaunchedEffect(ink.selection, ink.selectedItem) { colors = false }
    val last = remember { floatArrayOf(0f, 0f) }
    ink.version // follow edits to the selected item
    val item = ink.selectedItemValue()
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = (ink.selection != null || item != null) && !router.dragging,
            enter = fadeIn(tween(120)) + scaleIn(tween(180, easing = EaseOutSmooth), initialScale = 0.92f),
            exit = fadeOut(tween(100)),
            modifier = Modifier.offset {
                router.selectionOnScreen()?.let { b ->
                    val gap = 10.dp.toPx()
                    val barH = 48.dp.toPx()
                    last[0] = b.left.coerceAtLeast(8.dp.toPx())
                    last[1] = if (b.top - gap - barH > 8.dp.toPx()) b.top - gap - barH else b.bottom + gap
                }
                IntOffset(last[0].roundToInt(), last[1].roundToInt())
            },
        ) {
            Row(
                Modifier.shadow(10.dp, RoundedCornerShape(24.dp)).background(c.surface, RoundedCornerShape(24.dp)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (colors) {
                    PEN_COLORS.forEach { col ->
                        Box(
                            Modifier.padding(5.dp).size(30.dp).clip(CircleShape).background(Color(col))
                                .clickable(onClickLabel = "Recolor") {
                                    ink.recolorSelection(col)
                                    colors = false
                                },
                        )
                    }
                } else {
                    val page = ink.selectedItem?.page
                    if (item is TextItem && page != null) BarChip("Edit") { onEditText(page, item) }
                    if (item !is ImageItem) BarChip("Color") { colors = true }
                    BarChip("Duplicate") { ink.duplicateSelection() }
                    BarChip("Delete", c.danger) { ink.deleteSelection() }
                }
            }
        }
    }
}

@Composable
private fun BarChip(text: String, color: Color = Margin.colors.text, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(CircleShape).clickable(onClickLabel = text, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ReaderTopBar(
    title: String,
    state: ViewerState?,
    search: SearchState?,
    status: String?,
    pagesOpen: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onBookmark: () -> Unit,
    onPages: () -> Unit,
    onPageTools: () -> Unit,
    onToggleLock: () -> Unit,
    onShare: () -> Unit,
) {
    val c = Margin.colors
    val page by remember(state) { derivedStateOf { state?.currentPage ?: 0 } }
    val locked = state?.zoomLocked == true
    Column(Modifier.background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        if (search != null && search.open) {
            SearchBar(search, currentPage = { state?.currentPage ?: 0 }, Modifier.height(56.dp).padding(horizontal = 8.dp))
            HorizontalDivider(thickness = 1.dp, color = c.line)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconBtn(MIcons.Back, "Back to library", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state == null) "Opening…" else "Page ${page + 1} of ${state.pageCount}" + (status?.let { " · $it" } ?: ""),
                    color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconBtn(MIcons.Search, "Search in this PDF", onSearch, enabled = search != null)
            IconBtn(
                if (bookmarked) MIcons.BookmarkFilled else MIcons.Bookmark,
                if (bookmarked) "Remove bookmark" else "Bookmark this page",
                onBookmark, active = bookmarked, enabled = state != null,
            )
            IconBtn(
                if (locked) MIcons.Lock else MIcons.Unlock,
                if (locked) "Unlock zoom" else "Lock zoom",
                onToggleLock, active = locked, enabled = state != null,
            )
            IconBtn(MIcons.Pages, if (pagesOpen) "Hide pages" else "Show pages", onPages, active = pagesOpen, enabled = state != null)
            IconBtn(MIcons.Scissors, "Extract or split pages", onPageTools, enabled = state != null && state.pageCount > 1)
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

/** The view to restore after the page layout is rebuilt. */
private class StartView(var page: Int, var zoom: Float, var locked: Boolean)

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

/** Saving outlives the reader screen, so leaving a PDF never cuts a save short. */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Shares Margin's copy of the PDF ([file]) under its own name, so it works even when the original is gone. */
private suspend fun share(ctx: Context, file: File, title: String) {
    val named = runCatching {
        withContext(Dispatchers.IO) {
            File(ctx.cacheDir, "share").apply { mkdirs() }.resolve(pdfFileName(title)).also { file.copyTo(it, overwrite = true) }
        }
    }.getOrNull() ?: return
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", named)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, title)) }
}
