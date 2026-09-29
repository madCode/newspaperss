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

/** Looks up the titles of links saved without one (see [com.app.newspaperss.data.ReadingListTitles]). */
class ReadingListTitleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = inputData.getLongArray(ARTICLE_IDS)?.toList().orEmpty()
        val done = (applicationContext as NewspaperssApp).container.readingListTitles.fetch(ids)
        return when {
            done -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            // Not a failure: the list shows the domain and the edition finds the title anyway.
            // Failing would also cancel the batches queued behind this one.
            else -> Result.success()
        }
    }

    companion object {
        const val ARTICLE_IDS = "articleIds"
        const val MAX_ATTEMPTS = 3
        // Small, because WorkManager stops a worker after ten minutes and one slow page can take a minute.
        const val BATCH = 20
        private const val UNIQUE_NAME = "reading-list-titles"

        fun enqueue(context: Context, articleIds: List<Long>) {
            val work = WorkManager.getInstance(context)
            for (batch in articleIds.chunked(BATCH)) {
                val request = OneTimeWorkRequestBuilder<ReadingListTitleWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(ARTICLE_IDS to batch.toLongArray()))
                    .build()
                // One batch at a time, so a big import doesn't fetch hundreds of pages at once.
                work.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
    }
}
