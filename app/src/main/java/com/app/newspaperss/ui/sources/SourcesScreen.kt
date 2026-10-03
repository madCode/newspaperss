package com.app.newspaperss.ui.sources

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import com.app.newspaperss.core.plural
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.app.newspaperss.data.FeedChoice
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.lists.CuratedList
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onOpenReadingList: () -> Unit = {},
    onOpenSource: (Long) -> Unit = {},
    onOpenFeed: (sourceId: Long, key: String) -> Unit = { _, _ -> },
    onOpenLeftOut: (sourceId: Long) -> Unit = {},
    onOpenNotInPaper: (sourceId: Long) -> Unit = {},
    /** Opens Settings › Where your feeds live: the tt-rss account, and signing in to it. */
    onOpenAccount: () -> Unit = {},
) {
    val screen by viewModel.screen.collectAsState()
    val add by viewModel.add.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOpml(context.contentResolver, uri)
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) viewModel.exportOpml(context.contentResolver, uri)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    // tt-rss's answers, which can arrive after the dialog that asked has closed.
    val results by viewModel.subscribeResults.collectAsState()
    LaunchedEffect(results) { results.firstOrNull()?.let(viewModel::take) }
    LaunchedEffect(Unit) {
        viewModel.notices.collect { waiting ->
            val notice = waiting.firstOrNull() ?: return@collect
            // A snackbar under an open dialog can't be reached, and its Undo would time out unseen.
            viewModel.add.first { it == AddState.Closed }
            val undo = notice.undo
            val answer = snackbar.showSnackbar(notice.text, actionLabel = undo?.let { "Undo" }, duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed && undo != null) viewModel.undo(undo)
            viewModel.noticeShown(notice)
        }
    }
    val server = screen?.server != null
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Check for new articles") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // With a server, its feeds are tt-rss's to import and export (Preferences › Feeds there).
                            if (server) {
                                DropdownMenuItem(text = { Text("Where your feeds live") }, onClick = { menu = false; onOpenAccount() })
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Import from another reader (OPML)") },
                                    onClick = { menu = false; importFile.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export your sites (OPML)") },
                                    onClick = { menu = false; exportFile.launch("newspapeRSS-sources.opml") },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (server) "Add a site" else "Add a source") },
                // Filled like the app's other main buttons: the default pale container turns
                // almost white on e-ink.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val shown = screen
        val list = shown?.rows
        val srv = shown?.server
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            srv == null && list.isEmpty() -> Column(Modifier.padding(padding)) {
                ReadingListRow(onOpenReadingList)
                EmptySources(Modifier)
            }
            // Nothing folds or moves, and every key is unique and stable: on e-ink a list that
            // shifts is redrawn whole, and two equal keys crash the list.
            else -> Column(Modifier.padding(padding)) {
                // With a server, the account itself only shows when something needs doing: its
                // settings are in Settings, and nearly everything listed comes from it. Above the
                // list rather than in it: the list keeps its first row in place, so a banner
                // appearing later as its first item would land out of sight.
                val account = srv?.account
                // At most a third of the screen, scrolling within it, so with large text the list keeps room.
                val bannerMax = (LocalConfiguration.current.screenHeightDp / 3).dp
                Column(Modifier.heightIn(max = bannerMax).verticalScroll(rememberScrollState())) {
                    if (srv != null && shown.needsSignIn) SignInBanner(onOpenAccount)
                    else account?.let { accountProblem(it, locale, is24Hour) }?.let { AccountProblemBanner(it, onOpenAccount) }
                }
                SourceList(shown, viewModel, onOpenReadingList, onOpenSource, onOpenFeed, onOpenLeftOut, onOpenNotInPaper)
            }
        }
    }
    val curatedLists by viewModel.curatedLists.collectAsState()
    AddSourceDialog(add, curatedLists, viewModel, server)
    viewModel.mover?.let { mover ->
        val sheet by mover.sheet.collectAsState()
        sheet?.let { MoveSheetDialog(it, mover) }
    }
}

