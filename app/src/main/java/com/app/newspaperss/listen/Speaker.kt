package com.app.newspaperss.listen

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * A voice that says one line at a time and reports each line's start and end by its id. Callbacks
 * arrive on the main thread.
 */
interface Speaker {
    var listener: Listener?

    /**
     * Says [text] in [language] (a BCP 47 tag, or null for the phone's own) at [rate] (1.0 normal).
     * [flush] drops whatever was queued before it; otherwise it's said after.
     *
     * @return false if the voice has no [language] installed: it's said in the phone's own.
     */
    fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean

    /** Stops speaking and drops the queue, without calling [Listener.onDone]. */
    fun stop()

    fun release()

    interface Listener {
        fun onStart(id: String)
        fun onDone(id: String)
        fun onError(id: String)
    }
}

/**
 * The phone's text-to-speech voice (Speech Services by Google on most phones). It starts
 * asynchronously; lines asked for before it's ready wait for it.
 *
 * An engine that failed to start, or dropped out (updated, or killed), is started again on the next
 * line asked for, so "try again" after fixing Android's text-to-speech settings works without
 * restarting the app. So is one whose engine was changed in Android's settings, when it's next
 * started or moved (a line that drops the queue), so the change is heard without restarting.
 */
class SystemSpeaker(context: Context) : Speaker {
    override var listener: Speaker.Listener? = null
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private val waiting = mutableListOf<() -> Unit>()
    private var language: String? = null
    /** The engine Android's settings named when this one started. */
    private var engineName: String? = null
    private var tts: TextToSpeech = connect()

    private fun connect(): TextToSpeech {
        ready = false
        failed = false
        language = null
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(app) { status ->
            main.post {
                // A newer engine has replaced this one.
                if (engine !== tts) return@post
                if (status == TextToSpeech.SUCCESS) ready = true else failed = true
                // Failed, each waiting line reports an error, so the player can say the voice isn't working.
                val queued = waiting.toList()
                waiting.clear()
                queued.forEach { it() }
            }
        }
        engineName = engine.defaultEngine
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { main.post { listener?.onStart(utteranceId) } }
            override fun onDone(utteranceId: String) { main.post { listener?.onDone(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { main.post { listener?.onError(utteranceId) } }
            override fun onError(utteranceId: String, errorCode: Int) { main.post { listener?.onError(utteranceId) } }
        })
        return engine
    }

    override fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean {
        if (failed || (flush && ready && tts.defaultEngine != engineName)) {
            failed = false
            tts.shutdown()
            tts = connect()
        }
        if (!ready) {
            waiting += {
                if (failed) main.post { listener?.onError(id) } else speak(id, text, language, rate, flush)
            }
            return true
        }
        val installed = choose(language)
        tts.setSpeechRate(rate)
        if (tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) {
            // Unbound: no callback will ever come, so say so, and start the engine afresh next time.
            failed = true
            main.post { listener?.onError(id) }
        }
        return installed
    }

    /**
     * Switches to [tag]'s voice if it's installed. The phone's own language uses the voice chosen
     * in Android's settings (an en-GB voice on an en-US phone stays en-GB).
     */
    private fun choose(tag: String?): Boolean {
        val wanted = tag?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotEmpty() && it.language != Locale.getDefault().language }
        val key = wanted?.toLanguageTag()
        if (key == language) return wanted == null || tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE
        language = key
        val available = wanted != null && tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE
        if (available) tts.language = wanted else tts.defaultVoice?.let { tts.voice = it } ?: run { tts.language = Locale.getDefault() }
        return wanted == null || available
    }

    override fun stop() {
        waiting.clear()
        if (ready) tts.stop()
    }

    override fun release() {
        waiting.clear()
        tts.shutdown()
    }
}
