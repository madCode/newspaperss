package com.app.newspaperss.listen

import android.os.ParcelFileDescriptor
import android.os.Process
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
 * Two questions about a Media3 playlist that only a device can answer.
 *
 * The first is whether STATE_ENDED arrives before the AudioTrack has drained. If it does, the
 * stop() that follows it drops whatever was still buffered, and the end of an article's last piece
 * is cut off the way every piece used to be.
 *
 * The second is whether clipping the AAC encoder's priming delay off the front of each piece keeps
 * the playlist on one AudioTrack -- clipping is a reason a player may stop joining items gaplessly.
 *
 * Both read AudioFlinger's own figure for audio written but not yet heard, because
 * MediaPlayer/ExoPlayer position clocks do not report it (getTimestamp() tracks the read position
 * on an emulator, so it always says nothing is outstanding).
 */
@RunWith(AndroidJUnit4::class)
class Media3EndAndClipTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** AudioFlinger's view of this process's track: audio written but not yet rendered. */
    private data class Buffered(val atMs: Long, val trackId: String, val readyMs: Long, val sizeMs: Long, val underruns: Long)

    @Test
    fun whetherTheLastItemHasDrainedWhenThePlaylistSaysItEnded() {
        val first = encode("end-1.m4a", SECONDS)
        val second = encode("end-2.m4a", SECONDS)
        for (run in 1..RUNS) {
            settle()
            val samples = mutableListOf<Buffered>()
            var endedAt = 0L
            val ended = CountDownLatch(1)
            var player: ExoPlayer? = null
            instrumentation.runOnMainSync {
                val p = ExoPlayer.Builder(context).build()
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && ended.count > 0) {
                            endedAt = SystemClock.elapsedRealtime()
                            ended.countDown()
                        }
                    }
                })
                p.setMediaItems(listOf(item(first, 0), item(second, 0)))
                p.prepare()
                player = p
            }
            val p = player!!
            val sampler = sample(samples)
            instrumentation.runOnMainSync { p.play() }
            val startedAt = SystemClock.elapsedRealtime()
            val reached = ended.await((SECONDS * 2 * 1000).toLong() + GRACE_MS, TimeUnit.MILLISECONDS)
            // Deliberately neither stopped nor released here: that is what lets the drain be seen.
            Thread.sleep(AFTER_MS)
            sampler.interrupt()
            sampler.join()
            instrumentation.runOnMainSync { p.release() }

            // A single sample after STATE_ENDED that still holds audio proves the buffer had not
            // drained when it fired, whatever the sampling interval.
            val after = samples.filter { it.atMs > endedAt }
            val outstanding = after.maxOfOrNull { it.readyMs } ?: -1L
            val drainedAt = after.firstOrNull { it.readyMs == 0L }?.atMs ?: -1L
            val lastBusy = after.filter { it.readyMs > 0 }.maxOfOrNull { it.atMs } ?: -1L
            val gaps = samples.zipWithNext { a, b -> b.atMs - a.atMs }

            Log.i(TAG, "--- drain at STATE_ENDED, run #$run ---")
            Log.i(TAG, "  reached the end=$reached after ${endedAt - startedAt}ms")
            Log.i(TAG, "  samples ${samples.size}, ${gaps.minOrNull()}-${gaps.maxOrNull()}ms apart (a dumpsys read)")
            Log.i(TAG, "  most still buffered after STATE_ENDED: ${outstanding}ms" +
                if (outstanding > 0) "  => stop() then WOULD have dropped it" else "  => already drained")
            Log.i(TAG, "  last sample still holding audio: ${if (lastBusy < 0) "none" else "${lastBusy - endedAt}ms after ENDED"}")
            Log.i(TAG, "  first sample fully drained: ${if (drainedAt < 0) "never seen" else "${drainedAt - endedAt}ms after ENDED"}")
            Log.i(TAG, "  underruns over the run: ${samples.maxOfOrNull { it.underruns } ?: -1}; " +
                "AudioTracks used ${samples.map { it.trackId }.distinct().size}")
            for (s in samples.filter { it.atMs >= endedAt - 700 && it.atMs <= endedAt + 900 }) {
                Log.i(TAG, "    ${s.atMs - endedAt}ms from ENDED: buffered ${s.readyMs}ms of ${s.sizeMs}ms")
            }
        }
    }

    @Test
    fun whetherClippingThePrimingDelayKeepsTheJoinGapless() {
        val first = encode("clip-1.m4a", SECONDS)
        val second = encode("clip-2.m4a", SECONDS)
        // Unclipped first, so the pair's wall time can be compared on the same device and run.
        for (clipMs in listOf(0L, PRIMING_MS)) {
            for (run in 1..RUNS) {
                settle()
                val samples = mutableListOf<Buffered>()
                var endedAt = 0L
                val switchedAt = longArrayOf(-1L)
                val ended = CountDownLatch(1)
                var player: ExoPlayer? = null
                instrumentation.runOnMainSync {
                    val p = ExoPlayer.Builder(context).build()
                    p.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && switchedAt[0] < 0) {
                                switchedAt[0] = SystemClock.elapsedRealtime()
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED && ended.count > 0) {
                                endedAt = SystemClock.elapsedRealtime()
                                ended.countDown()
                            }
                        }
                    })
                    p.setMediaItems(listOf(item(first, clipMs), item(second, clipMs)))
                    p.prepare()
                    player = p
                }
                val p = player!!
                val sampler = sample(samples)
                instrumentation.runOnMainSync { p.play() }
                val startedAt = SystemClock.elapsedRealtime()
                val reached = ended.await((SECONDS * 2 * 1000).toLong() + GRACE_MS, TimeUnit.MILLISECONDS)
                sampler.interrupt()
                sampler.join()
                instrumentation.runOnMainSync { p.release() }

                // The buffer running dry mid-playlist is what a broken join would look like.
                val mid = samples.filter { it.atMs > startedAt + 500 && it.atMs < endedAt - 500 }
                val dry = mid.count { it.readyMs == 0L }
                Log.i(TAG, "--- clipping ${clipMs}ms off each piece, run #$run ---")
                Log.i(TAG, "  the pair took ${endedAt - startedAt}ms (reached the end=$reached)")
                Log.i(TAG, "  joined at ${if (switchedAt[0] < 0) -1 else switchedAt[0] - startedAt}ms")
                Log.i(TAG, "  mid-playlist samples ${mid.size}, of which empty-buffer $dry; " +
                    "underruns ${samples.maxOfOrNull { it.underruns } ?: -1}")
                // Whether the join kept one track is the id either side of it, not the ids seen
                // over the run: the run before leaves its own track draining for a moment.
                val before = samples.lastOrNull { it.atMs < switchedAt[0] - 150 }?.trackId
                val after = samples.firstOrNull { it.atMs > switchedAt[0] + 150 }?.trackId
                Log.i(TAG, "  track id ${before} before the join, ${after} after" +
                    if (before != null && before == after) "  => one track across the join" else "  => the join changed track")
                for (id in samples.map { it.trackId }.distinct()) {
                    val own = samples.filter { it.trackId == id }
                    Log.i(TAG, "    track $id seen ${own.first().atMs - startedAt}..${own.last().atMs - startedAt}ms")
                }
            }
        }
    }

    /** Waits for the previous run's track to go, so its id can't be read as this run's. */
    private fun settle() {
        val until = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < until) {
            if (buffered() == null) return
            Thread.sleep(50)
        }
        Log.w(TAG, "a track of ours was still there when the next run began")
    }

    private fun item(file: File, clipMs: Long): MediaItem {
        val builder = MediaItem.Builder().setUri(file.toURI().toString())
        if (clipMs > 0) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clipMs).build()
            )
        }
        return builder.build()
    }

    /** Polls AudioFlinger as fast as a dumpsys read allows, timestamping each answer. */
    private fun sample(into: MutableList<Buffered>): Thread {
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val got = buffered() ?: continue
                into += got
            }
        }
        thread.start()
        return thread
    }

    /** This process's active track row, or null while nothing of ours is playing. */
    private fun buffered(): Buffered? {
        val pid = Process.myPid().toString()
        val at = SystemClock.elapsedRealtime()
        val out = shell("dumpsys media.audio_flinger")
        for (line in out.lineSequence()) {
            val f = line.trim().split(Regex(" +"))
            // Id Active pid/ uid Session Port S Flags Format Chn SRate ... Server FrmCnt FrmRdy
            if (f.size < 25 || f[1] != "yes" || f[2].trimEnd('/') != pid) continue
            val rate = f[10].toLongOrNull() ?: continue
            if (rate <= 0) continue
            val size = f[21].toLongOrNull() ?: continue
            val ready = f[22].toLongOrNull() ?: continue
            return Buffered(at, f[0], ready * 1000 / rate, size * 1000 / rate, f[24].toLongOrNull() ?: -1L)
        }
        return null
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

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
        const val TAG = "Media3EndClip"
        const val RATE = 24_000
        const val BLOCK = 4_096
        const val SECONDS = 6.0
        const val RUNS = 3

        /** The AAC encoder's delay, as the podcast log's "+2 frames" per piece: 2048 samples. */
        const val PRIMING_MS = 85L

        const val GRACE_MS = 6_000L

        /** Sampled past STATE_ENDED, to watch what was left drain out. */
        const val AFTER_MS = 1_500L
    }
}
