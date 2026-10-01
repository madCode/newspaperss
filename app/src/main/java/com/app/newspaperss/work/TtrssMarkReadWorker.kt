package com.app.newspaperss.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.NewspaperssApp

/**
 * Marks an edition's tt-rss articles read on the server once it's delivered, or unread once it's
 * marked as not sent, retrying a few times.
 */
class TtrssMarkReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val editionId = inputData.getLong(EDITION_ID, -1)
        val done = (applicationContext as NewspaperssApp).container.ttrss.syncRead(editionId)
        return when {
            done -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            // The source shows the error; tt-rss is left as it was, which loses nothing.
            else -> Result.failure()
        }
    }

    companion object {
        const val EDITION_ID = "editionId"
        const val MAX_ATTEMPTS = 4

        fun enqueue(context: Context, editionId: Long) {
            val request = OneTimeWorkRequestBuilder<TtrssMarkReadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(EDITION_ID to editionId))
                .build()
            // Replaced, not kept: the work reads the edition's state when it runs, so only the
            // newest request matters, and one still retrying a delivery mustn't block undoing it.
            WorkManager.getInstance(context).enqueueUniqueWork("ttrss-mark-read-$editionId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
