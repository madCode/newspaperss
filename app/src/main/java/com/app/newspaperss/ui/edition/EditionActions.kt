package com.app.newspaperss.ui.edition

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.EditionIntents

/** An edition's one button: what to do with it next. */
enum class NextStep(val label: String) {
    SEND("Send"),
    /** Reading it on this device is how it's delivered (a Boox). */
    READ_NOW("Read now"),
    /** Sent by reading it here; reading it again. */
    READ("Read"),
    /** Just sent to a Kindle: see that it has arrived. */
    OPEN_KINDLE("Open Kindle"),
}

/** The rest, in the ⋮ menu of Today's card and of the edition's page. */
enum class MoreAction(val label: String) {
    SEND("Send"),
    /** The share sheet, when Send emails the Kindle. */
    SEND_ANOTHER_WAY("Send another way"),
    OPEN_HERE("Open on this phone"),
    OPEN_KINDLE_APP("Open the Kindle app"),
    SEND_AGAIN("Send again"),
    MARK_NOT_SENT(com.app.newspaperss.ui.edition.MARK_NOT_SENT),
}

data class EditionChoices(val next: NextStep?, val more: List<MoreAction>)

/**
 * What an edition offers, the same on Today's card and on its page: one button for the next step,
 * the rare rest in a menu. Without its book ([hasFile] false) there's nothing to send or open,
 * so only the Kindle app is left.
 *
 * @param preferOpen the reader reads on this device (a Boox), so reading it here delivers it.
 * @param offerOpen false for a Kindle or Kobo, whose reader gets it by sending it; opening it on
 *   the phone would only look like a way to read it.
 * @param emailsKindle Send opens the mail app to email it to a Kindle.
 * @param justSentToKindle in the half hour after a send to a Kindle, while it may not have arrived.
 */
fun editionChoices(
    status: EditionStatus,
    preferOpen: Boolean,
    offerOpen: Boolean,
    kindleReader: Boolean,
    emailsKindle: Boolean,
    justSentToKindle: Boolean,
    kindleAppInstalled: Boolean,
    hasFile: Boolean,
): EditionChoices {
    val kindleApp = kindleReader && kindleAppInstalled
    return when (status) {
        EditionStatus.READY -> EditionChoices(
            next = if (preferOpen) NextStep.READ_NOW else NextStep.SEND,
            more = if (!hasFile) emptyList() else buildList {
                if (preferOpen) add(MoreAction.SEND) else if (offerOpen) add(MoreAction.OPEN_HERE)
                if (emailsKindle) add(MoreAction.SEND_ANOTHER_WAY)
            },
        )
        EditionStatus.DELIVERED -> {
            val next = when {
                preferOpen && hasFile -> NextStep.READ
                // Most read on the Kindle itself; the app is worth a button only while checking it arrived.
                kindleApp && justSentToKindle -> NextStep.OPEN_KINDLE
                else -> null
            }
            EditionChoices(
                next = next,
                more = buildList {
                    if (hasFile) {
                        add(MoreAction.SEND_AGAIN)
                        add(MoreAction.MARK_NOT_SENT)
                        if (offerOpen && !preferOpen) add(MoreAction.OPEN_HERE)
                    }
                    if (kindleApp && next != NextStep.OPEN_KINDLE) add(MoreAction.OPEN_KINDLE_APP)
                },
            )
        }
        EditionStatus.FAILED, EditionStatus.BUILDING, EditionStatus.DELETED -> EditionChoices(null, emptyList())
    }
}

/**
 * The Kindle app's launch intent, or null without it; checked again whenever the screen comes
 * back, e.g. from installing or removing it.
 */
@Composable
fun rememberKindleApp(): MutableState<Intent?> {
    val context = LocalContext.current
    val launch = remember { mutableStateOf(EditionIntents.openKindle(context)) }
    LifecycleResumeEffect(Unit) {
        launch.value = EditionIntents.openKindle(context)
        onPauseOrDispose {}
    }
    return launch
}

/** Starts the Kindle app; if it was removed since the screen last checked, says so and forgets it. */
fun startKindle(context: Context, app: MutableState<Intent?>) {
    val intent = app.value ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        app.value = null
        Toast.makeText(context, "The Kindle app isn't on this phone any more.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun NextStepButton(
    step: NextStep,
    enabled: Boolean,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    kindleApp: MutableState<Intent?>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onClick = when (step) {
        NextStep.SEND -> onSend
        NextStep.READ_NOW, NextStep.READ -> onOpen
        NextStep.OPEN_KINDLE -> { { startKindle(context, kindleApp) } }
    }
    // The Kindle app doesn't need the book, so it opens even when the file is gone.
    Button(onClick = onClick, enabled = enabled || step == NextStep.OPEN_KINDLE, modifier = modifier) { Text(step.label) }
}

/** For a send this app can't see: one line, its answer a link-sized button beside it. */
@Composable
fun MarkAsSentLine(preferOpen: Boolean, onSent: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        // Its own TalkBack stop, read just before its button, even inside Today's clickable card.
        Text(
            if (preferOpen) "Read it another way?" else "Sent it another way?",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        TextButton(onClick = onSent, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Mark as sent") }
    }
}
