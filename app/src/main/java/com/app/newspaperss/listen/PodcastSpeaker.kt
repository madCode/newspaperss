package com.app.newspaperss.listen

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where a line [ListenPlayer] asks for is: its edition, page and line, and the play it belongs to. */
data class LineId(val generation: Int, val editionId: Long, val page: Int, val line: Int) {
    override fun toString() = "$generation:$editionId:$page:$line"

    companion object {
        fun parse(id: String): LineId? {
            val parts = id.split(':')
            if (parts.size != 4) return null
            return LineId(
                parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * Listen's voice when podcasts are made: an article whose podcast is made plays from it, any
 * other in the phone's voice. The choice is made when an article starts and kept until another
 * one does, so the voice changes only between articles, never mid-way.
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

    /** The article the voice was chosen for, and its pieces if it plays from the podcast. */
    private var article: Pair<Long, Int>? = null
    private var pieces: List<PodcastPiece> = emptyList()

    /** What's playing: the line it started from, the piece, and the last line reported. */
    private var playing: LineId? = null
    private var piece = 0
    private var reported = -1
    private var rate = 1f

    init {
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(ms: Long) = reach(ms)
            override fun onEnded() = pieceEnded()
            override fun onError() {
                val at = playing ?: return
                stopAudio()
                listener?.onError(at.copy(line = reported.coerceAtLeast(at.line)).toString())
            }
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
        if (here != article) choose(here)
        if (pieces.isEmpty()) return phone.speak(id, text, language, rate, flush)
        // Already on its way: the audio reaches it.
        if (!flush) return true
        phone.stop()
        play(line, rate)
        return true
    }

    private fun choose(here: Pair<Long, Int>) {
        article = here
        val (editionId, page) = here
        pieces = if (inUse() && podcasts.made(editionId, page)) podcasts.pieces(editionId, page) else emptyList()
        _voice.value = if (pieces.isEmpty()) null else podcasts.voice(editionId)
    }

    private fun play(from: LineId, rate: Float) {
        stopAudio()
        // The last piece starting at or before the line; its time within that piece.
        val index = pieces.indexOfLast { it.firstLine <= from.line }.coerceAtLeast(0)
        val start = pieces[index].starts.getOrNull(from.line - pieces[index].firstLine) ?: 0.0
        playing = from
        piece = index
        reported = from.line - 1
        this.rate = rate
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
class MediaPlayerAudio(context: Context) : PodcastAudio {
    override var listener: PodcastAudio.Listener? = null
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) listener?.onPosition(p.currentPosition.toLong())
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun play(file: File, fromMs: Long, speed: Float) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            // A local file of a couple of minutes: quick enough to prepare in place.
            p.prepare()
            p.setOnCompletionListener { if (player === it) main.post { listener?.onEnded() } }
            p.setOnErrorListener { mp, _, _ ->
                if (player === mp) main.post { listener?.onError() }
                true
            }
            if (fromMs > 0) p.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
            p.playbackParams = p.playbackParams.setSpeed(speed)
            p.start()
        } catch (e: Exception) {
            p.release()
            main.post { listener?.onError() }
            return
        }
        player = p
        main.post(tick)
    }

    override fun stop() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
    }

    override fun release() = stop()

    private companion object {
        const val TICK_MS = 50L
    }
}
