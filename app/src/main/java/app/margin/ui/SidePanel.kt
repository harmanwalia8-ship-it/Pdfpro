package app.margin.ui

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.margin.ink.InkController
import app.margin.pdf.OutlineEntry
import app.margin.pdf.PageTemplate
import app.margin.pdf.PdfDoc
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class SideTab(val label: String) { Pages("Pages"), Contents("Contents"), Bookmarks("Bookmarks") }

/** Width of the side panel; the pages slide right by half of it. */
val SidePanelWidth = 280.dp

/** What you can do to pages from the side panel. */
class PageActions(
    val onPick: (Int) -> Unit,
    val onAdd: (after: Int, PageTemplate) -> Unit,
    val onDelete: (Int) -> Unit,
    val onDuplicate: (Int) -> Unit,
    val onTurn: (Int) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit,
    val onRemoveBookmark: (Int) -> Unit,
)

/** The panel on the left: page thumbnails, the PDF's table of contents, and your bookmarks. */
@Composable
fun SidePanel(
    doc: PdfDoc,
    state: ViewerState,
    ink: InkController,
    version: Int,
    bookmarks: List<Int>,
    tab: SideTab,
    onTab: (SideTab) -> Unit,
    onClose: () -> Unit,
    actions: PageActions,
) {
    val c = Margin.colors
    val thumbs = remember(doc, version) {
        object : LruCache<Int, ImageBitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
        }
    }
    Column(Modifier.width(SidePanelWidth).fillMaxHeight().shadow(12.dp).background(c.background)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SideTab.entries.forEach { t -> TabChip(t.label, t == tab) { onTab(t) } }
            Spacer(Modifier.weight(1f))
            IconBtn(MIcons.Close, "Close pages", onClose)
        }
        when (tab) {
            SideTab.Pages -> PagesTab(doc, state, ink, version, bookmarks, thumbs, actions)
            SideTab.Contents -> ContentsTab(doc, state, version, actions.onPick)
            SideTab.Bookmarks -> BookmarksTab(doc, state, bookmarks, thumbs, actions)
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    val bg by animateColorAsState(if (selected) c.text else Color.Transparent, tween(180), label = "tabBg")
    val fg by animateColorAsState(if (selected) c.background else c.textMuted, tween(180), label = "tabFg")
    Box(
        Modifier.height(32.dp).clip(CircleShape).background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------------- Pages ----------------

@Composable
private fun PagesTab(
    doc: PdfDoc,
    state: ViewerState,
    ink: InkController,
    version: Int,
    bookmarks: List<Int>,
    thumbs: LruCache<Int, ImageBitmap>,
    actions: PageActions,
) {
    val c = Margin.colors
    var addAfter by remember { mutableStateOf<Int?>(null) }
    var deleteAt by remember { mutableStateOf<Int?>(null) }
    var notesOnly by rememberSaveable { mutableStateOf(false) }
    val current by remember(state) { derivedStateOf { state.currentPage } }
    val shown = remember(notesOnly, version, ink.version, doc.pageCount) {
        if (notesOnly) (0 until doc.pageCount).filter { ink.hasNotes(it) } else (0 until doc.pageCount).toList()
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = shown.indexOf(current).coerceAtLeast(1) - 1)
    LaunchedEffect(current) {
        val at = shown.indexOf(current)
        if (at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == at }) list.animateScrollToItem((at - 1).coerceAtLeast(0))
    }

    // Dragging a thumbnail to a new place (not while only pages with notes are shown).
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(dragFrom) {
        if (dragFrom < 0) return@LaunchedEffect
        // Scrolls the list while the dragged page is held near its top or bottom edge.
        while (isActive) {
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragFrom }
            if (info != null) {
                val center = info.offset + info.size / 2f + dragDy
                val step = when {
                    center < list.layoutInfo.viewportStartOffset + edge -> -14f
                    center > list.layoutInfo.viewportEndOffset - edge -> 14f
                    else -> 0f
                }
                if (step != 0f) dragDy += list.scrollBy(step)
            }
            delay(16)
        }
    }
    fun dropIndex(): Int {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragFrom } ?: return dragFrom
        val center = info.offset + info.size / 2f + dragDy
        val over = list.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }
        return over?.index ?: dragFrom
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip("With notes", notesOnly) { notesOnly = !notesOnly }
        Spacer(Modifier.weight(1f))
        Text("${doc.pageCount} pages", color = c.textMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
        IconBtn(MIcons.Plus, "Add page", { addAfter = current })
    }
    if (shown.isEmpty()) {
        EmptyNote("No pages with notes yet. Pages you write on, or add text or pictures to, show up here.")
        return
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(shown, key = { _, i -> "$version-$i" }) { pos, i ->
            val dragging = dragFrom == pos
            PageThumb(
                doc, i, thumbs,
                selected = i == current,
                bookmarked = i in bookmarks,
                lift = if (dragging) dragDy else 0f,
                modifier = if (dragging) Modifier.zIndex(1f) else Modifier,
                canDrag = !notesOnly && doc.pageCount > 1,
                onClick = { actions.onPick(i) },
                onDragStart = { dragFrom = pos; dragDy = 0f },
                onDrag = { dy -> dragDy += dy },
                onDragEnd = { moved ->
                    val to = if (moved) dropIndex() else pos
                    dragFrom = -1
                    dragDy = 0f
                    if (to != pos) actions.onMove(i, shown[to])
                },
                onAddAfter = { addAfter = i },
                onDuplicate = { actions.onDuplicate(i) },
                onTurn = { actions.onTurn(i) },
                onDelete = if (doc.pageCount > 1) ({ deleteAt = i }) else null,
            )
        }
    }
    addAfter?.let { after ->
        AddPageDialog(after, onAdd = { t ->
            addAfter = null
            actions.onAdd(after, t)
        }, onDismiss = { addAfter = null })
    }
    deleteAt?.let { i ->
        AlertDialog(
            onDismissRequest = { deleteAt = null },
            containerColor = c.surface,
            title = { Text("Delete page ${i + 1}?", color = c.text, fontWeight = FontWeight.SemiBold) },
            text = { Text("The page and your notes on it are removed from the PDF. You can still restore the original from the library.", color = c.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    deleteAt = null
                    actions.onDelete(i)
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteAt = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    Box(
        Modifier.height(30.dp).clip(CircleShape).background(if (on) c.accentSoft else c.surfaceAlt)
            .border(1.dp, if (on) c.accent else Color.Transparent, CircleShape)
            .selectable(selected = on, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) c.accent else c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * A page thumbnail. Tap to go to the page; press and hold for its menu, or hold and drag to move it.
 */
@Composable
private fun PageThumb(
    doc: PdfDoc,
    index: Int,
    cache: LruCache<Int, ImageBitmap>,
    selected: Boolean,
    bookmarked: Boolean,
    lift: Float,
    modifier: Modifier,
    canDrag: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (moved: Boolean) -> Unit,
    onAddAfter: () -> Unit,
    onDuplicate: () -> Unit,
    onTurn: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = Margin.colors
    var menu by remember { mutableStateOf(false) }
    val size = doc.pageSizes[index]
    val image = rememberPageThumb(doc, index, cache)
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "ring")
    val shape = RoundedCornerShape(4.dp)
    val tap by rememberUpdatedState(onClick)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Column(
        modifier.graphicsLayer {
            translationY = lift
            if (lift != 0f) {
                scaleX = 1.05f
                scaleY = 1.05f
                shadowElevation = 12.dp.toPx()
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(size.width / size.height)
                .shadow(2.dp, shape)
                .clip(shape)
                .background(Color.White)
                .border(2.5.dp, c.accent.copy(alpha = ring), shape)
                .pointerInput(index, canDrag) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var released = false
                        val waited = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed) {
                                    released = true
                                    break
                                }
                                // The list scrolled (or the finger moved): not a tap or a hold.
                                if (ch.isConsumed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            }
                        }
                        if (waited != null) {
                            if (released) tap()
                            return@awaitEachGesture
                        }
                        // Held: drag it to a new place, or let go for the page menu.
                        start()
                        var travel = Offset.Zero
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            ch.consume()
                            travel += d
                            if (canDrag) drag(d.y)
                        }
                        val moved = canDrag && travel.getDistance() > viewConfiguration.touchSlop
                        end(moved)
                        if (!moved) menu = true
                    }
                }
                .semantics {
                    contentDescription = "Page ${index + 1}"
                    onClick("Go to page ${index + 1}") { tap(); true }
                    onLongClick("Page options") { menu = true; true }
                },
        ) {
            image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (bookmarked) {
                Icon(
                    MIcons.BookmarkFilled, contentDescription = "Bookmarked", tint = c.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Add page after") },
                    leadingIcon = { Icon(MIcons.Plus, contentDescription = null) },
                    onClick = { menu = false; onAddAfter() },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate page") },
                    leadingIcon = { Icon(MIcons.Duplicate, contentDescription = null) },
                    onClick = { menu = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = { Text("Turn clockwise") },
                    leadingIcon = { Icon(MIcons.Rotate, contentDescription = null) },
                    onClick = { menu = false; onTurn() },
                )
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Delete page", color = c.danger) },
                        leadingIcon = { Icon(MIcons.Trash, contentDescription = null, tint = c.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
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
private fun rememberPageThumb(doc: PdfDoc, index: Int, cache: LruCache<Int, ImageBitmap>): ImageBitmap? {
    var image by remember(index, cache) { mutableStateOf(cache.get(index)) }
    LaunchedEffect(index, cache) {
        if (image == null) {
            doc.thumbnail(index, 264)?.asImageBitmap()?.let {
                cache.put(index, it)
                image = it
            }
        }
    }
    return image
}

/** Picks the look of a new page: blank, lined, grid, dotted, Cornell, music or planner. */
@Composable
private fun AddPageDialog(after: Int, onAdd: (PageTemplate) -> Unit, onDismiss: () -> Unit) {
    val c = Margin.colors
    var pick by remember { mutableStateOf(PageTemplate.Lined) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Add page after page ${after + 1}", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = { PaperPicker(pick, onPick = { pick = it }) },
        confirmButton = { TextButton(onClick = { onAdd(pick) }) { Text("Add page") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Contents ----------------

@Composable
private fun ContentsTab(doc: PdfDoc, state: ViewerState, version: Int, onPick: (Int) -> Unit) {
    val c = Margin.colors
    var entries by remember(doc, version) { mutableStateOf<List<OutlineEntry>?>(null) }
    LaunchedEffect(doc, version) { entries = doc.outline() }
    val list = entries
    val current by remember(state) { derivedStateOf { state.currentPage } }
    when {
        list == null -> Unit
        list.isEmpty() -> EmptyNote("This PDF has no table of contents.")
        else -> {
            // The section you're in: the last entry that starts at or before the current page.
            val here = list.indexOfLast { it.page in 0..current }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(list) { k, e ->
                    val on = k == here
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) c.accentSoft else Color.Transparent)
                            .clickable(enabled = e.page >= 0, onClickLabel = "Go to ${e.title}") { onPick(e.page) }
                            .padding(start = (16 + 14 * e.depth.coerceAtMost(6)).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.title, color = if (on) c.accent else c.text, fontSize = if (e.depth == 0) 14.sp else 13.sp,
                            fontWeight = if (e.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (e.page >= 0) Text("${e.page + 1}", color = c.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// ---------------- Bookmarks ----------------

@Composable
private fun BookmarksTab(
    doc: PdfDoc,
    state: ViewerState,
    bookmarks: List<Int>,
    thumbs: LruCache<Int, ImageBitmap>,
    actions: PageActions,
) {
    val c = Margin.colors
    val current by remember(state) { derivedStateOf { state.currentPage } }
    val marks = bookmarks.filter { it in 0 until doc.pageCount }
    if (marks.isEmpty()) {
        EmptyNote("No bookmarks yet. Tap the bookmark at the top to mark the page you're on.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsIndexed(marks, key = { _, p -> p }) { _, p ->
            val on = p == current
            Row(
                Modifier.fillMaxWidth()
                    .background(if (on) c.accentSoft else Color.Transparent)
                    .clickable(onClickLabel = "Go to page ${p + 1}") { actions.onPick(p) }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val size = doc.pageSizes[p]
                Box(Modifier.width(44.dp).aspectRatio(size.width / size.height).shadow(1.dp, RoundedCornerShape(2.dp)).background(Color.White)) {
                    rememberPageThumb(doc, p, thumbs)?.let {
                        Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
                Text("Page ${p + 1}", color = if (on) c.accent else c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconBtn(MIcons.Close, "Remove bookmark on page ${p + 1}", { actions.onRemoveBookmark(p) })
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = Margin.colors.textMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
}
