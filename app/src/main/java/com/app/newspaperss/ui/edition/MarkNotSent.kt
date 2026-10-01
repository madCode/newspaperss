package com.app.newspaperss.ui.edition

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * For a sent edition that never reached the e-reader: a failed Send to Kindle still counts as
 * sent, and would otherwise leave its articles used up. Asks first, because it changes what the
 * next edition holds.
 */
@Composable
fun MarkNotSent(title: String, onConfirm: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    // No start padding, so it lines up with the text and buttons above rather than sitting indented.
    TextButton(onClick = { asking = true }, contentPadding = PaddingValues(end = 12.dp)) { Text("Didn't arrive? Mark as not sent") }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Mark “$title” as not sent?") },
            text = { Text("It goes back to ready to send, so you can send it again. If you don't, its articles go into your next edition.") },
            confirmButton = { TextButton(onClick = { asking = false; onConfirm() }) { Text("Mark as not sent") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
