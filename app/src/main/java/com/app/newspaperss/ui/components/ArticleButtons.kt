package com.app.newspaperss.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * An article's buttons, under its details: the star, and "Mark as read" when [onMarkRead] is
 * given. They wrap onto a second line at large font sizes rather than truncating.
 */
@Composable
fun ArticleButtons(title: String, starred: Boolean, onStar: (Boolean) -> Unit, modifier: Modifier = Modifier, onMarkRead: (() -> Unit)? = null) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
        NextEditionToggle(title, starred, onStar)
        if (onMarkRead != null) {
            TextButton(
                onClick = onMarkRead,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mark $title as read" },
            ) { Text("Mark as read") }
        }
    }
}

/**
 * "Next edition", with a hollow star, or a filled one on a filled pill once starred. The same
 * words both ways: the shape and fill carry the state, so it reads on e-ink without colour, and
 * the glyph swaps at once instead of relying on a ripple. The title is in its label because
 * explore-by-touch can land on it without hearing the row first.
 */
@Composable
fun NextEditionToggle(title: String, starred: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val fill = if (starred) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, shape) else Modifier
    Row(
        Modifier.heightIn(min = 48.dp)
            .clip(shape)
            .then(fill)
            .toggleable(value = starred, role = Role.Switch, onValueChange = onToggle)
            .semantics {
                contentDescription = "Put $title in your next edition"
                stateDescription = if (starred) "Starred" else "Not starred"
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (starred) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
        // Cleared: the toggle's own label and state say it, without "black star".
        Text(if (starred) "★" else "☆", color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(8.dp))
        Text("Next edition", color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
    }
}
