package com.app.newspaperss.ui.sources

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(viewModel: SourcesViewModel, onOpenReadingList: () -> Unit = {}) {
    val rows by viewModel.rows.collectAsState()
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Check for new articles") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import from another reader (OPML)") },
                                onClick = { menu = false; importFile.launch(arrayOf("*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export your sites (OPML)") },
                                onClick = { menu = false; exportFile.launch("newspaperss-sources.opml") },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add a source") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> Column(Modifier.padding(padding)) {
                ReadingListRow(onOpenReadingList)
                EmptySources(Modifier)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.padding(padding)) {
                item { ReadingListRow(onOpenReadingList) }
                items(list, key = { it.source.id }) { row ->
                    SourceItem(row, onRemove = { viewModel.remove(row.source) }, onTogglePause = { viewModel.togglePaused(row.source) })
                    HorizontalDivider()
                }
            }
        }
    }
    AddSourceDialog(add, viewModel)
}

@Composable
private fun ReadingListRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Your reading list") },
        supportingContent = { Text("Links you share to newspaperss from any app") },
        leadingContent = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
        modifier = Modifier.clickable(onClickLabel = "Open reading list", onClick = onClick),
    )
    HorizontalDivider()
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
            "Add a website you like to read. Paste its address and newspaperss does the rest.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SourceItem(row: SourceRow, onRemove: () -> Unit, onTogglePause: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val s = row.source
    val status = when {
        s.paused -> "Paused"
        s.lastError != null -> s.lastError
        s.lastFetchedAt == null -> "Checking…"
        else -> freshness(row.lastNew)
    }
    ListItem(
        headlineContent = { Text(s.title) },
        supportingContent = {
            Column {
                Text(SourceRepository.hostOf(s.siteUrl ?: s.url), style = MaterialTheme.typography.bodySmall)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.lastError != null && !s.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${s.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (s.paused) "Resume" else "Pause") }, onClick = { menu = false; onTogglePause() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        },
    )
}

@Composable
private fun AddSourceDialog(state: AddState, viewModel: SourcesViewModel) {
    if (state == AddState.Closed) return
    AlertDialog(
        onDismissRequest = viewModel::closeAdd,
        title = { Text(if (state is AddState.Choosing) "Which part of this site?" else "Add a source") },
        text = {
            when (state) {
                is AddState.Editing -> Column {
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
                }
                is AddState.Searching -> Text("Checking ${state.input}…")
                is AddState.Choosing -> Column {
                    Text("This site offers more than one set of articles.", modifier = Modifier.padding(bottom = 8.dp))
                    state.feeds.forEach { feed ->
                        Text(
                            feed.title ?: feed.url,
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.choose(feed) }.padding(vertical = 12.dp),
                        )
                    }
                }
                AddState.Closed -> {}
            }
        },
        confirmButton = {
            if (state is AddState.Editing) {
                TextButton(onClick = viewModel::find, enabled = state.input.isNotBlank()) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeAdd) { Text("Cancel") } },
    )
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
