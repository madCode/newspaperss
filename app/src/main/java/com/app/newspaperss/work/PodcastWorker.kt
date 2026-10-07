package com.app.newspaperss.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.container
import com.app.newspaperss.listen.PodcastMaker
import kotlinx.coroutines.CancellationException

/**
 * Makes the podcasts asked for ([PodcastMaker.makeAll]), only while the phone charges: a
 * 30-minute podcast would take about a fifth of a charge. Unplugged, Android stops the work and
 * runs it again when the phone is plugged back in; the pages made are kept. Android's 10-minute
 * limit on work stops it the same way.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
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
