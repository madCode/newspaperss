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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
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
import com.app.newspaperss.ui.sources.LARGE_TEXT
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Settings as AppSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Settings: a row per page, each with a line saying how it's set now, so the setup reads at a glance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val settings by viewModel.settings.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        val s = settings ?: return@Scaffold
        val locale = LocalConfiguration.current.locales[0]
        val notificationsOn = rememberNotificationsEnabled()
        val folderReachable = rememberReachable(s.folderUri)
        val notesReachable = rememberReachable(s.notesFolderUri)
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SettingsPage.entries.forEach { page ->
                val summary = when (page) {
                    SettingsPage.EDITION -> SettingsSummary.edition(s)
                    SettingsPage.SCHEDULE -> SettingsSummary.schedule(s, notificationsOn, locale)
                    SettingsPage.DELIVERY -> SettingsSummary.delivery(s, folderReachable)
                    SettingsPage.NOTES -> SettingsSummary.notes(s, notesReachable)
                }
                SummaryRow(page.title, summary) { onOpen(page) }
                HorizontalDivider()
            }
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            Text(
                "newspapeRSS $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SummaryRow(title: String, summary: Summary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            summary.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One Settings page, opened from the summary: the same controls the summary row describes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(viewModel: SettingsViewModel, page: SettingsPage, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (page) {
                SettingsPage.EDITION -> EditionSection(s, viewModel)
                SettingsPage.SCHEDULE -> ScheduleSection(s, viewModel)
                SettingsPage.DELIVERY -> {
                    ReaderPicker(s, viewModel)
                    DeliverySection(s, viewModel)
                }
                SettingsPage.NOTES -> NotesSection(s, viewModel)
            }
        }
    }
}

@Composable
private fun SubHeading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })

@Composable
private fun EditionSection(s: AppSettings, vm: SettingsViewModel) {
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
    val perSource = @Composable { modifier: Modifier ->
        // A live region, so pressing − or + is followed by the new number.
        Text(
            "${plural(s.edition.maxPerSource, "article")} from each source, then more if there's room",
            modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val buttons = @Composable {
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
    // At large text the buttons get their own line, as on a source's page: beside the words they
    // squeezed them to a few words a line.
    if (LocalDensity.current.fontScale >= LARGE_TEXT) {
        perSource(Modifier)
        Row(Modifier.padding(top = 8.dp)) { buttons() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            perSource(Modifier.weight(1f))
            buttons()
        }
    }
    Text(
        "A source can have its own number: tap it in Sources.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SubHeading("Order", Modifier.padding(top = 12.dp))
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

/** The e-reader chosen in onboarding, changeable later: it decides Send or Open, the delivery choices and the tips shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPicker(s: AppSettings, vm: SettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    // A dropdown rather than six radio rows, so the page fits one screen with the Kindle email fields open.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = s.device?.label ?: "Not chosen",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Your e-reader") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Device.entries.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.label) },
                    onClick = {
                        vm.setDevice(device)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
    Text(
        "Decides how editions can reach it, and the tips you see.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
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
    SubHeading("How it gets there", Modifier.padding(top = 24.dp, bottom = 4.dp))
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
    // How to send to this e-reader with the share sheet: under the choice, where the reader is
    // looking when they pick it, and only then (a folder or email reader doesn't wait for a Send).
    if (s.delivery == DeliveryMethod.SHARE) {
        s.device?.let {
            Text(
                DeviceTips.tip(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = OPTION_INDENT, bottom = 8.dp),
            )
        }
    }
    val folderReachable = rememberReachable(s.folderUri)
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.FOLDER,
        title = "Save to a folder",
        detail = when {
            s.folderUri == null -> "Fully automatic, for KOReader and other readers that sync a folder on this phone (for example with Syncthing)."
            !folderReachable -> "Can't reach ${s.folderName ?: "your folder"}. Tap to choose it again."
            else -> "Saved automatically to ${s.folderName ?: "your folder"}."
        },
        problem = s.folderUri != null && !folderReachable,
        onClick = { if (s.folderUri != null && folderReachable) vm.useFolder(s.folderUri, s.folderName ?: "your folder") else pickFolder.launch(null) },
    )
    if (s.delivery == DeliveryMethod.FOLDER) {
        OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.padding(start = OPTION_INDENT)) { Text("Choose another delivery folder") }
    }
}

/** How far a setting's own controls sit in, under the option or switch they belong to. */
private val OPTION_INDENT = 48.dp

/**
 * Whether the app still holds its grant to the folder at [uri], checked again whenever the screen
 * comes back: the grant goes if the folder's app is uninstalled or its access is revoked, and
 * then every save to it fails.
 */
@Composable
private fun rememberReachable(uri: String?): Boolean {
    val context = LocalContext.current
    fun check() = uri == null || context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }
    var reachable by remember(uri) { mutableStateOf(check()) }
    LifecycleResumeEffect(uri) {
        reachable = check()
        onPauseOrDispose {}
    }
    return reachable
}

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
    val reachable = rememberReachable(s.notesFolderUri)
    fun turnOff() {
        release(context, s.notesFolderUri, keep = setOf(s.folderUri))
        vm.setNotesFolder(null, null)
    }
    // A folder that can't be reached is what the row offers to fix, so tapping it picks one again
    // rather than turning notes off under a line that says "Tap to choose it again".
    val tap = if (saving && !reachable) {
        Modifier.clickable(onClickLabel = "Choose the notes folder again") { pickFolder.launch(null) }
    } else {
        Modifier.toggleable(saving, role = Role.Switch) { on -> if (on) pickFolder.launch(null) else turnOff() }
    }
    Row(
        Modifier.fillMaxWidth().then(tap).heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Save notes for each edition", style = MaterialTheme.typography.bodyLarge)
            val name = s.notesFolderName ?: "your folder"
            Text(
                when {
                    !saving -> "A Markdown file for each edition, for Obsidian or any notes app. You'll pick the folder."
                    !reachable -> "Can't reach $name. Tap to choose it again."
                    else -> "Saved to $name when an edition is delivered."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (reachable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Switch(checked = saving, onCheckedChange = null)
    }
    if (saving) {
        Row(Modifier.padding(start = OPTION_INDENT), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose another notes folder") }
            // The row no longer turns notes off while the folder is unreachable, so this does.
            if (!reachable) TextButton(onClick = ::turnOff, modifier = Modifier.padding(start = 8.dp)) { Text("Turn off") }
        }
    }
}

@Composable
private fun DeliveryOption(selected: Boolean, title: String, detail: String, problem: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (rememberNotificationsEnabled()) return
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

/** Whether this app may post notifications, checked again whenever the screen comes back from Android's settings. */
@Composable
private fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    return enabled
}
