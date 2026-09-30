package com.app.newspaperss.ui.readinglist

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.ReadingTime
import androidx.compose.foundation.clickable
import android.net.Uri
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.SourceRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(viewModel: ReadingListViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(context.contentResolver, uri)
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) viewModel.export(context.contentResolver, uri)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    // Removing is one tap and routine, so it's undone rather than confirmed.
    // Leaving by another tab keeps this screen's state, so the ViewModel isn't cleared: settle a
    // waiting removal on the way out too, but not on rotation, which shows the Undo again.
    DisposableEffect(Unit) {
        onDispose { if ((context as? Activity)?.isChangingConfigurations != true) viewModel.commitRemove() }
    }
    val removed by viewModel.removed.collectAsState()
    LaunchedEffect(removed) {
        val gone = removed ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("Removed “${titleOf(gone)}”", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove() else viewModel.commitRemove()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading list") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Import or export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Import a reading list") }, onClick = {
                                menu = false
                                // Some providers label a .csv as an Excel file.
                                importFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
                            })
                            DropdownMenuItem(text = { Text("Export as a checklist") }, onClick = {
                                menu = false
                                exportFile.launch("reading-list.md")
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { AddLink(viewModel) }
            val list = items.orEmpty()
            if (items != null && list.isEmpty()) {
                item {
                    Text(
                        "Nothing saved yet. In any app, tap Share on a page and choose “Read in newspapeRSS”: " +
                            "it goes into your next edition.\n\nComing from Pocket or Instapaper? " +
                            "Choose “Import a reading list” in the menu and pick its export file.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(list, key = { it.id }) { article ->
                SavedLink(article, onRemove = { viewModel.remove(article) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AddLink(viewModel: ReadingListViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() {
        if (viewModel.add(text)) text = "" else error = true
    }
    // The button goes below the field, not beside it: at large font sizes it squeezes the field.
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.End) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = false },
            label = { Text("Paste a link") },
            singleLine = true,
            isError = error,
            supportingText = if (error) { { Text("That doesn't contain a web address.") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("Save") }
    }
}

@Composable
private fun SavedLink(article: ArticleEntity, onRemove: () -> Unit) {
    val status = if (article.starredAt != null && article.state != ArticleState.IN_EDITION) "Starred for your next edition" else when (article.state) {
        ArticleState.NEW -> "Waiting for an edition"
        ArticleState.IN_EDITION -> "In an edition you haven't sent yet"
        ArticleState.DELIVERED -> "In an edition you sent"
        ArticleState.SKIPPED, ArticleState.EXPIRED -> "Skipped"
    }
    val context = LocalContext.current
    val site = listOfNotNull(
        // An untitled row's stand-in title already names the site.
        SourceRepository.hostOf(article.url).takeIf { article.title.isNotBlank() },
        article.pageWords?.takeIf { it > 0 }?.let { "${ReadingTime.format(ReadingTime.minutes(it))} read" },
    ).joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Open in your browser") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        headlineContent = { Text(titleOf(article), maxLines = 2) },
        supportingContent = {
            Column {
                if (site.isNotEmpty()) Text(site, style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove ${titleOf(article)}") } },
    )
}

/** Before its title is found, a title made from the address reads better than the bare domain. */
private fun titleOf(article: ArticleEntity) = article.title.ifBlank { ArticleExtractor.titleFromUrl(article.url) }
