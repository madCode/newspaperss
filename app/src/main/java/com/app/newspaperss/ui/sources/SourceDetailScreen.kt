package com.app.newspaperss.ui.sources

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.app.newspaperss.ui.components.ArticleButtons
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
import androidx.compose.material3.ListItem
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
import androidx.compose.foundation.layout.widthIn
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
    val building by viewModel.building.collectAsState()
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        // Ended however the snackbar goes, including the screen leaving or rotating, so a stale
        // Undo never comes back. Missed, starring still brings the article back.
        try {
            // Long: on e-ink the reader may not see it straight away.
            val result = snackbar.showSnackbar("Marked as read", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoMarkRead(offer)
        } finally {
            viewModel.undoOfferEnded(offer)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (source == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val articles = detail?.articles.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Health(source, articles.maxOfOrNull { it.discoveredAt }, locale, is24Hour)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::togglePaused) { Text(if (source.paused) "Resume" else "Pause") }
                    if (source.kind == SourceKind.FEED) OutlinedButton(onClick = { choosingMode = true }) { Text("Article text: ${modeName(source)}") }
                    TextButton(onClick = { removing = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                if (source.kind == SourceKind.TTRSS) {
                    TtrssOptions(source, viewModel::openCategories, viewModel::setMarkReadOnServer)
                } else {
                    ArticleCap(source.maxArticles, detail?.defaultMax ?: 1, viewModel::stepMaxArticles, viewModel::followEditionMax)
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Text(
                    if (articles.isEmpty()) "Recent articles" else "Recent articles · ${articles.size}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics {
                        heading()
                        contentDescription = if (articles.isEmpty()) "Recent articles" else "Recent articles, ${articles.size}"
                    },
                )
                if (articles.isEmpty()) Text("No articles yet.", modifier = Modifier.padding(horizontal = 16.dp))
                if (building && articles.isNotEmpty()) {
                    Text(BUILDING_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            items(articles, key = { it.id }) { article ->
                RecentArticle(article, locale, building, onStar = { viewModel.setStarred(article.id, it) }, onMarkRead = { viewModel.markRead(article.id) })
                // Inset: full-width rules chopped the list into boxes to track across.
                HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
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

/**
 * Tapping the row opens the original and changes nothing; the buttons under it star the article
 * or mark it as read. A marked-read row stays where it is, so the list doesn't reflow (a full
 * refresh on e-ink) and the reader keeps her place.
 */
@Composable
private fun RecentArticle(article: ArticleEntity, locale: Locale, building: Boolean, onStar: (Boolean) -> Unit, onMarkRead: () -> Unit) {
    val context = LocalContext.current
    val status = articleStatus(article)
    val details = buildAnnotatedString {
        append(listOfNotNull(article.originTitle, shortDate(article.discoveredAt, locale)).joinToString(" · "))
        append(" · ")
        if (isStarred(article)) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(status) } else append(status)
    }
    val title = article.title.ifBlank { SourceRepository.hostOf(article.url) }
    Column {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "open in browser") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            // A shape per state, not a colour, so it reads on e-ink; the words are in the line below.
            leadingContent = {
                Text(statusMark(article), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 24.dp).clearAndSetSemantics {})
            },
            headlineContent = { Text(title, maxLines = 2, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        // Nothing to change on an article already in an unsent edition: it's going out.
        if (article.state != ArticleState.IN_EDITION) {
            ArticleButtons(
                title = title,
                starred = article.starredAt != null,
                onStar = onStar,
                onMarkRead = onMarkRead.takeIf { article.state == ArticleState.NEW },
                building = building,
                // Lines the button text up with the title, past the buttons' own padding.
                modifier = Modifier.padding(start = 44.dp, end = 16.dp, bottom = 4.dp),
            )
        }
    }
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

private fun shortDate(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(at.atZone(zone))
