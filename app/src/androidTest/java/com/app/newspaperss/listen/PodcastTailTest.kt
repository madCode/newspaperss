package com.app.newspaperss.listen

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
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
 * How much of a piece is still unheard when MediaPlayer says it has finished.
 *
 * Podcast mode plays an article as a series of ~2-minute AAC pieces, releasing each player when
 * onCompletion fires. On the phone the last 0.2-1 s of every piece was cut off, so onCompletion
 * must arrive before the audio has been rendered. getTimestamp() says which media position the
 * audio output is really sounding, so (duration - that) at completion is the unheard tail,
 * measured rather than inferred.
 *
 * Not a pass/fail test of the app: a probe that prints numbers for a given device, since the
 * tail is the audio HAL's buffering and an emulator's is not a phone's.
 */
@RunWith(AndroidJUnit4::class)
class PodcastTailTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)

    /** One sample of where the file is being read and where it is being heard. */
    private data class Sample(val atMs: Long, val positionMs: Int, val renderedMs: Long, val playing: Boolean)

    @Test
    fun theTailLeftUnheardWhenAPieceSaysItHasFinished() {
        val file = encode(SECONDS)
        Log.i(TAG, "file ${file.name}: ${file.length()} bytes for ${SECONDS}s of $RATE Hz mono")
        // Each run is the same piece played a different way, so only the speed handling differs.
        // Repeated: a single run can't tell a code path apart from the emulator's own jitter.
        for (run in listOf(
            "no PlaybackParams (PR's path at 1.0)" to null,
            "PlaybackParams speed 1.0 (main's path)" to 1.0f,
            "PlaybackParams speed 1.5" to 1.5f,
        )) {
            val late = mutableListOf<Long>()
            val lags = mutableListOf<Long>()
            for (i in 1..RUNS) {
                val (lateMs, lagMs) = play(file, "${run.first} #$i", run.second)
                late += lateMs
                lags += lagMs
            }
            Log.i(TAG, "=== ${run.first}: completion late by ${late.joinToString("/")}ms " +
                "(mean ${late.average().toLong()}ms); worst rendered-behind-position ${lags.max()}ms")
        }
    }

    /**
     * Plays [file] whole the way MediaPlayerAudio does, sampling position and rendered position
     * until well after completion. The player is deliberately not released at completion: that is
     * what lets the tail be seen draining.
     */
    private fun play(file: File, label: String, params: Float?): Pair<Long, Long> {
        var player: MediaPlayer? = null
        val ended = CountDownLatch(1)
        var endedAt = 0L
        instrumentation.runOnMainSync {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(file.path)
            p.prepare()
            p.setOnCompletionListener {
                endedAt = SystemClock.elapsedRealtime()
                ended.countDown()
            }
            player = p
        }
        val p = player!!
        val durationMs = p.duration
        val speed = params ?: 1.0f
        val samples = mutableListOf<Sample>()

        // Sampled off the main thread, so a busy looper can't hide the moment of completion.
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                samples += sample(p)
                try {
                    Thread.sleep(SAMPLE_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }

        val startedAt: Long
        instrumentation.runOnMainSync {
            if (params != null) p.playbackParams = PlaybackParams().setSpeed(params)
            p.start()
        }
        startedAt = SystemClock.elapsedRealtime()
        sampler.start()

        val fired = ended.await((durationMs / speed).toLong() + GRACE_MS, TimeUnit.MILLISECONDS)
        // Kept playing past completion on purpose, to watch the unrendered tail drain out.
        val atCompletion = sample(p)
        Thread.sleep(AFTER_MS)
        sampler.interrupt()
        sampler.join()

        val wallMs = endedAt - startedAt
        val expectedMs = (durationMs / speed).toLong()
        // Positive means completion came after the audio should have run out: the phone's bug is
        // the other sign. Checked against the clock, as the phone's log was.
        val lateMs = wallMs - expectedMs
        // How far the rendered position ever fell behind the read position. On a real output this
        // is the buffered audio not yet heard; if it stays at zero the timestamp is not a separate
        // clock here, and no tail can be measured with it.
        val worstLagMs = samples.filter { it.renderedMs >= 0 && it.playing }
            .maxOfOrNull { it.positionMs - it.renderedMs }?.toLong() ?: -1L
        val renderedAtEnd = atCompletion.renderedMs
        // How long after completion the output took to sound the position it had already announced.
        val drainedAfterMs = samples.filter { it.atMs > endedAt && it.renderedMs >= durationMs - FRAME_MS }
            .minOfOrNull { it.atMs - endedAt } ?: -1L

        Log.i(TAG, "--- $label ---")
        Log.i(TAG, "  duration ${durationMs}ms, speed $speed, completion fired=$fired")
        Log.i(TAG, "  wall clock to completion ${wallMs}ms vs ${expectedMs}ms expected: " +
            if (lateMs >= 0) "${lateMs}ms LATE" else "${-lateMs}ms EARLY")
        Log.i(TAG, "  rendered position fell at most ${worstLagMs}ms behind the read position" +
            if (worstLagMs <= 1L) " -- so getTimestamp() is not a separate clock on this device" else "")
        Log.i(TAG, "  at completion: position ${atCompletion.positionMs}ms, rendered ${renderedAtEnd}ms" +
            if (renderedAtEnd >= 0) " => ${durationMs - renderedAtEnd}ms UNHEARD" else " (no timestamp)")
        Log.i(TAG, "  tail finished rendering ${drainedAfterMs}ms after completion")
        Log.i(TAG, "  active playback configs at completion: ${audio.activePlaybackConfigurations.size}")
        val tail = if (TRACE) samples.filter { it.atMs >= endedAt - 200 }.take(20) else emptyList()
        for (s in tail) {
            Log.i(TAG, "    t=${s.atMs - startedAt}ms (${s.atMs - endedAt} from end) pos=${s.positionMs} rendered=${s.renderedMs} playing=${s.playing}")
        }
        instrumentation.runOnMainSync { p.release() }
        return lateMs to worstLagMs
    }

    private fun sample(p: MediaPlayer): Sample {
        val at = SystemClock.elapsedRealtime()
        // Either can throw on a player that has moved on; a failed sample must not end the run.
        val position = try { p.currentPosition } catch (e: Exception) { -1 }
        val playing = try { p.isPlaying } catch (e: Exception) { false }
        val rendered = try { p.timestamp?.anchorMediaTimeUs?.div(1000) ?: -1L } catch (e: Exception) { -1L }
        return Sample(at, position, rendered, playing)
    }

    /** [SECONDS] of a quiet tone through the app's own encoder, so the file is the one Listen plays. */
    private fun encode(seconds: Double): File {
        val file = File(context.cacheDir, "tail-probe.m4a")
        file.delete()
        AacEncoder.open(file, RATE).use { sink ->
            val total = (RATE * seconds).toInt()
            var done = 0
            while (done < total) {
                val n = minOf(BLOCK, total - done)
                val block = FloatArray(n) { i ->
                    (sin(2 * PI * 440 * (done + i) / RATE) * 0.3).toFloat()
                }
                sink.write(block)
                done += n
            }
            Log.i(TAG, "encoded: ${sink.report}")
        }
        return file
    }

    private companion object {
        const val TAG = "PodcastTail"
        const val RATE = 24_000
        const val BLOCK = 4_096
        const val SECONDS = 6.0
        const val SAMPLE_MS = 10L

        /** Runs per code path, so jitter can be told from a real difference. */
        const val RUNS = 5

        /** Per-sample lines; off once the shape of a run is known, since they dominate the log. */
        const val TRACE = false

        /** Waited past the expected end before giving up on completion. */
        const val GRACE_MS = 4_000L

        /** Sampled past completion, to see the tail drain. */
        const val AFTER_MS = 2_000L

        /** An AAC frame at 24 kHz; the rendered position moves a frame at a time. */
        const val FRAME_MS = 1024L * 1000 / RATE
    }
}
