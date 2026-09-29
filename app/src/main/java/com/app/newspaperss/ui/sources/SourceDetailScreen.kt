package com.app.newspaperss.ui.sources

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One source's health and its recent articles, so the reader can see what it has been sending. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailScreen(viewModel: SourceDetailViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsState()
    val source = detail?.source
    val locale = LocalConfiguration.current.locales[0]
    var removing by remember { mutableStateOf(false) }
    var choosingMode by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source?.title.orEmpty(), maxLines = 1) },
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
                Health(source, articles.maxOfOrNull { it.discoveredAt }, locale)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::togglePaused) { Text(if (source.paused) "Resume" else "Pause") }
                    if (source.kind == SourceKind.FEED) OutlinedButton(onClick = { choosingMode = true }) { Text("Article text") }
                    TextButton(onClick = { removing = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
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
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${source.title}?") },
            text = { Text("Its waiting articles go with it. If you add it again, articles you already got won't be sent again.") },
            confirmButton = { TextButton(onClick = { removing = false; viewModel.remove(onBack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Health(source: SourceEntity, lastNew: Instant?, locale: Locale) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SourceRepository.hostOf(source.siteUrl ?: source.url), style = MaterialTheme.typography.bodyMedium, color = muted)
        Text(statusLine(source, lastNew), color = if (hasProblem(source)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!source.paused) failingLine(source.failingSince, locale)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        lastCheckedLine(source.lastFetchedAt, locale)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        textLine(source)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
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

internal fun lastCheckedLine(at: Instant?, locale: Locale, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (at == null) return null
    val date = at.atZone(zone)
    return if (date.toLocalDate() == now.atZone(zone).toLocalDate()) {
        "Last checked today at ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date)}"
    } else {
        "Last checked ${shortDate(at, locale, zone)}"
    }
}

private fun shortDate(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(at.atZone(zone))
