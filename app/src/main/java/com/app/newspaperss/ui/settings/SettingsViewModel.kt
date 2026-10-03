package com.app.newspaperss.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.data.TtrssStatus
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
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
    /** Called after every change so the edition timer follows the schedule. */
    private val onChanged: (Settings) -> Unit,
) : ViewModel() {
    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    // Saved as typed, without telling the timer: neither changes when editions are made.
    fun setKindleEmail(address: String) {
        viewModelScope.launch { store.update { it.copy(kindleEmail = address.trim().ifEmpty { null }) } }
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
    }
}
