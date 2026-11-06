package app.margin.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The app's few color tokens (same palette as the mockups). */
@Immutable
data class MarginColors(
    val background: Color,
    val desk: Color, // behind PDF pages
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
)

private val Light = MarginColors(
    background = Color(0xFFF7F6F4),
    desk = Color(0xFFE8E6E2),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFECE9E4),
    line = Color(0xFFDEDAD4),
    text = Color(0xFF1C1B1A),
    textMuted = Color(0xFF5F5B57),
    accent = Color(0xFF2B59C3),
    accentSoft = Color(0xFFE3E9F8),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
)

private val Dark = MarginColors(
    background = Color(0xFF161514),
    desk = Color(0xFF0F0E0D),
    surface = Color(0xFF242321),
    surfaceAlt = Color(0xFF2F2D2B),
    line = Color(0xFF3B3936),
    text = Color(0xFFECE9E4),
    textMuted = Color(0xFFA9A39B),
    accent = Color(0xFF9DB4F5),
    accentSoft = Color(0xFF263052),
    onAccent = Color(0xFF0E1838),
    danger = Color(0xFFF2B8B5),
)

private val LocalMarginColors = staticCompositionLocalOf { Light }

object Margin {
    val colors: MarginColors
        @Composable @ReadOnlyComposable get() = LocalMarginColors.current
}

/** Ease-out curve used for all UI motion: quick start, gentle stop, no bounce. */
val EaseOutSmooth = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun MarginTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textMuted,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.background,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textMuted,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerLow = c.background,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalMarginColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
