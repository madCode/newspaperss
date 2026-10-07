package com.app.newspaperss.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.data.TtrssStatus
import com.app.newspaperss.listen.KokoroState
import com.app.newspaperss.listen.PodcastSetup
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

class SettingsViewModel(
    private val store: SettingsStore,
    /** The tt-rss account, for the "Where your feeds live" row. */
    ttrss: Flow<TtrssStatus> = flowOf(TtrssStatus.NONE),
    /** Says a line in the phone's voice at a speed: Settings › Listening's sample. */
    private val hear: (text: String, speed: Float) -> Unit = { _, _ -> },
    /** The podcast's voice; null hides the podcast, as release builds do until podcasts play. */
    val podcast: PodcastSetup? = null,
    /** Called after every change so the edition timer follows the schedule. */
    private val onChanged: (Settings) -> Unit,
) : ViewModel() {
    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Kokoro's download size, from its manifest: read off the main thread. */
    val kokoroSize: StateFlow<Long?> = (podcast?.let { setup -> flow { emit(setup.size) }.flowOn(Dispatchers.IO) } ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val kokoro: StateFlow<KokoroState?> = (podcast?.state ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ttrssStatus: StateFlow<TtrssStatus> = ttrss.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtrssStatus.NONE)

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            store.update(transform)
            onChanged(store.current())
        }
    }

    fun setMinutes(minutes: Int) = update { it.copy(edition = it.edition.copy(minutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))) }
    fun setMaxPerSource(max: Int) = update { it.copy(edition = it.edition.copy(maxPerSource = max.coerceIn(1, MAX_PER_SOURCE))) }
    fun setOrdering(ordering: Ordering) = update { it.copy(edition = it.edition.copy(ordering = ordering)) }
    fun setScheduleEnabled(enabled: Boolean) = update { it.copy(scheduleEnabled = enabled) }
    fun setTime(time: LocalTime) = update { it.copy(schedule = it.schedule.copy(time = time)) }
    fun toggleDay(day: DayOfWeek) = update {
        val days = it.schedule.days.let { d -> if (day in d) d - day else d + day }
        it.copy(schedule = it.schedule.copy(days = days))
    }
    fun useShare() = update { it.copy(delivery = DeliveryMethod.SHARE) }
    fun useKindleEmail() = update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL) }

    // Saved without telling the timer: none of these change when editions are made.
    fun setKindleEmail(address: String) {
        viewModelScope.launch { store.update { it.copy(kindleEmail = address.trim().ifEmpty { null }) } }
    }

    fun setPreviewTextSize(size: PreviewTextSize) {
        viewModelScope.launch { store.update { it.copy(previewTextSize = size) } }
    }

    fun setListenSpeed(speed: Float) {
        viewModelScope.launch { store.update { it.copy(listenSpeed = speed) } }
    }

    fun listenLive() {
        // Tapped while it's already chosen (as when stopping a download): nothing to save.
        if (settings.value?.listenVoice == ListenVoice.PHONE) return
        viewModelScope.launch { store.update { it.copy(listenVoice = ListenVoice.PHONE) } }
    }

    fun setPodcastVoice(voice: PodcastVoice) {
        viewModelScope.launch { store.update { it.copy(podcastVoice = voice) } }
    }

    fun downloadKokoro(mobileData: Boolean) = podcast?.download(mobileData)

    fun cancelKokoro() = podcast?.cancel()

    fun useKokoro() {
        viewModelScope.launch { podcast?.use() }
    }

    fun removeKokoro() {
        viewModelScope.launch { podcast?.remove() }
    }

    fun hear() {
        val s = settings.value ?: return
        hear(SAMPLE, s.listenSpeed)
    }

    fun setMailApp(packageName: String?) {
        viewModelScope.launch { store.update { it.copy(mailApp = packageName) } }
    }
    fun useFolder(uri: String, name: String) = update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = uri, folderName = name) }
    fun setNotesFolder(uri: String?, name: String?) = update { it.copy(notesFolderUri = uri, notesFolderName = name) }
    fun setDevice(device: Device) = update { it.copy(device = device) }

    companion object {
        const val MIN_MINUTES = 5
        const val MAX_MINUTES = 120
        const val MAX_PER_SOURCE = 10
        const val SAMPLE = "This is how your paper sounds, read aloud in your phone's voice."
    }
}
