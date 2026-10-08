package com.app.newspaperss.ui.sources

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.ui.components.historyLine
import com.app.newspaperss.ui.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One source's health and its recent articles, so the reader can see what it has been sending. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * @param onGone leaves the screen once the source is removed, from here or elsewhere. It runs
 *   after the removal finishes, when the reader may already have left, so it mustn't just go up.
 */
fun SourceDetailScreen(viewModel: SourceDetailViewModel, onBack: () -> Unit, onGone: () -> Unit = onBack) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val source = detail?.source
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // Saved: an open confirmation survives a rotation.
    var removing by rememberSaveable { mutableStateOf(false) }
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    var leavingOut by rememberSaveable { mutableStateOf(false) }
    val gone = detail != null && source == null
    LaunchedEffect(gone) { if (gone) onGone() }
    val snackbar = remember { SnackbarHostState() }
    val undoOffer by viewModel.undoOffer.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val building by viewModel.building.collectAsStateWithLifecycle()
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        // Ended however the snackbar goes, including the screen leaving or rotating, so a stale
        // Undo never comes back. Missed, starring still brings the article back.
        try {
            // Long: on e-ink the reader may not see it straight away.
            val result = snackbar.showSnackbar(undoMessage(offer.change), actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(offer)
        } finally {
            viewModel.undoOfferEnded(offer)
        }
    }
    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
        } finally {
            viewModel.noticeShown()
        }
    }
    // Ids rather than articles, so a selected article that changes state underneath is counted
    // as it is now; saved so a rotation keeps the selection.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<Long>()) }
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = selecting) { stopSelecting() }
    LaunchedEffect(detail) {
        // Only once loaded: straight after a process restart the list is briefly empty.
        val ids = detail?.articles?.mapTo(HashSet()) { it.id } ?: return@LaunchedEffect
        if (ids.isEmpty()) stopSelecting() else if (!ids.containsAll(selected)) selected = selected intersect ids
    }
    val articles = detail?.articles.orEmpty()
    val publication = detail?.text
    val knownTitle = publication?.title ?: articles.firstNotNullOfOrNull { it.originTitle }
    val feedTitle = knownTitle ?: "A feed"
    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                    navigationIcon = { IconButton(onClick = ::stopSelecting) { Icon(Icons.Default.Close, contentDescription = "Stop selecting") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.semantics { paneTitle = "Selecting. Tap articles to choose them." },
                )
            } else {
                TopAppBar(
                    title = { Text(if (viewModel.isFeed) feedTitle else source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    // Removing is rare and destructive: in the menu, as on an edition's page, not
                    // beside Pause. A tt-rss account is left from Settings, as the server setup.
                    actions = {
                        if (source != null && !viewModel.isFeed && source.kind != SourceKind.TTRSS) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Remove source") }, onClick = { menu = false; removing = true })
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (source == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // The selection bar lies over the list instead of being the Scaffold's bottom bar, and the
        // room reserved for it at the end of the list only ever grows: either change in the
        // list's bottom padding would scroll a list that's at its end, moving every row (a full
        // redraw on e-ink) on the way in or out of selection mode.
        val density = LocalDensity.current
        var barRoom by remember { mutableStateOf(0.dp) }
        var barHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + barRoom)) {
                item {
                    val cap: @Composable () -> Unit = {
                        ArticleCap(publication?.maxArticles, detail?.defaultMax ?: 1, viewModel::stepMaxArticles, viewModel::followEditionMax)
                    }
                    if (viewModel.isFeed) {
                        FeedHeader(publication)
                        val leftOut = publication?.leftOut == true
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (leftOut) {
                                OutlinedButton(onClick = { viewModel.setInPaper(knownTitle, true) }) { Text("Bring back") }
                            } else {
                                OutlinedButton(onClick = { leavingOut = true }) { Text("Leave out") }
                                OutlinedButton(onClick = { choosingMode = true }) { Text("Article text: ${modeName(publication)}") }
                            }
                        }
                        if (!leftOut) cap()
                    } else {
                        Health(source, publication, articles.maxOfOrNull { it.discoveredAt }, locale, is24Hour)
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::togglePaused) { Text(if (source.paused) "Resume" else "Pause") }
                            if (source.kind == SourceKind.FEED) OutlinedButton(onClick = { choosingMode = true }) { Text("Article text: ${modeName(publication)}") }
                        }
                        if (source.kind != SourceKind.TTRSS) cap()
                    }
                    val paidOnly = detail?.paidOnly
                    // Only once the publication has had one: most never do, and the page has enough on it.
                    // Each tt-rss feed has its own, on its page; the account's page has none. Like the
                    // other settings about the writing, it's hidden while the feed is left out.
                    val ownsPosts = source.kind != SourceKind.READING_LIST && (source.kind != SourceKind.TTRSS || viewModel.isFeed) &&
                        publication?.leftOut != true
                    val skip = publication?.skipPaidPosts == true
                    if (ownsPosts && paidOnly != null && (skip || paidOnly.found > 0)) {
                        PaidPostsOption(skip, paidOnly.skipped, viewModel::setSkipPaidPosts)
                    }
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    ArticlesHeading(articles, selecting, onSelect = { selecting = true })
                    if (building && articles.isNotEmpty()) {
                        Text(BUILDING_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
                items(articles, key = { it.id }) { article ->
                    RecentArticle(
                        article,
                        historyLine(
                            article,
                            detail?.history?.get(article.id),
                            // A curated list expires by count (its newest 12), so no day count there.
                            expires = source.kind == SourceKind.FEED || source.kind == SourceKind.TTRSS,
                            paused = source.paused,
                        ),
                        locale,
                        building,
                        selection = if (selecting) article.id in selected else null,
                        onStar = { viewModel.setStarred(article.id, it) },
                        onToggleRead = { viewModel.toggleRead(article.id) },
                        onSelect = { selected = if (article.id in selected) selected - article.id else selected + article.id },
                        onStartSelecting = {
                            selecting = true
                            selected = selected + article.id
                        },
                    )
                    // Inset: full-width rules chopped the list into boxes to track across.
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (selecting) {
                SelectionBar(
                    chosen = articles.filter { it.id in selected },
                    building = building,
                    onStar = { ids, starred -> viewModel.setStarred(ids, starred); stopSelecting() },
                    onMarkRead = { ids -> viewModel.markRead(ids); stopSelecting() },
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                        val height = with(density) { it.height.toDp() }
                        barHeight = height
                        if (height > barRoom) barRoom = height
                    },
                )
            }
            // Here rather than in the Scaffold, so it sits above the bar instead of over its buttons.
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selecting) barHeight else 0.dp))
        }
    }
    if (choosingMode && source != null) {
        ContentModeDialog(
            detail?.text?.chosenMode ?: ContentMode.AUTO,
            onChoose = { choosingMode = false; viewModel.chooseContentMode(it) },
            onDismiss = { choosingMode = false },
            automatic = if (viewModel.isFeed) "Automatic: learned for this feed" else "Automatic",
        )
    }
    if (leavingOut) {
        LeaveOutDialog(feedTitle, onConfirm = { leavingOut = false; viewModel.setInPaper(knownTitle, false) }, onDismiss = { leavingOut = false })
    }
    if (removing && source != null) {
        RemoveSourceDialog(source, onConfirm = { removing = false; viewModel.remove() }, onDismiss = { removing = false })
    }
}

