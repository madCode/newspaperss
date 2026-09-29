package com.app.newspaperss.ui.sources

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
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
                    if (source.kind == SourceKind.FEED) OutlinedButton(onClick = { choosingMode = true }) { Text("Article text") }
                    TextButton(onClick = { removing = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                if (source.kind != SourceKind.TTRSS) {
                    ArticleCap(source.maxArticles, detail?.defaultMax ?: 1, viewModel::stepMaxArticles, viewModel::followEditionMax)
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Text("Recent articles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                if (articles.isEmpty()) Text("No articles yet.", modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(articles, key = { it.id }) { article ->
                RecentArticle(article, locale)
                HorizontalDivider()
            }
        }
    }
    if (choosingMode && source != null) {
        ContentModeDialog(source, onChoose = { choosingMode = false; viewModel.chooseContentMode(it) }, onDismiss = { choosingMode = false })
    }
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
private fun ArticleCap(own: Int?, default: Int, onStep: (Int) -> Unit, onFollowDefault: () -> Unit) {
    val max = own ?: default
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (max == 1) "Up to 1 article in each edition" else "Up to $max articles in each edition")
            Text(
                if (own == null) "Your edition setting" else "Your edition setting: up to $default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { onStep(-1) }, enabled = max > 1, modifier = Modifier.semantics { contentDescription = "Fewer articles from this site" }) {
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
private fun RecentArticle(article: ArticleEntity, locale: Locale) {
    val details = listOfNotNull(article.originTitle, shortDate(article.discoveredAt, locale), articleStatus(article)).joinToString(" · ")
    ListItem(
        headlineContent = { Text(article.title.ifBlank { SourceRepository.hostOf(article.url) }, maxLines = 2) },
        supportingContent = { Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** Where the source's text comes from, including while the automatic check is still deciding. */
private fun textLine(source: SourceEntity): String? = fullTextLine(source)
    ?: if (source.kind == SourceKind.FEED && source.contentMode == ContentMode.AUTO) "Still working out whether this site sends full articles" else null

internal fun articleStatus(article: ArticleEntity): String = when (article.state) {
    ArticleState.NEW -> if (article.broughtBack) "Brought back for your next edition" else "Waiting for an edition"
    ArticleState.IN_EDITION -> "In an edition you haven't sent yet"
    ArticleState.DELIVERED -> "Delivered"
    ArticleState.SKIPPED -> "Skipped"
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
