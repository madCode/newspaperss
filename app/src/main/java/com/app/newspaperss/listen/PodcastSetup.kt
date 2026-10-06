package com.app.newspaperss.listen

import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Where getting Kokoro onto this phone stands, for Settings › Listening. */
sealed interface KokoroState {
    /** A 32-bit phone: Kokoro can't run. */
    data object Unsupported : KokoroState

    /** Not downloaded, or part way and stopped. */
    data object Absent : KokoroState

    /** Download asked for, waiting for Wi-Fi ([wifi]) or for any connection. */
    data class Waiting(val wifi: Boolean) : KokoroState

    data class Downloading(val got: Long, val total: Long) : KokoroState

    /** Downloaded; seeing how fast this phone makes speech. */
    data object Checking : KokoroState

    /** Downloaded and checked: [pace] as in [Settings.podcastPace]. */
    data class Ready(val pace: Double) : KokoroState

    data class Failed(val message: String) : KokoroState
}

/**
 * Getting Kokoro onto the phone, checking it and removing it. The work itself runs in
 * [com.app.newspaperss.work.KokoroWorker], so a download outlives the screen and waits for Wi-Fi
 * by itself; this reads its state back from WorkManager and the files.
 */
class PodcastSetup(
    private val install: KokoroInstall,
    private val settings: SettingsStore,
    work: Flow<WorkInfo?>,
    private val start: (mobileData: Boolean) -> Unit,
    private val stop: () -> Unit,
    private val supported: Boolean,
    private val engine: (PodcastVoice) -> PodcastEngine,
    private val now: () -> Long = System::nanoTime,
) {
    val size: Long get() = install.bytes

    val state: Flow<KokoroState> = combine(settings.settings, work) { s, info -> stateOf(s, info) }

    private fun stateOf(s: Settings, info: WorkInfo?): KokoroState {
        if (!supported) return KokoroState.Unsupported
        when (info?.state) {
            WorkInfo.State.RUNNING -> return if (info.progress.getString(PHASE) == PHASE_CHECK) {
                KokoroState.Checking
            } else {
                KokoroState.Downloading(info.progress.getLong(GOT, 0), info.progress.getLong(TOTAL, install.bytes))
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                return KokoroState.Waiting(wifi = info.constraints.requiredNetworkType == NetworkType.UNMETERED)
            else -> {}
        }
        val pace = s.podcastPace
        if (pace != null && install.complete) return KokoroState.Ready(pace.toDouble())
        if (info?.state == WorkInfo.State.FAILED) return KokoroState.Failed(info.outputData.getString(ERROR) ?: "Kokoro couldn't be set up.")
        return KokoroState.Absent
    }

    /** Downloads Kokoro, on Wi-Fi only unless [mobileData], then checks it. */
    fun download(mobileData: Boolean) = start(mobileData)

    /** Stops a download, keeping what's arrived for next time. */
    fun cancel() = stop()

    suspend fun use() = settings.update { it.copy(listenVoice = ListenVoice.PODCAST) }

    /** Deletes Kokoro and goes back to reading live. */
    suspend fun remove() {
        stop()
        install.remove()
        settings.update { it.copy(listenVoice = ListenVoice.PHONE, podcastPace = null) }
    }

    /**
     * The work: download what's missing, then time Kokoro making [SAMPLE] and keep the pace it
     * suggests once the phone is warm.
     */
    suspend fun downloadAndCheck(download: KokoroDownload, onProgress: (got: Long, total: Long) -> Unit, onChecking: suspend () -> Unit) {
        var shown = 0L
        download.run { got, total ->
            // Every 64 KB would flood WorkManager's database with progress.
            val t = now()
            if (t - shown > PROGRESS_EVERY_NS || got == total) {
                shown = t
                onProgress(got, total)
            }
        }
        onChecking()
        val voice = settings.current().podcastVoice
        val kokoro = engine(voice)
        try {
            val started = now()
            val speech = kokoro.speak(SAMPLE)
            val pace = PodcastPace.fromSample((now() - started) / 1e9 / speech.seconds)
            settings.update { it.copy(podcastPace = pace.toFloat()) }
        } finally {
            kokoro.release()
        }
    }

    companion object {
        const val PHASE = "phase"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_CHECK = "check"
        const val GOT = "got"
        const val TOTAL = "total"
        const val ERROR = "error"
        private const val PROGRESS_EVERY_NS = 500_000_000L

        /** About 14 seconds of speech: long enough to time, short enough to wait for. */
        const val SAMPLE = "For forty years, nobody could say how many ways a loop of string can tangle. " +
            "Then a retired schoolteacher wrote a program. The question sounds like a puzzle for a rainy afternoon. " +
            "Tie a knot in a piece of string, fuse the ends, and ask which knots are really different."
    }
}
