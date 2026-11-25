package app.margin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.data.Doc
import app.margin.pdf.PageTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private enum class PageToolMode { Keep, Split }

/**
 * "Extract or split pages": keep a range of pages as a new PDF ("pages 1–200"), or split the PDF
 * into parts of N pages. The new PDFs go into the library; [doc] itself stays as it is.
 * [source] returns Margin's up-to-date copy of [doc]; [onMade] gets the new PDFs once they're in the library.
 */
@Composable
fun PageToolsDialog(
    doc: Doc,
    pageCount: Int,
    source: suspend () -> File,
    onMade: (List<Doc>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Margin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(PageToolMode.Keep) }
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf(pageCount.toString()) }
    var every by rememberSaveable { mutableStateOf((if (pageCount > 10) 10 else 1).toString()) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    val plan = remember(mode, from, to, every, pageCount) {
        if (mode == PageToolMode.Keep) PageTools.keepPlan(from, to, pageCount) else PageTools.splitPlan(every, pageCount)
    }
    val ranges = (plan as? PageTools.Plan.Ok)?.ranges

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = c.surface,
        title = { Text("Extract or split pages", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "${doc.title} · $pageCount pages", color = c.textMuted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Keep pages", mode == PageToolMode.Keep, enabled = !working) { mode = PageToolMode.Keep }
                    ModeChip("Split", mode == PageToolMode.Split, enabled = !working) { mode = PageToolMode.Split }
                }
                if (mode == PageToolMode.Keep) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField("First page", from, { from = it }, enabled = !working, tag = "keepFrom")
                        Text("to", color = c.textMuted, fontSize = 14.sp)
                        NumberField("Last page", to, { to = it }, enabled = !working, tag = "keepTo")
                    }
                } else {
                    NumberField("Pages per PDF", every, { every = it }, enabled = !working, tag = "pagesPerPdf")
                }
                when (plan) {
                    is PageTools.Plan.Ok -> Text(
                        PageTools.describe(plan.ranges) + " This PDF stays as it is.",
                        color = c.textMuted, fontSize = 14.sp,
                    )
                    is PageTools.Plan.Problem -> Text(plan.message, color = c.danger, fontSize = 14.sp)
                }
                if (working && ranges != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { done.toFloat() / ranges.size },
                            modifier = Modifier.fillMaxWidth(),
                            color = c.accent,
                            trackColor = c.surfaceAlt,
                        )
                        Text(
                            if (ranges.size == 1) "Making the PDF…" else "Making PDF ${minOf(done + 1, ranges.size)} of ${ranges.size}…",
                            color = c.textMuted, fontSize = 13.sp,
                        )
                    }
                }
                failed?.let { Text(it, color = c.danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ranges != null && !working,
                onClick = {
                    val r = ranges ?: return@TextButton
                    working = true
                    failed = null
                    done = 0
                    scope.launch {
                        try {
                            onMade(PageTools.extract(ctx, doc, source(), r) { done = it })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = "Couldn't make the new PDF${if (r.size > 1) "s" else ""}. Nothing was changed."
                            working = false
                        }
                    }
                },
            ) {
                Text(
                    when {
                        mode == PageToolMode.Keep -> "Make PDF"
                        ranges != null -> "Make ${ranges.size} PDFs"
                        else -> "Make PDFs"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun ModeChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Margin.colors
    val bg by animateColorAsState(if (selected) c.text else c.surfaceAlt, tween(200), label = "modeBg")
    val fg by animateColorAsState(if (selected) c.background else c.textMuted, tween(200), label = "modeFg")
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(bg)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** A page-number box: digits only, number keyboard. */
@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.width(140.dp).testTag(tag),
    )
}
