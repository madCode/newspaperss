package com.app.newspaperss.listen

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Where a line [ListenPlayer] asks for is: its edition, page and line, of how many [lines] on
 * the page, and the play it belongs to.
 */
data class LineId(val generation: Int, val editionId: Long, val page: Int, val line: Int, val lines: Int) {
    override fun toString() = "$generation:$editionId:$page:$line:$lines"

    companion object {
        fun parse(id: String): LineId? {
            val parts = id.split(':')
            if (parts.size != 5) return null
            return LineId(
                parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null,
                parts[4].toIntOrNull() ?: return null,
            )
        }
    }
}

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

    /** What's playing: the line it started from, the piece, and the last line reported. */
    private var playing: LineId? = null
    private var piece = 0
    private var reported = -1
    private var rate = 1f
    /** The voice the article was chosen to play in: shown while it plays. */
    private var chosen: PodcastVoice? = null
    /** A piece ended and the next is starting: every line reported so far was heard to its end. */
    private var between = false

    init {
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(ms: Long) = reach(ms)
            override fun onNext() = nextPiece()
            override fun onEnded() = lastPieceEnded()
            override fun onError() = audioFailed()
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
     * line it reached, so Listen doesn't sit silent showing it's playing.
     */
    private fun audioFailed() {
        val at = playing ?: return
        // Failing to start the next piece, the last line reported was heard whole: on from the next.
        val line = if (between) reported + 1 else reported.coerceAtLeast(at.line)
        article?.let { failed += it }
        log("Playing: piece ${pieces.getOrNull(piece)?.audio?.name} failed; the phone's voice from line $line")
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
        piece = index
        reported = from.line - 1
        between = false
        this.rate = rate
        log("Playing edition ${from.editionId} page ${from.page} from line ${from.line}: ${pieces[index].audio.name} at ${"%.2f".format(Locale.ROOT, start)}s, speed $rate")
        // Its lines are reported from the audio's first tick, not from inside this call. The
        // article's pieces go as one list, so the audio runs on from one to the next unbroken.
        audio.play(pieces.map { it.audio }, index, (start * 1000).toLong(), rate)
    }

    /** Reports every line the audio has reached since the last. */
    private fun reach(ms: Long) {
        val at = playing ?: return
        val current = pieces.getOrNull(piece) ?: return
        val seconds = ms / 1000.0
        val upTo = current.firstLine + current.starts.indexOfLast { it <= seconds + EARLY }
        while (reported < upTo) {
            between = false
            reported++
            if (reported >= at.line) listener?.onStart(at.copy(line = reported).toString())
        }
    }

    /** The audio has run on into the next piece: every line of the one before was heard. */
    private fun nextPiece() {
        if (playing == null || piece >= pieces.lastIndex) return
        reach(Long.MAX_VALUE / 2)
        piece++
        between = true
    }

    private fun lastPieceEnded() {
        val at = playing ?: return
        val current = pieces.getOrNull(piece) ?: return
        reach(Long.MAX_VALUE / 2)
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
        /** Seconds before a line's start that it counts as reached: the ticks come 50 ms apart. */
        const val EARLY = 0.05
    }
}

/** Plays a podcast's audio, a list of files one after another, telling how far in it is as it goes. */
interface PodcastAudio {
    var listener: Listener?

    /** Plays [files] in turn from [fromMs] into the one at [index], at [speed] (1.0 normal), stopping whatever was playing. */
    fun play(files: List<File>, index: Int, fromMs: Long, speed: Float)

    fun stop()

    fun release()

    /** Called on the main thread. */
    interface Listener {
        /** Every few tens of milliseconds while playing: how far into the current file, in milliseconds. */
        fun onPosition(ms: Long)

        /** The audio ran on from one file into the next. */
        fun onNext()

        /** The last file has been heard to its end. */
        fun onEnded()

        fun onError()
    }
}

/**
 * [PodcastAudio] through a Media3 player given the whole list: one decoder and one audio output
 * carry on from file to file, so nothing is stopped between pieces. A MediaPlayer per file says it
 * has finished while the file's last moment is still to be heard, and stopping it to start the
 * next cuts that moment off. Media3's player says it has ended only once the audio has been
 * played out, and keeps the voice's pitch at other speeds. Audio focus, the lock
 * screen and headphones are [ListenService]'s, as for the phone's voice.
 *
 * @param newPlayer makes the player for each play: an ExoPlayer, or a fake in tests.
 */
class Media3Audio(private val log: (String) -> Unit = {}, private val newPlayer: () -> Player) : PodcastAudio {
    override var listener: PodcastAudio.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var names: List<String> = emptyList()

    /** The file the listener was last told of: it hears of each one the player moves on to, in turn. */
    private var index = 0

    /** Bumped by each play and stop, so news posted for an earlier one is dropped. */
    private var plays = 0

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            follow(p.currentMediaItemIndex)
            if (p.isPlaying) listener?.onPosition(p.currentPosition)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Tells of each file the player has moved on to, whether a tick or the player noticed first. */
    private fun follow(now: Int) {
        while (index < now && index < names.lastIndex) {
            index++
            log("Playing: on to ${names[index]}")
            listener?.onNext()
        }
    }

    override fun play(files: List<File>, index: Int, fromMs: Long, speed: Float) {
        stop()
        val play = plays
        // Posted rather than told at once: the speaker may stop and release this player in answer.
        fun later(call: PodcastAudio.Listener.() -> Unit) = main.post { if (plays == play) listener?.call() }
        val p = newPlayer()
        player = p
        names = files.map { it.name }
        this.index = index
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (plays == play) follow(p.currentMediaItemIndex)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (plays != play || playbackState != Player.STATE_ENDED) return
                follow(names.lastIndex)
                log("Playing: ${names.lastOrNull()} ended")
                later { onEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (plays != play) return
                log("Playing: ${names.getOrNull(p.currentMediaItemIndex)} failed, ${error.errorCodeName}")
                later { onError() }
            }
        })
        p.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) }, index, fromMs)
        p.playbackParameters = PlaybackParameters(speed)
        p.prepare()
        p.play()
        main.post(tick)
    }

    override fun stop() {
        plays++
        main.removeCallbacks(tick)
        player?.release()
        player = null
    }

    override fun release() = stop()

    private companion object {
        const val TICK_MS = 50L
    }
}
