package com.app.newspaperss.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shown above the articles while unstarring and marking read are held; the dimmed controls alone would be a colour-only cue. */
const val BUILDING_NOTE = "Your edition is being made. Mark as read and unstarring come back once it's ready."

/** A row icon's size: it grows with the text, to 32dp at 200%, while its touch target stays 48dp. */
@Composable
fun rowIconSize(): Dp = 24.dp * LocalDensity.current.fontScale.coerceIn(1f, 4f / 3f)

/**
 * An article row: [leading] (a status mark or a checkbox) in a slot of fixed width, the title and
 * details, and a 48dp trailing slot whose centre sits on the title's first line, so it doesn't
 * move when the details wrap. With [reserveTrailing] the slot stays even when [trailing] is null,
 * so titles line up across rows. Swapping [leading] never moves the text, which on e-ink would
 * redraw the whole list.
 */
@Composable
fun ArticleRowFrame(
    title: @Composable () -> Unit,
    details: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    reserveTrailing: Boolean = trailing != null,
) {
    val titleLine = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = if (reserveTrailing) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp)) {
        if (leading != null) {
            Box(Modifier.width(rowIconSize()).height(titleLine), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            title()
            details()
        }
        if (reserveTrailing) {
            val shift = titleLine / 2 - 24.dp
            Box(
                Modifier
                    // Lifted into the row's top padding, and only the part left below counts
                    // towards the row's height, so a short row isn't made taller by it.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val dy = shift.roundToPx()
                        layout(placeable.width, (placeable.height + dy).coerceAtLeast(0)) { placeable.place(0, dy) }
                    }
                    .padding(start = 4.dp)
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) { trailing?.invoke() }
        }
    }
}

/**
 * The star that puts an article in the next edition: an outline, or filled on an outlined disc
 * once starred. Shape and fill carry the state, so it reads on e-ink without colour; the disc's
 * outline keeps it visible where the container colour turns almost white. The title is in its
 * label because explore-by-touch can land on it without hearing the row first.
 */
@Composable
fun StarToggle(title: String, starred: Boolean, onToggle: (Boolean) -> Unit, enabled: Boolean = true) {
    val icon = rowIconSize()
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Box(
        Modifier.size(48.dp)
            .toggleable(
                value = starred,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 24.dp),
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics {
                contentDescription = "Put $title in your next edition"
                stateDescription = if (starred) "Starred" else "Not starred"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (starred) {
            Box(
                Modifier.size(40.dp + (icon - 24.dp) / 2).alpha(alpha)
                    .border(1.dp, colors.outline, CircleShape)
                    .background(colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(icon), tint = colors.onSecondaryContainer) }
        } else {
            Icon(Icons.Outlined.StarOutline, contentDescription = null, modifier = Modifier.size(icon).alpha(alpha), tint = colors.onSurfaceVariant)
        }
    }
}
