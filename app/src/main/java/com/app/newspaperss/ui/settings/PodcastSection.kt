package com.app.newspaperss.ui.settings

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.newspaperss.R
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.core.listen.PodcastPace.Verdict
import com.app.newspaperss.listen.KokoroState
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.Settings as AppSettings

/** Left of the option's text, as the radio rows' text starts. */
private val INDENT = 44.dp

/**
 * Settings › Listening's two voices: live in the phone's voice, or a podcast in Kokoro's, made
 * ahead. Picking the podcast downloads Kokoro (asking first off Wi-Fi) and checks how fast this
 * phone makes speech. Downloaded but not in use, it says what that means for the reader's paper,
 * however long ago the check finished, for them to take or leave.
 */
@Composable
internal fun ListeningVoices(s: AppSettings, vm: SettingsViewModel, kokoro: KokoroState?, size: Long?, phoneVoice: String?, onGetPhoneVoice: () -> Unit) {
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf(false) }
    // Choosing to read live puts a failed setup aside for this visit.
    var failureSeen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(kokoro) {
        if (kokoro !is KokoroState.Absent && kokoro !is KokoroState.Failed) asking = false
    }
    val busy = kokoro is KokoroState.Waiting || kokoro is KokoroState.Downloading || kokoro is KokoroState.Checking
    val podcast = s.listenVoice == ListenVoice.PODCAST || asking || busy
    val mb = size?.let { "${it / 1_000_000} MB" } ?: "a few hundred MB"

    fun pickPodcast() {
        when (kokoro) {
            // Too slow, its verdict is already showing, with Remove.
            is KokoroState.Ready -> if (verdict(s, kokoro) != Verdict.TOO_SLOW) vm.useKokoro()
            KokoroState.Absent, is KokoroState.Failed -> {
                failureSeen = false
                if (onWifi(context)) vm.downloadKokoro(mobileData = false) else asking = true
            }
            else -> {}
        }
    }

    Column(Modifier.selectableGroup()) {
        VoiceOption(
            selected = !podcast,
            title = "Read live in this phone's voice",
            detail = phoneVoice ?: "No voice on this phone",
            onSelect = {
                asking = false
                failureSeen = true
                // Choosing to read live while Kokoro downloads stops it; what's arrived is kept.
                if (busy) vm.cancelKokoro()
                vm.listenLive()
            },
            sample = if (phoneVoice != null) vm::hear else null,
        )
        if (phoneVoice == null && !podcast) {
            Column(Modifier.padding(start = INDENT, bottom = 8.dp)) {
                Text("Listen can't read live without one. Speech Services by Google is free.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onGetPhoneVoice) { Text("Get Speech Services by Google") }
            }
        }
        val unsupported = kokoro is KokoroState.Unsupported
        VoiceOption(
            selected = podcast,
            title = "Make a podcast in a natural voice",
            detail = if (unsupported) "Kokoro needs a 64-bit phone, and this one is 32-bit." else "Kokoro · made ahead while your phone charges · $mb",
            enabled = !unsupported,
            onSelect = ::pickPodcast,
            sample = if (unsupported) null else ({ playSample(context, sampleOf(s.podcastVoice)) }),
        )
    }

    when {
        asking && (kokoro == KokoroState.Absent || kokoro is KokoroState.Failed) -> Step("You're not on Wi-Fi") {
            Text("Kokoro is a one-time $mb download. Use mobile data for it, or wait and download it when you're on Wi-Fi?")
            Buttons {
                OutlinedButton(onClick = { asking = false; vm.downloadKokoro(mobileData = false) }) { Text("Wait for Wi-Fi") }
                Button(onClick = { asking = false; vm.downloadKokoro(mobileData = true) }) { Text("Use mobile data") }
            }
        }
        kokoro is KokoroState.Waiting && kokoro.retrying -> Step("Trying again shortly") {
            Text("The download stopped part way. It carries on by itself in a few minutes${if (kokoro.wifi) ", on Wi-Fi" else ""}, keeping what's arrived.")
            Buttons {
                if (kokoro.wifi) OutlinedButton(onClick = { vm.downloadKokoro(mobileData = true) }) { Text("Use mobile data") }
                TextButton(onClick = vm::cancelKokoro) { Text("Cancel") }
            }
        }
        kokoro is KokoroState.Waiting -> Step(if (kokoro.wifi) "Waiting for Wi-Fi" else "Waiting for a connection") {
            Text("Kokoro ($mb) will download by itself${if (kokoro.wifi) " on the next Wi-Fi" else " once the phone is online"}.")
            Buttons {
                if (kokoro.wifi) OutlinedButton(onClick = { vm.downloadKokoro(mobileData = true) }) { Text("Use mobile data") }
                TextButton(onClick = vm::cancelKokoro) { Text("Cancel") }
            }
        }
        kokoro is KokoroState.Downloading -> Step("Downloading Kokoro") {
            LinearProgressIndicator(progress = { if (kokoro.total > 0) kokoro.got.toFloat() / kokoro.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("${kokoro.got / 1_000_000} of ${kokoro.total / 1_000_000} MB. Then about 20 seconds to see how fast this phone makes speech.")
            TextButton(onClick = vm::cancelKokoro) { Text("Cancel") }
        }
        kokoro is KokoroState.Checking -> Step("Seeing how fast this phone is…") {
            Text("About 20 seconds. Kokoro is making a short paragraph.")
        }
        kokoro is KokoroState.Failed && !failureSeen -> Step("Kokoro isn't set up") {
            Text(kokoro.message)
            Buttons { OutlinedButton(onClick = ::pickPodcast) { Text("Try again") } }
        }
        kokoro is KokoroState.Ready && s.listenVoice == ListenVoice.PODCAST -> Podcast(s, vm, kokoro, mb)
        kokoro is KokoroState.Ready -> Verdict(s, kokoro, mb, onUse = vm::useKokoro, onRemove = vm::removeKokoro)
    }
}

@Composable
private fun VoiceOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit, sample: (() -> Unit)?, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sample != null) OutlinedButton(onClick = sample, modifier = Modifier.padding(start = 8.dp)) { Text("Hear it") }
    }
}

