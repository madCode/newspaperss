package com.app.newspaperss.ui.settings

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.app.newspaperss.listen.PhoneVoice
import com.app.newspaperss.listen.LISTEN_SPEEDS
import com.app.newspaperss.ui.listen.speedLabel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.plural
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.settings.Settings as AppSettings
import com.app.newspaperss.ui.components.CheckChip
import com.app.newspaperss.ui.sources.LARGE_TEXT
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        val s = settings ?: return@Scaffold
        val locale = LocalConfiguration.current.locales[0]
        val notificationsOn = rememberNotificationsEnabled()
        val folderReachable = rememberReachable(s.folderUri)
        val notesReachable = rememberReachable(s.notesFolderUri)
        val ttrss by viewModel.ttrssStatus.collectAsStateWithLifecycle()
        val voice = rememberPhoneVoice()
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SettingsPage.entries.forEach { page ->
                val summary = when (page) {
                    SettingsPage.EDITION -> SettingsSummary.edition(s)
                    SettingsPage.SCHEDULE -> SettingsSummary.schedule(s, notificationsOn, locale)
                    SettingsPage.TEXT_SIZE -> SettingsSummary.textSize(s)
                    SettingsPage.LISTENING -> SettingsSummary.listening(s, hasVoice = voice != null)
                    SettingsPage.DELIVERY -> SettingsSummary.delivery(s, folderReachable)
                    SettingsPage.FEEDS -> SettingsSummary.feedsFrom(s.feedsFrom(hasServer = ttrss.source != null), ttrss)
                    SettingsPage.NOTES -> SettingsSummary.notes(s, notesReachable)
                }
                SummaryRow(page.title, summary) { onOpen(page) }
                HorizontalDivider()
            }
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            val line = "newspapeRSS $version"
            val copy = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("newspapeRSS version", line))
                // On every version: an e-reader's Android may show no clipboard notice of its own.
                Toast.makeText(context, "Version copied", Toast.LENGTH_SHORT).show()
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Long press only, for a bug report: a tap does nothing, so it isn't announced as a button.
                modifier = Modifier
                    .pointerInput(line) { detectTapGestures(onLongPress = { copy() }) }
                    .semantics { onLongClick("copy version") { copy(); true } }
                    .padding(vertical = 16.dp),
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

