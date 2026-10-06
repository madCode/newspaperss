package com.app.newspaperss.listen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.app.newspaperss.MainActivity
import com.app.newspaperss.NewspaperssApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the edition playing with the screen off, and gives it Android's media controls. Media3
 * makes it a foreground service while it plays, with the notification as its player.
 */
@OptIn(UnstableApi::class)
class ListenService : MediaSessionService() {
    private var session: MediaSession? = null
    private var focus: AudioFocus? = null

    override fun onCreate() {
        super.onCreate()
        val listen = (application as NewspaperssApp).container.listen
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_LISTENING).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SessionPlayer(listen))
            .setSessionActivity(open)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK).setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("Back a sentence").setSlots(CommandButton.SLOT_BACK_SECONDARY).build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD).setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("Next sentence").setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build(),
                ),
            )
            .build()
        focus = AudioFocus(this, listen)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiped out of recents while paused: there's nothing to keep going.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        focus?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /**
         * Binds the service, so starting to play makes it a foreground service with its
         * notification. Kept for the app's life: Media3 lets the service go when nothing plays.
         */
        fun connect(context: Context) {
            val app = context.applicationContext
            if (connected) return
            connected = true
            MediaController.Builder(app, SessionToken(app, ComponentName(app, ListenService::class.java))).buildAsync()
        }

        @Volatile private var connected = false
    }
}

/**
 * Asks for the audio while it plays, and pauses for a call, a navigation voice or another app's
 * audio; after a short interruption it carries on by itself. Headphones unplugged pause it too.
 */
private class AudioFocus(private val context: Context, private val listen: ListenPlayer) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumeOnGain = false
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; listen.play() }
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; held = false; listen.pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    if (listen.state.value.playing) { resumeOnGain = true; listen.pause() }
            }
        }
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { listen.pause() }
    }

    init {
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            listen.state.map { it.playing }.distinctUntilChanged().collect { playing ->
                if (playing && !held) {
                    held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!held) listen.pause()
                } else if (!playing && held && !resumeOnGain) {
                    audio.abandonAudioFocusRequest(request)
                    held = false
                }
            }
        }
    }

    fun release() {
        scope.cancel()
        context.unregisterReceiver(noisy)
        if (held) audio.abandonAudioFocusRequest(request)
    }
}