/** A step under the podcast option: what's happening, and what can be done about it. */
@Composable
private fun Step(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = INDENT, top = 4.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Only the step is announced as it changes, not each megabyte under it.
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** What the check found, in this reader's terms: how long their paper takes to make here. */
@Composable
private fun Verdict(s: AppSettings, kokoro: KokoroState.Ready, size: String, onUse: () -> Unit, onRemove: () -> Unit) {
    val paper = s.edition.minutes
    val time = SettingsSummary.aboutTime(PodcastPace.minutesToMake(paper, kokoro.pace))
    when (verdict(s, kokoro)) {
        Verdict.CAN -> Step("This phone can do it") {
            Text("Your $paper-minute paper takes $time to make here, while charging.")
            Buttons {
                Button(onClick = onUse) { Text("Use it") }
                OutlinedButton(onClick = onRemove) { Text("Remove it") }
            }
        }
        Verdict.SLOW -> Step("This phone is slow at it, but it can") {
            Text("Your $paper-minute paper takes $time to make here. Leave the phone charging overnight.")
            Buttons {
                Button(onClick = onUse) { Text("Use it") }
                OutlinedButton(onClick = onRemove) { Text("Remove it") }
            }
        }
        Verdict.TOO_SLOW -> Step("Too slow on this phone") {
            Text("Your $paper-minute paper would take $time to make here, more than a night can give it. Listen keeps reading live in your phone's voice.")
            val fits = PodcastPace.longestPaper(kokoro.pace)
            if (fits >= MIN_PAPER) {
                Text("A shorter paper might fit: $fits minutes would take ${SettingsSummary.aboutTime(PodcastPace.minutesToMake(fits, kokoro.pace))}.")
            }
            Buttons { Button(onClick = onRemove) { Text("Remove Kokoro ($size)") } }
        }
    }
}

/** Kokoro in use: its voices, what a paper costs on this phone, and removing it. */
@Composable
private fun Podcast(s: AppSettings, vm: SettingsViewModel, kokoro: KokoroState.Ready, size: String) {
    val context = LocalContext.current
    Column(Modifier.padding(start = INDENT).selectableGroup()) {
        PodcastVoice.entries.forEach { voice ->
            VoiceOption(
                selected = s.podcastVoice == voice,
                title = voice.label,
                detail = voice.accent,
                onSelect = { vm.setPodcastVoice(voice) },
                sample = { playSample(context, sampleOf(voice)) },
            )
        }
        Text(
            "On this phone, your ${s.edition.minutes}-minute paper takes ${SettingsSummary.aboutTime(PodcastPace.minutesToMake(s.edition.minutes, kokoro.pace))} to make.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Until podcasts are made and played, which comes next, picking it changes nothing.
        Text(
            "Podcasts aren't made yet: until they are, Listen reads live.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = vm::removeKokoro, modifier = Modifier.padding(top = 4.dp)) { Text("Remove Kokoro") }
        Text(
            "Frees $size. Listen goes back to reading live.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun verdict(s: AppSettings, kokoro: KokoroState.Ready) = PodcastPace.verdict(s.edition.minutes, kokoro.pace)

/** Too short a paper to suggest: Your edition's smallest size. */
private const val MIN_PAPER = SettingsViewModel.MIN_MINUTES

/** Off Wi-Fi (or offline), a big download asks first. */
private fun onWifi(context: Context): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

@RawRes
private fun sampleOf(voice: PodcastVoice): Int = when (voice) {
    PodcastVoice.HEART -> R.raw.kokoro_sample_heart
    PodcastVoice.MICHAEL -> R.raw.kokoro_sample_michael
    PodcastVoice.EMMA -> R.raw.kokoro_sample_emma
    PodcastVoice.GEORGE -> R.raw.kokoro_sample_george
}

/** A clip made with Kokoro and shipped with the app, so a voice can be heard before downloading it. */
private fun playSample(context: Context, @RawRes sample: Int) {
    val player = MediaPlayer.create(context, sample) ?: return
    player.setOnCompletionListener { it.release() }
    player.start()
}
