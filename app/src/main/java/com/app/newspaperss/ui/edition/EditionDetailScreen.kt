package com.app.newspaperss.ui.edition

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.EditionIntents
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditionDetailScreen(viewModel: EditionDetailViewModel, onBack: () -> Unit, onReadArticle: (position: Int) -> Unit = {}) {
    val detail by viewModel.detail.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun launch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No reading app on this phone can open the edition. Try Send instead.", Toast.LENGTH_LONG).show()
        }
    }
    val notesFile by viewModel.notesFile.collectAsState()
    LaunchedEffect(notesFile) {
        val file = notesFile ?: return@LaunchedEffect
        context.startActivity(EditionIntents.shareNotes(context, file, detail?.edition?.title ?: file.nameWithoutExtension))
        viewModel.notesShared()
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edition") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (detail?.contents?.isNotEmpty() == true) {
                        TextButton(onClick = viewModel::writeNotes) { Text("Notes") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = viewModel::bringBack) { Text("Bring back ${selected.size}") }
            }
        },
    ) { padding ->
        val current = detail ?: return@Scaffold
        val edition = current.edition
        if (edition == null) {
            Text("This edition has been deleted.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Header(
                    edition,
                    fileMissing = current.file == null,
                    onSend = { current.file?.let { launch(EditionIntents.share(context, it, edition.title)) } },
                    onOpen = { current.file?.let { launch(EditionIntents.open(context, it)) } },
                    onSent = viewModel::markSent,
                )
            }
            if (current.contents.isNotEmpty()) {
                item {
                    Text("Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp))
                }
                if (current.contents.any(current::canBringBack)) {
                    item {
                        Text(
                            "Didn't get to some? Tick them to bring them back in your next edition.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            items(current.contents, key = { it.entry.id }) { content ->
                val articleId = content.entry.articleId
                ContentRow(
                    content,
                    selectable = current.canBringBack(content),
                    broughtBack = current.wasBroughtBack(content),
                    checked = articleId != null && articleId in selected,
                    onToggle = { if (articleId != null) viewModel.toggle(articleId) },
                    onOpen = if (current.file != null) { { onReadArticle(content.entry.position) } } else null,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Header(edition: EditionEntity, fileMissing: Boolean, onSend: () -> Unit, onOpen: () -> Unit, onSent: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(edition.title, style = MaterialTheme.typography.headlineSmall)
        Text(dateOf(edition.createdAt), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text(statusOf(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (edition.status == EditionStatus.FAILED) {
            Text(edition.error ?: "This edition couldn't be made.", color = MaterialTheme.colorScheme.error)
        }
        if (edition.articleCount > 0) {
            val articles = if (edition.articleCount == 1) "1 article" else "${edition.articleCount} articles"
            Text("$articles · about ${minutes(edition.minutes)} min", style = MaterialTheme.typography.bodyMedium)
        }
        if (edition.status == EditionStatus.READY || edition.status == EditionStatus.DELIVERED) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edition.status == EditionStatus.READY) {
                    Button(onClick = onSend, enabled = !fileMissing) { Text("Send") }
                } else {
                    OutlinedButton(onClick = onSend, enabled = !fileMissing) { Text("Send again") }
                }
                OutlinedButton(onClick = onOpen, enabled = !fileMissing) { Text("Open") }
            }
            if (fileMissing) {
                Text(
                    "This edition's file has been deleted, so it can't be sent or opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (edition.status == EditionStatus.READY) {
            Text(
                "Once it's on your e-reader, tell us so these articles don't come back.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = onSent) { Text("I've sent it") }
        }
    }
}

@Composable
private fun ContentRow(
    content: EditionContent,
    selectable: Boolean,
    broughtBack: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val entry = content.entry
    // Tapping the article previews it; the checkbox alone selects it for bringing back.
    val modifier = when {
        onOpen != null -> Modifier.clickable(onClickLabel = "Read", onClick = onOpen)
        selectable -> Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
        else -> Modifier
    }
    ListItem(
        modifier = modifier,
        leadingContent = if (selectable) {
            { Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.semantics { contentDescription = "Bring back ${entry.title}" }) }
        } else null,
        headlineContent = { Text(entry.title, maxLines = 3) },
        supportingContent = {
            Column {
                Text("${entry.sourceTitle} · ${minutes(entry.minutes)} min", style = MaterialTheme.typography.bodySmall)
                if (broughtBack) {
                    Text(
                        "Brought back for your next edition",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

private fun minutes(value: Double) = value.roundToInt().coerceAtLeast(1)

private fun dateOf(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

private fun statusOf(edition: EditionEntity): String = when (edition.status) {
    EditionStatus.READY -> "Ready to send"
    EditionStatus.DELIVERED -> edition.deliveredAt?.takeIf { dateOf(it) != dateOf(edition.createdAt) }?.let { "Sent on ${dateOf(it)}" } ?: "Sent"
    EditionStatus.FAILED -> "Not sent"
    EditionStatus.BUILDING -> "Being made"
}
