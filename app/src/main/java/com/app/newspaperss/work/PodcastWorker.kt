package com.app.newspaperss.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
 * It runs in the foreground, with a quiet notification: as background work, Android stops it
 * after 10 minutes and, overnight, lets it run only a minute in several. Android refuses
 * foreground work started from the background unless the app's battery use is Unrestricted (or
 * the app is on screen); refused, it runs as background work, slowly.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        goForeground()
        applicationContext.container.podcastMaker.makeAll()
        Result.success()
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

    private suspend fun goForeground() {
        val notification = applicationContext.container.notifier.makingPodcast()
        val info = when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifier.PODCAST_ID, notification)
        }
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, mostly: started from the background.
            // Slow, but still making it.
            Log.i(TAG, "Making the podcast in the background: ${e.javaClass.name}")
        }
    }

    companion object {
        private const val TAG = "PodcastWorker"
        private const val UNIQUE = "podcast"

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
