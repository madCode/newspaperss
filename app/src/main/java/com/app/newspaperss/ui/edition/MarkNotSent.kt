package com.app.newspaperss.ui.edition

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The menu item on a sent edition that opens [MarkNotSentDialog]. */
const val MARK_NOT_SENT = "Didn't arrive? Mark as not sent"

/**
 * For a sent edition that never reached the e-reader: a failed Send to Kindle still counts as
 * sent, and would otherwise leave its articles used up. Asks first, because it changes what the
 * next edition holds.
 */
@Composable
fun MarkNotSentDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark “$title” as not sent?") },
        text = { Text("It goes back to ready to send, so you can send it again. If you don't, its articles go into your next edition.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Mark as not sent") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
