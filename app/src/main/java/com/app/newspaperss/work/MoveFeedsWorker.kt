package com.app.newspaperss.work

import com.app.newspaperss.container
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Moves phone feeds into tt-rss ([com.app.newspaperss.data.FeedMoves.run]). What's left to do is
 * kept in DataStore, so a run stopped part way, by the app dying or the system's time limit on
 * work, picks up where it was when the work runs again.
 */
class MoveFeedsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.container.feedMoves.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A database error, say: the batch is still stored, so later is worth a try, a few
        // times. After that what's left goes back to the banner, rather than "Moving" for good.
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            applicationContext.container.feedMoves.giveUp("Something went wrong moving it. Try again.")
            Result.failure()
        }
    }

    companion object {
        private const val UNIQUE = "move-feeds"
        internal const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<MoveFeedsWorker>()
                .setConstraints(CONNECTED_NETWORK)
                .build()
            // Appended, not kept: a run finishing up just as a new batch starts would otherwise
            // swallow the request and leave the batch waiting. A run with nothing to do ends at once.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
