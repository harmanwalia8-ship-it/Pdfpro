package app.margin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.ink.EraserMode
import app.margin.ink.InkController
import app.margin.ink.PenType
import app.margin.ink.Tool

enum class TrayPanel { Pen, Highlighter, Eraser }

internal val PEN_COLORS = listOf(
    0xFF1C1B1A, 0xFF1F3A93, 0xFF2B6CDB, 0xFFC62828, 0xFF2E7D32, 0xFFE65100, 0xFF6A1B9A, 0xFF6D5D52,
).map { it.toInt() }
private val HL_COLORS = listOf(0xFFFFD54A, 0xFF8BE28E, 0xFF8FD3FF, 0xFFFF9EBB, 0xFFFFB870).map { it.toInt() }
private val PEN_TYPES = listOf(PenType.Ballpoint, PenType.Fountain, PenType.Pencil, PenType.Marker)

private fun PenType.label() = when (this) {
    PenType.Ballpoint -> "Ballpoint"
    PenType.Fountain -> "Fountain"
    PenType.Pencil -> "Pencil"
    PenType.Marker -> "Marker"
    PenType.Highlighter -> "Highlighter"
}

/**
 * The floating tool tray: real-looking pens that rise when picked, plus undo/redo.
 * Tap a tool to pick it; tap it again for its options.
 */
@Composable
fun PenTray(
    ink: InkController,
    panel: TrayPanel?,
    onPanel: (TrayPanel?) -> Unit,
    onClearPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(panel ?: TrayPanel.Pen) }
    if (panel != null) shown = panel
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = panel != null,
            enter = fadeIn(tween(140)) + scaleIn(tween(220, easing = EaseOutSmooth), 0.94f, TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(150), 0.96f, TransformOrigin(0.5f, 1f)),
        ) {
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier.padding(bottom = 10.dp).shadow(16.dp, shape).background(Margin.colors.surface, shape)
                    .padding(16.dp).width(320.dp),
            ) {
                when (shown) {
                    TrayPanel.Pen -> PenOptions(ink)
                    TrayPanel.Highlighter -> HighlighterOptions(ink)
                    TrayPanel.Eraser -> EraserOptions(ink, onClearPage)
                }
            }
        }
        Tray(ink, panel, onPanel)
    }
}

@Composable
private fun Tray(ink: InkController, panel: TrayPanel?, onPanel: (TrayPanel?) -> Unit) {
    val c = Margin.colors
    fun tap(tool: Tool, options: TrayPanel?) {
        if (ink.tool == tool) onPanel(if (panel == null) options else null) else {
            ink.selectTool(tool)
            onPanel(null)
        }
    }
    Box(contentAlignment = Alignment.BottomCenter) {
        // The white tray is the lower 76dp; picked pens rise above it.
        Box(
            Modifier.matchParentSize().padding(top = 24.dp)
                .shadow(14.dp, RoundedCornerShape(24.dp)).background(c.surface, RoundedCornerShape(24.dp)),
        )
        Row(
            Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(Tool.Pen0, Tool.Pen1, Tool.Pen2).forEachIndexed { i, tool ->
                val pen = ink.pens[i]
                Slot("Pen ${i + 1}: ${pen.type.label()}", ink.tool == tool, { tap(tool, TrayPanel.Pen) }) {
                    AnimatedContent(
                        targetState = pen.type,
                        transitionSpec = {
                            slideInVertically(tween(260, easing = EaseOutSmooth)) { it } togetherWith
                                slideOutVertically(tween(180)) { it }
                        },
                        label = "penType",
                    ) { type -> Instrument(type.name, Color(pen.color)) }
                }
            }
            Slot("Highlighter", ink.tool == Tool.Highlighter, { tap(Tool.Highlighter, TrayPanel.Highlighter) }) {
                Instrument("Highlighter", Color(ink.highlighter.color))
            }
            Slot("Eraser", ink.tool == Tool.Eraser, { tap(Tool.Eraser, TrayPanel.Eraser) }) {
                Instrument("Eraser", Color.Unspecified)
            }
            Slot("Lasso select", ink.tool == Tool.Lasso, { tap(Tool.Lasso, null) }) {
                Instrument("Lasso", Color.Unspecified)
            }
            Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 18.dp).size(1.dp, 40.dp).background(c.line))
            Row(Modifier.padding(bottom = 16.dp)) {
                IconBtn(MIcons.Undo, "Undo", { ink.undo() }, enabled = ink.canUndo)
                IconBtn(MIcons.Redo, "Redo", { ink.redo() }, enabled = ink.canRedo)
            }
        }
    }
}

