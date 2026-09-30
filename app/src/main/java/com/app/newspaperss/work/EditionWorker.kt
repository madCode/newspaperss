package com.app.newspaperss.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.edition.BuildResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class EditionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NewspaperssApp).container
        setProgress(workDataOf(STAGE to STAGE_SYNCING))
        // Thrown out of doWork, an exception fails the work with no output, so the Today screen
        // could only say "Something went wrong."
        val result = try {
            container.editionRun.run(
                scheduled = inputData.getBoolean(SCHEDULED, false),
                dueAt = inputData.getLong(DUE_AT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                onProgress = { done -> setProgressAsync(workDataOf(STAGE to STAGE_FETCHING, FETCHED to done)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The run also delivers, so the edition may already exist; don't claim it wasn't made.
            return Result.failure(workDataOf(ERROR to "Something went wrong. If an edition was made, it's below."))
        }
        return when (result) {
            is BuildResult.Built -> Result.success(workDataOf(EDITION_ID to result.editionId))
            BuildResult.NothingNew -> Result.success(workDataOf(NOTHING_NEW to true))
            is BuildResult.Failed -> Result.failure(workDataOf(EDITION_ID to result.editionId, ERROR to result.reason))
            is BuildResult.Unreachable -> Result.failure(workDataOf(ERROR to result.reason))
        }
    }

    companion object {
        // One build at a time: a scheduled build and "Make one now" share this name.
        const val UNIQUE = "edition-build"
        const val SCHEDULED = "scheduled"
        const val DUE_AT = "dueAt"
        const val STAGE = "stage"
        const val STAGE_SYNCING = "syncing"
        const val STAGE_FETCHING = "fetching"
        const val FETCHED = "fetched"
        const val EDITION_ID = "editionId"
        const val NOTHING_NEW = "nothingNew"
        const val ERROR = "error"

        /** @param dueAt for a timed edition, when it's due (epoch ms); it's titled and dated for then. */
        fun buildNow(context: Context, scheduled: Boolean = false, dueAt: Long? = null) {
            val request = OneTimeWorkRequestBuilder<EditionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(SCHEDULED to scheduled, DUE_AT to (dueAt ?: 0L)))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { it.lastOrNull() }
    }
}
