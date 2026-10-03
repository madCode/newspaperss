package com.app.newspaperss.ui.sources

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.app.newspaperss.core.plural
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A tt-rss account's left-out feeds, each opening its page to bring it back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeftOutScreen(viewModel: SourceDetailViewModel, onBack: () -> Unit, onOpenFeed: (key: String) -> Unit) {
    val feeds by viewModel.feeds.collectAsState()
    val detail by viewModel.detail.collectAsState()
    // The account was removed, here or elsewhere.
    LaunchedEffect(detail) { if (detail != null && detail?.source == null) onBack() }
    val leftOut = feeds.filter { !it.inPaper }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Left out of the paper") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Text(
                    if (leftOut.isEmpty()) "Every feed goes in your paper." else "These stay in your tt-rss. Open one to bring it back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(leftOut, key = { it.originId }) { feed ->
                ListItem(
                    headlineContent = { Text(feed.title) },
                    supportingContent = feed.publication?.category?.let { { Text(it) } },
                    modifier = Modifier.clickable(onClickLabel = "Open ${feed.title}") { onOpenFeed(feed.originId) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * A tt-rss account's feeds in categories its paper doesn't take articles from, by category. They
 * come back by choosing all categories, or theirs, in Settings, so there's nothing to open here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotInPaperScreen(viewModel: SourceDetailViewModel, onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val outside by viewModel.outside.collectAsState()
    val detail by viewModel.detail.collectAsState()
    LaunchedEffect(detail) { if (detail != null && detail?.source == null) onBack() }
    val chosen = detail?.source?.ttrssCategoryTitle
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Not in your paper") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item(key = "hint") {
                Text(
                    if (chosen == null || outside.isEmpty()) "Your paper takes articles from all your tt-rss feeds."
                    else "Your paper takes articles from $chosen only. These stay in your tt-rss.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                TextButton(onClick = onOpenAccount, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Change Articles from in Settings") }
                HorizontalDivider()
            }
            items(outside, key = { it.name?.let { n -> "category/$n" } ?: "uncategorized" }) { category ->
                ListItem(
                    headlineContent = { Text(category.name ?: UNCATEGORIZED) },
                    supportingContent = { Text("${plural(category.feeds.size, "feed")}: ${category.feeds.joinToString(", ") { it.title }}") },
                )
                HorizontalDivider()
            }
        }
    }
}
