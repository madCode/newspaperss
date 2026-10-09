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
    fun aStopNothingAskedForIsLogged() {
        audio.play(piece, 0, 1f)
        idle(1_000)
        players.last().pause()
        idle(100)

        assertEquals(listOf("Playing: 4-59.m4a stopped at 1000ms of 120000ms, unasked"), logged)
    }

    @Test
    fun aPieceThatPlaysToItsEndGoesStraightOnToTheNext() {
        audio.play(piece, 119_000, 1f)
        idle(999)
        assertEquals(emptyList<String>(), heard)
        idle(2)

        assertEquals(listOf("ended"), heard)
        assertEquals(listOf("Playing: 4-59.m4a ended after 1000ms of its 1000ms"), logged)
    }

    @Test
    fun aPlayerThatSaysItHasFinishedEarlyIsGivenTimeForItsLastWords() {
        audio.play(piece, 118_000, 1f)
        idle(1_000)
        // As a phone does with the file's last second still in its output buffer.
        players.last().pause()
        player.invokeCompletionListener()

        assertEquals(listOf("Playing: 4-59.m4a ended after 1000ms of its 2000ms; waiting 1000ms for the rest"), logged)
        idle(990)
        assertEquals(emptyList<String>(), heard)
        idle(20)
        assertEquals(listOf("ended"), heard)
    }

    @Test
    fun theWaitForAPlayerThatEndedFarTooSoonIsCapped() {
        audio.play(piece, 0, 1f)
        idle(1_000)
        players.last().pause()
        player.invokeCompletionListener()
        idle(3_010)

        assertTrue(logged.single(), logged.single().endsWith("; waiting 3000ms for the rest"))
        assertEquals(listOf("ended"), heard)
    }

    @Test
    fun aStopWhileWaitingForTheEndMeansNoEnd() {
        audio.play(piece, 118_000, 1f)
        idle(1_000)
        players.last().pause()
        player.invokeCompletionListener()
        audio.stop()
        idle(2_000)

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aFinishedPieceIsLetGoASecondAfterTheNextStarts() {
        audio.play(piece, 119_000, 1f)
        idle(1_010)
        val finished = players.last()
        audio.play(piece, 0, 1f)

        // Still sounding its last moment while the next begins.
        assertTrue(shadowOf(finished).state != ShadowMediaPlayer.State.END)
        idle(1_010)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(finished).state)
        assertTrue(players.last().isPlaying)
    }

    @Test
    fun theLastPieceOfAnArticleGetsItsLastMomentTooButReleaseEndsEverything() {
        audio.play(piece, 119_000, 1f)
        idle(1_010)
        val finished = players.last()
        // The article's end: Listen stops rather than playing on.
        audio.stop()
        assertTrue(shadowOf(finished).state != ShadowMediaPlayer.State.END)

        audio.release()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(finished).state)
    }

    @Test
    fun aPieceStoppedPartWayIsLetGoAtOnce() {
        audio.play(piece, 0, 1f)
        idle(1_000)
        val playing = players.last()
        audio.play(piece, 5_000, 1f)

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(playing).state)
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
