package com.app.newspaperss.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssStatus
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.ui.sources.lastCheckedLine
import com.app.newspaperss.ui.ttrss.TtrssSignInFields

/** The sign-in form, in place of the page while it's open. */
@Composable
internal fun FeedsFromSignIn(vm: FeedsFromViewModel) {
    val form by vm.form.collectAsState()
    val f = form ?: return
    Text(
        "FreshRSS and Miniflux are coming.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    TtrssSignInFields(f, vm::editSignIn, vm::signIn)
    Button(onClick = vm::signIn, enabled = f.canSubmit, modifier = Modifier.padding(top = 8.dp)) { Text("Sign in") }
}

/** The choice as two radio rows and, with tt-rss, the account's own settings. */
@Composable
internal fun FeedsFromSection(vm: FeedsFromViewModel) {
    val state by vm.state.collectAsState()
    val s = state ?: return
    val server = s.choice == FeedsFrom.SERVER
    val source = s.ttrss.source
    Text(
        "Your sites come from one place. The reading list and curated lists are always on this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    // Picking the other one asks first (sign in, or the leaving dialog) rather than switching at once.
    Column(Modifier.selectableGroup()) {
        SetupChoice(!server, "This phone", "newspapeRSS finds and fetches your sites itself") { if (server) vm.usePhone() }
        SetupChoice(
            server,
            "My own RSS server",
            if (server && source != null) "tt-rss · ${SourceRepository.hostOf(source.url)}" else "tt-rss today. FreshRSS and Miniflux are coming.",
        ) { if (!server) vm.openSignIn() }
    }
    if (!server) return
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Your tt-rss", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (source == null) {
        Text(
            "Sign in to your tt-rss. Until you do, your paper has only what's on this phone: your reading list and curated lists.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(onClick = vm::openSignIn) { Text("Sign in") }
        return
    }
    Account(source, s.ttrss)
    if (source.paused) {
        Text("Paused: nothing comes from your tt-rss until you resume it.", modifier = Modifier.padding(bottom = 4.dp))
        OutlinedButton(onClick = vm::resume, modifier = Modifier.padding(vertical = 4.dp)) { Text("Resume") }
    }
    OutlinedButton(onClick = vm::openSignIn, modifier = Modifier.padding(vertical = 4.dp)) { Text("Sign in again") }
    TtrssOptions(source, vm::openCategories, vm::setMarkRead)
    val startingFresh by vm.startingFresh.collectAsState()
    StartFresh(source, startingFresh, vm::startFresh)
    val categories by vm.categories.collectAsState()
    categories?.let { CategoryDialog(it, source.ttrssCategoryId, vm::chooseCategory, vm::closeCategories) }
    val leaving by vm.leaving.collectAsState()
    if (leaving) LeaveDialog(onConfirm = vm::leave, onDismiss = vm::cancelLeaving)
}

@Composable
private fun SetupChoice(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The server, who's signed in, and what's wrong with the login if anything. */
@Composable
private fun Account(source: SourceEntity, status: TtrssStatus) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(SourceRepository.hostOf(source.url), style = MaterialTheme.typography.bodyMedium, color = muted)
        val problem = SettingsSummary.loginProblem(status)
        if (problem != null) {
            Text(problem, color = MaterialTheme.colorScheme.error)
        } else {
            Text(status.user?.takeIf { it.isNotEmpty() }?.let { "Signed in as $it" } ?: "Signed in")
        }
        lastCheckedLine(source.lastFetchedAt, locale, is24Hour)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
    }
}

@Composable
private fun LeaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch your feeds on this phone instead?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "newspapeRSS signs out of your tt-rss and removes it here, with its articles waiting for your paper, " +
                        "starred ones too. Nothing changes in tt-rss itself, and read status stops syncing.",
                )
                Text(
                    "Your tt-rss feeds won't come along yet: add your sites again in Sources, or export an OPML file from tt-rss and import it there.",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Switch to this phone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TtrssOptions(source: SourceEntity, onChangeCategory: () -> Unit, onMarkRead: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Articles from")
            Text(source.ttrssCategoryTitle ?: "All your unread articles", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        TextButton(onClick = onChangeCategory) { Text("Change") }
    }
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = source.markReadOnServer, role = Role.Switch, onValueChange = onMarkRead)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sync read status with tt-rss")
            Text(
                if (source.markReadOnServer) {
                    "What you read or mark unread here or in tt-rss shows in both. Delivered articles count as read."
                } else {
                    "tt-rss and this app keep their own read and unread"
                },
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
    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        "Starred ones too: they stay starred, but read. newspapeRSS can't undo this.",
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
