package com.app.newspaperss.ui.sources

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.app.newspaperss.ui.components.ArticleRowFrame
import com.app.newspaperss.ui.components.StarToggle
import com.app.newspaperss.ui.components.rowIconSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import com.app.newspaperss.ui.components.BUILDING_NOTE
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import com.app.newspaperss.core.plural
import com.app.newspaperss.core.ttrss.TtrssCategory
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
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
    val detail by viewModel.detail.collectAsState()
    val source = detail?.source
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var removing by remember { mutableStateOf(false) }
    var choosingMode by remember { mutableStateOf(false) }
    val gone = detail != null && source == null
    LaunchedEffect(gone) { if (gone) onGone() }
    val snackbar = remember { SnackbarHostState() }
    val undoOffer by viewModel.undoOffer.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val building by viewModel.building.collectAsState()
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
                    title = { Text(source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
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
                    Health(source, articles.maxOfOrNull { it.discoveredAt }, locale, is24Hour)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::togglePaused) { Text(if (source.paused) "Resume" else "Pause") }
                        if (source.kind == SourceKind.FEED) OutlinedButton(onClick = { choosingMode = true }) { Text("Article text: ${modeName(source)}") }
                        TextButton(onClick = { removing = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    if (source.kind == SourceKind.TTRSS) {
                        TtrssOptions(source, viewModel::openCategories, viewModel::setMarkReadOnServer)
                        val startingFresh by viewModel.startingFresh.collectAsState()
                        StartFresh(source, startingFresh, viewModel::startFresh)
                    } else {
                        ArticleCap(source.maxArticles, detail?.defaultMax ?: 1, viewModel::stepMaxArticles, viewModel::followEditionMax)
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
                        locale,
                        building,
                        selection = if (selecting) article.id in selected else null,
                        onStar = { viewModel.setStarred(article.id, it) },
                        onMarkRead = { viewModel.markRead(article.id) },
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
        ContentModeDialog(source, onChoose = { choosingMode = false; viewModel.chooseContentMode(it) }, onDismiss = { choosingMode = false })
    }
    val categories by viewModel.categories.collectAsState()
    categories?.let { CategoryDialog(it, source?.ttrssCategoryId, viewModel::chooseCategory, viewModel::closeCategories) }
    if (removing && source != null) {
        RemoveSourceDialog(source, onConfirm = { removing = false; viewModel.remove() }, onDismiss = { removing = false })
    }
}

@Composable
private fun Health(source: SourceEntity, lastNew: Instant?, locale: Locale, is24Hour: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SourceRepository.hostOf(source.siteUrl ?: source.url), style = MaterialTheme.typography.bodyMedium, color = muted)
        Text(statusLine(source, lastNew), color = if (hasProblem(source)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!source.paused) failingLine(source.failingSince, locale)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        lastCheckedLine(source.lastFetchedAt, locale, is24Hour)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        textLine(source)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
    }
}

@Composable
private fun TtrssOptions(source: SourceEntity, onChangeCategory: () -> Unit, onMarkRead: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Articles from")
            Text(source.ttrssCategoryTitle ?: "All your unread articles", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        TextButton(onClick = onChangeCategory) { Text("Change") }
    }
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = source.markReadOnServer, role = Role.Switch, onValueChange = onMarkRead)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mark as read in tt-rss")
            Text(
                if (source.markReadOnServer) "Articles are marked read once they're delivered or you mark them read" else "Articles are left unread in tt-rss",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        Switch(checked = source.markReadOnServer, onCheckedChange = null)
    }
}

