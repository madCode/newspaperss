package com.app.newspaperss.listen

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import java.io.File

/** Plays a podcast's audio, a list of files one after another, telling how far in it is as it goes. */
interface PodcastAudio {
    var listener: Listener?

    /** Plays [files] in turn from [fromMs] into the one at [index], at [speed] (1.0 normal), stopping whatever was playing. */
    fun play(files: List<File>, index: Int, fromMs: Long, speed: Float)

    fun stop()

    fun release()

    /** Called on the main thread. */
    interface Listener {
        /** Every few tens of milliseconds while playing: which file, and how far into it in milliseconds. */
        fun onPosition(index: Int, ms: Long)

        /** The last file has been heard to its end. */
        fun onEnded()

        /** The file at [index] can't be played; [code] says why, for the log. */
        fun onError(index: Int, code: String)
    }
}

/**
 * [PodcastAudio] through one Media3 player given the whole list, so the audio runs on from file
 * to file without stopping. A player per file has to be stopped to start the next, and Android's
 * MediaPlayer says it has finished while its last moment is still to be heard (over Bluetooth,
 * most). Audio focus, the lock screen and headphones are [ListenService]'s.
 *
 * @param newPlayer makes the player, once: an ExoPlayer, or a fake in tests.
 */
class Media3Audio(private val newPlayer: () -> Player) : PodcastAudio {
    override var listener: PodcastAudio.Listener? = null
    private val main = Handler(Looper.getMainLooper())

    // Kept from play to play: making and releasing one takes long enough to hold up the screen,
    // and every pause, skip and change of speed is a new play.
    private var made: Player? = null
    private var playing = false

    /** Bumped by each play and stop, so news posted for an earlier one is dropped. */
    private var plays = 0

    private val tick = object : Runnable {
        override fun run() {
            val p = made?.takeIf { playing } ?: return
            val play = plays
            if (p.isPlaying) listener?.onPosition(p.currentMediaItemIndex, p.currentPosition)
            // A new play in answer has started its own ticks.
            if (plays == play) main.postDelayed(this, TICK_MS)
        }
    }

    /** Posted rather than told at once: the speaker may stop the player in answer. */
    private fun later(call: PodcastAudio.Listener.() -> Unit) {
        val play = plays
        main.post { if (plays == play) listener?.call() }
    }

    private fun player(): Player = made ?: newPlayer().also { p ->
        made = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playing && playbackState == Player.STATE_ENDED) later { onEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!playing) return
                // The next file fails to open only once the one before has been played to its
                // end, and before the player moves on: it's that file that failed, not this one.
                val heard = p.duration > 0 && p.currentPosition >= p.duration - END_MS
                val failed = (p.currentMediaItemIndex + if (heard) 1 else 0).coerceAtMost(p.mediaItemCount - 1)
                later { onError(failed, error.errorCodeName) }
            }
        })
    }

    override fun play(files: List<File>, index: Int, fromMs: Long, speed: Float) {
        stop()
        val p = player()
        playing = true
        p.setMediaItems(files.map(::item), index, fromMs)
        p.playbackParameters = PlaybackParameters(speed)
        p.prepare()
        p.play()
        main.post(tick)
    }

    /**
     * A piece without the silence Android's AAC encoder puts before the audio. Unclipped, it's a
     * pause at every join, and every position runs that far ahead of the line starts kept with
     * the piece, which count only the speech fed in.
     */
    private fun item(file: File) = MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(ENCODER_DELAY_MS).build())
        .build()

    override fun stop() {
        plays++
        main.removeCallbacks(tick)
        if (!playing) return
        playing = false
        made?.stop()
    }

    override fun release() {
        stop()
        made?.release()
        made = null
    }

    companion object {
        const val TICK_MS = 50L

        /** This close to a file's end, it has been heard. */
        private const val END_MS = 500L

        /**
         * The encoder's lead-in: 2048 samples, two frames, at the 24 kHz Kokoro speaks at. The
         * file doesn't record it (MediaMuxer writes no edit list), so the player can't skip it
         * by itself.
         */
        private const val ENCODER_DELAY_MS = 85L
    }
}
