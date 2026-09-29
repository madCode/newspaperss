package com.app.newspaperss.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.core.feed.StarterFeed
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

enum class Step { WELCOME, DEVICE, SOURCES, SIZE, }

data class OnboardingState(
    val step: Step = Step.WELCOME,
    val device: Device? = null,
    val folderUri: String? = null,
    val folderName: String? = null,
    /** Feed URLs chosen from the starter packs or found from a pasted address. */
    val chosen: Set<String> = emptySet(),
    val found: List<StarterFeed> = emptyList(),
    val pasted: String = "",
    val searching: Boolean = false,
    val findError: String? = null,
    val minutes: Int = 30,
    val scheduleEnabled: Boolean = true,
    val time: LocalTime = LocalTime.of(6, 30),
    val finishing: Boolean = false,
) {
    /** KOReader reads from a synced folder, so it's offered folder delivery. */
    val needsFolder get() = device == Device.KOREADER
    val canContinue get() = when (step) {
        Step.WELCOME -> true
        Step.DEVICE -> device != null
        Step.SOURCES -> chosen.isNotEmpty()
        Step.SIZE -> !finishing
    }
}

class OnboardingViewModel(
    private val settings: SettingsStore,
    private val sources: SourceRepository,
    private val finder: FeedFinder,
    /** Survives process death: the folder picker or the Play Store can get the app killed mid-flow. */
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** Schedules the timer and starts the first edition once onboarding is saved. */
    private val onFinished: (Settings) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(restore(saved))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.collect { store(it, saved) } }
    }

    fun next() = _state.update { s -> if (!s.canContinue) s else s.copy(step = Step.entries.getOrElse(s.step.ordinal + 1) { s.step }) }
    fun back() = _state.update { s -> s.copy(step = Step.entries.getOrElse(s.step.ordinal - 1) { s.step }) }

    fun chooseDevice(device: Device) = _state.update { it.copy(device = device) }
    fun chooseFolder(uri: String, name: String) = _state.update { it.copy(folderUri = uri, folderName = name) }

    fun toggleFeed(url: String) = _state.update { it.copy(chosen = if (url in it.chosen) it.chosen - url else it.chosen + url) }

    fun togglePack(name: String) = _state.update { s ->
        val urls = StarterPacks.all.first { it.name == name }.feeds.map { it.url }.toSet()
        s.copy(chosen = if (s.chosen.containsAll(urls)) s.chosen - urls else s.chosen + urls)
    }

    fun editPasted(text: String) = _state.update { it.copy(pasted = text, findError = null) }

    fun findPasted() {
        val input = state.value.pasted.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(searching = true, findError = null) }
        viewModelScope.launch {
            when (val result = finder.find(input)) {
                is FindResult.NotFound -> _state.update { it.copy(searching = false, findError = result.reason) }
                is FindResult.Found -> _state.update { s ->
                    val feeds = result.feeds.map { StarterFeed(it.title ?: SourceRepository.hostOf(it.url), it.url) }
                    // Several feeds on one site: pick the first, show the rest to tick.
                    s.copy(searching = false, pasted = "", found = (s.found + feeds).distinctBy { it.url }, chosen = s.chosen + feeds.first().url)
                }
            }
        }
    }

    fun setMinutes(minutes: Int) = _state.update { it.copy(minutes = minutes) }
    fun setScheduleEnabled(on: Boolean) = _state.update { it.copy(scheduleEnabled = on) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }

    fun finish() {
        val s = state.value
        // A permission result delivered after process death lands on a fresh, empty state;
        // saving that would finish onboarding with nothing chosen.
        if (s.finishing || s.chosen.isEmpty()) return
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            val titles = (StarterPacks.all.flatMap { it.feeds } + s.found).associate { it.url to it.title }
            s.chosen.forEach { url -> sources.addFeed(url, titles[url]) }
            settings.update {
                it.copy(
                    onboarded = true,
                    device = s.device,
                    edition = it.edition.copy(minutes = s.minutes),
                    scheduleEnabled = s.scheduleEnabled,
                    schedule = Schedule(time = s.time),
                    delivery = if (s.needsFolder && s.folderUri != null) DeliveryMethod.FOLDER else DeliveryMethod.SHARE,
                    folderUri = s.folderUri,
                    folderName = s.folderName,
                )
            }
            onFinished(settings.current())
        }
    }
}

private const val KEY = "onboarding."

private fun store(s: OnboardingState, saved: SavedStateHandle) {
    saved[KEY + "step"] = s.step.name
    saved[KEY + "device"] = s.device?.name
    saved[KEY + "folderUri"] = s.folderUri
    saved[KEY + "folderName"] = s.folderName
    saved[KEY + "chosen"] = ArrayList(s.chosen)
    saved[KEY + "foundUrls"] = ArrayList(s.found.map { it.url })
    saved[KEY + "foundTitles"] = ArrayList(s.found.map { it.title })
    saved[KEY + "minutes"] = s.minutes
    saved[KEY + "scheduleEnabled"] = s.scheduleEnabled
    saved[KEY + "time"] = s.time.toString()
}

private fun restore(saved: SavedStateHandle): OnboardingState {
    val d = OnboardingState()
    val step = saved.get<String>(KEY + "step") ?: return d
    val urls = saved.get<ArrayList<String>>(KEY + "foundUrls").orEmpty()
    val titles = saved.get<ArrayList<String>>(KEY + "foundTitles").orEmpty()
    return d.copy(
        step = runCatching { Step.valueOf(step) }.getOrDefault(Step.WELCOME),
        device = saved.get<String>(KEY + "device")?.let { runCatching { Device.valueOf(it) }.getOrNull() },
        folderUri = saved[KEY + "folderUri"],
        folderName = saved[KEY + "folderName"],
        chosen = saved.get<ArrayList<String>>(KEY + "chosen").orEmpty().toSet(),
        found = urls.zip(titles) { url, title -> StarterFeed(title, url) },
        minutes = saved[KEY + "minutes"] ?: d.minutes,
        scheduleEnabled = saved[KEY + "scheduleEnabled"] ?: d.scheduleEnabled,
        time = saved.get<String>(KEY + "time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.time,
    )
}