/** A tt-rss feed's account and category, its address, and where its article text comes from. */
@Composable
private fun FeedHeader(publication: PublicationEntity?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOfNotNull("In your tt-rss", publication?.category?.let { "category $it" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = muted)
        publication?.feedUrl?.let { Text(SourceRepository.hostOf(it), style = MaterialTheme.typography.bodyMedium, color = muted) }
        val serverError = publication?.serverError
        if (serverError != null) {
            // tt-rss's own words, which can be technical ("HTTP Code: 404"): what to do comes first.
            Text("$SERVER_CANT_FETCH, so nothing new comes from it. The feed may have moved or closed: check its address in tt-rss.")
            Text("tt-rss says: $serverError", style = MaterialTheme.typography.bodyMedium, color = muted)
        } else if (publication?.awaitingFirstFetch == true) {
            Text("$WAITING_FOR_FIRST_FETCH. Its first articles come once tt-rss has fetched it, usually within the hour.")
        }
        if (publication?.leftOut == true) {
            Text("Left out of the paper. It stays in your tt-rss and isn't fetched. Starred articles from it still go in.")
        } else {
            Text(textLine(publication), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }
}

@Composable
private fun LeaveOutDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave $title out?") },
        text = {
            Text("It stays in your tt-rss, so you can bring it back. It isn't fetched for the paper, and its waiting articles go, except ones you starred.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Health(source: SourceEntity, learned: PublicationEntity?, lastNew: Instant?, locale: Locale, is24Hour: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SourceRepository.hostOf(source.siteUrl ?: source.url), style = MaterialTheme.typography.bodyMedium, color = muted)
        Text(statusLine(source, lastNew), color = if (hasProblem(source)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!source.paused) failingLine(source.failingSince, locale)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        lastCheckedLine(source.lastFetchedAt, locale, is24Hour)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        if (source.kind == SourceKind.FEED) Text(textLine(learned), style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

@Composable
private fun PaidPostsOption(skip: Boolean, skipped: Int, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = skip, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Skip paid posts with nothing free")
            Text(
                when {
                    skip && skipped > 0 -> "${plural(skipped, "paid post")} skipped so far: a title and a picture, nothing to read."
                    skip -> "A post that's only a title and a picture won't take a place."
                    else -> "Some of its posts are for paying subscribers, with only a title and a picture free."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = skip, onCheckedChange = null)
    }
}

/** The font scale from which rows stack rather than squeeze. */
internal const val LARGE_TEXT = 1.3f

@Composable
private fun ArticleCap(own: Int?, default: Int, onStep: (Int) -> Unit, onFollowDefault: () -> Unit) {
    val max = own ?: default
    // The edition's number gives way when there's room; a site's own number is a hard limit.
    val words: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text(
                if (own == null) "${plural(default, "article")} from this site, then more if there's room"
                else "At most ${plural(own, "article")} from this site in each edition",
            )
            Text(
                if (own == null) "Your edition setting. Choose fewer or more to give this site a firm limit."
                else "Your edition setting: ${plural(default, "article")} from each source, then more if there's room",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val buttons: @Composable () -> Unit = {
        OutlinedButton(onClick = { onStep(-1) }, enabled = own == null || own > 1, modifier = Modifier.semantics { contentDescription = "Fewer articles from this site" }) {
            Text("−")
        }
        OutlinedButton(
            onClick = { onStep(1) },
            enabled = max < SettingsViewModel.MAX_PER_SOURCE,
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "More articles from this site" },
        ) { Text("+") }
    }
    val padding = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
    // At large text the buttons get their own line: beside the words they squeezed them to a few
    // words a line.
    if (LocalDensity.current.fontScale >= LARGE_TEXT) {
        Column(padding) {
            words(Modifier)
            Row(Modifier.padding(top = 8.dp)) { buttons() }
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically) {
            words(Modifier.weight(1f))
            buttons()
        }
    }
    if (own != null) {
        TextButton(onClick = onFollowDefault, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Use your edition setting") }
    }
}

/** The article-text setting in a word or two, for its button. */
private fun modeName(publication: PublicationEntity?) = when (publication?.chosenMode) {
    ContentMode.FEED -> "Feed's text"
    ContentMode.PAGE -> "Full page"
    else -> "Automatic"
}

/** Where a publication's text comes from, including while the automatic check is still deciding. */
private fun textLine(publication: PublicationEntity?): String =
    fullTextLine(publication) ?: "Still working out whether this site sends full articles"

/** Null until a failure has lasted past the day it started: one bad sync isn't worth a second line. */
internal fun failingLine(since: Instant?, locale: Locale, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (since == null) return null
    val days = ChronoUnit.DAYS.between(since.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0 -> null
        days == 1L -> "Failing since yesterday"
        else -> "Failing for $days days, since ${shortDate(since, locale, zone)}"
    }
}

internal fun lastCheckedLine(at: Instant?, locale: Locale, is24Hour: Boolean, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (at == null) return null
    val date = at.atZone(zone)
    return if (date.toLocalDate() == now.atZone(zone).toLocalDate()) {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hma")
        "Last checked today at ${DateTimeFormatter.ofPattern(pattern, locale).format(date)}"
    } else {
        "Last checked ${shortDate(at, locale, zone)}"
    }
}

/** Month and day, and the year too when it isn't this one. */
internal fun shortDate(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = at.atZone(zone)
    val skeleton = if (date.year == java.time.LocalDate.now(zone).year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}
