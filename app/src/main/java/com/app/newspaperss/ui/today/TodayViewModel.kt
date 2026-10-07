package com.app.newspaperss.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.ui.settings.SettingsSummary
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    /** A sent edition offers to open the Kindle app, where it shows up. */
    val kindleReader: Boolean = false,
    /** What to call the reader's e-reader in prompts. */
    val deviceName: String = "e-reader",
    /** Starred articles not yet in an edition, from sources that aren't paused. */
    val starredWaiting: Int = 0,
    /** Editions just sent to a Kindle, and how, which can take a few minutes to arrive. */
    val sentToKindle: Map<Long, KindleSend> = emptyMap(),
    /** Where Send emails editions, or null when it shares them. */
    val kindleEmail: KindleEmail? = null,
    /** The latest edition's articles, in the book's order. */
    val latestArticles: List<EditionArticleEntity> = emptyList(),
)

class TodayViewModel(
    private val editions: EditionRepository,
    work: Flow<WorkInfo?>,
    settings: Flow<Settings> = flowOf(Settings()),
    online: Flow<Boolean> = flowOf(true),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val lastDue: () -> Long = { 0L },
    private val lastStart: () -> Long = lastDue,
    sentToKindle: Flow<Map<Long, KindleSend>> = flowOf(emptyMap()),
    private val startBuild: () -> Unit,
) : ViewModel() {
    // The latest edition's articles arrive with the list, so its card is drawn once, whole, rather
    // than growing a moment later: on e-ink that would be a second refresh.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val editionsWithLatest: Flow<Pair<List<EditionEntity>, List<EditionArticleEntity>>> = editions.observeAll().flatMapLatest { list ->
        val latest = list.firstOrNull() ?: return@flatMapLatest flowOf(list to emptyList())
        // edition_articles alone: a query joining articles would rerun on every write to them, as a sync makes.
        editions.observeArticles(latest.id).map { list to it }
    }

    private val editionsAndKindle = combine(editionsWithLatest, sentToKindle, ::Pair)

    val state: StateFlow<TodayState> = combine(editionsAndKindle, work, settings, online, editions.observeStarredWaiting()) { (withLatest, kindle), info, s, isOnline, starred ->
        val (list, latestArticles) = withLatest
        TodayState(
            editions = list,
            latestArticles = latestArticles,
            sentToKindle = kindle,
            build = buildStateOf(info, isOnline),
            next = nextEdition(s, now(), lastDue(), lastStart()),
            preferOpen = s.device == Device.BOOX,
            offerOpen = s.device.offersOpen,
            kindleReader = s.device == Device.KINDLE,
            deviceName = when (s.device) {
                Device.KINDLE -> "Kindle"
                Device.KOBO -> "Kobo"
                Device.POCKETBOOK -> "PocketBook"
                else -> "e-reader"
            },
            starredWaiting = starred,
            kindleEmail = s.kindleEmailTarget,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(null, BuildState.Idle))

    fun makeOneNow() = startBuild()

    fun fileOf(edition: EditionEntity): File? = editions.fileOf(edition)

    fun emailBody(editionId: Long): Flow<String?> = editions.observeEmailBody(editionId)

    fun markSent(edition: EditionEntity) = markSent(edition.id)

    fun markSent(editionId: Long) {
        viewModelScope.launch { editions.markSent(editionId) }
    }

    /** The mail app was opened to email [editionId] to the reader's Kindle, which counts as sent. */
    fun markEmailed(editionId: Long) {
        viewModelScope.launch { editions.markEmailedToKindle(editionId) }
    }

    fun markNotSent(editionId: Long) {
        viewModelScope.launch { editions.markNotSent(editionId) }
    }

    companion object {
        /**
         * @param lastDueMs [EditionScheduler.LAST_DUE] and [lastStartMs] [EditionScheduler.LAST_START],
         *   so an edition started early isn't shown as still to come.
         */
        fun nextEdition(s: Settings, now: ZonedDateTime, lastDueMs: Long = 0L, lastStartMs: Long = lastDueMs): String? {
            if (!s.scheduleEnabled) return null
            val next = EditionScheduler.nextDue(s, now, lastDueMs, lastStartMs)?.atZone(now.zone) ?: return null
            val time = next.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            val day = when (next.toLocalDate()) {
                now.toLocalDate() -> "Today"
                now.toLocalDate().plusDays(1) -> "Tomorrow"
                else -> next.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            }
            val podcast = SettingsSummary.podcastStart(s, Locale.getDefault())?.let {
                ", with its podcast. It starts at $it: leave your phone charging"
            }.orEmpty()
            return "$day at $time \u00b7 about ${s.edition.minutes} min$podcast"
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
