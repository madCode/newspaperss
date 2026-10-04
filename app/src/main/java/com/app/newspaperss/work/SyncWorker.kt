package com.app.newspaperss.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.NewspaperssApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NewspaperssApp).container
        container.feedSync.syncAll()
        // Moved phone feeds go once their last starred article has been delivered.
        container.feedMoves.tidy()
        return Result.success()
    }

    companion object {
        internal const val PERIODIC = "sync-periodic-12h"
        private const val OLD_PERIODIC = "sync-periodic"
        private const val NOW = "sync-now"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Keeps the Sources screen current between editions. Twice a day is plenty: every edition
         * syncs right before it's built anyway, so these runs only refresh what the screen shows.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build()
            val work = WorkManager.getInstance(context)
            // A new name rather than UPDATE, which would re-schedule the work on every app start
            // (an extra wakeup each time); the old 4-hour work is cancelled once.
            work.cancelUniqueWork(OLD_PERIODIC)
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            // Append, not KEEP: a sync already running read the source list before
            // a newly added source existed, so it wouldn't fetch it.
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
