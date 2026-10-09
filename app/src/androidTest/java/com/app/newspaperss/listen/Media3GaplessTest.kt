package com.app.newspaperss.listen

import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Whether two pieces in a Media3 playlist play whole, with no gap and nothing cut off.
 *
 * MediaPlayer needs one player per piece, so the piece that just finished is released while its
 * last moment is still being output. One ExoPlayer over a playlist keeps a single decoder and
 * AudioTrack across the join, so there is nothing to release in between. This measures the join:
 * where the first piece's position had got to when the second began, and what the two together
 * cost against the sum of their lengths.
 */
@RunWith(AndroidJUnit4::class)
class Media3GaplessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Where the playlist's clock was, and which piece it was on. */
    private data class Sample(val atMs: Long, val positionMs: Long, val durationMs: Long, val index: Int)

    @Test
    fun twoPiecesInAPlaylistPlayWhole() {
        val first = encode("gapless-1.m4a", SECONDS)
        val second = encode("gapless-2.m4a", SECONDS)
        // Repeated: one run can't tell a clean join from a lucky one.
        for (i in 1..RUNS) playOnce(first, second, i)
    }

    private fun playOnce(first: File, second: File, run: Int) {

        var player: ExoPlayer? = null
        val done = CountDownLatch(1)
        // What the first piece's clock read when the second item took over: the audio lost at the join.
        var transitionAtMs = -1L
        var transitionPositionMs = -1L
        var firstDurationMs = -1L
        val startedAt: Long

        instrumentation.runOnMainSync {
            val p = ExoPlayer.Builder(context).build()
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (transitionAtMs < 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        transitionAtMs = SystemClock.elapsedRealtime()
                        // Position has already moved to the new item, so the first piece's length
                        // is what it should have reached.
                        transitionPositionMs = p.currentPosition
                        Log.i(TAG, "transition: reason=$reason position now ${p.currentPosition}ms")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.i(TAG, "state=$state")
                    if (state == Player.STATE_ENDED) done.countDown()
                }
            })
            p.setMediaItems(listOf(MediaItem.fromUri(first.toURI().toString()), MediaItem.fromUri(second.toURI().toString())))
            p.prepare()
            player = p
        }
        val p = player!!
        instrumentation.runOnMainSync { p.play() }
        startedAt = SystemClock.elapsedRealtime()

        // ExoPlayer's getters belong to its own thread, so every sample hops to the main looper.
        // 25 ms apart: often enough to place the join, seldom enough not to flood that looper.
        val samples = mutableListOf<Sample>()
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                var position = -1L
                var duration = -1L
                var index = -1
                instrumentation.runOnMainSync {
                    position = p.currentPosition
                    duration = p.duration
                    index = p.currentMediaItemIndex
                }
                samples += Sample(SystemClock.elapsedRealtime(), position, duration, index)
                try { Thread.sleep(25) } catch (e: InterruptedException) { return@Thread }
            }
        }
        sampler.start()
        val ended = done.await((SECONDS * 2 * 1000).toLong() + 6_000, TimeUnit.MILLISECONDS)
        val endedAt = SystemClock.elapsedRealtime()
        sampler.interrupt()
        sampler.join()
        instrumentation.runOnMainSync { p.release() }

        // Where the first piece's clock stopped, against its own length: the audio lost at the join.
        val onFirst = samples.filter { it.index == 0 }
        firstDurationMs = onFirst.maxOfOrNull { it.durationMs } ?: -1L
        val lastOnFirst = onFirst.maxOfOrNull { it.positionMs } ?: -1L
        val switchedAt = samples.firstOrNull { it.index == 1 }?.atMs ?: -1L

        Log.i(TAG, "--- Media3 run #$run: one ExoPlayer over a playlist of two ${SECONDS}s pieces ---")
        Log.i(TAG, "  reached the end=$ended; the pair took ${endedAt - startedAt}ms")
        Log.i(TAG, "  first piece's own duration ${firstDurationMs}ms")
        Log.i(TAG, "  last position seen on the first piece ${lastOnFirst}ms" +
            " => ${firstDurationMs - lastOnFirst}ms of it unaccounted for (one sample is ${SAMPLE_MS}ms)")
        Log.i(TAG, "  moved to the second piece ${switchedAt - startedAt}ms in; " +
            "a join that loses nothing is ~${firstDurationMs}ms")
        Log.i(TAG, "  transition callback said position ${transitionPositionMs}ms at ${transitionAtMs - startedAt}ms")
        for (s in samples.filter { it.atMs in (switchedAt - 150)..(switchedAt + 150) }) {
            Log.i(TAG, "    t=${s.atMs - startedAt}ms item=${s.index} position=${s.positionMs} duration=${s.durationMs}")
        }
    }

    private fun encode(name: String, seconds: Double): File {
        val file = File(context.cacheDir, name)
        file.delete()
        AacEncoder.open(file, RATE).use { sink ->
            val total = (RATE * seconds).toInt()
            var done = 0
            while (done < total) {
                val n = minOf(BLOCK, total - done)
                val block = FloatArray(n) { i -> (sin(2 * PI * 440 * (done + i) / RATE) * 0.3).toFloat() }
                sink.write(block)
                done += n
            }
        }
        return file
    }

    private companion object {
        const val TAG = "Media3Gapless"
        const val RATE = 24_000
        const val SAMPLE_MS = 25
        const val RUNS = 5
        const val BLOCK = 4_096
        const val SECONDS = 6.0
    }
}
