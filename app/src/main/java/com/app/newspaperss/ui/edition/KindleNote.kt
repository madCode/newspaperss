package com.app.newspaperss.ui.edition

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val KINDLE_NOTE = "Sent to Kindle. It can take a few minutes to show up in your library."

/** Shown for a while after a send with the Kindle app (see [com.app.newspaperss.delivery.KindleSends]). */
@Composable
fun KindleNote() {
    Text(KINDLE_NOTE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
}