@Composable
private fun StartFresh(source: SourceEntity, working: Boolean, onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Back after a break?")
            Text(
                "Mark everything older than two weeks as read in tt-rss, and start from what's recent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { confirming = true }, enabled = !working) { Text(if (working) "Marking…" else "Start fresh") }
    }
    if (confirming) {
        val scope = source.ttrssCategoryTitle?.let { " in $it" } ?: ""
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start fresh?") },
            text = {
                Text(
                    "Every unread article that reached tt-rss more than two weeks ago$scope will be marked read there. " +
                        "Starred articles stay starred. newspapeRSS can't undo this.",
                )
            },
            confirmButton = { TextButton(onClick = { confirming = false; onConfirm() }) { Text("Mark as read") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryDialog(picker: CategoryPicker, current: Int?, onChoose: (TtrssCategory?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Articles from") },
        text = {
            when (picker) {
                CategoryPicker.Loading -> Text("Asking tt-rss for your categories…")
                is CategoryPicker.Failed -> Text(picker.message, color = MaterialTheme.colorScheme.error)
                is CategoryPicker.Choosing -> Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    CategoryChoice("All your unread articles", current == null) { onChoose(null) }
                    picker.categories.forEach { category -> CategoryChoice(category.title, current == category.id) { onChoose(category) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CategoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ArticleCap(own: Int?, default: Int, onStep: (Int) -> Unit, onFollowDefault: () -> Unit) {
    val max = own ?: default
    // The edition's number gives way when there's room; a site's own number is a hard limit.
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (own == null) "${plural(default, "article")} from this site, then more if there's room"
                else "At most ${plural(own, "article")} from this site in each edition",
            )
            Text(
                if (own == null) "Your edition setting. Choose fewer or more to give this site a firm limit."
                else "Your edition setting: ${plural(default, "article")} from each site, then more if there's room",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { onStep(-1) }, enabled = own == null || own > 1, modifier = Modifier.semantics { contentDescription = "Fewer articles from this site" }) {
            Text("−")
        }
        OutlinedButton(
            onClick = { onStep(1) },
            enabled = max < SettingsViewModel.MAX_PER_SOURCE,
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "More articles from this site" },
        ) { Text("+") }
    }
    if (own != null) {
        TextButton(onClick = onFollowDefault, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Use your edition setting") }
    }
}

@Composable
private fun ArticlesHeading(articles: List<ArticleEntity>, selecting: Boolean, onSelect: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (articles.isEmpty()) "Recent articles" else "Recent articles · ${articles.size}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics {
                heading()
                contentDescription = if (articles.isEmpty()) "Recent articles" else "Recent articles, ${articles.size}"
            },
        )
        if (articles.isNotEmpty()) {
            // Hidden rather than removed while selecting: removing it would change the heading's
            // height and width, and move every row on the way in and out of selection mode.
            TextButton(
                onClick = onSelect,
                enabled = !selecting,
                modifier = Modifier.heightIn(min = 48.dp).then(if (selecting) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
            ) { Text("Select") }
        }
    }
    if (articles.isEmpty()) Text("No articles yet.", modifier = Modifier.padding(horizontal = 16.dp))
    if (articles.isNotEmpty()) {
        val canStar = articles.any { it.state != ArticleState.IN_EDITION }
        val second = if (selecting) CHOOSE_HELP else SELECT_HELP.takeIf { articles.any { it.state == ArticleState.NEW } }
        val shown = listOfNotNull(STAR_HELP.takeIf { canStar }, second).joinToString(" ")
        // The longest wordings are laid out invisibly under the one shown, so the line keeps one
        // height as what it says changes: a change in its line count would move every row below it.
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            HelperText("$STAR_HELP $SELECT_HELP", shown = false)
            HelperText("$STAR_HELP $CHOOSE_HELP", shown = false)
            if (shown.isNotEmpty()) HelperText(shown, shown = true)
        }
    }
}

private const val STAR_HELP = "Tap ☆ to put one in your next edition."
private const val SELECT_HELP = "Tap Select to mark some as read."
private const val CHOOSE_HELP = "Tap articles to choose them."

@Composable
private fun HelperText(text: String, shown: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // TalkBack would read ☆ as "white star".
        modifier = if (shown) Modifier.semantics { contentDescription = text.replace("☆", "the star") } else Modifier.alpha(0f).clearAndSetSemantics {},
    )
}

/**
 * The actions for the selected articles, labelled once here instead of on every row. Counts are
 * of what each action would change: articles in an unsent edition can be selected but nothing
 * applies to them. The buttons stack at large font sizes rather than truncating.
 */
@Composable
private fun SelectionBar(
    chosen: List<ArticleEntity>,
    building: Boolean,
    onStar: (List<Long>, Boolean) -> Unit,
    onMarkRead: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waiting = chosen.filter { it.state == ArticleState.NEW }.map { it.id }
    val starrable = chosen.filter { it.state != ArticleState.IN_EDITION }
    val takeOut = starrable.isNotEmpty() && starrable.all { it.starredAt != null }
    val toChange = starrable.filter { (it.starredAt != null) == takeOut }.map { it.id }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            // A rule, not a shadow: it still reads on e-ink.
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (toChange.isEmpty() && waiting.isEmpty()) {
                Text(
                    if (chosen.isEmpty()) "Tap articles to choose them." else "These are in an edition you haven't sent yet, so there's nothing to change.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                return@Column
            }
            val starLabel = if (takeOut) "Take out of next edition" else "Next edition"
            val markLabel = "Mark ${waiting.size} as read"
            val measurer = rememberTextMeasurer()
            val labelStyle = MaterialTheme.typography.labelLarge
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Side by side only if each label fits on one line in half the width; otherwise
                // stacked at full width. Squeezed side by side, large text would wrap inside the
                // buttons word by word.
                val half = (maxWidth - 8.dp) / 2
                val density = LocalDensity.current
                fun fits(label: String, chrome: Dp) = with(density) { measurer.measure(label, labelStyle).size.width.toDp() } + chrome <= half
                val sideBySide = toChange.isEmpty() || waiting.isEmpty() || (fits(starLabel, BUTTON_CHROME + 26.dp) && fits(markLabel, BUTTON_CHROME))
                val starButton: @Composable (Modifier) -> Unit = { modifier ->
                    val articles = plural(toChange.size, "article")
                    OutlinedButton(
                        onClick = { onStar(toChange, !takeOut) },
                        enabled = !(takeOut && building),
                        modifier = modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = if (takeOut) "Take $articles out of your next edition" else "Put $articles in your next edition"
                        },
                    ) {
                        Icon(if (takeOut) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(starLabel, textAlign = TextAlign.Center)
                    }
                }
                val markButton: @Composable (Modifier) -> Unit = { modifier ->
                    Button(
                        onClick = { onMarkRead(waiting) },
                        enabled = !building,
                        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mark ${plural(waiting.size, "article")} as read" },
                    ) { Text(markLabel, textAlign = TextAlign.Center) }
                }
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (toChange.isNotEmpty()) starButton(Modifier.weight(1f))
                        if (waiting.isNotEmpty()) markButton(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        starButton(Modifier.fillMaxWidth())
                        markButton(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** A button's own horizontal padding around its label (Material's 24dp a side). */
private val BUTTON_CHROME = 48.dp

private fun undoMessage(change: SourceDetailViewModel.Change): String = when (change) {
    is SourceDetailViewModel.Change.Read -> if (change.marked.size == 1) "Marked as read" else "Marked ${change.marked.size} as read"
    is SourceDetailViewModel.Change.Stars -> {
        val count = change.batch.changed.size
        if (change.batch.starred) "$count in your next edition" else "$count taken out of your next edition"
    }
}

private val IdSetSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

/**
 * Tapping the row opens the original and changes nothing; the star beside it puts the article in
 * the next edition. Marking read is in selection mode, or the row's accessibility action. A
 * marked-read row stays where it is, so the list doesn't reflow (a full refresh on e-ink) and the
 * reader keeps her place.
 *
 * @param selection whether the row is selected, or null outside selection mode. In it, a tap
 *   selects instead of opening, and a checkbox takes the status mark's place.
 */
@Composable
private fun RecentArticle(
    article: ArticleEntity,
    locale: Locale,
    building: Boolean,
    selection: Boolean?,
    onStar: (Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onSelect: () -> Unit,
    onStartSelecting: () -> Unit,
) {
    val context = LocalContext.current
    val status = articleStatus(article)
    val details = buildAnnotatedString {
        // When it was published: a tt-rss backlog arrives all at once, and every row would show
        // the day it was fetched.
        append(listOfNotNull(article.originTitle, shortDate(article.shownDate, locale)).joinToString(" · "))
        append(" · ")
        if (isStarred(article)) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(status) } else append(status)
    }
    val title = article.title.ifBlank { SourceRepository.hostOf(article.url) }
    val action = if (selection != null) {
        Modifier
            // Extra to the checkbox's shape, which carries the state on e-ink.
            .background(if (selection) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClickLabel = if (selection) "deselect" else "select", role = Role.Checkbox, onClick = onSelect)
            .semantics {
                toggleableState = ToggleableState(selection)
                stateDescription = if (selection) "Selected" else "Not selected"
            }
    } else {
        Modifier.combinedClickable(
            onClickLabel = "open in browser",
            onLongClickLabel = "select",
            onLongClick = onStartSelecting,
            onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
    // One step for TalkBack and Switch Access, where selecting would take several.
    val markRead = if (article.state == ArticleState.NEW && !building) {
        Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Mark as read") { onMarkRead(); true }) }
    } else {
        Modifier
    }
    ArticleRowFrame(
        modifier = action.then(markRead),
        leading = {
            if (selection != null) {
                Icon(
                    if (selection) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(rowIconSize()),
                    tint = if (selection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // A shape per state, not a colour, so it reads on e-ink; the words are in the line below.
                Text(statusMark(article), style = MaterialTheme.typography.bodyLarge, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
            }
        },
        title = { Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        details = { Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        // Nothing to change on an article already in an unsent edition: it's going out. Its slot
        // stays empty so the titles line up.
        trailing = if (article.state == ArticleState.IN_EDITION) null else {
            { StarToggle(title, article.starredAt != null, onStar, enabled = !(building && article.starredAt != null)) }
        },
        reserveTrailing = true,
    )
}

/** A star counts unless the article is already in an unsent edition, where it can't change. */
private fun isStarred(article: ArticleEntity) = article.starredAt != null && article.state != ArticleState.IN_EDITION

private fun statusMark(article: ArticleEntity) = if (isStarred(article)) "●" else when (article.state) {
    ArticleState.NEW, ArticleState.IN_EDITION -> "●"
    ArticleState.DELIVERED -> "✓"
    ArticleState.SKIPPED, ArticleState.EXPIRED -> "○"
}

/** The article-text setting in a word or two, for its button. */
private fun modeName(source: SourceEntity) = when {
    !source.contentModeChosen -> "Automatic"
    source.contentMode == ContentMode.FEED -> "Feed's text"
    source.contentMode == ContentMode.PAGE -> "Full page"
    else -> "Automatic"
}

/** Where the source's text comes from, including while the automatic check is still deciding. */
private fun textLine(source: SourceEntity): String? = fullTextLine(source)
    ?: if (source.kind == SourceKind.FEED && source.contentMode == ContentMode.AUTO) "Still working out whether this site sends full articles" else null

internal fun articleStatus(article: ArticleEntity): String = if (isStarred(article)) "Starred for your next edition" else when (article.state) {
    ArticleState.NEW -> "Waiting for an edition"
    ArticleState.IN_EDITION -> "In an edition you haven't sent yet"
    ArticleState.DELIVERED -> "Delivered"
    ArticleState.SKIPPED -> "Marked as read"
    ArticleState.EXPIRED -> "Not picked before it got old"
}

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
private fun shortDate(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = at.atZone(zone)
    val skeleton = if (date.year == java.time.LocalDate.now(zone).year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}
