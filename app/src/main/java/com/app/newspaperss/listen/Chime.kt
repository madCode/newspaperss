package com.app.newspaperss.listen

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.app.newspaperss.R

/**
 * The pause between articles: a moment's quiet, a soft breath of sound with a page turning, and
 * the next article once it has settled, so a listener can let one go before the next begins.
 */
interface Chime {
    /** Plays it, then calls [done] on the main thread. [stop] cancels both. */
    fun play(done: () -> Unit)

    fun stop()

    fun release()

    companion object {
        /** No sound: the next article starts at once. */
        val NONE = object : Chime {
            override fun play(done: () -> Unit) = done()
            override fun stop() {}
            override fun release() {}
        }
    }
}

/** [Chime] through MediaPlayer. A chime that can't play is passed over: Listen mustn't stall on it. */
class MediaPlayerChime(context: Context) : Chime {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    /** Bumped by each play and stop, so a finish posted for an earlier one is dropped. */
    private var plays = 0

    override fun play(done: () -> Unit) {
        stop()
        val play = plays
        var finished = false
        val finish = {
            if (plays == play && !finished) {
                finished = true
                stop()
                done()
            }
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val p = try {
            MediaPlayer.create(app, R.raw.listen_chime, attributes, 0)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            main.post(finish)
            return
        }
        player = p
        p.setOnCompletionListener { main.post(finish) }
        p.setOnErrorListener { _, _, _ ->
            main.post(finish)
            true
        }
        try {
            p.start()
        } catch (e: Exception) {
            main.post(finish)
        }
        // A player that never says it's done mustn't leave Listen silent.
        main.postDelayed(finish, LONGEST_MS)
    }

    override fun stop() {
        plays++
        player?.release()
        player = null
    }

    override fun release() = stop()

    private companion object {
        /** The sound, its quiet included, is 4 s long. */
        const val LONGEST_MS = 6_000L
    }
}
