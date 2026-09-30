package com.app.newspaperss.ui.today

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.EditionIntents
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun TodayScreen(viewModel: TodayViewModel, today: LocalDate = LocalDate.now(), onOpenEdition: (Long) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    fun launch(intent: android.content.Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No reading app on this phone can open the edition. Try Send instead.", Toast.LENGTH_LONG).show()
        false
    }
    val editions = state.editions.orEmpty()
    val latest = editions.firstOrNull()
    // Remembered here, not in the panel, which moves below the card once an edition is ready.
    val announcer = remember { BuildAnnouncer() }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Keyed, so an item keeps its state when others come and go above it: the build panel's
        // status line has to stay the same node for TalkBack to announce its changes.
        item(key = "masthead") { Masthead(today, Modifier.padding(top = 24.dp, bottom = 16.dp)) }
        state.next?.let { next ->
            item(key = "next") { Text("Next edition: $next", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        if (state.editions != null && latest == null && state.build == BuildState.Idle) {
            item(key = "firstPrompt") {
                Text(
                    if (state.next != null) "Your first edition arrives on schedule. Or make one now." else "Make your first edition whenever you're ready.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        // A ready edition waiting to be sent comes first; making another is secondary.
        val readyWaiting = latest?.status == EditionStatus.READY
        // The same key in both places: only one exists at a time, and it moves rather than restarts.
        if (!readyWaiting) item(key = "build") { BuildPanel(state.build, announcer, primary = true, onMake = viewModel::makeOneNow) }
        if (latest != null) {
            item(key = "latest") {
                LatestEdition(
                    latest,
                    first = editions.size == 1,
                    deviceName = state.deviceName,
                    preferOpen = state.preferOpen,
                    onRetry = viewModel::makeOneNow,
                    onDetails = { onOpenEdition(latest.id) },
                    onSend = { viewModel.fileOf(latest)?.let { launch(EditionIntents.share(context, it, latest.title, latest.id)) } },
                    onOpen = {
                        viewModel.fileOf(latest)?.let {
                            // Reading here is how an edition reaches a Boox.
                            if (launch(EditionIntents.open(context, it)) && state.preferOpen) viewModel.markSent(latest)
                        }
                    },
                    onSent = { viewModel.markSent(latest) },
                )
            }
        }
        if (readyWaiting) item(key = "build") { BuildPanel(state.build, announcer, primary = false, onMake = viewModel::makeOneNow) }
        if (editions.size > 1) {
            item(key = "earlier") {
                Text(
                    "Earlier editions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                )
            }
            items(editions.drop(1), key = { it.id }) { edition ->
                EditionRow(edition, onClick = { onOpenEdition(edition.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun Masthead(date: LocalDate, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("newspapeRSS", style = MaterialTheme.typography.displaySmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 2.dp)
        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), style = MaterialTheme.typography.labelLarge)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

internal const val BUILD_STATUS = "buildStatus"

/**
 * What the build status has seen while Today was up. A plain holder, not state: it only has to be
 * right when the status line is next composed, which a build's every change causes.
 */
private class BuildAnnouncer {
    /** A build ran while the screen was up; until then a result is an old one WorkManager kept. */
    var sawRunning = false
    /** That build then finished successfully. */
    var finished = false

    fun update(build: BuildState) {
        val running = build == BuildState.Syncing || build == BuildState.WaitingForNetwork || build is BuildState.Fetching
        if (running) finished = false
        else if (sawRunning && build == BuildState.Idle) finished = true
        if (running) sawRunning = true
    }
}

@Composable
private fun BuildPanel(build: BuildState, announcer: BuildAnnouncer, primary: Boolean, onMake: () -> Unit) {
    val running = build == BuildState.Syncing || build == BuildState.WaitingForNetwork || build is BuildState.Fetching
    // Announced only once a build has run while this screen was up: a failure WorkManager still
    // remembers from earlier would otherwise be read out every time Today opens.
    announcer.update(build)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!running) {
            if (primary) Button(onClick = onMake) { Text("Make an edition now") }
            else TextButton(onClick = onMake, modifier = Modifier.padding(top = 8.dp)) { Text("Make another edition") }
        }
        // One status line, always composed, whose text changes: TalkBack announces a change to a
        // live region, not one appearing. Stepped text rather than a spinner, which smears on e-ink.
        Text(
            when (build) {
                BuildState.Syncing -> "Checking your sources for new articles…"
                BuildState.WaitingForNetwork -> "Waiting for an internet connection…"
                is BuildState.Fetching -> "Making your edition"
                BuildState.NothingNew -> "Nothing new to read yet. Add sources, or check back later."
                is BuildState.Failed -> build.reason
                BuildState.Idle -> if (announcer.finished) "Your edition is ready." else ""
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).testTag(BUILD_STATUS).semantics { if (announcer.sawRunning) liveRegion = LiveRegionMode.Polite },
        )
        // Outside the live region, or TalkBack would read every count. Kept while syncing too, so
        // the page doesn't shift by a line (an extra refresh on e-ink) when fetching starts.
        if (running) {
            Text(
                (build as? BuildState.Fetching)?.done?.let { if (it == 1) "1 article read so far" else "$it articles read so far" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LatestEdition(
    edition: EditionEntity,
    first: Boolean,
    deviceName: String,
    preferOpen: Boolean,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onSent: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "See what's inside", onClick = onDetails)) {
                Text(edition.title, style = MaterialTheme.typography.headlineSmall)
                Text(summary(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            // The title opens the contents too, but nothing about it says so.
            if (edition.articleCount > 0 && edition.status != EditionStatus.BUILDING) {
                TextButton(onClick = onDetails, contentPadding = PaddingValues(0.dp)) { Text("See what's inside") }
            }
            when (edition.status) {
                EditionStatus.READY -> {
                    if (first) {
                        Text(
                            if (preferOpen) "Your first edition is ready. Tap Open to start reading."
                            else "Your first edition is ready. Tap Send to put it on your $deviceName.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (preferOpen) {
                            Button(onClick = onOpen) { Text("Open") }
                            OutlinedButton(onClick = onSend) { Text("Send") }
                        } else {
                            Button(onClick = onSend) { Text("Send") }
                            OutlinedButton(onClick = onOpen) { Text("Open") }
                        }
                    }
                    Text(
                        if (preferOpen) "Opening it here counts as delivered. Read it another way? Tell us so these articles don't come back."
                        else "Choosing an app to send it with counts as delivered. Sent it another way? Tell us so these articles don't come back.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = onSent) { Text("I've sent it") }
                }
                EditionStatus.DELIVERED -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSend) { Text("Send again") }
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                }
                EditionStatus.FAILED -> {
                    Text(
                        edition.error ?: "This edition couldn't be made.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
                EditionStatus.BUILDING, EditionStatus.DELETED -> {}
            }
        }
    }
}

@Composable
private fun EditionRow(edition: EditionEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "See what's inside", onClick = onClick).padding(vertical = 12.dp)) {
        Text(edition.title, style = MaterialTheme.typography.titleMedium)
        Text(summary(edition), style = MaterialTheme.typography.bodySmall)
    }
}

/** No date: the title has it, for when the edition is due rather than when it was made. */
internal fun summary(edition: EditionEntity): String {
    val status = when (edition.status) {
        EditionStatus.READY -> "ready to send"
        EditionStatus.DELIVERED -> "sent"
        EditionStatus.FAILED -> "not sent"
        EditionStatus.BUILDING -> "being made"
        EditionStatus.DELETED -> "deleted"
    }
    if (edition.articleCount == 0) return status.replaceFirstChar { it.uppercase() }
    val articles = if (edition.articleCount == 1) "1 article" else "${edition.articleCount} articles"
    return "$articles · about ${edition.minutes.roundToInt().coerceAtLeast(1)} min · $status"
}
