package com.app.newspaperss.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.lists.CuratedList
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(viewModel: SourcesViewModel, onOpenReadingList: () -> Unit = {}, onOpenSource: (Long) -> Unit = {}) {
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
                                onClick = { menu = false; exportFile.launch("newspapeRSS-sources.opml") },
                            )
                            if (viewModel.canAddTtrss) {
                                DropdownMenuItem(text = { Text("Add tt-rss account") }, onClick = { menu = false; viewModel.openTtrss() })
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
                    SourceItem(
                        row,
                        onOpen = { onOpenSource(row.source.id) },
                        onRemove = { viewModel.remove(row.source) },
                        onTogglePause = { viewModel.togglePaused(row.source) },
                        onChooseMode = { viewModel.chooseContentMode(row.source, it) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    val curatedLists by viewModel.curatedLists.collectAsState()
    AddSourceDialog(add, curatedLists, viewModel)
    val ttrssForm by viewModel.ttrssForm.collectAsState()
    ttrssForm?.let { TtrssDialog(it, viewModel) }
}

@Composable
internal fun TtrssDialog(form: TtrssForm, viewModel: SourcesViewModel) {
    val categories = form.categories
    if (categories != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeTtrss,
            // A stray tap beside it (easy on e-ink) shouldn't throw the choice away.
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Which articles?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Take unread articles from all your feeds, or from one category. You can change this on the source's page.",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Column(Modifier.selectableGroup()) {
                        TtrssCategoryRow("All your unread articles", form.category == null) { viewModel.pickTtrssCategory(null) }
                        categories.forEach { category ->
                            TtrssCategoryRow(category.title, form.category?.id == category.id) { viewModel.pickTtrssCategory(category) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::finishTtrss, enabled = !form.testing) { Text("Add") } },
            dismissButton = { TextButton(onClick = viewModel::closeTtrss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = viewModel::closeTtrss,
        title = { Text("Add tt-rss account") },
        text = {
            Column {
                Text(
                    "If you run Tiny Tiny RSS, newspapeRSS can make editions from your unread articles and mark them read there once an edition is delivered.",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = form.address,
                    onValueChange = { viewModel.editTtrss(form.copy(address = it)) },
                    label = { Text("Address") },
                    placeholder = { Text("rss.example.com/tt-rss") },
                    singleLine = true,
                    enabled = !form.testing,
                    supportingText = if (form.address.trim().startsWith("http://", ignoreCase = true)) {
                        { Text("This address isn't encrypted: your password would be sent in the clear. Use https:// if your server supports it.") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.user,
                    onValueChange = { viewModel.editTtrss(form.copy(user = it)) },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !form.testing,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { viewModel.editTtrss(form.copy(password = it)) },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !form.testing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.connectTtrss() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    form.testing -> Text("Signing in…", modifier = Modifier.padding(top = 12.dp))
                    form.error != null -> Text(
                        form.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::connectTtrss, enabled = form.address.isNotBlank() && !form.testing) { Text("Test and add") }
        },
        dismissButton = { TextButton(onClick = viewModel::closeTtrss) { Text("Cancel") } },
    )
}

@Composable
private fun TtrssCategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
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
            "Add a website you like to read. Paste its address and newspapeRSS does the rest.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SourceItem(row: SourceRow, onOpen: () -> Unit, onRemove: () -> Unit, onTogglePause: () -> Unit, onChooseMode: (ContentMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var choosingMode by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val s = row.source
    val fullText = fullTextLine(s)
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
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${s.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (s.paused) "Resume" else "Pause") }, onClick = { menu = false; onTogglePause() })
                    if (s.kind == SourceKind.FEED) {
                        DropdownMenuItem(text = { Text("Article text") }, onClick = { menu = false; choosingMode = true })
                    }
                    DropdownMenuItem(text = { Text("Remove source") }, onClick = { menu = false; removing = true })
                }
            }
        },
    )
    if (choosingMode) {
        ContentModeDialog(s, onChoose = { choosingMode = false; onChooseMode(it) }, onDismiss = { choosingMode = false })
    }
    if (removing) {
        RemoveSourceDialog(s, onConfirm = { removing = false; onRemove() }, onDismiss = { removing = false })
    }
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
internal fun ContentModeDialog(source: SourceEntity, onChoose: (ContentMode) -> Unit, onDismiss: () -> Unit) {
    val current = if (source.contentModeChosen) source.contentMode else ContentMode.AUTO
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Article text") },
        text = {
            Column(Modifier.selectableGroup()) {
                CONTENT_MODE_CHOICES.forEach { (mode, label) ->
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

/** Where a site's article text comes from, once the app knows or the reader has chosen; null while it's still checking. */
internal fun fullTextLine(source: SourceEntity): String? {
    if (source.kind != SourceKind.FEED) return null
    if (source.contentModeChosen) {
        return when (source.contentMode) {
            ContentMode.FEED -> "Uses the text the site sends (your choice)"
            ContentMode.PAGE -> "Always fetches the full page (your choice)"
            ContentMode.AUTO -> null
        }
    }
    return when (source.contentMode) {
        ContentMode.AUTO -> null
        ContentMode.PAGE -> "Full articles"
        ContentMode.FEED -> when (source.fullTextEvidence) {
            FullTextEvidence.FEED_SHORT -> "Summaries only"
            FullTextEvidence.BLOCKED -> "Site blocks fetching: using the summaries it sends"
            else -> "Full articles"
        }
    }
}

@Composable
private fun AddSourceDialog(state: AddState, curatedLists: List<CuratedList>, viewModel: SourcesViewModel) {
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
                    if (state.page != null) {
                        TextButton(onClick = viewModel::saveInstead) { Text("Save this page to your reading list instead") }
                    }
                    if (curatedLists.isNotEmpty()) CuratedListChoices(curatedLists, viewModel::addList)
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
