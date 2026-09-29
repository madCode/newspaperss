package com.app.newspaperss.ui.onboarding

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.settings.Device
import com.app.newspaperss.core.plural
import com.app.newspaperss.ui.components.CheckChip
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.TtrssDialog
import com.app.newspaperss.ui.today.Masthead
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
/** @param sources offers importing from another reader: an OPML file or a tt-rss account. */
fun OnboardingScreen(viewModel: OnboardingViewModel, sources: SourcesViewModel? = null) {
    val s by viewModel.state.collectAsState()
    BackHandler(enabled = s.step != Step.WELCOME) { viewModel.back() }
    // Asked here, as the first edition is made, because a scheduled edition is only
    // announced by notification; the answer doesn't change what happens next.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.finish() }
    val finish = {
        if (s.scheduleEnabled && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.finish()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (s.step != Step.WELCOME) {
                LinearProgressIndicator(
                    progress = { s.step.ordinal / (Step.entries.size - 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                when (s.step) {
                    Step.WELCOME -> Welcome()
                    Step.DEVICE -> DeviceStep(s, viewModel)
                    Step.SOURCES -> SourcesStep(s, viewModel, sources)
                    Step.SIZE -> SizeStep(s, viewModel)
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.step != Step.WELCOME) TextButton(onClick = viewModel::back) { Text("Back") }
                Spacer(Modifier.weight(1f))
                when (s.step) {
                    Step.WELCOME -> Button(onClick = viewModel::next) { Text("Get started") }
                    Step.SIZE -> Button(onClick = finish, enabled = s.canContinue) {
                        Text(if (s.finishing) "Setting up…" else "Make my first edition")
                    }
                    else -> Button(onClick = viewModel::next, enabled = s.canContinue) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))

@Composable
private fun Welcome() {
    Masthead(LocalDate.now(), Modifier.fillMaxWidth().padding(bottom = 32.dp))
    Text("Your own newspaper, on your e-reader.", style = MaterialTheme.typography.headlineSmall)
    listOf(
        "You choose the sources. No algorithm decides what you see.",
        "It's a set size, like a paper: about half an hour, then it ends.",
        "It arrives on your Kindle, Kobo or other e-reader, away from your phone's distractions.",
    ).forEach { Text("•  $it", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun DeviceStep(s: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.chooseFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    Title("Where do you read?")
    Device.entries.forEach { device ->
        Row(
            Modifier.fillMaxWidth().selectable(s.device == device, role = Role.RadioButton) { vm.chooseDevice(device) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.device == device, onClick = null)
            Text(device.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    val device = s.device ?: return
    Text(DeviceTips.tip(device), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
    if (s.needsFolder) {
        OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(top = 12.dp)) {
            Text(s.folderName?.let { "Folder: $it" } ?: "Choose the folder")
        }
        if (s.folderUri == null) {
            Text(
                "You can skip this and send editions yourself for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesStep(s: OnboardingState, vm: OnboardingViewModel, sources: SourcesViewModel?) {
    Title("What do you like to read?")
    Text("Pick a few to start. You can change them any time.", style = MaterialTheme.typography.bodyMedium)
    if (sources != null) FromAnotherReader(s.added, vm, sources)
    Text(
        "Saw something to read later? In any app, tap Share and choose \u201cRead in newspapeRSS\u201d.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    OutlinedTextField(
        value = s.pasted,
        onValueChange = vm::editPasted,
        label = { Text("Add a website you read") },
        placeholder = { Text("example.com") },
        singleLine = true,
        isError = s.findError != null,
        supportingText = s.findError?.let { { Text(it) } },
        trailingIcon = {
            if (s.searching) Text("Checking…", Modifier.padding(end = 12.dp)) else TextButton(onClick = vm::findPasted, enabled = s.pasted.isNotBlank()) { Text("Add") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.findPasted() }),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    s.found.forEach { feed -> FeedCheck(feed.title, feed.url in s.chosen) { vm.toggleFeed(feed.url) } }
    StarterPacks.all.forEach { pack ->
        val urls = pack.feeds.map { it.url }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleLarge)
                Text(pack.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CheckChip(
                selected = s.chosen.containsAll(urls),
                onClick = { vm.togglePack(pack.name) },
                label = "All",
                modifier = Modifier.semantics { contentDescription = "All of ${pack.name}" },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pack.feeds.forEach { feed ->
                CheckChip(selected = feed.url in s.chosen, onClick = { vm.toggleFeed(feed.url) }, label = feed.title)
            }
        }
    }
}

@Composable
private fun FromAnotherReader(added: Int, onboarding: OnboardingViewModel, sources: SourcesViewModel) {
    val context = LocalContext.current
    val message by sources.message.collectAsState()
    val ttrssForm by sources.ttrssForm.collectAsState()
    val rows by sources.rows.collectAsState()
    LaunchedEffect(rows) { rows?.let { onboarding.sourcesAdded(it.size) } }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sources.importOpml(context.contentResolver, uri)
    }
    Text("Already use a feed reader?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import an OPML file") }
        if (sources.canAddTtrss) OutlinedButton(onClick = sources::openTtrss) { Text("Connect tt-rss") }
    }
    val status = message ?: if (added > 0) "${plural(added, "source")} added. Pick more below, or go on." else null
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    ttrssForm?.let { TtrssDialog(it, sources) }
}

@Composable
private fun FeedCheck(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(title, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SizeStep(s: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    Title("How big, how often?")
    Text("About ${s.minutes} minutes of reading", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = s.minutes.toFloat(),
        onValueChange = { vm.setMinutes((it / 5).roundToInt() * 5) },
        valueRange = 10f..90f,
    )
    Text(
        when {
            s.minutes <= 15 -> "A quick look at the headlines."
            s.minutes <= 40 -> "About a commute's worth."
            else -> "A proper Sunday-paper sit-down."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp).toggleable(s.scheduleEnabled, role = Role.Switch) { vm.setScheduleEnabled(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("A new edition every day", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = s.scheduleEnabled, onCheckedChange = null)
    }
    if (s.scheduleEnabled) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ready by", Modifier.weight(1f))
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, h, m -> vm.setTime(LocalTime.of(h, m)) }, s.time.hour, s.time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
            }) { Text(s.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
        }
        Text(
            "We'll send a notification when each edition is ready. Nothing else, ever. You can pick days in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