@Composable
private fun SourceList(
    shown: SourcesList,
    viewModel: SourcesViewModel,
    onOpenReadingList: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onOpenFeed: (sourceId: Long, key: String) -> Unit,
    onOpenLeftOut: (sourceId: Long) -> Unit,
    onOpenNotInPaper: (sourceId: Long) -> Unit,
) {
    val list = shown.rows
    val srv = shown.server
    val account = srv?.account
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "reading-list") { ReadingListRow(onOpenReadingList) }
        if (srv == null) {
            itemsIndexed(list, key = { _, it -> "source/${it.source.id}" }) { i, row ->
                if (i > 0) RowDivider()
                SourceItem(row, onOpen = { onOpenSource(row.source.id) })
            }
            return@LazyColumn
        }
        val (feeds, lists) = list.partition { it.source.kind == SourceKind.FEED }
        // Above the server's feeds, since they ask for something: moving into tt-rss.
        val mover = viewModel.mover
        val moving = shown.phoneFeeds?.takeIf { mover != null }
        if (feeds.isNotEmpty() || moving != null) {
            item(key = "on-phone") { SectionHeading("Still on this phone") }
            if (mover != null && moving != null) {
                item(key = "phone-feeds") { PhoneFeedsBanner(moving, onMove = { mover.open() }, onMoveOthers = mover::open) }
            }
            itemsIndexed(feeds, key = { _, it -> "source/${it.source.id}" }) { i, row ->
                if (i > 0) RowDivider()
                SourceItem(row, onOpen = { onOpenSource(row.source.id) })
            }
        }
        if (account != null) serverCategories(account.id, srv, onOpenFeed)
        if (lists.isNotEmpty()) {
            item(key = "lists") { SectionHeading("Curated lists") }
            itemsIndexed(lists, key = { _, it -> "source/${it.source.id}" }) { i, row ->
                if (i > 0) RowDivider()
                SourceItem(row, onOpen = { onOpenSource(row.source.id) })
            }
        }
        if (account != null) outsidePaper(account.id, srv, onOpenLeftOut, onOpenNotInPaper)
    }
}

/** The server setup with no working account: nothing comes from tt-rss until the reader signs in. */
@Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Sign in to your tt-rss", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Your sites come from your tt-rss, and newspapeRSS isn't signed in to it. Until it is, your paper has only what's on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = onSignIn) { Text("Sign in") }
        }
    }
}

@Composable
private fun ReadingListRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Your reading list") },
        supportingContent = { Text("Links you share to newspapeRSS from any app") },
        leadingContent = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
        modifier = Modifier.clickable(onClickLabel = "Open reading list", onClick = onClick),
    )
}

@Composable
private fun EmptySources(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No sources yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add a website you like to read. Paste its address and newspapeRSS does the rest.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The problem with the tt-rss account, and the way to Settings, where it's put right. */
@Composable
private fun AccountProblemBanner(problem: String, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            // A heading, so TalkBack can jump to it, and announced when it appears while Sources is open.
            Text(problem, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = onOpen, modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "Your tt-rss settings" }) {
                Text("Settings")
            }
        }
    }
}

/** The paper's feeds from tt-rss, under its categories. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.serverCategories(accountId: Long, server: ServerSources, onOpenFeed: (sourceId: Long, key: String) -> Unit) {
    if (server.categories.isEmpty()) {
        item(key = "no-feeds") {
            val category = server.account?.ttrssCategoryTitle
            Text(
                when {
                    server.waitingForList -> "Your feeds${category?.let { " in $it" }.orEmpty()} show here after the next check."
                    server.account?.lastFetchedAt == null -> "Your feeds show here once your tt-rss has been checked."
                    else -> "None of your feeds are in the paper."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    server.categories.forEach { category ->
        val name = category.name ?: UNCATEGORIZED
        // A feed is listed once, under one category, so its id alone is a unique key.
        // Pinned while its feeds scroll by, so in a long category you know which one you're in.
        stickyHeader(key = category.name?.let { "category/$it" } ?: "uncategorized") { CategoryHeading(name, category.feeds.size) }
        itemsIndexed(category.feeds, key = { _, it -> "feed/${it.originId}" }) { i, feed ->
            if (i > 0) RowDivider()
            ListItem(
                headlineContent = { Text(feed.title) },
                supportingContent = feedNote(feed)?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
                modifier = Modifier.clickable(onClickLabel = "Open ${feed.title}") { onOpenFeed(accountId, feed.originId) },
            )
        }
    }
}

/** The account's feeds that aren't in the paper: outside its category, and left out. */
private fun LazyListScope.outsidePaper(accountId: Long, server: ServerSources, onOpenLeftOut: (Long) -> Unit, onOpenNotInPaper: (Long) -> Unit) {
    if (server.outside.isNotEmpty() || server.leftOut > 0) item(key = "outside-heading") { SectionHeading("Not in your paper") }
    if (server.outside.isNotEmpty()) {
        item(key = "not-in-paper") {
            val title = server.account?.ttrssCategoryTitle?.let { "Outside $it" } ?: "Outside your category"
            LinkRow(title, plural(server.outside.sumOf { it.feeds.size }, "feed")) { onOpenNotInPaper(accountId) }
        }
    }
    if (server.leftOut > 0) {
        item(key = "left-out") {
            if (server.outside.isNotEmpty()) RowDivider()
            LinkRow("Left out", plural(server.leftOut, "feed")) { onOpenLeftOut(accountId) }
        }
    }
}

