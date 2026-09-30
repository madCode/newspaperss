package com.app.newspaperss.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.work.EditionWorker
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.offersOpen
import com.app.newspaperss.settings.Settings
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface BuildState {
    data object Idle : BuildState
    data object Syncing : BuildState
    /** Queued, but it needs an internet connection to start. */
    data object WaitingForNetwork : BuildState
    /** A timed run read no source and tries again at [atMillis] (epoch ms). */
    data class Retrying(val atMillis: Long) : BuildState
    data class Fetching(val done: Int) : BuildState
    data object NothingNew : BuildState
    data class Failed(val reason: String) : BuildState
}

data class TodayState(
    val editions: List<EditionEntity>?,
    val build: BuildState,
    /** "Tomorrow at 6:30 AM · about 30 min", or null when there's no schedule. */
    val next: String? = null,
    /** Readers who read on this device (a Boox) open editions rather than send them. */
    val preferOpen: Boolean = false,
    /** False for a Kindle or Kobo, where the book is sent, never opened here. */
    val offerOpen: Boolean = true,
    /** What to call the reader's e-reader in prompts. */
    val deviceName: String = "e-reader",
    /** Starred articles not yet in an edition, from sources that aren't paused. */
    val starredWaiting: Int = 0,
)

class TodayViewModel(
    private val editions: EditionRepository,
    work: Flow<WorkInfo?>,
    settings: Flow<Settings> = flowOf(Settings()),
    online: Flow<Boolean> = flowOf(true),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val lastDue: () -> Long = { 0L },
    private val startBuild: () -> Unit,
) : ViewModel() {
    val state: StateFlow<TodayState> = combine(editions.observeAll(), work, settings, online, editions.observeStarredWaiting()) { list, info, s, isOnline, starred ->
        TodayState(
            editions = list,
            build = buildStateOf(info, isOnline),
            next = nextEdition(s, now(), lastDue()),
            preferOpen = s.device == Device.BOOX,
            offerOpen = s.device.offersOpen,
            deviceName = when (s.device) {
                Device.KINDLE -> "Kindle"
                Device.KOBO -> "Kobo"
                Device.POCKETBOOK -> "PocketBook"
                else -> "e-reader"
            },
            starredWaiting = starred,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(null, BuildState.Idle))

    fun makeOneNow() = startBuild()

    fun fileOf(edition: EditionEntity): File? = editions.fileOf(edition)

    fun markSent(edition: EditionEntity) = markSent(edition.id)

    fun markSent(editionId: Long) {
        viewModelScope.launch { editions.markSent(editionId) }
    }

    companion object {
        /** @param lastDueMs [EditionScheduler.LAST_DUE], so an edition started early isn't shown as still to come. */
        fun nextEdition(s: Settings, now: ZonedDateTime, lastDueMs: Long = 0L): String? {
            if (!s.scheduleEnabled) return null
            val next = EditionScheduler.nextDue(s, now, lastDueMs)?.atZone(now.zone) ?: return null
            val time = next.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            val day = when (next.toLocalDate()) {
                now.toLocalDate() -> "Today"
                now.toLocalDate().plusDays(1) -> "Tomorrow"
                else -> next.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            }
            return "$day at $time \u00b7 about ${s.edition.minutes} min"
        }

        /** @param online without a connection, queued work is waiting for one, not checking sources. */
        fun buildStateOf(info: WorkInfo?, online: Boolean = true): BuildState = when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> when {
                // Not "checking": nothing runs until the retry.
                info.runAttemptCount > 0 -> BuildState.Retrying(info.nextScheduleTimeMillis)
                online -> BuildState.Syncing
                else -> BuildState.WaitingForNetwork
            }
            WorkInfo.State.RUNNING ->
                if (info.progress.getString(EditionWorker.STAGE) == EditionWorker.STAGE_FETCHING) {
                    BuildState.Fetching(info.progress.getInt(EditionWorker.FETCHED, 0))
                } else BuildState.Syncing
            WorkInfo.State.SUCCEEDED ->
                if (info.outputData.getBoolean(EditionWorker.NOTHING_NEW, false)) BuildState.NothingNew else BuildState.Idle
            WorkInfo.State.FAILED -> BuildState.Failed(info.outputData.getString(EditionWorker.ERROR) ?: "Something went wrong.")
            WorkInfo.State.CANCELLED, null -> BuildState.Idle
        }
    }
}