@Composable
private fun Slot(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val rise by animateDpAsState(
        if (selected) 2.dp else 30.dp,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "rise",
    )
    Box(Modifier.size(44.dp, 100.dp).clipToBounds()) {
        Box(Modifier.offset { IntOffset(5.dp.roundToPx(), rise.roundToPx()) }) { content() }
        // Only the part inside the white tray is tappable: the headroom above it (where a picked
        // pen rises) stays part of the page, so writing or scrolling just above the tray works.
        Box(
            Modifier.align(Alignment.BottomCenter).size(44.dp, 76.dp)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .semantics { contentDescription = label },
        )
    }
}

// ---- The pen drawings (40 x 120 grid, tip up) ----

private class Parts(val body: Path, val nib: Path, val tip: Path, val band: Path, val hi: Path, val ring: Path)

private fun p(d: String): Path = if (d.isEmpty()) Path() else PathParser().parsePathString(d).toPath()

private val PARTS = mapOf(
    "Ballpoint" to Parts(p("M14 44h12v76H14z"), p("M14 44L18.6 20H21.4L26 44Z"), p("M18.6 20L20 13L21.4 20Z"), p("M14 50h12v6H14z"), p("M16 46h2.4v74H16z"), p("")),
    "Fountain" to Parts(p("M13 48h14v72H13z"), p("M14 48L15.6 32H24.4L26 48Z"), p("M15.6 32C16 24 18.6 14 20 6C21.4 14 24 24 24.4 32Z"), p("M13 54h14v6H13z"), p("M15 50h2.4v70H15z"), p("M20 13V28")),
    "Pencil" to Parts(p("M13 46h14v74H13z"), p("M13 46L18.4 18H21.6L27 46Z"), p("M18.4 18L20 10L21.6 18Z"), p("M13 46h14v2H13z"), p("M15.5 48h2.4v72h-2.4z"), p("")),
    "Marker" to Parts(p("M12 50h16v70H12z"), p("M13 50L15 34H25L27 50Z"), p("M15.5 34C15.5 22 24.5 22 24.5 34Z"), p("M12 58h16v10H12z"), p("M14.5 52h2.6v68h-2.6z"), p("")),
    "Highlighter" to Parts(p("M11 52h18v68H11z"), p("M12 52L14 38H26L28 52Z"), p("M14.5 38V28L25.5 21V38Z"), p("M11 60h18v14H11z"), p("M13.5 54h2.6v66h-2.6z"), p("")),
    "Eraser" to Parts(p("M10 40h20v80H10z"), p("M11 40V22A5 5 0 0 1 16 17H24A5 5 0 0 1 29 22V40Z"), p(""), p("M10 46h20v6H10z"), p("M12.5 42h2.6v78h-2.6z"), p("")),
    "Lasso" to Parts(p("M14 46h12v74H14z"), p("M14 46L18 30H22L26 46Z"), p("M18 30L20 24L22 30Z"), p("M14 52h12v5H14z"), p("M16 48h2.4v72H16z"), p("M20 24C9 21 8 7 20 7C32 7 31 21 20 24")),
)

