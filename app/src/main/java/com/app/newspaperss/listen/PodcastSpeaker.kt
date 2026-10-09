package com.app.newspaperss.listen

import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Listen's voice when podcasts are made: an article whose podcast is made plays from it, any
 * other in the phone's voice. The choice is made when an article starts, and kept while it plays
 * on, so the voice changes between articles, not mid-sentence. An article in the phone's voice
 * moves to its podcast when played or jumped in once that's made.
 *
 * Playing a podcast, it reports each line's start as the audio reaches it, from the times kept
 * with each piece, so the player tints and saves sentences as it does for the phone's voice.
 * Lines queued behind the one playing need nothing: the audio carries on through them.
 *
 * @param inUse whether Listen is to play podcasts at all (Kokoro chosen in Settings).
 */
class PodcastSpeaker(
    private val phone: Speaker,
    private val podcasts: PodcastStore,
    private val audio: PodcastAudio,
    /** For the podcast log: where each play starts, and what interrupted one. */
    private val log: (String) -> Unit = {},
    private val inUse: () -> Boolean,
) : Speaker {
    override var listener: Speaker.Listener? = null
        set(value) {
            field = value
            phone.listener = value
        }

    private val _voice = MutableStateFlow<PodcastVoice?>(null)

    /** The podcast's voice while an article plays from it; null in the phone's voice. */
    val voice: StateFlow<PodcastVoice?> = _voice.asStateFlow()

    private val _instead = MutableStateFlow<Instead?>(null)

    /** Why an article of an edition with a podcast plays in the phone's voice; null otherwise. */
    val instead: StateFlow<Instead?> = _instead.asStateFlow()

    enum class Instead {
        /** The podcast hasn't reached it yet. */
        NOT_MADE_YET,

        /** The podcast left it to the phone's voice: not in English, or something Kokoro couldn't say. */
        LEFT_OUT,
    }

    /** The article the voice was chosen for, and its pieces if it plays from the podcast. */
    private var article: Pair<Long, Int>? = null
    private var pieces: List<PodcastPiece> = emptyList()

    /** What the player has asked this article's lines to say: the phone's voice takes over with them. */
    private val asked = mutableMapOf<Int, Asked>()

    private class Asked(val text: String, val language: String?, val rate: Float)

    /** Articles whose podcast failed to play: the phone's voice for them, not the same failure each play. */
    private val failed = mutableSetOf<Pair<Long, Int>>()

    /** What's playing: the line it started from, and the last line reported. */
    private var playing: LineId? = null
    private var reported = -1
    /** The voice the article was chosen to play in: shown while it plays. */
    private var chosen: PodcastVoice? = null

    init {
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(index: Int, ms: Long) = reach(index, ms)
            override fun onEnded() = lastPieceEnded()
            override fun onError(index: Int, code: String) = audioFailed(index, code)
        }
    }

    override fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean {
        val line = LineId.parse(id)
        if (line == null) {
            // Not a line of an edition (Settings' sample): the phone's voice, the podcast stopped.
            stopAudio()
            return phone.speak(id, text, language, rate, flush)
        }
        val here = line.editionId to line.page
        // A new article, or one read in the phone's voice played or jumped in again: its podcast
        // may be made since (overnight, while paused). Once from the podcast, it stays so.
        if (here != article || (flush && pieces.isEmpty())) choose(here, line.lines)
        asked[line.line] = Asked(text, language, rate)
        // Kokoro turned off, or removed with its podcasts, since the article started: the phone's
        // voice from here, rather than a file that's gone.
        if (flush && pieces.isNotEmpty() && !(inUse() && pieces.all { it.audio.exists() })) toPhone()
        if (pieces.isEmpty()) {
            _voice.value = null
            return phone.speak(id, text, language, rate, flush)
        }
        // Already on its way: the audio reaches it.
        if (!flush) return true
        _voice.value = chosen
        phone.stop()
        play(line, rate)
        return true
    }

    private fun choose(here: Pair<Long, Int>, lines: Int) {
        article = here
        asked.clear()
        val (editionId, page) = here
        val made = if (inUse() && podcasts.made(editionId, page) && here !in failed) podcasts.pieces(editionId, page) else emptyList()
        // Made from the same lines as the player reads: an update that splits sentences
        // differently would otherwise tint the wrong ones, and never reach the article's end.
        pieces = made.takeIf { it.isNotEmpty() && it.last().firstLine + it.last().starts.size == lines }.orEmpty()
        chosen = if (pieces.isEmpty()) null else podcasts.voice(editionId)
        _instead.value = when {
            pieces.isNotEmpty() || !inUse() || podcasts.voice(editionId) == null -> null
            // Left to the phone's voice, or made from other sentences than the book now has.
            podcasts.live(editionId, page) || made.isNotEmpty() -> Instead.LEFT_OUT
            else -> Instead.NOT_MADE_YET
        }
    }

    private fun toPhone() {
        stopAudio()
        pieces = emptyList()
        chosen = null
        _voice.value = null
    }

    /**
     * The podcast can't be played (a damaged file, say): the phone's voice reads on from the
     * line it reached, so Listen doesn't sit silent showing it's playing. A piece that fails
     * before any of its lines was reached was never begun: on from its first line.
     */
    private fun audioFailed(index: Int, code: String) {
        val at = playing ?: return
        val first = pieces.getOrNull(index)?.firstLine ?: 0
        val line = maxOf(reported, at.line, first)
        article?.let { failed += it }
        log("Playing: ${pieces.getOrNull(index)?.audio?.name} failed ($code); the phone's voice from line $line")
        toPhone()
        val now = asked[line]
        if (now == null) {
            listener?.onError(at.copy(line = line).toString())
            return
        }
        phone.speak(at.copy(line = line).toString(), now.text, now.language, now.rate, flush = true)
        asked[line + 1]?.let { phone.speak(at.copy(line = line + 1).toString(), it.text, it.language, it.rate, flush = false) }
    }

    private fun play(from: LineId, rate: Float) {
        stopAudio()
        // The last piece starting at or before the line; its time within that piece.
        val index = pieces.indexOfLast { it.firstLine <= from.line }.coerceAtLeast(0)
        val start = pieces[index].starts.getOrNull(from.line - pieces[index].firstLine) ?: 0.0
        playing = from
        reported = from.line - 1
        log("Playing edition ${from.editionId} page ${from.page} from line ${from.line}: ${pieces[index].audio.name} at ${"%.2f".format(Locale.ROOT, start)}s, speed $rate")
        // Its lines are reported from the audio's first tick, not from inside this call. The
        // article's pieces go as one list, so the audio runs on from one to the next unbroken.
        audio.play(pieces.map { it.audio }, index, (start * 1000).toLong(), rate)
    }

    /** Reports every line the audio has reached since the last, [ms] into the piece at [index]. */
    private fun reach(index: Int, ms: Long) {
        val at = playing ?: return
        val current = pieces.getOrNull(index) ?: return
        val seconds = ms / 1000.0
        val upTo = current.firstLine + current.starts.indexOfLast { it <= seconds + EARLY }
        while (reported < upTo) {
            reported++
            if (reported >= at.line) listener?.onStart(at.copy(line = reported).toString())
        }
    }

    private fun lastPieceEnded() {
        val at = playing ?: return
        val current = pieces.lastOrNull() ?: return
        reach(pieces.lastIndex, Long.MAX_VALUE / 2)
        val last = current.firstLine + current.starts.lastIndex
        stopAudio()
        listener?.onDone(at.copy(line = last).toString())
    }

    private fun stopAudio() {
        if (playing == null) return
        playing = null
        audio.stop()
    }

    override fun stop() {
        stopAudio()
        _voice.value = null
        phone.stop()
    }

    override fun release() {
        stopAudio()
        audio.release()
        phone.release()
    }

    private companion object {
        /** Seconds before a line's start that it counts as reached: a tick early, as the ticks are that far apart. */
        const val EARLY = Media3Audio.TICK_MS / 1000.0
    }
}

