package app.margin.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 44dp icon button; [active] gives it the soft accent background (e.g. Pages panel open). */
@Composable
fun IconBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Margin.colors
    val bg by animateColorAsState(if (active) c.accentSoft else Color.Transparent, tween(200), label = "bg")
    val fg by animateColorAsState(
        when {
            !enabled -> c.textMuted.copy(alpha = 0.4f)
            active -> c.accent
            else -> c.text
        },
        tween(200),
        label = "fg",
    )
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** "12 minutes ago", "Yesterday", "Oct 28"... */
fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH,
    ).toString()
