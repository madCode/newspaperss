package com.app.newspaperss.ui.listen

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.listen.Listening
import com.app.newspaperss.listen.Unfinished
import kotlinx.coroutines.launch

/**
 * The edition page's way into listening: Listen, Resume where it was left, or back to the player
 * while it plays. Starting a newer edition with an older one left part way offers to finish that
 * first, the way yesterday's paper gets finished before today's.
 *
 * @param minutes each article's reading time, in book order.
 */
@Composable
fun ListenButton(listening: Listening, editionId: Long, minutes: List<Double>, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val state by listening.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<Unfinished?>(null) }
    val here = state.editionId == editionId
    // Read again whenever the player moves, so coming back from it shows where it was left.
    val saved = remember(state.at, state.finished, state.editionId) { listening.saved(editionId) }
    val label = when {
        here && (state.playing || state.loading) -> "Listening · Open player"
        saved != null -> "Resume · ${ReadingTime.format(minutes.drop(saved.page).sumOf(ListenTime::fromReading))} left"
        else -> "Listen · about ${ReadingTime.format(minutes.sumOf(ListenTime::fromReading))}"
    }
    fun start(id: Long) {
        listening.start(id)
        onOpenPlayer()
    }
    OutlinedButton(
        onClick = {
            when {
                here && (state.playing || state.loading) -> onOpenPlayer()
                saved != null -> start(editionId)
                else -> scope.launch {
                    val earlier = listening.earlierUnfinished(editionId)
                    if (earlier != null) offer = earlier else start(editionId)
                }
            }
        },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier,
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 8.dp))
    }
    offer?.let { earlier ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text("Finish ${earlier.title} first?") },
            text = { Text("You stopped part way through it, with about ${ReadingTime.format(earlier.minutesLeft)} left.") },
            confirmButton = { TextButton(onClick = { offer = null; start(earlier.editionId) }) { Text("Finish it") } },
            dismissButton = { TextButton(onClick = { offer = null; start(editionId) }) { Text("Start this one") } },
        )
    }
}
