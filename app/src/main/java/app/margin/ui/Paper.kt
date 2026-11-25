package app.margin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.pdf.PageTemplate

/** The kinds of paper (blank, lined, grid, ...) as small page previews, for new pages and new notebooks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperPicker(pick: PageTemplate, onPick: (PageTemplate) -> Unit, landscape: Boolean = false) {
    val c = Margin.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTemplate.entries.forEach { t ->
            val on = t == pick
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.clip(shape).background(if (on) c.accentSoft else Color.Transparent)
                    .border(1.5.dp, if (on) c.accent else c.line, shape)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(t) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TemplatePreview(t, if (landscape) Modifier.size(70.dp, 52.dp) else Modifier.size(52.dp, 70.dp))
                Text(t.label, fontSize = 12.sp, color = if (on) c.text else c.textMuted)
            }
        }
    }
}

@Composable
private fun TemplatePreview(t: PageTemplate, modifier: Modifier) {
    Canvas(modifier.shadow(1.dp, RoundedCornerShape(3.dp)).background(Color.White, RoundedCornerShape(3.dp))) {
        val step = size.width / 7f
        when (t) {
            PageTemplate.Blank -> Unit
            PageTemplate.Lined -> {
                var y = step * 1.6f
                while (y < size.height - step * 0.5f) {
                    drawLine(Color(0xFFC2D1F0), Offset(step * 0.6f, y), Offset(size.width - step * 0.6f, y), 1.dp.toPx())
                    y += step
                }
            }
            PageTemplate.Grid -> {
                var x = step
                while (x < size.width) { drawLine(Color(0xFFDDDAD5), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = step
                while (y < size.height) { drawLine(Color(0xFFDDDAD5), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            }
            PageTemplate.Dotted -> {
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) { drawCircle(Color(0xFFB8B3AC), 1.2.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
            PageTemplate.Cornell -> {
                val m = step * 0.6f
                val top = step * 1.2f
                val summary = size.height * 0.8f
                val cue = m + (size.width - 2 * m) * 0.3f
                var y = top + step * 0.8f
                while (y < summary - step * 0.4f) {
                    drawLine(Color(0xFFC2D1F0), Offset(cue, y), Offset(size.width - m, y), 1f)
                    y += step * 0.8f
                }
                drawLine(Color(0xFF8C9ECC), Offset(m, top), Offset(size.width - m, top), 1.dp.toPx())
                drawLine(Color(0xFF8C9ECC), Offset(m, summary), Offset(size.width - m, summary), 1.dp.toPx())
                drawLine(Color(0xFFDB8C8C), Offset(cue, top), Offset(cue, summary), 1.dp.toPx())
            }
            PageTemplate.Music -> {
                val m = step * 0.7f
                var top = step * 1.2f
                while (top + step * 1.2f < size.height - m) {
                    for (j in 0 until 5) {
                        val y = top + j * step * 0.25f
                        drawLine(Color(0xFF8C8C8C), Offset(m, y), Offset(size.width - m, y), 1f)
                    }
                    top += step * 2.2f
                }
            }
            PageTemplate.Planner -> {
                val m = step * 0.6f
                val head = step * 1.6f
                val split = m + (size.width - 2 * m) * 0.62f
                drawLine(Color(0xFF8C9ECC), Offset(m, head), Offset(size.width - m, head), 1.dp.toPx())
                var y = head + step
                while (y < size.height - m) {
                    drawLine(Color(0xFFC2D1F0), Offset(m, y), Offset(split, y), 1f)
                    drawRect(Color(0xFFC2D1F0), Offset(split + m * 0.6f, y - step * 0.55f), androidx.compose.ui.geometry.Size(step * 0.35f, step * 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    y += step
                }
                drawLine(Color(0xFFC2D1F0), Offset(m + step * 1.3f, head), Offset(m + step * 1.3f, y - step), 1f)
            }
        }
    }
}
