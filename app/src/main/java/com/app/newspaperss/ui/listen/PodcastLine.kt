package com.app.newspaperss.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.ui.settings.SettingsSummary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Under Listen, while Kokoro is in use: how much of the edition's podcast is made, as a bar of its
 * articles filled as each is made, or, for an edition without one, **Make the podcast**.
 *
 * @param minutes each article's reading time, in book order.
 */
@Composable
fun PodcastLine(podcasts: Podcasts, editionId: Long, minutes: List<Double>, modifier: Modifier = Modifier) {
    val progress by remember(editionId, minutes.size) { podcasts.observe(editionId, minutes.size) }.collectAsStateWithLifecycle(null)
    val p = progress ?: return
    val scope = rememberCoroutineScope()
    val heard = minutes.map(ListenTime::fromReading)
    val small = MaterialTheme.typography.bodySmall
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        if (!p.asked) {
            Text("No podcast for this edition yet: it plays in your phone's voice.", style = small, color = soft)
            val making = SettingsSummary.aboutTime(PodcastPace.minutesToMake(minutes.sum().toInt(), p.pace))
            TextButton(onClick = {
                scope.launch {
                    // Asked for and its work started together, even if the page is left meanwhile;
                    // a full disk mustn't take the app down.
                    runCatching { withContext(NonCancellable) { podcasts.make(editionId) } }
                }
            }) { Text("Make the podcast · $making while charging") }
            return@Column
        }
        // The text says the same: TalkBack reads that, not the bar.
        Row(Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            heard.forEachIndexed { i, weight ->
                // An article being made fills as its pieces are kept, every couple of minutes of audio.
                val share = p.share.getOrElse(i) { 0.0 }.toFloat()
                Box(Modifier.weight(weight.toFloat().coerceAtLeast(0.1f)).height(6.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)) {
                    if (share > 0f) Box(Modifier.fillMaxWidth(share).height(6.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
            }
        }
        val made = heard.indices.sumOf { heard[it] * p.share.getOrElse(it) { 0.0 } }
        val anyMade = p.made.any { it }
        val leftOut = p.leftOut.any { it }
        val total = ReadingTime.format(heard.sum())
        // Rounded to whole minutes, nearly done would read as all of it.
        val sofar = if (ReadingTime.format(made) == total) "Almost all of $total made." else "${ReadingTime.format(made)} of $total made."
        val rest = if (leftOut) "The rest plays in your phone's voice until it's made, and articles it left out always do." else "The rest plays in your phone's voice until it's made."
        val text = when {
            p.made.all { it } -> "The podcast is ready."
            p.finished && !anyMade -> "The podcast couldn't make any of this edition, so it plays in your phone's voice."
            p.finished -> "The podcast is ready. Articles it left out, not in English or that it couldn't say, play in your phone's voice."
            p.makingNow -> "Making the podcast now. $sofar $rest"
            p.makingOther -> "Waiting while another edition's podcast is made: this one comes next. " + (if (made > 0.0) "$sofar " else "") + rest
            made == 0.0 -> "Waiting to make the podcast: it's made while the phone charges, and Android can take a few minutes to start. Until then, Listen uses your phone's voice."
            else -> "$sofar It carries on while the phone charges. $rest"
        }
        Text(text, style = small, color = soft, modifier = Modifier.padding(top = 6.dp))
    }
}
