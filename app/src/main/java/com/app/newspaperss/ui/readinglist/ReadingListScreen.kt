package com.app.newspaperss.ui.readinglist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(viewModel: ReadingListViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { viewModel.import(it.bufferedReader().readText()) }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            scope.launch {
                val text = viewModel.exportText()
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            }
        }
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
                title = { Text("Reading list") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Import or export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Import a checklist") }, onClick = {
                                menu = false
                                importFile.launch(arrayOf("text/markdown", "text/plain", "text/*"))
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
                        "Nothing saved yet. In any app, tap Share on a page and choose “Read in newspaperss”: " +
                            "it goes into your next edition.",
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
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = false },
            label = { Text("Paste a link") },
            singleLine = true,
            isError = error,
            supportingText = if (error) { { Text("That doesn't contain a web address.") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("Save") }
    }
}

@Composable
private fun SavedLink(article: ArticleEntity, onRemove: () -> Unit) {
    val status = when (article.state) {
        ArticleState.NEW -> "Waiting for your next edition"
        ArticleState.IN_EDITION -> "In an edition you haven't sent yet"
        ArticleState.DELIVERED -> "Delivered"
        ArticleState.SKIPPED, ArticleState.EXPIRED -> "Skipped"
    }
    ListItem(
        headlineContent = { Text(if (article.title == article.url) SourceRepository.hostOf(article.url) else article.title, maxLines = 2) },
        supportingContent = {
            Column {
                if (article.title != article.url) Text(SourceRepository.hostOf(article.url), style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") } },
    )
}
