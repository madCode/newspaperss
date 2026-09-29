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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EditionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NewspaperssApp).container
        setProgress(workDataOf(STAGE to STAGE_SYNCING))
        container.feedSync.syncAll()
        val result = container.editionBuilder.build(container.editionSettings()) { done ->
            setProgressAsync(workDataOf(STAGE to STAGE_FETCHING, FETCHED to done))
        }
        return when (result) {
            is BuildResult.Built -> Result.success(workDataOf(EDITION_ID to result.editionId))
            BuildResult.NothingNew -> Result.success(workDataOf(NOTHING_NEW to true))
            is BuildResult.Failed -> Result.failure(workDataOf(EDITION_ID to result.editionId, ERROR to result.reason))
        }
    }

    companion object {
        // One build at a time: a scheduled build and "Make one now" share this name.
        const val UNIQUE = "edition-build"
        const val STAGE = "stage"
        const val STAGE_SYNCING = "syncing"
        const val STAGE_FETCHING = "fetching"
        const val FETCHED = "fetched"
        const val EDITION_ID = "editionId"
        const val NOTHING_NEW = "nothingNew"
        const val ERROR = "error"

        fun buildNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<EditionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { it.lastOrNull() }
    }
}
