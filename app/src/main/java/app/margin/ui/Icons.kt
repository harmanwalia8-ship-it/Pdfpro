package app.margin.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Thin line icons drawn on a 24x24 grid, matching the mockups. Tinted by Icon(). */
object MIcons {
    val Back = line("M15 5l-7 7 7 7")
    val Pages = line("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z")
    val Search = line("M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13zM20 20l-4.2-4.2")
    val Share = line("M12 4v11M7.5 8.5L12 4l4.5 4.5M5 14v5h14v-5")
    val Plus = line("M12 5v14M5 12h14")
    val Close = line("M6 6l12 12M18 6L6 18")
    val Check = line("M5 12.5l4.5 4.5L19 7.5")
    val Arrow = line("M5 12h14M13 6l6 6-6 6")
    val Download = line("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")
    val Settings = line(
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z" +
            "M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7",
    )
    val Star = line("M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z")
    val StarFilled = filled("M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z")
    val Trash = line("M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13")
    val More = filled(
        "M5 10.3a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z" +
            "M12 10.3a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z" +
            "M19 10.3a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z",
    )
    val Nib = line("M5 19l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L9 17.5z M13.5 7l3 3")
    val ChevronDown = line("M6 9l6 6 6-6")
    val Undo = line("M9 14L4 9l5-5 M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Redo = line("M15 14l5-5-5-5 M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val Lock = line("M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5v-6A1.5 1.5 0 0 1 6.5 11z M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val Unlock = line("M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5v-6A1.5 1.5 0 0 1 6.5 11z M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2")

    private fun line(d: String) = build(d, fill = false)
    private fun filled(d: String) = build(d, fill = true)

    private fun build(d: String, fill: Boolean): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(d),
            fill = if (fill) SolidColor(Color.Black) else null,
            stroke = if (fill) null else SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