@Composable
private fun Instrument(kind: String, ink: Color) {
    val parts = PARTS.getValue(kind)
    val animatedInk by animateColorAsState(ink, tween(250), label = "ink")
    var body = Color(0xFFF6F4F0)
    var nib = Color(0xFFE4E0DA)
    var tip = animatedInk
    var band = animatedInk
    var ring = Color.Transparent
    when (kind) {
        "Ballpoint" -> tip = Color(0xFF6F6A64)
        "Fountain" -> { nib = Color(0xFF3A3835); tip = Color(0xFFD8B25A); ring = Color(0xFF8C6A22) }
        "Pencil" -> { body = animatedInk; nib = Color(0xFFEAC995); band = Color(0x2E000000) }
        "Eraser" -> { nib = Color(0xFFF3A8A0); band = Color(0xFFA39D95) }
        "Lasso" -> { tip = Color(0xFF2B59C3); band = Color(0xFF2B59C3); ring = Color(0xFF2B59C3) }
    }
    val ringStyle = Stroke(1.4f, pathEffect = if (kind == "Lasso") PathEffect.dashPathEffect(floatArrayOf(3f, 2.4f)) else null)
    Canvas(Modifier.size(34.dp, 102.dp)) {
        scale(size.width / 40f, size.height / 120f, pivot = Offset.Zero) {
            translate1(parts.body) // soft shadow
            drawPath(parts.body, body)
            drawPath(parts.body, Color(0xFFD2CDC6), style = Stroke(0.8f))
            drawPath(parts.nib, nib)
            drawPath(parts.nib, Color(0xFFCFC9C1), style = Stroke(0.8f))
            drawPath(parts.tip, tip)
            drawPath(parts.band, band)
            drawPath(parts.hi, Color.White.copy(alpha = 0.55f))
            drawPath(parts.ring, ring, style = ringStyle)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.translate1(path: Path) {
    drawContext.canvas.save()
    drawContext.canvas.translate(1.2f, 0.8f)
    drawPath(path, Color(0x22000000))
    drawContext.canvas.restore()
}

// ---- Option panels ----

@Composable
private fun PenOptions(ink: InkController) {
    val c = Margin.colors
    val style = ink.currentStyle ?: return
    val n = when (ink.tool) { Tool.Pen0 -> 1; Tool.Pen1 -> 2; else -> 3 }
    Column {
        Header("Pen $n", style.type.label())
        Preview(style.type, Color(style.color), style.size)
        Label("Type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PEN_TYPES.forEach { t ->
                val on = style.type == t
                val border by animateColorAsState(if (on) c.accent else c.line, tween(150), label = "tile")
                Column(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.accentSoft else Color.Transparent)
                        .border(1.5.dp, border, RoundedCornerShape(12.dp))
                        .selectable(selected = on, role = Role.RadioButton) { ink.updateCurrentStyle { it.copy(type = t) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Squiggle(t, c.text, Modifier.size(52.dp, 22.dp))
                    Text(t.label(), fontSize = 12.sp, color = if (on) c.text else c.textMuted)
                }
            }
        }
        Label("Size")
        SizeSlider(style.size, 0.5f..6f) { v -> ink.updateCurrentStyle { it.copy(size = v) } }
        Label("Color")
        Swatches(PEN_COLORS, style.color) { col -> ink.updateCurrentStyle { it.copy(color = col) } }
    }
}

@Composable
private fun HighlighterOptions(ink: InkController) {
    val style = ink.highlighter
    Column {
        Header("Highlighter", "Sits behind the text")
        Preview(PenType.Highlighter, Color(style.color), style.size)
        Label("Size")
        SizeSlider(style.size, 6f..24f) { v -> ink.highlighter = style.copy(size = v) }
        Label("Color")
        Swatches(HL_COLORS, style.color) { col -> ink.highlighter = style.copy(color = col) }
    }
}

@Composable
private fun EraserOptions(ink: InkController, onClearPage: () -> Unit) {
    val c = Margin.colors
    Column {
        // Area | Stroke, with a sliding white indicator
        Box(Modifier.fillMaxWidth().height(44.dp).background(c.surfaceAlt, RoundedCornerShape(12.dp)).padding(4.dp)) {
            val x by animateDpAsState(if (ink.eraserMode == EraserMode.Area) 0.dp else 144.dp, tween(250, easing = EaseOutSmooth), label = "seg")
            Box(Modifier.offset(x = x).size(144.dp, 36.dp).shadow(1.dp, RoundedCornerShape(9.dp)).background(c.surface, RoundedCornerShape(9.dp)))
            Row {
                listOf(EraserMode.Area to "Area", EraserMode.Stroke to "Stroke").forEach { (mode, text) ->
                    val on = ink.eraserMode == mode
                    Box(
                        Modifier.size(144.dp, 36.dp).clip(RoundedCornerShape(9.dp))
                            .selectable(selected = on, role = Role.RadioButton) { ink.eraserMode = mode },
                        contentAlignment = Alignment.Center,
                    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.text else c.textMuted) }
                }
            }
        }
        if (ink.eraserMode == EraserMode.Area) {
            Label("Size")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { SizeSlider(ink.eraserSizeDp, 16f..120f) { ink.eraserSizeDp = it } }
                Box(Modifier.size(88.dp).background(c.surfaceAlt.copy(alpha = 0.5f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    val ring by animateDpAsState((ink.eraserSizeDp * 0.7f).dp, tween(120), label = "ring")
                    Box(Modifier.size(ring).border(2.dp, c.accent, CircleShape).background(c.accent.copy(alpha = 0.08f), CircleShape))
                }
            }
        } else {
            Text(
                "Touch any line to remove the whole stroke at once.",
                fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 14.dp),
            )
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(c.line))
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Clear page", onClick = onClearPage),
            contentAlignment = Alignment.Center,
        ) { Text("Clear page", color = c.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Header(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Margin.colors.text)
        Spacer(Modifier.weight(1f))
        Text(sub, fontSize = 12.sp, color = Margin.colors.textMuted)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Margin.colors.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun SizeSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = Margin.colors
    Slider(
        value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceAlt),
        modifier = Modifier.height(32.dp),
    )
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    val c = Margin.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { col ->
            val on = col == selected
            val ring by animateColorAsState(if (on) c.accent else Color.Transparent, tween(150), label = "swatch")
            Box(
                Modifier.size(32.dp).border(2.dp, ring, CircleShape).padding(4.dp).clip(CircleShape).background(Color(col))
                    .selectable(selected = on, role = Role.RadioButton) { onPick(col) },
            )
        }
    }
}

/** A sample line in the current pen, as a quick preview of type, size and color. */
@Composable
private fun Preview(type: PenType, color: Color, size: Float) {
    val c = Margin.colors
    Box(Modifier.fillMaxWidth().height(56.dp).background(c.surfaceAlt.copy(alpha = 0.45f), RoundedCornerShape(12.dp))) {
        Squiggle(type, color, Modifier.matchParentSize().padding(horizontal = 14.dp, vertical = 10.dp), width = size * 2.2f)
    }
}

@Composable
private fun Squiggle(type: PenType, color: Color, modifier: Modifier, width: Float? = null) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0.04f * w, 0.62f * h)
            cubicTo(0.18f * w, 0.05f * h, 0.30f * w, 0.95f * h, 0.46f * w, 0.48f * h)
            cubicTo(0.62f * w, 0.05f * h, 0.78f * w, 0.40f * h, 0.96f * w, 0.52f * h)
        }
        val base = width?.dp?.toPx() ?: 2.dp.toPx()
        val (strokeWidth, alpha) = when (type) {
            PenType.Ballpoint -> base * 0.7f to 1f
            PenType.Fountain -> base to 1f
            PenType.Pencil -> base * 0.8f to 0.7f
            PenType.Marker -> base * 1.8f to 0.92f
            PenType.Highlighter -> base * 2.2f to 0.45f
        }
        drawPath(path, color.copy(alpha = alpha), style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}
