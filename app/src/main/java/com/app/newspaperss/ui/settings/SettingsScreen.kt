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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.ui.components.CheckChip
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
            DeliverySection(s, viewModel)
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
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))

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
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (s.edition.maxPerSource == 1) "Up to 1 article from each site" else "Up to ${s.edition.maxPerSource} articles from each site",
            Modifier.weight(1f),
        )
        OutlinedButton(onClick = { vm.setMaxPerSource(s.edition.maxPerSource - 1) }, enabled = s.edition.maxPerSource > 1) { Text("−") }
        OutlinedButton(
            onClick = { vm.setMaxPerSource(s.edition.maxPerSource + 1) },
            enabled = s.edition.maxPerSource < SettingsViewModel.MAX_PER_SOURCE,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("+") }
    }
    Text(
        "A site can have its own number: tap it in Sources.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    listOf(
        Ordering.TAKE_TURNS to "Take turns between sources",
        Ordering.IN_ORDER to "Source by source, in list order",
        Ordering.SHUFFLE to "Shuffle",
    ).forEach { (ordering, label) ->
        Row(
            Modifier.fillMaxWidth().selectable(s.edition.ordering == ordering, role = Role.RadioButton) { vm.setOrdering(ordering) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.edition.ordering == ordering, onClick = null)
            Text(label, Modifier.padding(start = 12.dp))
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
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Make an edition automatically", Modifier.weight(1f))
        Switch(checked = s.scheduleEnabled, onCheckedChange = null)
    }
    if (!s.scheduleEnabled) return
    if (s.delivery == DeliveryMethod.SHARE) NotificationsOffWarning()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("At", Modifier.weight(1f))
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

@Composable
private fun DeliverySection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Grants are capped per app; let go of the folder this one replaces.
            s.folderUri?.takeIf { it != uri.toString() }?.let { old ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
            }
            vm.useFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    Heading("Delivery")
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.SHARE,
        title = "Send it myself",
        detail = "You get a notification with a Send button. Pick the Kindle app, Dropbox (for a Kobo), email, or any other app.",
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
        OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.padding(start = 48.dp)) { Text("Choose another folder") }
        Row(
            Modifier.fillMaxWidth().toggleable(s.notesWithEdition, role = Role.Switch, onValueChange = vm::setNotesWithEdition)
                .padding(start = 48.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Also save notes with each edition", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "A Markdown file for Obsidian, Logseq or any notes app, with each article's details and prompts for your thoughts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.notesWithEdition, onCheckedChange = null)
        }
    }
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

/** Without notifications a scheduled shared edition is made but nobody hears about it. */
@Composable
private fun NotificationsOffWarning() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    if (enabled) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Notifications are off, so you won't hear when an edition is ready to send.",
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
