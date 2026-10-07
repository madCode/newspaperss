package com.app.newspaperss.listen

import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlinx.coroutines.withContext

/** Where getting Kokoro onto this phone stands, for Settings › Listening. */
sealed interface KokoroState {
    /** A 32-bit phone: Kokoro can't run. */
    data object Unsupported : KokoroState

    /** Not downloaded, or part way and stopped. */
    data object Absent : KokoroState

    /**
     * Download asked for, waiting for Wi-Fi ([wifi]) or for any connection; or [retrying] after a
     * failed try, which waits a while whatever the connection.
     */
    data class Waiting(val wifi: Boolean, val retrying: Boolean = false) : KokoroState

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
    private val podcasts: PodcastStore,
    private val makePodcasts: () -> Unit,
    private val now: () -> Long = System::nanoTime,
) {
    /** The download's size; reads the manifest, so not on the main thread. */
    val size: Long get() = install.bytes

    // Each emission reads which files are in place: that much off the main thread.
    val state: Flow<KokoroState> = combine(settings.settings, work) { s, info -> withContext(Dispatchers.IO) { stateOf(s, info) } }

    private fun stateOf(s: Settings, info: WorkInfo?): KokoroState {
        if (!supported) return KokoroState.Unsupported
        when (info?.state) {
            WorkInfo.State.RUNNING -> return if (info.progress.getString(PHASE) == PHASE_CHECK) {
                KokoroState.Checking
            } else {
                KokoroState.Downloading(info.progress.getLong(GOT, 0), info.progress.getLong(TOTAL, install.bytes))
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> return KokoroState.Waiting(
                wifi = info.constraints.requiredNetworkType == NetworkType.UNMETERED,
                // Its own record, not WorkManager's run count, which Android's stops also raise.
                retrying = failures.exists() || damagedRuns.exists(),
            )
            else -> {}
        }
        val pace = s.podcastPace
        if (pace != null && install.complete) return KokoroState.Ready(pace.toDouble())
        if (info?.state == WorkInfo.State.FAILED) return KokoroState.Failed(info.outputData.getString(ERROR) ?: "Kokoro couldn't be set up.")
        return KokoroState.Absent
    }

    /** Downloads Kokoro, on Wi-Fi only unless [mobileData], then checks it. */
    fun download(mobileData: Boolean) {
        // Asked for afresh: earlier failures don't count against it, nor a check that went wrong.
        failures.delete()
        damagedRuns.delete()
        checking.delete()
        start(mobileData)
    }

    // Beside the files, so Remove clears them too.
    private val failures get() = File(install.dir, ".failures")
    private val checking get() = File(install.dir, ".checking")
    private val damagedRuns get() = File(install.dir, ".damaged")

    /**
     * Counts a failed run and returns how many have failed since anything last arrived. Android's
     * own stops (its time limit on work, Wi-Fi lost) aren't failures, so its run count can't be used.
     */
    @Synchronized
    fun failed(): Int = count(failures)

    /**
     * Counts a run that ended with a file arriving damaged. Unlike [failed], bytes arriving don't
     * reset it: a damaged file arrives in full each time, and would otherwise be fetched forever.
     */
    @Synchronized
    fun damaged(): Int = count(damagedRuns)

    private fun count(file: File): Int {
        install.dir.mkdirs()
        val count = (file.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0) + 1
        file.writeText(count.toString())
        return count
    }

    /** Stops a download, keeping what's arrived for next time. */
    fun cancel() = stop()

    /** Kokoro reads from now on; podcasts asked for before it was turned off carry on being made. */
    suspend fun use() {
        settings.update { it.copy(listenVoice = ListenVoice.PODCAST) }
        if (withContext(Dispatchers.IO) { podcasts.waiting().isNotEmpty() }) makePodcasts()
    }

    /** Deletes Kokoro and the podcasts made with it, and goes back to reading live. */
    suspend fun remove() {
        stop()
        install.remove()
        withContext(Dispatchers.IO) { podcasts.deleteAll() }
        settings.update { it.copy(listenVoice = ListenVoice.PHONE, podcastPace = null) }
    }

    /**
     * The work: download what's missing, then time Kokoro making [SAMPLE] and keep the pace it
     * suggests once the phone is warm.
     */
    suspend fun downloadAndCheck(download: KokoroDownload, onProgress: (got: Long, total: Long) -> Unit, onChecking: suspend () -> Unit) {
        var shown = -1L
        var from = -1L
        val lock = Any()
        // Eight files arrive at once: one at a time here, so progress can't step backwards.
        download.run { got, total -> synchronized(lock) {
            when {
                from < 0 -> from = got
                // Something arrived: failures before it are no longer "in a row".
                from in 0 until got -> {
                    failures.delete()
                    from = Long.MAX_VALUE
                }
            }
            // In whole percent: every 64 KB would flood WorkManager's database, and redraw an e-ink screen.
            val percent = got * 100 / total.coerceAtLeast(1)
            if (percent > shown) {
                shown = percent
                onProgress(got, total)
            }
        } }
        onChecking()
        // One Kokoro at a time, a podcast being made included: a cancelled check keeps speaking
        // (native code doesn't stop), and two at once would hold the model twice and time each
        // other too slow.
        KokoroEngine.lock.withLock {
            // Marked first, counting checks that started and never finished: sherpa can abort in
            // native code, and two in a row that took the app down aren't run again on the next
            // start, only when asked afresh. One could be the app swiped away.
            install.dir.mkdirs()
            val unfinished = checking.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0
            if (unfinished >= CHECK_TRIES) throw IllegalStateException("The last checks stopped the app")
            checking.writeText((unfinished + 1).toString())
            val kokoro = engine(settings.current().podcastVoice)
            try {
                val started = now()
                val speech = kokoro.speak(SAMPLE)
                check(speech.seconds > 0) { "Kokoro said nothing" }
                // Cancelled while it spoke: its timing isn't the one wanted.
                currentCoroutineContext().ensureActive()
                val pace = PodcastPace.fromSample((now() - started) / 1e9 / speech.seconds)
                settings.update { it.copy(podcastPace = pace.toFloat()) }
            } finally {
                kokoro.release()
                // Only a crash in native code skips this, which is what the marker is for: a stop
                // or an error here mustn't read as one next time.
                checking.delete()
            }
        }
    }

    companion object {
        /** Checks that took the app down in a row before it's taken as Kokoro crashing this phone. */
        private const val CHECK_TRIES = 2

        const val PHASE = "phase"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_CHECK = "check"
        const val GOT = "got"
        const val TOTAL = "total"
        const val ERROR = "error"

        /** About 14 seconds of speech: long enough to time, short enough to wait for. */
        const val SAMPLE = "For forty years, nobody could say how many ways a loop of string can tangle. " +
            "Then a retired schoolteacher wrote a program. The question sounds like a puzzle for a rainy afternoon. " +
            "Tie a knot in a piece of string, fuse the ends, and ask which knots are really different."
    }
}
