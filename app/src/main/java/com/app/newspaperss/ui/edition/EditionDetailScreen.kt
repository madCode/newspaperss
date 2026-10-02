package com.app.newspaperss.ui.edition

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.ui.components.ArticleRowFrame
import com.app.newspaperss.ui.components.StarToggle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.ui.today.failureColor
import com.app.newspaperss.delivery.EditionEmail
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.settings.KindleEmail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * @param preferOpen the reader reads on this device (a Boox), so opening an edition delivers it.
 * @param offerOpen false for a Kindle or Kobo, whose reader sends the book rather than opening it here.
 * @param kindleEmail Send emails the edition to this Kindle address rather than sharing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditionDetailScreen(
    viewModel: EditionDetailViewModel,
    onBack: () -> Unit,
    onReadArticle: (position: Int) -> Unit = {},
    preferOpen: Boolean = false,
    offerOpen: Boolean = true,
    kindleEmail: KindleEmail? = null,
) {
    val detail by viewModel.detail.collectAsState()
    val message by viewModel.message.collectAsState()
    val building by viewModel.building.collectAsState()
    val sentToKindle by viewModel.sentToKindle.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No reading app on this phone can open the edition. Try Send instead.", Toast.LENGTH_LONG).show()
        false
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

    var deleting by remember { mutableStateOf(false) }
    detail?.edition?.takeIf { deleting }?.let { edition ->
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${edition.title}?") },
            text = {
                Text(
                    when (edition.status) {
                        EditionStatus.READY -> "It hasn't been sent, so its articles go into your next edition."
                        EditionStatus.DELIVERED -> "Its articles won't come back. The copy on your e-reader stays."
                        else -> "The file on this phone is deleted too."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete(onBack) }) { Text("Delete edition") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
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
                    val status = detail?.edition?.status
                    // Rare and destructive, so in the menu rather than beside Notes, where a slow
                    // e-ink refresh makes a mis-tap easy.
                    if (status != null && status != EditionStatus.BUILDING) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Delete edition") }, onClick = { menu = false; deleting = true })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = detail ?: return@Scaffold
        val edition = current.edition
        if (edition == null) {
            Text("This edition has been deleted.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Header(
                    edition,
                    fileMissing = current.file == null,
                    sentToKindle = sentToKindle,
                    onSend = {
                        current.file?.let {
                            val body = kindleEmail?.let { EditionEmail.body(edition.title, current.contents.map(EditionContent::entry)) }
                            EditionIntents.launchSend(context, it, edition.title, edition.id, kindleEmail, body, onMailAppOpened = viewModel::markEmailed)
                        }
                    },
                    onOpen = if (offerOpen) {
                        { current.file?.let { if (launch(EditionIntents.open(context, it)) && preferOpen) viewModel.markSent() } }
                    } else null,
                    onSent = viewModel::markSent,
                    onNotSent = viewModel::markNotSent,
                )
            }
            if (current.contents.isNotEmpty()) {
                item {
                    Text("Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp))
                }
                if (current.contents.any(current::canStar)) {
                    item {
                        Text(
                            "Didn't get to one? Tap ☆ to bring it back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics {
                                contentDescription = "Didn't get to one? Tap the star to bring it back."
                            },
                        )
                        if (building) {
                            Text(BUILDING_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            val anyStar = current.contents.any(current::canStar)
            items(current.contents, key = { it.entry.id }) { content ->
                val articleId = content.entry.articleId
                ContentRow(
                    content,
                    canStar = current.canStar(content),
                    starred = current.isStarred(content),
                    onStar = { if (articleId != null) viewModel.setStarred(articleId, it) },
                    building = building,
                    reserveStar = anyStar,
                    onOpen = if (current.file != null) { { onReadArticle(content.entry.position) } } else null,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Header(edition: EditionEntity, fileMissing: Boolean, sentToKindle: KindleSend?, onSend: () -> Unit, onOpen: (() -> Unit)?, onSent: () -> Unit, onNotSent: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(edition.title, style = MaterialTheme.typography.headlineSmall)
        Text(dateOf(edition.createdAt), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text(statusOf(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (edition.status == EditionStatus.FAILED) {
            Text(edition.error ?: "This edition couldn't be made.", color = failureColor(edition.error))
        }
        if (edition.articleCount > 0) {
            val articles = if (edition.articleCount == 1) "1 article" else "${edition.articleCount} articles"
            Text("$articles · about ${minutes(edition.minutes)} min", style = MaterialTheme.typography.bodyMedium)
        }
        if (edition.status == EditionStatus.DELIVERED) sentToKindle?.let { KindleNote(it) }
        if (edition.status == EditionStatus.READY || edition.status == EditionStatus.DELIVERED) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edition.status == EditionStatus.READY) {
                    Button(onClick = onSend, enabled = !fileMissing) { Text("Send") }
                } else {
                    OutlinedButton(onClick = onSend, enabled = !fileMissing) { Text("Send again") }
                }
                if (onOpen != null) OutlinedButton(onClick = onOpen, enabled = !fileMissing) { Text("Open") }
            }
            if (fileMissing) {
                Text(
                    "This edition's file has been deleted, so it can't be sent or opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (edition.status == EditionStatus.DELIVERED) {
                MarkNotSent(edition.title, onNotSent)
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

/** @param reserveStar keeps the star's slot on a row without one, so titles line up with those that have it. */
@Composable
private fun ContentRow(
    content: EditionContent,
    canStar: Boolean,
    starred: Boolean,
    onStar: (Boolean) -> Unit,
    building: Boolean,
    reserveStar: Boolean,
    onOpen: (() -> Unit)?,
) {
    val entry = content.entry
    // Words, not the glyph, so it isn't mistaken for the toggle beside it; past tense, since that
    // toggle is about the next edition and may be off.
    val details = listOfNotNull(entry.sourceTitle, "${minutes(entry.minutes)} min", "You starred it".takeIf { entry.starred }).joinToString(" · ")
    ArticleRowFrame(
        modifier = if (onOpen != null) Modifier.clickable(onClickLabel = "Read", onClick = onOpen) else Modifier,
        title = { Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        details = {
            Column {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (starred) {
                    Text("Starred for your next edition", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailing = if (canStar) {
            { StarToggle(entry.title, starred, onStar, enabled = !(building && starred)) }
        } else null,
        reserveTrailing = canStar || reserveStar,
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
    EditionStatus.DELETED -> "Deleted"
}
