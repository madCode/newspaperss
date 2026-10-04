package com.app.newspaperss.ui.edition

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.newspaperss.delivery.KindleSend

private const val SENT = "Sent to Kindle. It can take a few minutes to show up in your library."
private const val EMAILED = "Emailed to your Kindle. It can take a few minutes to arrive; it shows up by itself."
private const val NOT_THERE = "Not there after 20 minutes? Use ⋮ to mark it as not sent."
// TalkBack would read the glyph as "vertical ellipsis"; this names the button instead.
private const val NOT_THERE_SPOKEN = "Not there after 20 minutes? Use More options to mark it as not sent."
internal const val KINDLE_NOTE = "$SENT $NOT_THERE"
internal const val KINDLE_EMAIL_NOTE = "$EMAILED $NOT_THERE"

/** Shown for a while after a send to a Kindle (see [com.app.newspaperss.delivery.KindleSends]). */
@Composable
fun KindleNote(how: KindleSend) {
    val first = when (how) {
        KindleSend.APP -> SENT
        KindleSend.EMAIL -> EMAILED
    }
    Text(
        "$first $NOT_THERE",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp).semantics { contentDescription = "$first $NOT_THERE_SPOKEN" },
    )
}
