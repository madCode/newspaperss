package com.app.newspaperss.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.plural
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.ui.components.CheckChip
import com.app.newspaperss.ui.components.KindleEmailFields
import com.app.newspaperss.settings.Device
import com.app.newspaperss.ui.onboarding.DeviceTips
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Settings as AppSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            EditionSection(s, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            ScheduleSection(s, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            // Before Delivery: the e-reader decides which delivery options are offered.
            ReaderSection(s, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            DeliverySection(s, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            NotesSection(s, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            Text(
                "newspapeRSS $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })

@Composable
private fun EditionSection(s: AppSettings, vm: SettingsViewModel) {
    Heading("Your edition")
    var minutes by remember(s.edition.minutes) { mutableFloatStateOf(s.edition.minutes.toFloat()) }
    Text("About ${minutes.roundToInt()} minutes of reading")
    Slider(
        value = minutes,
        onValueChange = { minutes = ((it / 5).roundToInt() * 5).toFloat() },
        onValueChangeFinished = { vm.setMinutes(minutes.roundToInt()) },
        valueRange = SettingsViewModel.MIN_MINUTES.toFloat()..SettingsViewModel.MAX_MINUTES.toFloat(),
        // Stops every 5 minutes, so TalkBack's adjust gestures move by 5 and say minutes, not a percentage.
        steps = (SettingsViewModel.MAX_MINUTES - SettingsViewModel.MIN_MINUTES) / 5 - 1,
        modifier = Modifier.semantics { stateDescription = "${minutes.roundToInt()} minutes" },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A live region, so pressing − or + is followed by the new number.
        Text(
            "${plural(s.edition.maxPerSource, "article")} from each source, then more if there's room",
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = { vm.setMaxPerSource(s.edition.maxPerSource - 1) },
            enabled = s.edition.maxPerSource > 1,
            modifier = Modifier.semantics { contentDescription = "Fewer from each source" },
        ) { Text("−") }
        OutlinedButton(
            onClick = { vm.setMaxPerSource(s.edition.maxPerSource + 1) },
            enabled = s.edition.maxPerSource < SettingsViewModel.MAX_PER_SOURCE,
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "More from each source" },
        ) { Text("+") }
    }
    Text(
        "A source can have its own number: tap it in Sources.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    Column(Modifier.selectableGroup()) {
        listOf(
            Ordering.TAKE_TURNS to "Take turns between sources",
            Ordering.IN_ORDER to "Source by source, in list order",
            Ordering.SHUFFLE to "Shuffle",
        ).forEach { (ordering, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(s.edition.ordering == ordering, role = Role.RadioButton) { vm.setOrdering(ordering) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.edition.ordering == ordering, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Heading("Schedule")
    Row(
        Modifier.fillMaxWidth().toggleable(s.scheduleEnabled, role = Role.Switch) { on ->
            vm.setScheduleEnabled(on)
            if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Make an edition automatically", Modifier.weight(1f))
        Switch(checked = s.scheduleEnabled, onCheckedChange = null)
    }
    if (!s.scheduleEnabled) return
    NotificationsOffWarning(s.delivery)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Ready by", Modifier.weight(1f))
        OutlinedButton(onClick = {
            TimePickerDialog(context, { _, h, m -> vm.setTime(LocalTime.of(h, m)) }, s.schedule.time.hour, s.schedule.time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }) { Text(s.schedule.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
    }
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        DayOfWeek.entries.forEach { day ->
            CheckChip(
                selected = day in s.schedule.days,
                onClick = { vm.toggleDay(day) },
                label = day.getDisplayName(TextStyle.SHORT, locale),
            )
        }
    }
    if (s.schedule.days.isEmpty()) {
        Text("Pick at least one day.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** The e-reader chosen in onboarding, changeable later: it decides Send or Open, and the tips shown. */
@Composable
private fun ReaderSection(s: AppSettings, vm: SettingsViewModel) {
    Heading("Your e-reader")
    Column(Modifier.selectableGroup()) {
        Device.entries.forEach { device ->
            Row(
                Modifier.fillMaxWidth().selectable(s.device == device, role = Role.RadioButton) { vm.setDevice(device) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.device == device, onClick = null)
                Text(device.label, Modifier.padding(start = 12.dp))
            }
        }
    }
    // The tip is about sending with the share sheet. Folder and email delivery say what they do
    // under their own options, where a Kindle owner saving to a folder isn't told to wait for a Send.
    val tip = s.device?.takeIf { s.delivery == DeliveryMethod.SHARE }?.let(DeviceTips::tip)
    tip?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DeliverySection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, FOLDER_GRANT)
            release(context, s.folderUri, keep = setOf(uri.toString(), s.notesFolderUri))
            vm.useFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    Heading("Delivery")
    if (s.device == Device.KINDLE || s.delivery == DeliveryMethod.KINDLE_EMAIL) {
        DeliveryOption(
            selected = s.delivery == DeliveryMethod.KINDLE_EMAIL,
            title = "Email it to your Kindle",
            detail = "Arrives on your Kindle by itself.",
            onClick = vm::useKindleEmail,
        )
        if (s.delivery == DeliveryMethod.KINDLE_EMAIL) {
            // Held here and saved as it changes: fed back from the store, each save would move the cursor.
            var address by rememberSaveable { mutableStateOf(s.kindleEmail.orEmpty()) }
            KindleEmailFields(
                address = address,
                onAddress = { address = it; vm.setKindleEmail(it) },
                mailApp = s.mailApp,
                onMailApp = vm::setMailApp,
                label = "Kindle's email address",
                modifier = Modifier.padding(start = OPTION_INDENT),
            )
            // The saved address, not the typed one: Send goes by what's saved.
            if (s.kindleEmailTarget == null) {
                Text(
                    "Add your Kindle's email address; until then Send opens the share sheet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = OPTION_INDENT, top = 4.dp),
                )
            }
            Text(
                "The address you send from must be on Amazon's approved list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = OPTION_INDENT, top = 4.dp, bottom = 8.dp),
            )
        }
    }
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.SHARE,
        title = "Send it myself",
        detail = "Tap Send on each edition and choose an app: the Kindle app, Dropbox, email or any other.",
        onClick = vm::useShare,
    )
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.FOLDER,
        title = "Save to a folder",
        detail = s.folderName?.let { "Saved automatically to $it." }
            ?: "Fully automatic, for KOReader and other readers that sync a folder on this phone (for example with Syncthing).",
        onClick = { if (s.folderUri != null) vm.useFolder(s.folderUri, s.folderName ?: "your folder") else pickFolder.launch(null) },
    )
    if (s.delivery == DeliveryMethod.FOLDER) {
        OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.padding(start = OPTION_INDENT)) { Text("Choose another delivery folder") }
    }
}

/** How far a setting's own controls sit in, under the option or switch they belong to. */
private val OPTION_INDENT = 48.dp

private const val FOLDER_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Lets go of a folder no setting uses any more: grants are capped per app. The delivery and
 * notes folders can be the same one, so neither lets go of a folder the other still holds.
 */
private fun release(context: android.content.Context, old: String?, keep: Set<String?>) {
    if (old == null || old in keep) return
    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), FOLDER_GRANT) }
}

@Composable
private fun NotesSection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, FOLDER_GRANT)
            release(context, s.notesFolderUri, keep = setOf(uri.toString(), s.folderUri))
            vm.setNotesFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    val saving = s.notesFolderUri != null
    Heading("Reading notes")
    Row(
        Modifier.fillMaxWidth().toggleable(saving, role = Role.Switch) { on ->
            if (on) {
                pickFolder.launch(null)
            } else {
                release(context, s.notesFolderUri, keep = setOf(s.folderUri))
                vm.setNotesFolder(null, null)
            }
        }.heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Save notes for each edition", style = MaterialTheme.typography.bodyLarge)
            Text(
                s.notesFolderName?.let { "Saved to $it when an edition is delivered." }
                    ?: "A Markdown file for each edition, for Obsidian or any notes app. You'll pick the folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = saving, onCheckedChange = null)
    }
    if (saving) OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.padding(start = OPTION_INDENT)) { Text("Choose another notes folder") }
}

@Composable
private fun DeliveryOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Without notifications a timed edition is made but nobody hears about it: a shared or emailed
 * one's Send is in its notification, and a folder delivery that fails says so only there.
 */
@Composable
private fun NotificationsOffWarning(delivery: DeliveryMethod) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    if (enabled) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            if (delivery == DeliveryMethod.FOLDER) "Notifications are off, so you won't hear if an edition fails to arrive."
            else "Notifications are off, so you won't hear when an edition is ready to send.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Turn on notifications") }
    }
}
