package app.margin.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.margin.BuildConfig
import app.margin.data.Doc
import app.margin.data.Library
import app.margin.update.Updater
import app.margin.pdf.PdfStore
import app.margin.pdf.pdfFileName
import android.widget.Toast
import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbMemory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** The cover of [id] changed (e.g. its first page moved): load it afresh next time. */
fun forgetThumb(id: String) {
    thumbMemory.remove(id)
}

@Composable
private fun rememberThumb(library: Library, id: String): ImageBitmap? {
    var image by remember(id) { mutableStateOf(thumbMemory.get(id)) }
    LaunchedEffect(id) {
        if (image == null) {
            val file = library.thumbFile(id)
            if (file.exists()) {
                image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                    ?.also { thumbMemory.put(id, it) }
            }
        }
    }
    return image
}

@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = Margin.colors
    val library = remember { Library.get(ctx) }
    val docs by library.docs.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(library.add(uri).id)
    }
    val openPicker = { picker.launch(arrayOf("application/pdf")) }
    var query by rememberSaveable { mutableStateOf("") }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Doc?>(null) }
    var deleteTarget by remember { mutableStateOf<Doc?>(null) }
    var toolsTarget by remember { mutableStateOf<Doc?>(null) }
    // Saveable: the app may be stopped while the Files picker is open.
    var exportTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewNotes by remember { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.Recent) }
    var renameTarget by remember { mutableStateOf<Doc?>(null) }
    var folderTarget by remember { mutableStateOf<Doc?>(null) }
    var mergeTarget by remember { mutableStateOf<Doc?>(null) }
    var renameFolder by remember { mutableStateOf<String?>(null) }
    // How much room each PDF takes (Margin's copy), for sorting by size.
    val sizes = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(docs, sortBy) {
        if (sortBy != SortBy.Size) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { docs.associate { it.id to PdfStore.copyFile(ctx, it.id).length() } }
        sizes.putAll(found)
    }
    val folders = remember(docs) { library.folders() }
    if (folder != null && folder !in folders) folder = null
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { to ->
        val d = exportTargetId?.let(library::get)
        exportTargetId = null
        if (to != null && d != null) {
            scope.launch {
                val ok = PdfStore.export(ctx, d.id, to)
                Toast.makeText(ctx, if (ok) "Saved a copy of \"${d.title}\"" else "Couldn't save a copy", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val shown = remember(docs, query, starredOnly, folder, sortBy, sizes.toMap()) {
        val list = docs.filter {
            (!starredOnly || it.starred) && (folder == null || it.folder == folder) &&
                (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true))
        }
        when (sortBy) {
            SortBy.Recent -> list.sortedByDescending { maxOf(it.lastOpened, it.added) }
            SortBy.Name -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SortBy.Size -> list.sortedByDescending { sizes[it.id] ?: 0L }
        }
    }
    // The PDF you read last (PDFs Margin just made, never opened, don't count).
    val last = remember(docs) { docs.filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened } ?: docs.firstOrNull() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header", span = full) {
            Header(query, { query = it }, { showSettings = true })
        }
        if (last == null) {
            item(key = "empty", span = full) { EmptyState(openPicker, onNewNotes = { showNewNotes = true }) }
        } else {
            item(key = "hero", span = full) {
                Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ContinueCard(last, rememberThumb(library, last.id), { onOpen(last.id) }, Modifier.weight(1f))
                    Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActionCard(MIcons.Plus, "Open a PDF", "From Files, Drive or OneDrive", openPicker, Modifier.weight(1f))
                        ActionCard(MIcons.NewPage, "New notes", "A blank notebook to write in", { showNewNotes = true }, Modifier.weight(1f))
                    }
                }
            }
            item(key = "recent", span = full) {
                ShelfHeader(
                    starredOnly = starredOnly,
                    folder = folder,
                    folders = folders,
                    sortBy = sortBy,
                    onAll = { starredOnly = false; folder = null },
                    onStarred = { starredOnly = true; folder = null },
                    onFolder = { starredOnly = false; folder = it },
                    onRenameFolder = { renameFolder = it },
                    onRemoveFolder = { f ->
                        library.renameFolder(f, null)
                        if (folder == f) folder = null
                    },
                    onSort = { sortBy = it },
                )
            }
            items(shown, key = { it.id }) { d ->
                DocCard(
                    doc = d,
                    thumb = rememberThumb(library, d.id),
                    onOpen = { onOpen(d.id) },
                    onToggleStar = { library.update(d.id) { it.copy(starred = !it.starred) } },
                    meta = if (sortBy == SortBy.Size) sizes[d.id]?.let { Formatter.formatShortFileSize(ctx, it) } else null,
                    onRename = { renameTarget = d },
                    onMoveToFolder = { folderTarget = d },
                    onMerge = if (docs.size > 1) ({ mergeTarget = d }) else null,
                    onPageTools = if (d.pageCount > 1) ({ toolsTarget = d }) else null,
                    onExport = {
                        exportTargetId = d.id
                        runCatching { exporter.launch(pdfFileName(d.title)) }
                    },
                    onRestore = if (PdfStore.hasBackup(ctx, d.id)) ({ restoreTarget = d }) else null,
                    onDelete = { deleteTarget = d },
                    modifier = Modifier.animateItem(),
                )
            }
            if (shown.isEmpty()) {
                item(key = "none", span = full) {
                    Text(
                        when {
                            query.isNotBlank() -> "No PDFs match \"$query\"."
                            starredOnly -> "No starred PDFs yet. Long-press a PDF to star it."
                            folder != null -> "No PDFs in \"$folder\"."
                            else -> "No PDFs yet."
                        },
                        color = c.textMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    renameTarget?.let { d ->
        RenameDialog("Rename PDF", d.title, onRename = { name ->
            renameTarget = null
            library.update(d.id) { it.copy(title = name) }
        }, onDismiss = { renameTarget = null })
    }
    renameFolder?.let { f ->
        RenameDialog("Rename folder", f, onRename = { name ->
            renameFolder = null
            library.renameFolder(f, name)
            if (folder == f) folder = name
        }, onDismiss = { renameFolder = null })
    }
    folderTarget?.let { d ->
        FolderDialog(d, folders, onPick = { f ->
            folderTarget = null
            library.update(d.id) { it.copy(folder = f) }
            Toast.makeText(ctx, if (f == null) "Taken out of its folder" else "Moved to \"$f\"", Toast.LENGTH_SHORT).show()
        }, onDismiss = { folderTarget = null })
    }
    mergeTarget?.let { d ->
        MergeDialog(d, docs, onMade = { made ->
            mergeTarget = null
            Toast.makeText(ctx, "Made \"${made.title}\" (${made.pageCount} pages)", Toast.LENGTH_SHORT).show()
        }, onDismiss = { mergeTarget = null })
    }
    if (showNewNotes) {
        NewNotesDialog(
            onCreated = { d ->
                showNewNotes = false
                onOpen(d.id)
            },
            onDismiss = { showNewNotes = false },
        )
    }
    restoreTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            containerColor = c.surface,
            title = { Text("Restore the original PDF?", color = c.text, fontWeight = FontWeight.SemiBold) },
            text = { Text("\"${d.title}\" goes back to how it was before Margin first saved ink into it. Your ink in this PDF will be removed.", color = c.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    scope.launch {
                        val ok = PdfStore.restore(ctx, d.id)
                        Toast.makeText(ctx, if (ok) "Original restored" else "Couldn't restore the original", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Restore", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = c.surface,
            title = { Text("Delete \"${d.title}\" from Margin?", color = c.text, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (d.uri != null) {
                        "Margin's copy of this PDF and your ink in it are deleted for good. The original file isn't touched."
                    } else {
                        "This PDF was made in Margin and only exists here, so it's gone for good. " +
                            "To keep it, use Save a copy to Files first."
                    },
                    color = c.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { PdfStore.delete(ctx, d) }
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    toolsTarget?.let { d ->
        PageToolsDialog(
            doc = d,
            pageCount = d.pageCount,
            source = { PdfStore.prepare(ctx, d.id).file },
            onMade = { made ->
                toolsTarget = null
                Toast.makeText(ctx, if (made.size == 1) "Made a new PDF" else "Made ${made.size} PDFs", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { toolsTarget = null },
        )
    }
}

@Composable
private fun Header(query: String, onQuery: (String) -> Unit, onSettings: () -> Unit) {
    val c = Margin.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 28.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(c.accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(MIcons.Nib, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        }
        Text("Margin", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.width(300.dp).height(44.dp).background(c.surface, CircleShape).border(1.dp, c.line, CircleShape)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(MIcons.Search, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search your PDFs" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search your PDFs", color = c.textMuted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Clear search") { onQuery("") },
                    contentAlignment = Alignment.Center,
                ) { Icon(MIcons.Close, contentDescription = "Clear search", tint = c.textMuted, modifier = Modifier.size(16.dp)) }
            }
        }
        IconBtn(MIcons.Settings, "Settings and updates", onSettings)
    }
}

@Composable
private fun ContinueCard(doc: Doc, thumb: ImageBitmap?, onOpen: () -> Unit, modifier: Modifier) {
    val c = Margin.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxHeight().shadow(2.dp, shape).clip(shape).background(c.surface).clickable(onClick = onOpen).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Cover(thumb, Modifier.width(120.dp).fillMaxHeight(), RoundedCornerShape(6.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CONTINUE WHERE YOU LEFT OFF", color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
            Text(doc.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pages = if (doc.pageCount > 0) "Page ${doc.lastPage + 1} of ${doc.pageCount} · " else ""
            Text("${pages}${if (doc.lastOpened > 0) "opened " else ""}${openedText(doc)}", color = c.textMuted, fontSize = 13.sp)
            val progress = if (doc.pageCount > 0) (doc.lastPage + 1f) / doc.pageCount else 0f
            Box(Modifier.padding(top = 6.dp).width(320.dp).height(6.dp).background(c.surfaceAlt, CircleShape)) {
                Box(Modifier.fillMaxWidth(progress).height(6.dp).background(c.accent, CircleShape))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                contentPadding = PaddingValues(horizontal = 22.dp),
            ) {
                Text("Continue", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(MIcons.Arrow, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A dashed card with an icon, a title and a line of explanation ("Open a PDF", "New notes"). */
@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    val c = Margin.colors
    val dash = c.textMuted.copy(alpha = 0.4f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.accent)
        }
        Column {
            Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, onNewNotes: () -> Unit) {
    val c = Margin.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp).background(c.surface, RoundedCornerShape(20.dp)).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(MIcons.Nib, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
        }
        Text("Open your first PDF", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Pick a PDF from Files, Drive or OneDrive, or start a notebook to write in. " +
                "Margin keeps its own copy of every PDF, so it stays here even if the original is deleted.",
            color = c.textMuted, fontSize = 14.sp,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Open a PDF", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onNewNotes) { Text("New notes", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** How the library grid is sorted. */
enum class SortBy(val label: String) { Recent("Recent"), Name("Name"), Size("Size") }

/** "Your PDFs" with filters (all, starred, each folder) and the sort order. Hold a folder to rename or remove it. */
@Composable
private fun ShelfHeader(
    starredOnly: Boolean,
    folder: String?,
    folders: List<String>,
    sortBy: SortBy,
    onAll: () -> Unit,
    onStarred: () -> Unit,
    onFolder: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onSort: (SortBy) -> Unit,
) {
    val c = Margin.colors
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your PDFs", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("All", selected = !starredOnly && folder == null, onClick = onAll)
            Chip("Starred", selected = starredOnly, onClick = onStarred)
            folders.forEach { f ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Chip(f, selected = folder == f, icon = MIcons.Folder, onClick = { onFolder(f) }, onLongClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename folder…") },
                            leadingIcon = { Icon(MIcons.Nib, contentDescription = null) },
                            onClick = { menu = false; onRenameFolder(f) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove folder (keeps its PDFs)") },
                            leadingIcon = { Icon(MIcons.Close, contentDescription = null) },
                            onClick = { menu = false; onRemoveFolder(f) },
                        )
                    }
                }
            }
        }
        Box {
            Row(
                Modifier.height(36.dp).clip(CircleShape).clickable(onClickLabel = "Sort") { sortMenu = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MIcons.Sort, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                Text(sortBy.label, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortBy.entries.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(if (o == SortBy.Recent) "Recently opened" else if (o == SortBy.Name) "Name" else "Size") },
                        leadingIcon = { if (o == sortBy) Icon(MIcons.Check, contentDescription = null) },
                        onClick = { sortMenu = false; onSort(o) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Margin.colors
    val bg by animateColorAsState(if (selected) c.text else c.surfaceAlt, tween(200), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.background else c.textMuted, tween(200), label = "chipFg")
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "Folder options" })
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(
    doc: Doc,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    meta: String?,
    onRename: () -> Unit,
    onMoveToFolder: () -> Unit,
    onMerge: (() -> Unit)?,
    onPageTools: (() -> Unit)?,
    onExport: () -> Unit,
    onRestore: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Margin.colors
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(150), label = "press")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Open",
                onLongClickLabel = "More options",
                onClick = onOpen,
                onLongClick = { menu = true },
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            Cover(thumb, Modifier.fillMaxWidth().aspectRatio(3f / 4f), RoundedCornerShape(8.dp))
            if (doc.starred) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(MIcons.StarFilled, contentDescription = "Starred", tint = Color(0xFFE0A100), modifier = Modifier.size(16.dp)) }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (doc.starred) "Remove star" else "Star") },
                    leadingIcon = { Icon(MIcons.Star, contentDescription = null) },
                    onClick = { menu = false; onToggleStar() },
                )
                DropdownMenuItem(
                    text = { Text("Rename…") },
                    leadingIcon = { Icon(MIcons.Nib, contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Move to folder…") },
                    leadingIcon = { Icon(MIcons.Folder, contentDescription = null) },
                    onClick = { menu = false; onMoveToFolder() },
                )
                if (onPageTools != null) {
                    DropdownMenuItem(
                        text = { Text("Extract or split pages…") },
                        leadingIcon = { Icon(MIcons.Scissors, contentDescription = null) },
                        onClick = { menu = false; onPageTools() },
                    )
                }
                if (onMerge != null) {
                    DropdownMenuItem(
                        text = { Text("Merge with other PDFs…") },
                        leadingIcon = { Icon(MIcons.Merge, contentDescription = null) },
                        onClick = { menu = false; onMerge() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Save a copy to Files…") },
                    leadingIcon = { Icon(MIcons.SaveFile, contentDescription = null) },
                    onClick = { menu = false; onExport() },
                )
                if (onRestore != null) {
                    DropdownMenuItem(
                        text = { Text("Restore original PDF") },
                        leadingIcon = { Icon(MIcons.Undo, contentDescription = null) },
                        onClick = { menu = false; onRestore() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete from Margin…", color = c.danger) },
                    leadingIcon = { Icon(MIcons.Trash, contentDescription = null, tint = c.danger) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(doc.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = meta ?: openedText(doc)
            Text(if (doc.pageCount > 0) "${doc.pageCount} pages · $info" else info, color = c.textMuted, fontSize = 12.sp)
        }
    }
}

/** "2 hours ago", or "new" for a PDF Margin made that you haven't opened yet. */
private fun openedText(doc: Doc): String = if (doc.lastOpened > 0) relativeTime(doc.lastOpened) else "new"

/** A page-shaped cover image, or blank text lines while there's no thumbnail yet. */
@Composable
private fun Cover(image: ImageBitmap?, modifier: Modifier, shape: RoundedCornerShape) {
    Box(modifier.shadow(3.dp, shape).clip(shape).background(Color.White)) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(7.dp).background(Color(0xFFD9D5CF), RoundedCornerShape(2.dp)))
                repeat(7) { Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFE7E4DF))) }
            }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val c = Margin.colors
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Margin", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = c.textMuted, fontSize = 14.sp)
                OutlinedButton(onClick = { Updater.openReleases(ctx) }) { Text("Check for updates") }
                Text(
                    "Opens the newest release on GitHub in Chrome. If its build number is higher than " +
                        "${BuildConfig.VERSION_CODE}, download the APK there and tap it to install.",
                    color = c.textMuted, fontSize = 13.sp,
                )
                Text("Privacy", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Margin has no internet access and never changes your original files. Your PDFs, notes " +
                        "and ink only leave this tablet when you choose Share or Save a copy to Files, or through " +
                        "Android's own backup and the transfer to a new tablet (encrypted with your screen lock). " +
                        "Google's backup skips apps over 25 MB, so for a big library, use the new-tablet transfer " +
                        "or Save a copy.",
                    color = c.textMuted, fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
