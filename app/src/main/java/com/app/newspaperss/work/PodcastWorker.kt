package com.app.newspaperss.work

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.container
import com.app.newspaperss.listen.PodcastMaker
import com.app.newspaperss.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Makes the podcasts asked for ([PodcastMaker.makeAll]), only while the phone charges: a
 * 30-minute podcast would take about a fifth of a charge. Unplugged, it stops and runs again
 * when the phone is plugged back in; the pages made are kept.
 *
 * It runs in the foreground, with a quiet notification, where Android allows that: as background
 * work, Android stops it after 10 minutes and, overnight, lets it run only a minute in several.
 * From Android 12, foreground work can start from the background only if the app's battery use
 * is Unrestricted, or the app is on screen; otherwise it's made as background work, slowly.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (mayGoForeground() && !goForeground()) {
            enqueue(applicationContext)
        } else {
            val started = SystemClock.elapsedRealtime()
            val stopped = makeWhile(CHECK_MS, { applicationContext.container.podcastMaker.makeAll() }) {
                plugged() && (inForeground() || SystemClock.elapsedRealtime() - started < BACKGROUND_MS)
            }
            if (stopped) enqueue(applicationContext)
        }
        Result.success()
    } catch (e: CancellationException) {
        // Android 15's 6 hours a day in the foreground used up: asking again soon would be
        // refused silently, inside WorkManager's service.
        if (Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
            refusedAt = SystemClock.elapsedRealtime() + TIMED_OUT_MS - HOLD_OFF_MS
        }
        throw e
    } catch (e: Exception) {
        // Kokoro couldn't load (its files damaged since the check, say): asked again, it tries again.
        Log.w(TAG, "Podcast stopped: ${e.javaClass.name}")
        Result.failure()
    } catch (e: LinkageError) {
        Log.w(TAG, "Kokoro's native code didn't load")
        Result.failure()
    }

    /**
     * Whether Android will let this work go foreground. Asking when it won't is worse than not
     * asking: WorkManager counts the work as foreground before Android refuses, and then ignores
     * the phone being unplugged and its own 10-minute limit.
     */
    private fun mayGoForeground(): Boolean {
        if (SystemClock.elapsedRealtime() - refusedAt < HOLD_OFF_MS) return false
        if (Build.VERSION.SDK_INT < 31) return true
        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(applicationContext.packageName)) return true
        return importance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** False if Android refused: this run ends, so WorkManager lets go of it, and the next runs in the background. */
    private suspend fun goForeground(): Boolean {
        val notification = applicationContext.container.notifier.makingPodcast()
        val info = when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifier.PODCAST_ID, notification)
        }
        return try {
            setForeground(info)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Started from the background after all: the app left the screen just then, say.
            Log.i(TAG, "Foreground refused: ${e.javaClass.name}")
            refusedAt = SystemClock.elapsedRealtime()
            false
        }
    }

    /**
     * Android can refuse the foreground silently too, inside WorkManager's service (past Android
     * 15's 6 hours a day, say): only the process's standing shows whether it really is.
     */
    private fun inForeground() = importance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    private fun importance() = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    /** Plugged in, charging or not: a phone holding its charge at 80% overnight still counts. */
    private fun plugged(): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    companion object {
        private const val TAG = "PodcastWorker"
        private const val UNIQUE = "podcast"
        private const val CHECK_MS = 30_000L

        /** Short of WorkManager's 10 minutes, when the work isn't really in the foreground. */
        private const val BACKGROUND_MS = 9 * 60_000L

        /** After a refusal, how long the work stays in the background before asking again. */
        private const val HOLD_OFF_MS = 60 * 60_000L

        /** After the foreground's daily time ran out: Android counts it over a day. */
        private const val TIMED_OUT_MS = 12 * 60 * 60_000L

        /** When Android last refused the foreground, for [HOLD_OFF_MS]. Kept only while the app runs. */
        @Volatile
        internal var refusedAt = Long.MIN_VALUE / 2

        /**
         * Runs [make] while [keepGoing], checked every [checkMs]; true if it was stopped. Here as
         * well as in WorkManager: work that went foreground in WorkManager's eyes but not
         * Android's is never stopped by WorkManager.
         */
        internal suspend fun makeWhile(checkMs: Long, make: suspend () -> Unit, keepGoing: () -> Boolean): Boolean = coroutineScope {
            var stopped = false
            val making = launch { make() }
            val guard = launch {
                while (true) {
                    delay(checkMs)
                    if (!keepGoing()) {
                        stopped = true
                        making.cancel()
                        break
                    }
                }
            }
            making.join()
            guard.cancel()
            stopped
        }

        /**
         * Makes the podcasts asked for once the phone charges. Appended: a run under way finishes
         * its page, sees the newest edition asked for, and moves to it; one finishing just as
         * this is asked runs again after.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PodcastWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
