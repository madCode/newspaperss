package com.app.newspaperss.listen

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * The [ListenPlayer] as Android's media controls see it: on the lock screen, in the notification,
 * from headphones and in a car. Each article is a track, so ⏮ ⏭ move by article; the seek back
 * and forward buttons go back and on a sentence, not a number of seconds.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(private val listen: ListenPlayer) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { listen.state.collect { invalidateState() } }
    }

    override fun getState(): State {
        val s = listen.state.value
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(s.speed))
        // Media3 allows an empty playlist only when idle, so an edition still opening is idle too.
        if (s.pages.isEmpty()) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val items = s.pages.mapIndexed { index, page ->
            MediaItemData.Builder("page-$index")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(page.title)
                        .setArtist(page.source ?: s.editionTitle)
                        .setAlbumTitle(s.editionTitle)
                        .setTrackNumber(index + 1)
                        .setTotalTrackCount(s.pages.size)
                        .build(),
                )
                .setDurationUs((page.minutes * 60_000_000).roundToLong().coerceAtLeast(1))
                .build()
        }
        val inPage = s.secondsInPage
        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(s.at.page.coerceIn(0, items.lastIndex))
            .setContentPositionMs((inPage * 1000).roundToLong())
            .setPlaybackState(if (s.finished) Player.STATE_ENDED else Player.STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) listen.play() else listen.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> listen.back()
            Player.COMMAND_SEEK_FORWARD -> listen.forward()
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> listen.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> listen.previous()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> listen.seek(ListenPosition(mediaItemIndex, 0))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        listen.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        listen.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        ).build()
    }
}
