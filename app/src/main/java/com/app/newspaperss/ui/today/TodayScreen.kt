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
import androidx.compose.material3.LinearProgressIndicator
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun TodayScreen(viewModel: TodayViewModel, today: LocalDate = LocalDate.now(), onOpenEdition: (Long) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    fun launch(intent: android.content.Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open an EPUB.", Toast.LENGTH_LONG).show()
        }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Masthead(today, Modifier.padding(top = 24.dp, bottom = 16.dp)) }
        item { BuildPanel(state.build, onMake = viewModel::makeOneNow) }
        val editions = state.editions.orEmpty()
        val latest = editions.firstOrNull()
        if (latest != null) {
            item {
                LatestEdition(
                    latest,
                    onDetails = { onOpenEdition(latest.id) },
                    onSend = { viewModel.fileOf(latest)?.let { launch(EditionIntents.share(context, it, latest.title)) } },
                    onOpen = { viewModel.fileOf(latest)?.let { launch(EditionIntents.open(context, it)) } },
                    onSent = { viewModel.markSent(latest) },
                )
            }
        }
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
        Text("newspaperss", style = MaterialTheme.typography.displaySmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 2.dp)
        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), style = MaterialTheme.typography.labelLarge)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun BuildPanel(build: BuildState, onMake: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (build) {
            BuildState.Syncing, is BuildState.Fetching -> {
                Text(
                    if (build is BuildState.Fetching) "Fetching articles… ${build.done} so far" else "Checking your sources…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> {
                Button(onClick = onMake) { Text("Make an edition now") }
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
private fun LatestEdition(edition: EditionEntity, onDetails: () -> Unit, onSend: () -> Unit, onOpen: () -> Unit, onSent: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onDetails)) {
                Text(edition.title, style = MaterialTheme.typography.headlineSmall)
                Text(summary(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            when (edition.status) {
                EditionStatus.READY -> {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSend) { Text("Send") }
                        OutlinedButton(onClick = onOpen) { Text("Open") }
                    }
                    Text(
                        "Once it's on your e-reader, tell us so these articles don't come back.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = onSent) { Text("I've sent it") }
                }
                EditionStatus.DELIVERED -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSend) { Text("Send again") }
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                }
                EditionStatus.FAILED -> Text(
                    edition.error ?: "This edition couldn't be made.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
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

internal fun summary(edition: EditionEntity): String {
    val date = edition.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val status = when (edition.status) {
        EditionStatus.READY -> "ready to send"
        EditionStatus.DELIVERED -> "sent"
        EditionStatus.FAILED -> "not sent"
        EditionStatus.BUILDING -> "being made"
    }
    if (edition.articleCount == 0) return "$date · $status"
    val articles = if (edition.articleCount == 1) "1 article" else "${edition.articleCount} articles"
    return "$date · $articles · about ${edition.minutes.roundToInt().coerceAtLeast(1)} min · $status"
}
