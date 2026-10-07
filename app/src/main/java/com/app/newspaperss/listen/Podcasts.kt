package com.app.newspaperss.listen

import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * An edition's podcast as its page shows it.
 *
 * @param made for each article, in book order, whether it plays from the podcast.
 * @param leftOut for each article, whether the podcast left it to the phone's voice: not in
 *   English, or something Kokoro couldn't say.
 * @param asked whether a podcast was asked for: made with a scheduled edition, or by hand.
 * @param pace this phone's, to say how long making one would take.
 */
data class PodcastProgress(val asked: Boolean, val made: List<Boolean>, val finished: Boolean, val pace: Double, val leftOut: List<Boolean>)

/** Podcasts as the rest of the app sees them: what's made of each, asking for one, and deleting them. */
class Podcasts(
    private val store: PodcastStore,
    private val settings: SettingsStore,
    private val install: KokoroInstall,
    /** Starts [PodcastMaker] once the phone charges. */
    private val start: () -> Unit,
) {
    /** [editionId]'s podcast, with [articles] articles; null while Kokoro isn't in use. */
    fun observe(editionId: Long, articles: Int): Flow<PodcastProgress?> = combine(store.changes, settings.settings) { _, s ->
        val pace = s.podcastPace
        if (s.listenVoice != ListenVoice.PODCAST || pace == null || !install.complete) return@combine null
        PodcastProgress(
            asked = store.voice(editionId) != null,
            made = (0 until articles).map { store.made(editionId, it) },
            leftOut = (0 until articles).map { store.live(editionId, it) },
            finished = store.finished(editionId),
            pace = pace.toDouble(),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Asks for [editionId]'s podcast if Kokoro is in use, and starts making it once the phone
     * charges. The voice is the one chosen now: a podcast keeps the voice it started in.
     */
    suspend fun request(editionId: Long) {
        val s = settings.current()
        if (s.listenVoice != ListenVoice.PODCAST || !withContext(Dispatchers.IO) { install.complete }) return
        withContext(Dispatchers.IO) { store.want(editionId, s.podcastVoice) }
        start()
    }

    suspend fun resume() {
        if (withContext(Dispatchers.IO) { store.waiting().isNotEmpty() }) start()
    }

    fun deleteAll() = store.deleteAll()
}