/**
 * One Settings page, opened from the summary: the same controls the summary row describes.
 *
 * @param feedsFrom the "Where your feeds live" page's own state; that page is empty without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(viewModel: SettingsViewModel, page: SettingsPage, onBack: () -> Unit, feedsFrom: FeedsFromViewModel? = null) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signingIn = page == SettingsPage.FEEDS && feedsFrom != null && feedsFrom.form.collectAsStateWithLifecycle().value != null
    // Signing in takes the page over, as a step of its own: Back returns to the page.
    BackHandler(enabled = signingIn) { feedsFrom?.closeSignIn() }
    val snackbar = remember { SnackbarHostState() }
    if (page == SettingsPage.FEEDS && feedsFrom != null) {
        val notice by feedsFrom.notice.collectAsStateWithLifecycle()
        LaunchedEffect(notice) {
            val text = notice ?: return@LaunchedEffect
            try {
                snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
            } finally {
                feedsFrom.noticeShown()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (signingIn) "Sign in to your tt-rss" else page.title) },
                navigationIcon = {
                    IconButton(onClick = { if (signingIn) feedsFrom.closeSignIn() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (page) {
                SettingsPage.EDITION -> EditionSection(s, viewModel)
                SettingsPage.SCHEDULE -> ScheduleSection(s, viewModel)
                SettingsPage.TEXT_SIZE -> TextSizeSection(s, viewModel)
                SettingsPage.LISTENING -> ListeningSection(s, viewModel)
                SettingsPage.DELIVERY -> {
                    ReaderPicker(s, viewModel)
                    DeliverySection(s, viewModel)
                }
                SettingsPage.FEEDS -> when {
                    feedsFrom == null -> {}
                    signingIn -> FeedsFromSignIn(feedsFrom)
                    else -> FeedsFromSection(feedsFrom)
                }
                SettingsPage.NOTES -> NotesSection(s, viewModel)
            }
        }
    }
}

@Composable
internal fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

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

/** The article preview's text size: a sample line drawn at the chosen size, then one row per size. */
@Composable
private fun TextSizeSection(s: AppSettings, vm: SettingsViewModel) {
    Text(
        "How big articles are when you open one in an edition, on top of Android's own font size. " +
            "The rest of the app follows Android's font size, and your e-reader its own settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // In sp, like the preview's zoom: the sample grows with Android's font size as the article does.
    val body = MaterialTheme.typography.bodyLarge
    Text(
        SAMPLE_LINE,
        style = body.copy(fontFamily = FontFamily.Serif, fontSize = body.fontSize * s.previewTextSize.percent / 100, lineHeight = body.lineHeight * s.previewTextSize.percent / 100),
        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(16.dp),
    )
    Column(Modifier.selectableGroup()) {
        PreviewTextSize.entries.forEach { size ->
            Row(
                Modifier.fillMaxWidth().selectable(s.previewTextSize == size, role = Role.RadioButton) { vm.setPreviewTextSize(size) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.previewTextSize == size, onClick = null)
                Text(size.label, Modifier.padding(start = 12.dp))
            }
        }
    }
}

private const val SAMPLE_LINE = "The quiet return of the night train"

/** Listen's voice, the phone's own, with a sample; the speed it reads at; and where Android changes the voice. */
@Composable
private fun ListeningSection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val voice = rememberPhoneVoice()
    SubHeading("Voice")
    if (vm.podcast != null) {
        val kokoro by vm.kokoro.collectAsStateWithLifecycle()
        val size by vm.kokoroSize.collectAsStateWithLifecycle()
        ListeningVoices(s, vm, kokoro, size, voice, onGetPhoneVoice = { if (!PhoneVoice.get(context)) nothingOpens(context) })
    } else if (voice != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("This phone's voice", style = MaterialTheme.typography.bodyLarge)
                Text(voice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = vm::hear, modifier = Modifier.padding(start = 8.dp)) { Text("Hear it") }
        }
    } else {
        Text(
            "This phone has no text-to-speech voice, so Listen can't read editions aloud. " +
                "Speech Services by Google is free.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedButton(onClick = { if (!PhoneVoice.get(context)) nothingOpens(context) }) { Text("Get Speech Services by Google") }
    }
    SubHeading("Speed", Modifier.padding(top = 24.dp))
    Column(Modifier.selectableGroup()) {
        LISTEN_SPEEDS.sorted().forEach { speed ->
            Row(
                Modifier.fillMaxWidth().selectable(s.listenSpeed == speed, role = Role.RadioButton) { vm.setListenSpeed(speed) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.listenSpeed == speed, onClick = null)
                Text(speedLabel(speed) + if (speed == 1f) " · normal" else "", Modifier.padding(start = 12.dp))
            }
        }
    }
    if (voice != null) {
        HorizontalDivider(Modifier.padding(top = 16.dp))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { if (!PhoneVoice.openSettings(context)) nothingOpens(context) }.heightIn(min = 48.dp).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Change the phone's voice", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Another voice, language or engine, in Android's text-to-speech settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun nothingOpens(context: android.content.Context) =
    Toast.makeText(context, "This phone has nothing that opens it.", Toast.LENGTH_LONG).show()

/** The phone's text-to-speech engine's name, or null without one; checked again on coming back from Android's settings. */
@Composable
private fun rememberPhoneVoice(): String? {
    val context = LocalContext.current
    var voice by remember { mutableStateOf(PhoneVoice.name(context)) }
    LifecycleResumeEffect(Unit) {
        voice = PhoneVoice.name(context)
        onPauseOrDispose {}
    }
    return voice
}
