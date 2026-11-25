package app.margin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.data.Doc
import app.margin.pdf.PageTemplate
import app.margin.pdf.PageTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** "New notes": names a new notebook and picks its paper; [onCreated] gets it, already in the library. */
@Composable
fun NewNotesDialog(onCreated: (Doc) -> Unit, onDismiss: () -> Unit) {
    val c = Margin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("Notes " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())) }
    var paper by rememberSaveable { mutableStateOf(PageTemplate.Lined) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = c.surface,
        title = { Text("New notes", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("notesName"),
                )
                Text("Pages", color = c.textMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Portrait", !landscape) { landscape = false }
                    Choice("Landscape", landscape) { landscape = true }
                }
                Text("Paper", color = c.textMuted, fontSize = 13.sp)
                PaperPicker(paper, onPick = { paper = it }, landscape = landscape)
                Text(
                    "A4 pages. When you write on the last page, a new one is added after it.",
                    color = c.textMuted, fontSize = 13.sp,
                )
                if (failed) Text("Couldn't make the notebook.", color = c.danger, fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && name.isNotBlank(),
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        try {
                            onCreated(PageTools.createNotes(ctx, name, paper, landscape))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = true
                            working = false
                        }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

/** A small two-way choice chip (Portrait / Landscape). */
@Composable
internal fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(if (selected) c.text else c.surfaceAlt)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) c.background else c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
