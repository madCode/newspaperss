package com.app.newspaperss.ui.today

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.ui.edition.KindleNote
import com.app.newspaperss.ui.edition.MARK_NOT_SENT
import com.app.newspaperss.ui.edition.MarkNotSentDialog
import com.app.newspaperss.ui.edition.OpenKindleButton
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
        if (state.starredWaiting > 0) {
            item(key = "starred") {
                // Right after a build, these are exactly the stars that didn't fit. The glyph is
                // decoration, so TalkBack reads only the words.
                val words = if (state.starredWaiting == 1) "1 starred article is waiting for your next edition" else "${state.starredWaiting} starred articles are waiting for your next edition"
                Text(
                    "★ $words",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp).clearAndSetSemantics { contentDescription = words },
                )
            }
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
        // A filled button only when there's no edition yet: once one is made, another is optional.
        // A failed edition's card has its own Try again, and a ready one's Send comes before a retry.
        val make = when {
            latest == null -> MakeButton.PRIMARY
            latest.status == EditionStatus.FAILED -> MakeButton.NONE
            readyWaiting -> MakeButton.ANOTHER
            state.build is BuildState.Failed -> MakeButton.RETRY
            else -> MakeButton.ANOTHER
        }
        // A build that failed making an edition leaves its reason on the card too; the status line
        // keeps it, so TalkBack announces it, and the card doesn't say it again.
        val saidAbove = (state.build as? BuildState.Failed)?.reason
        // The same key in both places: only one exists at a time, and it moves rather than restarts.
        if (!readyWaiting) item(key = "build") { BuildPanel(state.build, announcer, make, hadOne = latest != null, onMake = viewModel::makeOneNow) }
        if (latest != null) {
            item(key = "latest") {
                // Ready before Send is tapped: the mail app has to open while the screen is still in front.
                val emailBody by remember(latest.id) { viewModel.emailBody(latest.id) }.collectAsState(null)
                val hasFile = remember(latest.fileName, latest.status) { viewModel.fileOf(latest) != null }
                LatestEdition(
                    latest,
                    articles = state.latestArticles,
                    first = editions.size == 1,
                    saidAbove = saidAbove,
                    deviceName = state.deviceName,
                    preferOpen = state.preferOpen,
                    offerOpen = state.offerOpen,
                    kindleReader = state.kindleReader,
                    hasFile = hasFile,
                    sentToKindle = state.sentToKindle[latest.id],
                    // Only when Send opens the mail app itself; otherwise it's the share sheet, as usual.
                    emailsKindle = state.kindleEmail?.let { remember(it) { EditionIntents.mailAppFor(context, it) } } != null,
                    onRetry = viewModel::makeOneNow,
                    onDetails = { onOpenEdition(latest.id) },
                    onSend = {
                        viewModel.fileOf(latest)?.let {
                            EditionIntents.launchSend(context, it, latest.title, latest.id, state.kindleEmail, emailBody) { viewModel.markEmailed(latest.id) }
                        }
                    },
                    onOpen = {
                        viewModel.fileOf(latest)?.let {
                            // Reading here is how an edition reaches a Boox.
                            if (launch(EditionIntents.open(context, it)) && state.preferOpen) viewModel.markSent(latest)
                        }
                    },
                    onSent = { viewModel.markSent(latest) },
                    onNotSent = { viewModel.markNotSent(latest.id) },
                )
            }
        }
        if (readyWaiting) item(key = "build") { BuildPanel(state.build, announcer, make, hadOne = latest != null, onMake = viewModel::makeOneNow) }
        // Below the latest edition in every state, so it doesn't jump when the build panel moves.
        if (make == MakeButton.ANOTHER && !isRunning(state.build)) {
            item(key = "another") { TextButton(onClick = viewModel::makeOneNow, modifier = Modifier.padding(top = 8.dp)) { Text("Make another edition") } }
        }
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

private fun isRunning(build: BuildState) =
    build == BuildState.Syncing || build == BuildState.WaitingForNetwork || build is BuildState.Retrying || build is BuildState.Fetching

private enum class MakeButton { PRIMARY, RETRY, ANOTHER, NONE }

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
        val running = isRunning(build)
        if (running) finished = false
        else if (sawRunning && build == BuildState.Idle) finished = true
        if (running) sawRunning = true
    }
}

