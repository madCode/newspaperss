package com.app.newspaperss.work

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.container
import com.app.newspaperss.listen.PodcastMaker
import com.app.newspaperss.notify.Notifier
import kotlinx.coroutines.CancellationException

/**
 * Makes the podcasts asked for ([PodcastMaker.makeAll]), only while the phone charges: a
 * 30-minute podcast would take about a fifth of a charge. Unplugged, Android stops the work and
 * runs it again when the phone is plugged back in; the pages made are kept.
 *
 * It runs in the foreground, with a quiet notification, where Android allows that: as background
 * work, Android stops it after 10 minutes and, overnight, lets it run only a minute in several.
 * From Android 12, foreground work can start from the background only if the app's battery use
 * is Unrestricted, or the app is on screen; otherwise it's made as background work, slowly.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (mayGoForeground() && !goForeground()) {
            Result.retry()
        } else {
            applicationContext.container.podcastMaker.makeAll()
            Result.success()
        }
    } catch (e: CancellationException) {
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
     * the phone being unplugged.
     */
    private fun mayGoForeground(): Boolean {
        if (refused) return false
        if (Build.VERSION.SDK_INT < 31) return true
        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(applicationContext.packageName)) return true
        val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
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
            // Started from the background after all (the app left the screen just then), or past
            // Android 15's 6 hours a day: background work until the app is next started.
            Log.i(TAG, "Foreground refused: ${e.javaClass.name}")
            refused = true
            false
        }
    }

    companion object {
        private const val TAG = "PodcastWorker"
        private const val UNIQUE = "podcast"

        /** Android refused the foreground since the app started. */
        @Volatile
        internal var refused = false

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
