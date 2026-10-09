package com.app.newspaperss.listen

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The pieces handed to Media3 as one list, and what the speaker hears of them. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class Media3AudioTest {
    /** A player that plays nothing: the test says where it is and when it ends or fails. */
    @OptIn(UnstableApi::class)
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var items = emptyList<MediaItem>()
        var index = 0
        var positionMs = 0L
        var speed = 1f
        var playing = false
        var prepared = false
        var ended = false
        var error: PlaybackException? = null
        var released = false
        var stops = 0

        override fun getState(): State {
            val state = State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackParameters(PlaybackParameters(speed))
                .setPlayerError(error)
            if (items.isEmpty()) return state.setPlaybackState(Player.STATE_IDLE).build()
            return state
                .setPlaylist(items.mapIndexed { i, item -> MediaItemData.Builder(i).setMediaItem(item).setDurationUs(120_000_000).build() })
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs(positionMs)
                .setPlaybackState(
                    when {
                        error != null || !prepared -> Player.STATE_IDLE
                        ended -> Player.STATE_ENDED
                        else -> Player.STATE_READY
                    },
                )
                .build()
        }

        override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            items = mediaItems.toList()
            index = startIndex
            positionMs = startPositionMs
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            prepared = true
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            speed = playbackParameters.speed
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            stops++
            prepared = false
            error = null
            ended = false
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> {
            released = true
            return Futures.immediateVoidFuture()
        }

        fun at(index: Int, ms: Long) {
            this.index = index
            positionMs = ms
            invalidateState()
        }

        fun end() {
            ended = true
            invalidateState()
        }

        fun fail() {
            error = PlaybackException("damaged", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
            invalidateState()
        }
    }

    private val players = mutableListOf<FakePlayer>()
    private val logged = mutableListOf<String>()
    private val audio = Media3Audio(logged::add) { FakePlayer().also { players += it } }
    private val heard = mutableListOf<String>()
    private val player get() = players.last()
    private val pieces = listOf(File("0-0.m4a"), File("0-28.m4a"), File("0-59.m4a"))

    @Before
    fun listen() {
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(ms: Long) { heard += "at $ms" }
            override fun onNext() { heard += "next" }
            override fun onEnded() { heard += "ended" }
            override fun onError() { heard += "error" }
        }
    }

    private fun idle(ms: Long = 60) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun theWholeListIsGivenFromThePieceAndPlaceAskedForAtTheSpeedAsked() {
        audio.play(pieces, 1, 3_000, 1.5f)
        idle()

        assertEquals(pieces.map { Uri.fromFile(it) }, player.items.map { it.localConfiguration!!.uri })
        assertEquals(1, player.index)
        assertEquals(1.5f, player.speed)
        assertTrue(player.prepared && player.playing)
        assertTrue(heard.first(), heard.first().startsWith("at 3"))
    }

    @Test
    fun runningOnIntoTheNextPieceIsToldOnceAndPositionsAreWithinIt() {
        audio.play(pieces, 0, 0, 1f)
        idle()
        player.at(1, 0)
        idle()
        player.at(1, 7_000)
        idle()

        assertEquals(1, heard.count { it == "next" })
        assertTrue(heard.toString(), heard.indexOf("next") < heard.indexOfFirst { it.startsWith("at 70") })
        assertEquals(listOf("Playing: on to 0-28.m4a"), logged)
    }

    @Test
    fun theEndOfTheLastPieceEndsIt() {
        audio.play(pieces, 2, 0, 1f)
        idle()
        player.end()
        idle()

        assertEquals("ended", heard.last())
        assertEquals("Playing: 0-59.m4a ended", logged.last())
    }

    @Test
    fun aPieceThatCantBePlayedSaysSo() {
        audio.play(pieces, 0, 0, 1f)
        idle()
        player.at(0, 5_000)
        player.fail()
        idle()

        assertEquals("error", heard.last())
        assertTrue(heard.none { it == "next" })
        assertEquals("Playing: 0-0.m4a failed, ERROR_CODE_PARSING_CONTAINER_MALFORMED", logged.last())
    }

    @Test
    fun aNextPieceThatCantBeOpenedIsTheOneThatFailed() {
        audio.play(pieces, 0, 0, 1f)
        idle()
        // The player reads the next file only once this one is played out, and fails before moving on.
        player.at(0, 119_800)
        player.fail()
        idle()

        assertEquals(listOf("next", "error"), heard.filter { !it.startsWith("at") })
        assertEquals("Playing: 0-28.m4a failed, ERROR_CODE_PARSING_CONTAINER_MALFORMED", logged.last())
    }

    @Test
    fun oneMadePlayerServesEveryPlayAndNothingIsHeardFromAStoppedOne() {
        audio.play(pieces, 0, 0, 1f)
        idle()
        audio.stop()
        player.fail()
        idle()
        audio.play(pieces, 2, 4_000, 1.2f)
        idle()

        assertEquals(1, players.size)
        assertTrue(heard.none { it == "error" })
        assertEquals(2, player.index)
        assertEquals(1.2f, player.speed)
        assertTrue(player.prepared && player.playing && !player.released)
    }

    @Test
    fun stoppingStopsThePlayerAndTheTicksAndReleasingLetsItGo() {
        audio.play(pieces, 0, 0, 1f)
        idle()
        audio.stop()
        val before = heard.size
        idle(500)

        assertEquals(1, player.stops)
        assertEquals(before, heard.size)
        audio.release()
        assertTrue(player.released)
    }
}