@Composable
private fun BuildPanel(build: BuildState, announcer: BuildAnnouncer, make: MakeButton, hadOne: Boolean, onMake: () -> Unit) {
    val running = isRunning(build)
    // Announced only once a build has run while this screen was up: a failure WorkManager still
    // remembers from earlier would otherwise be read out every time Today opens.
    announcer.update(build)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // One status line, always composed, whose text changes: TalkBack announces a change to a
        // live region, not one appearing. Stepped text rather than a spinner, which smears on e-ink.
        Text(
            when (build) {
                BuildState.Syncing -> "Checking your sources for new articles…"
                BuildState.WaitingForNetwork -> "Waiting for an internet connection…"
                is BuildState.Retrying -> "Couldn't read your sources. Trying again at ${timeOf(build.atMillis)}."
                is BuildState.Fetching -> "Making your edition"
                // A finished state once there's been an edition, rather than an empty one.
                BuildState.NothingNew -> if (hadOne) "Nothing new since your last edition. Check back later." else "Nothing new to read yet. Add sources, or check back later."
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
                (build as? BuildState.Fetching)?.done?.let { if (it == 1) "1 article so far" else "$it articles so far" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Below the status, so a failure or "nothing new" reads before the button that answers it.
        if (!running) {
            val top = Modifier.padding(top = 8.dp)
            when (make) {
                MakeButton.PRIMARY -> Button(onClick = onMake, modifier = top) { Text("Make an edition now") }
                MakeButton.RETRY -> OutlinedButton(onClick = onMake, modifier = top) { Text("Try again") }
                MakeButton.ANOTHER, MakeButton.NONE -> {}
            }
        }
    }
}

@Composable
private fun LatestEdition(
    edition: EditionEntity,
    articles: List<EditionArticleEntity>,
    first: Boolean,
    saidAbove: String?,
    deviceName: String,
    preferOpen: Boolean,
    offerOpen: Boolean,
    kindleReader: Boolean,
    hasFile: Boolean,
    sentToKindle: KindleSend?,
    emailsKindle: Boolean,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onSent: () -> Unit,
    onNotSent: () -> Unit,
) {
    // An outline as well as the tint, which is almost white on e-ink. The whole card opens the
    // edition; the buttons inside it take their own taps.
    Card(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(CardDefaults.shape).clickable(onClickLabel = "See what's inside", onClick = onDetails),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            val sent = edition.status == EditionStatus.DELIVERED
            val inside = edition.articleCount > 0
            Row {
                Column(Modifier.weight(1f)) {
                    Text(edition.title, style = MaterialTheme.typography.headlineSmall)
                    // An empty failed edition's summary is just "Not sent", which its error already says.
                    if (!(edition.status == EditionStatus.FAILED && edition.articleCount == 0 && edition.error != null && edition.error != saidAbove)) {
                        Text(summary(edition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                // Pulled into the card's padding, so the glyph lines up with the card's edge while
                // the touch target stays 48dp.
                // Without its book there's nothing to send or take back.
                if (sent && hasFile) SentEditionMenu(edition.title, onSend, onNotSent, Modifier.offset(x = 12.dp, y = (-12).dp))
            }
            // Only for an edition that is or was on its way: a failed one's articles go back to wait.
            // A ready card has no See what's inside: its headlines show what's in it, and Send is
            // what's next. The card itself opens the contents.
            if (edition.status == EditionStatus.READY || sent) Headlines(articles)
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
                            if (offerOpen) OutlinedButton(onClick = onOpen) { Text("Open") }
                        }
                    }
                    Text(
                        when {
                            preferOpen -> "Opening it here counts as sent. Read it another way? Tell us so these articles don't come back."
                            emailsKindle -> "Send opens your mail app, ready to go. Opening it counts as sent. Sent it another way? Tell us so these articles don't come back."
                            else -> "Choosing an app to send it with counts as sent. Sent it another way? Tell us so these articles don't come back."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        // Its own TalkBack stop, between Send and its answer, I've sent it, rather
                        // than merged into the card, which TalkBack reads before any button.
                        modifier = Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) {},
                    )
                    // No start padding, so it lines up with the text above rather than sitting indented.
                    TextButton(onClick = onSent, contentPadding = PaddingValues(end = 12.dp)) { Text("I've sent it") }
                }
                EditionStatus.DELIVERED -> {
                    sentToKindle?.let { KindleNote(it) }
                    // What's next is reading it: on the phone where it opens here, otherwise a look at
                    // the contents. Sending again is rare, so it waits in the menu.
                    // Spaced per button, so a row left empty (no Kindle app, nothing inside) takes no room.
                    val top = Modifier.padding(top = 12.dp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            kindleReader -> {
                                if (inside) Button(onClick = onDetails, modifier = top) { Text("See what's inside") }
                                OpenKindleButton(top)
                            }
                            offerOpen -> {
                                Button(onClick = onOpen, modifier = top) { Text("Open") }
                                if (inside) OutlinedButton(onClick = onDetails, modifier = top) { Text("See what's inside") }
                            }
                            inside -> Button(onClick = onDetails, modifier = top) { Text("See what's inside") }
                        }
                    }
                }
                EditionStatus.FAILED -> {
                    val error = edition.error ?: "This edition couldn't be made."
                    // Its own stop too, so it's read just before Try again.
                    if (error != saidAbove) Text(error, color = failureColor(edition.error), modifier = Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {})
                    OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
                EditionStatus.BUILDING, EditionStatus.DELETED -> {}
            }
        }
    }
}

/** The edition's first [SHOWN] articles in the book's order, as its contents page opens, and how many more. */
@Composable
private fun Headlines(articles: List<EditionArticleEntity>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    articles.take(SHOWN).forEach { article ->
        Column(Modifier.padding(top = 10.dp)) {
            // TalkBack reads the star as "Starred", not the glyph.
            Text(
                if (article.starred) "★ ${article.sourceTitle}" else article.sourceTitle,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                modifier = Modifier.clearAndSetSemantics { contentDescription = if (article.starred) "Starred, ${article.sourceTitle}" else article.sourceTitle },
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(minutes(article.minutes), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    val more = articles.size - SHOWN
    if (more > 0) Text("and $more more", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 10.dp))
}

private const val SHOWN = 3

private fun minutes(m: Double) = "${m.roundToInt().coerceAtLeast(1)} min"

/** Send again and Mark as not sent for a sent edition whose book is still here, both rare. */
@Composable
private fun SentEditionMenu(title: String, onSend: () -> Unit, onNotSent: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    // Keyed by the edition: a newer one can take its place on Today while the dialog is open.
    var asking by rememberSaveable(title) { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options for $title") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Send again") }, onClick = { open = false; onSend() })
            DropdownMenuItem(text = { Text(MARK_NOT_SENT) }, onClick = { open = false; asking = true })
        }
    }
    if (asking) MarkNotSentDialog(title, onDismiss = { asking = false }, onConfirm = onNotSent)
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

/** Red for a failure; an edition that simply wasn't sent in time isn't one. */
@Composable
internal fun failureColor(error: String?) =
    if (error == EditionBuilder.NOT_SENT || error == EditionBuilder.OLD_NOT_SENT) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error

private fun timeOf(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
