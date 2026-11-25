package app.margin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Blank, lined, grid or dotted paper, as small page previews (for new pages and new notebooks). */
@Composable
fun PaperPicker(pick: PageTemplate, onPick: (PageTemplate) -> Unit) {
    val c = Margin.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                TemplatePreview(t, Modifier.size(52.dp, 70.dp))
                Text(t.name, fontSize = 12.sp, color = if (on) c.text else c.textMuted)
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
        }
    }
}
