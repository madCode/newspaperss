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

/** Marks a delivered edition's tt-rss articles read on the server, retrying a few times. */
class TtrssMarkReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val editionId = inputData.getLong(EDITION_ID, -1)
        val done = (applicationContext as NewspaperssApp).container.ttrss.markRead(editionId)
        return when {
            done -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            // The source shows the error; the articles stay unread in tt-rss, which loses nothing.
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
            // An edition confirmed as delivered twice only needs marking read once.
            WorkManager.getInstance(context).enqueueUniqueWork("ttrss-mark-read-$editionId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