/**
 * A group of the page's own: the space above it is what separates groups, as rules only divide
 * rows within one.
 */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * A server category: the same serif as the sections a size down and in black, as there can be a
 * dozen (rust is for what you tap, and turns a light grey on e-ink). Opaque, as it's pinned over
 * the rows. TalkBack reads it with its count: "News, 9 feeds".
 */
@Composable
private fun CategoryHeading(name: String, feeds: Int) {
    Text(
        "$name · $feeds",
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MaterialTheme.typography.titleMedium.fontFamily, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading(); contentDescription = "$name, ${plural(feeds, "feed")}" },
    )
}

/** Between two rows of one group, starting at the text so the groups' edges stay clean. */
@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp))

@Composable
private fun LinkRow(title: String, detail: String, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClickLabel = "Open $title", onClick = onOpen),
    )
}

/**
 * Why nothing new is coming from the account, or null: paused, its last sync's error and since
 * when, or a problem telling tt-rss what was read. A problem is marked with ⚠, as colour doesn't
 * show on e-ink.
 */
internal fun accountProblem(account: SourceEntity, locale: Locale, is24Hour: Boolean, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? = when {
    account.paused -> "Your tt-rss is paused: nothing new comes from it until you resume it in Settings."
    account.lastError != null -> listOfNotNull(
        "⚠ ${account.lastError}",
        account.failingSince?.let { failingSinceLine(it, locale, is24Hour, now, zone) },
        "Showing what it last listed.",
    ).joinToString(" ")
    account.serverNote != null -> "⚠ ${account.serverNote}"
    else -> null
}

/** "Since 6:10 AM" today, or "Since Sep 30". */
private fun failingSinceLine(since: Instant, locale: Locale, is24Hour: Boolean, now: Instant, zone: ZoneId): String {
    val date = since.atZone(zone)
    val skeleton = when {
        date.toLocalDate() == now.atZone(zone).toLocalDate() -> if (is24Hour) "Hm" else "hma"
        date.year == now.atZone(zone).year -> "MMMd"
        else -> "yMMMd"
    }
    return "Since ${DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)}."
}

/** A feed just subscribed to in tt-rss, which has nothing to give until tt-rss's own schedule fetches it. */
internal const val WAITING_FOR_FIRST_FETCH = "Not fetched by tt-rss yet"

/**
 * A line under a feed only when it has something to say: a first fetch still to come, or its own
 * settings. A line under all of them would be noise.
 */
internal fun feedNote(feed: FeedChoice): String? {
    val p = feed.publication
    return listOfNotNull(
        WAITING_FOR_FIRST_FETCH.takeIf { p?.awaitingFirstFetch == true },
        when (p?.chosenMode) {
            ContentMode.FEED -> "Feed's text"
            ContentMode.PAGE -> "Full page"
            else -> null
        },
        p?.maxArticles?.let { "At most $it" },
        "Skips paid posts".takeIf { p?.skipPaidPosts == true },
    ).joinToString(" · ").ifEmpty { null }
}

/** A source's row: tapping it opens the source's page, where its settings are. Nothing else on the row, so 50+ rows stay plain. */
@Composable
private fun SourceItem(row: SourceRow, onOpen: () -> Unit) {
    val s = row.source
    val fullText = if (s.kind == SourceKind.FEED) fullTextLine(row.text) else null
    val status = statusLine(s, row.lastNew)
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Open ${s.title}", onClick = onOpen),
        headlineContent = { Text(s.title) },
        supportingContent = {
            Column {
                Text(SourceRepository.hostOf(s.siteUrl ?: s.url), style = MaterialTheme.typography.bodySmall)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasProblem(s)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fullText != null) {
                    Text(fullText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
internal fun RemoveSourceDialog(source: SourceEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${source.title}?") },
        text = {
            Text(
                when (source.kind) {
                    SourceKind.TTRSS -> "Its waiting and starred articles go with it. This also signs newspapeRSS out of your tt-rss account; your articles stay on the server."
                    else -> "Its waiting and starred articles go with it. If you add it again, articles you already got won't be sent again."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove source") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
internal fun ContentModeDialog(current: ContentMode, onChoose: (ContentMode) -> Unit, onDismiss: () -> Unit, automatic: String = "Automatic") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Article text") },
        text = {
            Column(Modifier.selectableGroup()) {
                CONTENT_MODE_CHOICES.forEach { (mode, choice) ->
                    val label = if (mode == ContentMode.AUTO) automatic else choice
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = mode == current, role = Role.RadioButton, onClick = { onChoose(mode) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val CONTENT_MODE_CHOICES = listOf(
    ContentMode.AUTO to "Automatic",
    ContentMode.FEED to "Always use the text the site sends",
    ContentMode.PAGE to "Always fetch the full page",
)

/** The one line that says how a source is doing: paused, its problem, or how recently it published. */
internal fun statusLine(source: SourceEntity, lastNew: Instant?): String = when {
    source.paused -> "Paused"
    source.lastError != null -> source.lastError
    source.serverNote != null -> source.serverNote
    source.lastFetchedAt == null -> "Checking…"
    else -> freshness(lastNew)
}

internal fun hasProblem(source: SourceEntity) = (source.lastError != null || source.serverNote != null) && !source.paused

/**
 * Where a publication's article text comes from, once the app knows or the reader has chosen;
 * null while it's still checking.
 */
internal fun fullTextLine(learned: PublicationEntity?): String? {
    when (learned?.chosenMode) {
        ContentMode.FEED -> return "Uses the text the site sends (your choice)"
        ContentMode.PAGE -> return "Always fetches the full page (your choice)"
        else -> {}
    }
    return when (learned?.contentMode ?: ContentMode.AUTO) {
        ContentMode.AUTO -> null
        ContentMode.PAGE -> "Full articles"
        ContentMode.FEED -> when (learned?.fullTextEvidence) {
            FullTextEvidence.FEED_SHORT -> "Summaries only"
            FullTextEvidence.BLOCKED -> "Site blocks fetching: using the summaries it sends"
            else -> "Full articles"
        }
    }
}

@Composable
private fun AddSourceDialog(state: AddState, curatedLists: List<CuratedList>, viewModel: SourcesViewModel, server: Boolean) {
    if (state == AddState.Closed) return
    // The category list replaces the Subscribe step's content, then Done brings it back.
    var picking by remember(state is AddState.Subscribing) { mutableStateOf(false) }
    AlertDialog(
        // Back from the category list returns to the Subscribe step rather than losing the feed found.
        onDismissRequest = { if (picking) picking = false else viewModel.closeAdd() },
        title = {
            Text(
                when (state) {
                    is AddState.Choosing -> "Which part of this site?"
                    is AddState.Subscribing -> if (picking) "Category" else "Subscribe in your tt-rss"
                    is AddState.Asking -> "Subscribe in your tt-rss"
                    is AddState.AlreadyIn -> "Already in your tt-rss"
                    is AddState.NoFeed -> if (state.list != null) "A curated list" else "No feed on this site"
                    is AddState.Refused -> if (state.couldntFetch) "tt-rss couldn't fetch it" else "tt-rss didn't add it"
                    else -> if (server) "Add a site" else "Add a source"
                },
            )
        },
        text = {
            when (state) {
                // Scrolls: at large text an error plus the curated lists can outgrow the dialog.
                is AddState.Editing -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Paste a website or feed address.", modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::editInput,
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.find() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (server) Muted("It goes into your tt-rss, so your other reader apps get it too.")
                    if (state.page != null) {
                        TextButton(onClick = viewModel::saveInstead) { Text("Save this page to your reading list instead") }
                    }
                    if (curatedLists.isNotEmpty()) CuratedListChoices(curatedLists, viewModel::addList)
                }
                is AddState.Searching -> Text("Checking ${state.input}…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                is AddState.Choosing -> Column {
                    Text("This site offers more than one set of articles.", modifier = Modifier.padding(bottom = 8.dp))
                    state.feeds.forEach { feed ->
                        Text(
                            feed.title ?: feed.url,
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.choose(feed) }.padding(vertical = 12.dp),
                        )
                    }
                }
                is AddState.Subscribing -> if (picking) CategoryChoices(state, viewModel::chooseCategory) else SubscribeStep(state, onPick = { picking = true })
                is AddState.Asking -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Stepped text rather than a spinner: e-ink redraws the whole screen for an animation.
                    Text("Asking tt-rss to subscribe…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Muted("A slow server can take half a minute. You can close this; it carries on.")
                }
                is AddState.AlreadyIn -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.title} is in ${state.category ?: UNCATEGORIZED}.")
                    alreadyNote(state)?.let { Muted(it) }
                }
                is AddState.NoFeed -> Column {
                    Text(
                        if (state.list != null) "${state.site} has no feed, but newspapeRSS can read its picks each day, on this phone."
                        else "${state.site} doesn't offer a feed, so tt-rss can't follow it.",
                    )
                    state.list?.let { list -> TextButton(onClick = { viewModel.addList(list) }) { Text("Add ${list.title}") } }
                    if (state.page != null) TextButton(onClick = viewModel::saveInstead) { Text("Save this page to your reading list") }
                }
                is AddState.Refused -> Column {
                    Text(state.reason)
                    if (state.couldntFetch) Muted("Some sites block servers, or your server can't reach them.")
                    if (state.page != null) TextButton(onClick = viewModel::saveInstead) { Text("Save this page to your reading list") }
                }
                AddState.Closed -> {}
            }
        },
        confirmButton = {
            when {
                state is AddState.Editing -> TextButton(onClick = viewModel::find, enabled = state.input.isNotBlank()) { Text("Add") }
                state is AddState.Subscribing && picking -> TextButton(onClick = { picking = false }) { Text("Done") }
                state is AddState.Subscribing -> TextButton(onClick = viewModel::subscribeInTtrss, enabled = state.categories != null) { Text("Subscribe") }
                state is AddState.AlreadyIn -> TextButton(onClick = viewModel::closeAdd) { Text("OK") }
            }
        },
        dismissButton = {
            when (state) {
                is AddState.AlreadyIn -> {}
                is AddState.Asking -> TextButton(onClick = viewModel::closeAdd) { Text("Close") }
                else -> if (!(state is AddState.Subscribing && picking)) TextButton(onClick = viewModel::closeAdd) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/** The feed found, and the tt-rss category it goes into. */
@Composable
private fun SubscribeStep(state: AddState.Subscribing, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.feed.title ?: SourceRepository.hostOf(state.feed.url), style = MaterialTheme.typography.titleSmall)
        Muted(state.feed.url.substringAfter("://").removePrefix("www."))
        Text("Category", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        val categories = state.categories
        if (categories == null) {
            Text("Loading your categories…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            val name = categories.firstOrNull { it.id == state.chosen }?.title ?: UNCATEGORIZED
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Category, $name" }) {
                Text(name, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        state.error?.let { Text("Couldn't load your categories. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        // tt-rss's API can't make a category.
        Muted("To add a category, make it in tt-rss first.")
    }
}

@Composable
private fun CategoryChoices(state: AddState.Subscribing, onChoose: (Int) -> Unit) {
    Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
        state.categories.orEmpty().forEach { category ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = category.id == state.chosen, role = Role.RadioButton, onClick = { onChoose(category.id) })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = category.id == state.chosen, onClick = null)
                Text(category.title, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Muted("To add a category, make it in tt-rss first.")
    }
}

/** Sites that aren't feeds but pick a few links a day; one tap adds one. */
@Composable
private fun CuratedListChoices(lists: List<CuratedList>, onAdd: (CuratedList) -> Unit) {
    Text("Curated lists", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    lists.forEach { list ->
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Add ${list.title}") { onAdd(list) }.padding(vertical = 8.dp)) {
            Text(list.title)
            Text(list.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How recently a site published, so a quiet or dead one stands out without counting what's unread. */
internal fun freshness(lastNew: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastNew == null) return "No articles yet"
    val days = ChronoUnit.DAYS.between(lastNew.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0 -> "New articles today"
        days == 1L -> "Last new article yesterday"
        else -> "Last new article $days days ago"
    }
}
