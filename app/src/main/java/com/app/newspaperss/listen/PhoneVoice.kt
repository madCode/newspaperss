package com.app.newspaperss.listen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech

/** The phone's text-to-speech engine, which Listen reads with, and the ways to change or get one. */
object PhoneVoice {
    /** Speech Services by Google: free, and what most phones read with. */
    private const val GOOGLE = "com.google.android.tts"

    /**
     * The engine's name as Android's settings show it ("Speech Services by Google"), or null if
     * the phone has none, as on many e-readers.
     */
    fun name(context: Context): String? {
        val pm = context.packageManager
        val engines = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        if (engines.isEmpty()) return null
        val chosen = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        // With none chosen, or the chosen one uninstalled, Android reads with a built-in one, as TextToSpeech does.
        val engine = engines.firstOrNull { it.serviceInfo.packageName == chosen }
            ?: engines.firstOrNull { it.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: engines.first()
        return engine.loadLabel(pm).toString()
    }

    /**
     * Android's text-to-speech settings: the engine, its voice and language, or Android's settings
     * without that page. False if neither opens, as on some e-readers.
     */
    fun openSettings(context: Context): Boolean =
        open(context, Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))

    /** Speech Services by Google in an app store, or on the web without one. False if neither opens. */
    fun get(context: Context): Boolean = open(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE")),
    )

    /** Opens the first of [intents] something on the phone handles. */
    private fun open(context: Context, vararg intents: Intent): Boolean = intents.any {
        try {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
