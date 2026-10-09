package com.app.newspaperss.listen

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs

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
            override fun onEnded() = pieceEnded()
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
        // Its lines are reported from the audio's first tick, not from inside this call.
        audio.play(pieces[index].audio, (start * 1000).toLong(), rate)
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

    private fun pieceEnded() {
        val at = playing ?: return
        val current = pieces.getOrNull(piece) ?: return
        reach(Long.MAX_VALUE / 2)
        if (piece < pieces.lastIndex) {
            piece++
            between = true
            log("Playing: ${current.audio.name} ended, on to ${pieces[piece].audio.name}")
            audio.play(pieces[piece].audio, 0, rate)
            return
        }
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

/** Plays a podcast's audio, one file at a time, telling how far in it is as it goes. */
interface PodcastAudio {
    var listener: Listener?

    /** Plays [file] from [fromMs] at [speed] (1.0 normal), stopping whatever was playing. */
    fun play(file: File, fromMs: Long, speed: Float)

    fun stop()

    fun release()

    /** Called on the main thread. */
    interface Listener {
        /** Every few tens of milliseconds while playing: how far into the file, in milliseconds. */
        fun onPosition(ms: Long)

        fun onEnded()

        fun onError()
    }
}

/**
 * [PodcastAudio] through Android's MediaPlayer, whose speed keeps the voice's pitch. Audio focus,
 * the lock screen and headphones are [ListenService]'s, as for the phone's voice.
 */
class MediaPlayerAudio(
    private val log: (String) -> Unit = {},
    /** Tests hand out players they can steer. */
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) : PodcastAudio {
    override var listener: PodcastAudio.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** Bumped by each play and stop, so an end or error posted for an earlier one is dropped. */
    private var plays = 0

    /** The file playing, its length and speed, and the last tick's position and time: to log a skip or a stall. */
    private var name = ""
    private var durationMs = 0L
    private var speed = 1f
    private var lastMs = -1L
    private var lastAt = 0L
    /** Skips and stalls logged since the app started: a phone whose position always lags mustn't fill the log. */
    private var oddities = 0

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                val ms = p.currentPosition.toLong()
                watch(ms)
                listener?.onPosition(ms)
            } else if (lastMs >= 0) {
                // At its end, the next piece follows; anywhere else, nothing here asked it to stop.
                // Its length is kept from before: asked of a player that failed, it would throw.
                if (lastMs < durationMs - SLACK_MS) log("Playing: $name stopped at ${lastMs}ms of ${durationMs}ms, unasked")
                lastMs = -1
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Logs the audio's position moving other than with the clock: a jump skips audio, a stall
     * holds it. Ticks come 50 ms apart; a busy main thread delays them, which the clock allows for.
     */
    private fun watch(ms: Long) {
        val at = SystemClock.elapsedRealtime()
        if (lastMs >= 0) {
            val expected = ((at - lastAt) * speed).toLong()
            val moved = ms - lastMs
            if (abs(moved - expected) > SLACK_MS && ++oddities <= MAX_ODDITIES) {
                log("Playing: $name went from ${lastMs}ms to ${ms}ms in ${at - lastAt}ms" + if (oddities == MAX_ODDITIES) "; no more of these until the app restarts" else "")
            }
        }
        lastMs = ms
        lastAt = at
    }

    override fun play(file: File, fromMs: Long, speed: Float) {
        stop()
        val play = plays
        fun later(call: PodcastAudio.Listener.() -> Unit) = main.post { if (plays == play) listener?.call() }
        val p = newPlayer()
        name = file.name
        this.speed = speed
        lastMs = -1
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            // A local file of a couple of minutes: quick enough to prepare in place.
            p.prepare()
            durationMs = p.duration.toLong()
            p.setOnCompletionListener { later { onEnded() } }
            p.setOnInfoListener { _, what, extra ->
                log("Playing: $name says ${INFO[what] ?: what} ($extra)")
                false
            }
            p.setOnErrorListener { _, what, extra ->
                log("Playing: $name failed, $what ($extra)")
                later { onError() }
                true
            }
            // Setting a speed starts playback, so only once the seek has landed: before, a moment
            // from the piece's start would be heard.
            player = p
            var begun = false
            val begin = begin@{
                if (plays != play || begun) return@begin
                begun = true
                try {
                    // Only the fields set are applied, so a new one changes the speed alone.
                    p.playbackParams = PlaybackParams().setSpeed(speed)
                    p.start()
                    main.post(tick)
                } catch (e: Exception) {
                    // A speed this phone's audio can't play, or a player that failed while seeking.
                    main.removeCallbacks(tick)
                    if (player === p) player = null
                    p.release()
                    later { onError() }
                }
            }
            if (fromMs > 0) {
                p.setOnSeekCompleteListener { begin() }
                p.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
                // A seek that never says it's done mustn't leave Listen silent.
                main.postDelayed({ begin() }, SEEK_WAIT_MS)
            } else {
                begin()
            }
        } catch (e: Exception) {
            if (player === p) player = null
            p.release()
            later { onError() }
        }
    }

    override fun stop() {
        plays++
        lastMs = -1
        main.removeCallbacks(tick)
        player?.release()
        player = null
    }

    override fun release() = stop()

    private companion object {
        const val TICK_MS = 50L
        const val SEEK_WAIT_MS = 2_000L
        const val SLACK_MS = 300L
        const val MAX_ODDITIES = 30

        /** MediaPlayer's info codes an audio file can get, by their constants' names. */
        val INFO = mapOf(
            MediaPlayer.MEDIA_INFO_UNKNOWN to "unknown",
            MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT to "started as next",
            MediaPlayer.MEDIA_INFO_BUFFERING_START to "buffering start",
            MediaPlayer.MEDIA_INFO_BUFFERING_END to "buffering end",
            MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING to "bad interleaving",
            MediaPlayer.MEDIA_INFO_NOT_SEEKABLE to "not seekable",
            MediaPlayer.MEDIA_INFO_METADATA_UPDATE to "metadata update",
            MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING to "audio not playing",
        )
    }
}
