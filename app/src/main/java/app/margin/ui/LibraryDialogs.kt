package app.margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.data.Doc
import app.margin.pdf.PageTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Merge PDFs: tap PDFs from the library in the order they should go in (starting with [start]),
 * name the result, and Margin joins them into a new PDF. The PDFs you picked stay as they are.
 */
@Composable
fun MergeDialog(start: Doc, all: List<Doc>, onMade: (Doc) -> Unit, onDismiss: () -> Unit) {
    val c = Margin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf(start.id) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val chosen = picked.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val title = if (nameEdited) name else PageTools.mergedTitle(chosen.map { it.title })
    val pages = chosen.sumOf { it.pageCount }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = c.surface,
        title = { Text("Merge PDFs", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tap PDFs in the order they should go in.", color = c.textMuted, fontSize = 14.sp)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, c.line, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()),
                ) {
                    all.forEach { d ->
                        val at = picked.indexOf(d.id)
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = at >= 0, enabled = !working, role = Role.Checkbox) {
                                    if (at >= 0) picked.removeAt(at) else picked.add(d.id)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape)
                                    .background(if (at >= 0) c.accent else Color.Transparent)
                                    .border(1.5.dp, if (at >= 0) c.accent else c.line, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (at >= 0) Text("${at + 1}", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(d.title, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (d.pageCount > 0) Text("${d.pageCount} p", color = c.textMuted, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { name = it.take(120); nameEdited = true },
                    label = { Text("Name of the new PDF") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("mergeName"),
                )
                Text(
                    if (chosen.size < 2) "Pick at least two PDFs." else "Makes a new PDF with $pages pages. The PDFs you picked stay as they are.",
                    color = c.textMuted, fontSize = 13.sp,
                )
                if (working) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / chosen.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt,
                    )
                }
                if (failed) Text("Couldn't merge these PDFs. Nothing was changed.", color = c.danger, fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.size >= 2 && !working && title.isNotBlank(),
                onClick = {
                    working = true
                    failed = false
                    done = 0
                    scope.launch {
                        try {
                            onMade(PageTools.merge(ctx, chosen, title) { done = it })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = true
                            working = false
                        }
                    }
                },
            ) { Text(if (chosen.size >= 2) "Merge ${chosen.size} PDFs" else "Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

/** Puts a PDF in a folder: one of [folders], a new one, or none. */
@Composable
fun FolderDialog(doc: Doc, folders: List<String>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = Margin.colors
    var pick by remember { mutableStateOf(doc.folder) }
    var fresh by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Move \"${doc.title}\" to a folder", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    (listOf<String?>(null) + folders).forEach { f ->
                        val on = fresh.isBlank() && pick == f
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .selectable(selected = on, role = Role.RadioButton) {
                                    pick = f
                                    fresh = ""
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(20.dp).border(2.dp, if (on) c.accent else c.line, CircleShape).padding(4.dp)
                                    .clip(CircleShape).background(if (on) c.accent else Color.Transparent),
                            )
                            Text(f ?: "No folder", color = if (f == null) c.textMuted else c.text, fontSize = 15.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = fresh,
                    onValueChange = { fresh = it.take(60) },
                    label = { Text("New folder") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("newFolder"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(fresh.trim().ifEmpty { null } ?: pick) }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for a new name ([title] of the dialog, [current] name to start from). */
@Composable
fun RenameDialog(title: String, current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Margin.colors
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.width(420.dp).testTag("renameField"),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
