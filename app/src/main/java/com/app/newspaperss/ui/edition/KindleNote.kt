package com.app.newspaperss.ui.edition

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.newspaperss.delivery.KindleSend

internal const val KINDLE_NOTE = "Sent to Kindle. It can take a few minutes to show up in your library."
internal const val KINDLE_EMAIL_NOTE = "Emailed to your Kindle. It can take a few minutes to arrive; it shows up by itself."

/** Shown for a while after a send to a Kindle (see [com.app.newspaperss.delivery.KindleSends]). */
@Composable
fun KindleNote(how: KindleSend) {
    Text(
        when (how) {
            KindleSend.APP -> KINDLE_NOTE
            KindleSend.EMAIL -> KINDLE_EMAIL_NOTE
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}
