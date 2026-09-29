package com.app.newspaperss.ui.today

import android.content.ActivityNotFoundException
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Masthead(today, Modifier.padding(top = 24.dp, bottom = 16.dp)) }
        state.next?.let { next ->
            item { Text("Next edition: $next", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        if (state.editions != null && latest == null && state.build == BuildState.Idle) {
            item {
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
        if (!readyWaiting) item { BuildPanel(state.build, primary = true, onMake = viewModel::makeOneNow) }
        if (latest != null) {
            item {
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
        if (readyWaiting) item { BuildPanel(state.build, primary = false, onMake = viewModel::makeOneNow) }
        if (editions.size > 1) {
            item {
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

@Composable
private fun BuildPanel(build: BuildState, primary: Boolean, onMake: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (build) {
            BuildState.Syncing, is BuildState.Fetching -> {
                // Stepped text rather than a spinner: an endless animation smears on e-ink screens.
                Text(
                    when {
                        build is BuildState.Fetching && build.done == 1 -> "Making your edition: 1 article read so far"
                        build is BuildState.Fetching -> "Making your edition: ${build.done} articles read so far"
                        else -> "Checking your sources for new articles…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                if (primary) Button(onClick = onMake) { Text("Make an edition now") }
                else TextButton(onClick = onMake, modifier = Modifier.padding(top = 8.dp)) { Text("Make another edition") }
                val message = when (build) {
                    BuildState.NothingNew -> "Nothing new to read yet. Add sources, or check back later."
                    is BuildState.Failed -> build.reason
                    else -> null
                }
                message?.let {
                    Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
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
            Column(Modifier.fillMaxWidth().clickable(onClick = onDetails)) {
                Text(edition.title, style = MaterialTheme.typography.headlineSmall)
                Text(summary(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
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
                EditionStatus.BUILDING -> {}
            }
        }
    }
}

@Composable
private fun EditionRow(edition: EditionEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
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
    }
    if (edition.articleCount == 0) return status.replaceFirstChar { it.uppercase() }
    val articles = if (edition.articleCount == 1) "1 article" else "${edition.articleCount} articles"
    return "$articles · about ${edition.minutes.roundToInt().coerceAtLeast(1)} min · $status"
}
