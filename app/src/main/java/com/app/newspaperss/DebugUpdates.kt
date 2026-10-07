package com.app.newspaperss

import android.content.SharedPreferences
import com.app.newspaperss.core.net.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Notices when CI has published a newer debug build than the one installed, so a tester isn't
 * left on a stale one. Asks GitHub when the app comes to the front, at most every [INTERVAL_MILLIS].
 *
 * @param installed this app's CI build number, or null for a release or locally built app, which
 *   never asks.
 */
class DebugUpdates(
    private val http: HttpClient,
    private val prefs: SharedPreferences,
    private val installed: Int?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val newerBuild = MutableStateFlow(newer(prefs.getInt(LATEST, 0)))

    /** The newest build's number while it's newer than this one, else null. */
    val newer: StateFlow<Int?> = newerBuild.asStateFlow()

    suspend fun checkIfDue() {
        if (installed == null) return
        // A clock set back counts as due, or it could hold the check off for as long.
        if (now() - prefs.getLong(CHECKED, 0L) in 0 until INTERVAL_MILLIS) return
        val response = try {
            http.get(RELEASE_API)
        } catch (_: IOException) {
            return
        }
        // Not recorded as checked: offline, it asks again next time the app opens.
        val latest = response.takeIf { it.isSuccessful }?.let { latestBuild(it.body) } ?: return
        prefs.edit().putLong(CHECKED, now()).putInt(LATEST, latest).apply()
        newerBuild.value = newer(latest)
    }

    private fun newer(latest: Int) = latest.takeIf { installed != null && it > installed }

    companion object {
        val INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(3)
        const val RELEASE_API = "https://api.github.com/repos/madCode/newspaperss/releases/tags/latest-debug"
        const val DOWNLOAD = "https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk"
        private const val CHECKED = "checked"
        private const val LATEST = "latest"

        // CI names each build's file after its run number, beside the fixed name DOWNLOAD links to.
        private val BUILD_FILE = Regex("""newspapeRSS-debug-(\d+)\.apk""")
        private val BUILD_IN_VERSION = Regex("""-debug\.(\d+)""")

        /** The newest build number among the release's files, from GitHub's release JSON. */
        fun latestBuild(releaseJson: String): Int? =
            BUILD_FILE.findAll(releaseJson).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

        /** The CI build number in a version name like "0.1.0-debug.464+012eb32"; null without one. */
        fun buildOf(versionName: String?): Int? = versionName?.let { BUILD_IN_VERSION.find(it) }?.groupValues?.get(1)?.toIntOrNull()
    }
}
