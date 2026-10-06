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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.settings.Device
import com.app.newspaperss.core.plural
import com.app.newspaperss.ui.components.CheckChip
import com.app.newspaperss.ui.components.KindleEmailFields
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.ui.ttrss.TtrssSignInFields
import com.app.newspaperss.ui.today.Masthead
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
/** @param sources offers importing an OPML file from another reader. */
fun OnboardingScreen(viewModel: OnboardingViewModel, sources: SourcesViewModel? = null, readingList: ReadingListViewModel? = null) {
    val s by viewModel.state.collectAsState()
    // Here, not in the device step, so the full list stays open after Next and Back.
    var allDevices by rememberSaveable { mutableStateOf(false) }
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
                // Counted along the path chosen: the server's has one step more.
                val of = s.path.size
                LinearProgressIndicator(
                    progress = { s.stepNumber / of.toFloat() },
                    // TalkBack would read "33 percent"; the welcome screen isn't a step.
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = "Step ${s.stepNumber} of $of" },
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                when (s.step) {
                    Step.WELCOME -> Welcome()
                    Step.DEVICE -> DeviceStep(s, viewModel, allDevices) { allDevices = true }
                    Step.FEEDS_FROM -> FeedsFromStep(s, viewModel)
                    Step.IMPORT -> ImportStep(s, viewModel)
                    Step.SOURCES -> SourcesStep(s, viewModel, sources, readingList)
                    Step.SIGN_IN -> SignInStep(s, viewModel)
                    Step.EXTRAS -> ExtrasStep(s, viewModel, readingList)
                    Step.SIZE -> SizeStep(s, viewModel)
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.step != Step.WELCOME) TextButton(onClick = viewModel::back, enabled = !s.signIn.testing && !s.forking) { Text("Back") }
                Spacer(Modifier.weight(1f))
                when {
                    s.step == Step.WELCOME -> Button(onClick = viewModel::next) { Text("Get started") }
                    s.step == Step.SIZE -> Button(onClick = finish, enabled = s.canContinue) {
                        Text(if (s.finishing) "Setting up…" else "Make my first edition")
                    }
                    s.step == Step.SIGN_IN && !s.signedIn -> Button(onClick = viewModel::signIn, enabled = s.signIn.canSubmit) { Text("Sign in") }
                    // The cards move on themselves.
                    s.step == Step.FEEDS_FROM -> Unit
                    s.step == Step.IMPORT -> Button(onClick = viewModel::next, enabled = s.canContinue) { Text(if (s.phoneFeeds > 0) "Next" else "Skip") }
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
private fun DeviceStep(s: OnboardingState, vm: OnboardingViewModel, allDevices: Boolean, onAllDevices: () -> Unit) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.chooseFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    Title("Where do you read?")
    // On an e-reader the answer is almost always "right here": say so, with the list a tap away.
    val showAll = allDevices || !vm.onEReader || (s.device != null && s.device != Device.HERE)
    if (!showAll) {
        Text(
            "Right here, on this e-reader. Each edition opens in your reading app, and reading it counts as delivered.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onAllDevices, contentPadding = PaddingValues(end = 12.dp)) { Text("I read on another device") }
    } else Device.entries.forEach { device ->
        Row(
            Modifier.fillMaxWidth().selectable(s.device == device, role = Role.RadioButton) { vm.chooseDevice(device) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.device == device, onClick = null)
            Text(device.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    val device = s.device ?: return
    if (s.emailsKindle) {
        KindleEmailSetup(s, vm)
        return
    }
    // The short line on an e-reader already says what the tip would.
    if (showAll) Text(DeviceTips.tip(device), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
    if (device == Device.KINDLE) {
        TextButton(onClick = vm::useKindleEmail, contentPadding = PaddingValues(end = 12.dp)) { Text("Email it to your Kindle instead") }
    }
    if (s.needsFolder) {
        OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(top = 12.dp)) {
            Text(s.folderName?.let { "Folder: $it" } ?: "Choose the folder")
        }
        if (s.folderUri == null) {
            Text(
                if (device == Device.HERE) "Optional: without one, tap Read for each edition." else "You can skip this and send editions yourself for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindleEmailSetup(s: OnboardingState, vm: OnboardingViewModel) {
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Send it straight to your Kindle", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(
        "Each Kindle has its own email address. Editions emailed there arrive on it by themselves. " +
            "When an edition is ready, Send opens your mail app with everything filled in; you tap Send.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    KindleEmailFields(
        address = s.kindleEmail,
        onAddress = vm::editKindleEmail,
        mailApp = s.mailApp,
        onMailApp = vm::chooseMailApp,
        label = "Your Kindle's email address",
        hint = "Find it on Amazon: Content & Devices \u203a Devices \u203a your Kindle.",
        required = true,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Text(
            "One more step on Amazon: add the email address you'll send from to your approved list " +
                "(Preferences \u203a Personal Document Settings). Otherwise Amazon drops the edition.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    TextButton(onClick = vm::useKindleApp, modifier = Modifier.padding(top = 8.dp), contentPadding = PaddingValues(end = 12.dp)) {
        Text("Use the Kindle app instead")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesStep(s: OnboardingState, vm: OnboardingViewModel, sources: SourcesViewModel?, readingList: ReadingListViewModel?) {
    Title("What do you like to read?")
    Text("Pick a few to start. You can change them any time.", style = MaterialTheme.typography.bodyMedium)
    if (sources != null) FromAnotherReader(s.added, vm, sources)
    if (readingList != null) SavedLinks(s.savedLinks, readingList)
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.findPasted() }),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    // Below the field, not inside it: at large font sizes a button inside leaves no room to type.
    // The status is always composed and only its text changes, so TalkBack announces it; the
    // button stays put rather than being swapped out from under focus.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.searching) "Checking…" else "", Modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = vm::findPasted, enabled = !s.searching && s.pasted.isNotBlank()) { Text("Add") }
    }
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
    val rows by sources.rows.collectAsState()
    val count = rows?.size
    LaunchedEffect(count) {
        if (count != null && count != added) {
            onboarding.sourcesAdded(count)
            // An earlier import's result ("Couldn't read that file") would otherwise hide the new count.
            sources.dismissMessage()
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sources.importOpml(context.contentResolver, uri)
    }
    Text("Already use a feed reader?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import an OPML file") }
    }
    val status = message ?: if (added > 0) "${plural(added, "source")} added. Pick more below, or go on; you can remove any later in Sources." else null
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** For someone leaving Pocket or Instapaper: their saved links can be the whole paper. */
@Composable
private fun SavedLinks(saved: Int, readingList: ReadingListViewModel) {
    val context = LocalContext.current
    val message by readingList.message.collectAsState()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readingList.import(context.contentResolver, uri)
    }
    Text("Leaving Pocket or Instapaper?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import your saved links") }
    // Both lines: the import's result, and whether that's enough to go on (archived links aren't).
    val waiting = if (saved > 0) "${plural(saved, "saved link")} waiting. That's enough to start; add sites too if you like." else null
    listOfNotNull(message, waiting).joinToString("\n").ifEmpty { null }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
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
private fun FeedsFromStep(s: OnboardingState, vm: OnboardingViewModel) {
    // A tap is the answer, and the server's answer removes sites already added here: it asks
    // first, so a mis-tap (easy on e-ink) or TalkBack reaching the card first loses nothing.
    var confirmServer by rememberSaveable { mutableStateOf(false) }
    Title("Where do your feeds live now?")
    AnswerCard("I'll pick some sites", "Newspapers, magazines, blogs, newsletters. Most people start here.", !s.forking) {
        vm.answer(FeedsAnswer.SITES)
    }
    AnswerCard("On my own RSS server", "Tiny Tiny RSS. Your feeds stay there; the paper is made from them.", !s.forking) {
        if (s.phoneFeeds > 0) confirmServer = true else vm.answer(FeedsAnswer.SERVER)
    }
    AnswerCard("In another reader app", "Feedly, Inoreader and others: bring your list as a file.", !s.forking) {
        vm.answer(FeedsAnswer.OTHER_APP)
    }
    Text(
        "You can change this later in Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    // After process death the dialog is back before the count is: it waits for one.
    if (confirmServer && s.phoneFeeds > 0) {
        AlertDialog(
            onDismissRequest = { confirmServer = false },
            title = { Text("Remove the ${plural(s.phoneFeeds, "site")} you added?") },
            text = { Text("With your own server, your sites come from it, so the ones added on this phone are removed.") },
            confirmButton = { TextButton(onClick = { confirmServer = false; vm.answer(FeedsAnswer.SERVER) }) { Text("Remove and use my server") } },
            dismissButton = { TextButton(onClick = { confirmServer = false }) { Text("Keep them") } },
        )
    }
    if (s.forking && s.signedIn && !s.server) Text("Signing out of tt-rss…", Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * An answer that moves on when tapped: one TalkBack button read as its title and detail. A
 * plain border and an arrow, with no selected state, so nothing depends on colour on e-ink.
 */
@Composable
private fun AnswerCard(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(min = 48.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** The list from another reader app, as an OPML file, before the phone's own sources step. */
@Composable
private fun ImportStep(s: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importOpml(context.contentResolver, uri)
    }
    Title("Bring your list")
    Text(
        "In Feedly or Inoreader, look for Export or OPML in settings. Save the file, then choose it here.",
        style = MaterialTheme.typography.bodyLarge,
    )
    val result = s.fileImport
    val failed = result == FileImport.Failed || (result is FileImport.Done && result.inFile == 0)
    Button(
        onClick = { pick.launch(arrayOf("*/*")) },
        enabled = result != FileImport.Reading,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(
            when {
                result == null || result == FileImport.Reading -> "Choose the file"
                failed -> "Try again"
                else -> "Choose another file"
            },
        )
    }
    val status = when (result) {
        null -> null
        FileImport.Reading -> "Reading the file…"
        FileImport.Failed -> "Couldn't read that file. Try again, or skip and pick sites instead."
        is FileImport.Done -> when {
            result.inFile == 0 -> "No sites in that file. Is it the OPML export? Try again, or skip and pick sites instead."
            result.added == 0 -> "All the sites in that file are added already."
            result.added < result.inFile -> "Added ${plural(result.added, "site")}. " +
                (if (result.inFile - result.added == 1) "The other one was added already." else "The other ${result.inFile - result.added} were added already.")
            else -> "Added ${plural(result.added, "site")}."
        }
    }
    // Always composed, so TalkBack hears it change.
    Text(
        status.orEmpty(),
        style = MaterialTheme.typography.bodyLarge,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The tt-rss sign-in, then, once signed in, what's in the account. */
@Composable
private fun SignInStep(s: OnboardingState, vm: OnboardingViewModel) {
    if (s.signedIn) {
        Title("Signed in")
        val form = s.signIn
        val who = listOfNotNull(form.address.trim().takeIf { it.isNotEmpty() }, form.user.trim().takeIf { it.isNotEmpty() }?.let { "signed in as $it" })
        if (who.isNotEmpty()) Text(who.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val found = s.serverFound
        Text(
            when {
                found == null -> "Your tt-rss is connected."
                found.categories > 1 -> "Found ${plural(found.feeds, "feed")} in ${found.categories} categories."
                else -> "Found ${plural(found.feeds, "feed")}."
            },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Your paper takes from all of them. To narrow it to one category, or leave feeds out, use Sources or Settings later.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    Title("Sign in to your tt-rss")
    Text("FreshRSS and Miniflux are coming.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
    TtrssSignInFields(s.signIn, vm::editSignIn, vm::signIn)
    TextButton(onClick = vm::usePhoneInstead, enabled = !s.signIn.testing, contentPadding = PaddingValues(end = 12.dp)) { Text("Use this phone instead") }
}

/** What stays on the phone with a server: the reading list and curated lists. */
@Composable
private fun ExtrasStep(s: OnboardingState, vm: OnboardingViewModel, readingList: ReadingListViewModel?) {
    Title("Also on this phone")
    Text("These stay on the phone, whatever your server does.", style = MaterialTheme.typography.bodyMedium)
    Text("Your reading list", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
    Text(
        "Links you share from any app: tap Share and choose \u201cRead in newspapeRSS\u201d. Always on.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (readingList != null) SavedLinks(s.savedLinks, readingList)
    Text("Curated lists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
    CuratedLists.all.forEach { list ->
        Row(
            Modifier.fillMaxWidth().toggleable(list.id in s.lists, role = Role.Checkbox) { vm.toggleList(list.id) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = list.id in s.lists, onCheckedChange = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(list.title)
                Text(list.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
