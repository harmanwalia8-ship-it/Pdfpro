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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbMemory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
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
    val update by Updater.status.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(library.add(uri).id)
    }
    val openPicker = { picker.launch(arrayOf("application/pdf")) }
    var query by rememberSaveable { mutableStateOf("") }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val shown = remember(docs, query, starredOnly) {
        docs.filter { (!starredOnly || it.starred) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
    }
    val last = docs.firstOrNull()
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
        val u = update
        if (u is Updater.Status.Available || u is Updater.Status.Downloading) {
            item(key = "update", span = full) { UpdateBanner(u, Modifier.animateItem()) }
        }
        if (last == null) {
            item(key = "empty", span = full) { EmptyState(openPicker) }
        } else {
            item(key = "hero", span = full) {
                Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ContinueCard(last, rememberThumb(library, last.id), { onOpen(last.id) }, Modifier.weight(1f))
                    OpenCard(openPicker, Modifier.width(300.dp).fillMaxHeight())
                }
            }
            item(key = "recent", span = full) {
                RecentHeader(starredOnly) { starredOnly = it }
            }
            items(shown, key = { it.id }) { d ->
                DocCard(
                    doc = d,
                    thumb = rememberThumb(library, d.id),
                    onOpen = { onOpen(d.id) },
                    onToggleStar = { library.update(d.id) { it.copy(starred = !it.starred) } },
                    onRemove = { library.remove(d.id) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (shown.isEmpty()) {
                item(key = "none", span = full) {
                    Text(
                        if (starredOnly) "No starred PDFs yet. Long-press a PDF to star it." else "No PDFs match \"$query\".",
                        color = c.textMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
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
private fun UpdateBanner(status: Updater.Status, modifier: Modifier = Modifier) {
    val c = Margin.colors
    val ctx = LocalContext.current
    Row(
        modifier.fillMaxWidth().background(c.accentSoft, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(MIcons.Download, contentDescription = null, tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text("A new version of Margin is ready", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (status) {
                    is Updater.Status.Downloading -> "Downloading… ${(status.progress * 100).toInt()}%"
                    is Updater.Status.Available -> "Build ${status.build} · you have build ${Updater.currentBuild}"
                    else -> ""
                },
                color = c.textMuted, fontSize = 13.sp,
            )
        }
        if (status is Updater.Status.Available) {
            Button(
                onClick = { Updater.downloadAndInstall(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Update", fontWeight = FontWeight.SemiBold) }
        }
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
            Text("${pages}opened ${relativeTime(doc.lastOpened)}", color = c.textMuted, fontSize = 13.sp)
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

@Composable
private fun OpenCard(onClick: () -> Unit, modifier: Modifier) {
    val c = Margin.colors
    val dash = c.textMuted.copy(alpha = 0.4f)
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
            .clickable(onClickLabel = "Open a PDF", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(52.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(MIcons.Plus, contentDescription = null, tint = c.accent)
        }
        Text("Open a PDF", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("From Files, Drive or OneDrive", color = c.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
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
            "Pick a PDF from Files, Drive or OneDrive. Margin keeps a link to it; the file stays where it is.",
            color = c.textMuted, fontSize = 14.sp,
        )
        Button(
            onClick = onOpen,
            modifier = Modifier.padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) { Text("Open a PDF", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun RecentHeader(starredOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Recent", color = Margin.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Chip("All", selected = !starredOnly) { onChange(false) }
        Chip("Starred", selected = starredOnly) { onChange(true) }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    val bg by animateColorAsState(if (selected) c.text else c.surfaceAlt, tween(200), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.background else c.textMuted, tween(200), label = "chipFg")
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(
    doc: Doc,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onRemove: () -> Unit,
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
                    text = { Text("Remove from library") },
                    leadingIcon = { Icon(MIcons.Trash, contentDescription = null) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(doc.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = if (doc.pageCount > 0) "${doc.pageCount} pages · ${relativeTime(doc.lastOpened)}" else relativeTime(doc.lastOpened)
            Text(meta, color = c.textMuted, fontSize = 12.sp)
        }
    }
}

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
    val status by Updater.status.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Margin", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = c.textMuted, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val s = status
                    OutlinedButton(
                        onClick = { Updater.check(ctx, force = true) },
                        enabled = s !is Updater.Status.Checking && s !is Updater.Status.Downloading,
                    ) { Text("Check for updates") }
                    Text(
                        when (s) {
                            Updater.Status.Checking -> "Checking…"
                            Updater.Status.UpToDate -> "You're up to date"
                            is Updater.Status.Available -> "Build ${s.build} is available"
                            is Updater.Status.Downloading -> "Downloading…"
                            is Updater.Status.Failed -> s.message
                            Updater.Status.Idle -> ""
                        },
                        color = c.textMuted, fontSize = 13.sp,
                    )
                }
                Text("Updates come from github.com/${BuildConfig.UPDATE_REPO}", color = c.textMuted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
