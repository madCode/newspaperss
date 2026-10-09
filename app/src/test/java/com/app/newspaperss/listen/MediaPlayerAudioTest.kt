package com.app.newspaperss.listen

import android.media.MediaPlayer
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/** The podcast log's view of playback: what Android's player did that nothing here asked for. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MediaPlayerAudioTest {
    @get:Rule val tmp = TemporaryFolder()

    private val logged = mutableListOf<String>()
    private val players = mutableListOf<MediaPlayer>()
    private val audio = MediaPlayerAudio(logged::add) { MediaPlayer().also { players += it } }
    private val heard = mutableListOf<String>()

    private val piece by lazy {
        tmp.newFile("4-59.m4a").also { ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(it.path), ShadowMediaPlayer.MediaInfo(120_000, 0)) }
    }

    private val player get() = shadowOf(players.last())

    @Before
    fun listen() {
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(ms: Long) {}
            override fun onEnded() { heard += "ended" }
            override fun onError() { heard += "error" }
        }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun playingOnWithTheClockLogsNothing() {
        audio.play(piece, 0, 1f)
        idle(5_000)

        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun audioThatSkipsAheadIsLoggedWithWhereItWentFrom() {
        audio.play(piece, 0, 1f)
        idle(1_000)
        // As a player passing over a second and a half of the file would.
        player.setCurrentPosition(1_500)
        idle(100)

        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().startsWith("Playing: 4-59.m4a went from 1000ms to 2550ms in 50ms"))
    }

    @Test
    fun skipsStopBeingLoggedOnceThereAreEnoughToGoOn() {
        audio.play(piece, 0, 1f)
        idle(100)
        repeat(40) {
            player.setCurrentPosition(1_000 * (it + 1))
            idle(50)
        }

        assertEquals(30, logged.size)
        assertTrue(logged.last(), logged.last().endsWith("; no more of these until the app restarts"))
    }

    @Test
    fun aStopNothingAskedForIsLoggedAndTheEndOfThePieceSaysHowFarItGot() {
        audio.play(piece, 0, 1f)
        idle(1_000)
        players.last().pause()
        idle(100)
        assertEquals(listOf("Playing: 4-59.m4a stopped at 1000ms of 120000ms, unasked"), logged)

        logged.clear()
        audio.play(piece, 119_900, 1f)
        idle(1_000)
        assertEquals(listOf("ended"), heard)
        // The last tick before the end, against the file's length: not "stopped, unasked" as well.
        assertEquals(1, logged.size)
        assertTrue(logged.single(), Regex("Playing: 4-59\\.m4a ended at 1199\\d\\dms of 120000ms").matches(logged.single()))
    }

    @Test
    fun aFasterSpeedPlaysToo() {
        audio.play(piece, 0, 1.5f)
        idle(1_000)

        assertTrue(players.last().isPlaying)
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun theInfoAndErrorsAndroidsPlayerSendsAreLoggedByName() {
        audio.play(piece, 0, 1f)
        idle(100)
        player.invokeInfoListener(MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING, 0)
        player.invokeInfoListener(12_345, 7)
        player.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)
        // A tick due after the failure mustn't ask the failed player anything that throws.
        idle(200)

        assertEquals(
            listOf("Playing: 4-59.m4a says audio not playing (0)", "Playing: 4-59.m4a says 12345 (7)", "Playing: 4-59.m4a failed, 1 (-1004)"),
            logged.take(3),
        )
        assertEquals(listOf("error"), heard)
    }
}
