package app.margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.ink.TextItem

/** Text sizes to pick from, in points. */
private val TEXT_SIZES = listOf("Small" to 11f, "Medium" to 15f, "Large" to 22f, "Title" to 32f)

/** Writes or changes a text box: the words, size and color. [onDelete] is offered for an existing box. */
@Composable
fun TextBoxDialog(
    existing: TextItem?,
    onDone: (text: String, size: Float, color: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = Margin.colors
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var size by remember { mutableFloatStateOf(existing?.size ?: 15f) }
    var color by remember { mutableIntStateOf(existing?.color ?: PEN_COLORS[0]) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (existing == null) "Add text" else "Edit text", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000) },
                    minLines = 3,
                    maxLines = 10,
                    placeholder = { Text("Type your note") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("textBox"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_SIZES.forEach { (label, pt) -> Choice(label, size == pt) { size = pt } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PEN_COLORS.forEach { col ->
                        val on = col == color
                        Box(
                            Modifier.size(30.dp).border(2.dp, if (on) c.accent else Color.Transparent, CircleShape).padding(4.dp)
                                .clip(CircleShape).background(Color(col))
                                .selectable(selected = on, role = Role.RadioButton) { color = col }
                                .semantics { contentDescription = "Text color" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text, size, color) }, enabled = text.isNotBlank()) {
                Text(if (existing == null) "Add" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = c.danger, fontSize = 14.sp) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
