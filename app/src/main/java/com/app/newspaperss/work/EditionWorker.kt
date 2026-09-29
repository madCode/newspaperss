package com.app.newspaperss.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.edition.BuildResult
import com.app.newspaperss.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EditionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifier(applicationContext).building()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.BUILDING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.BUILDING_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as NewspaperssApp).container
        setProgress(workDataOf(STAGE to STAGE_SYNCING))
        // Thrown out of doWork, an exception fails the work with no output, so the Today screen
        // could only say "Something went wrong."
        val result = try {
            container.editionRun.run(
                scheduled = inputData.getBoolean(SCHEDULED, false),
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
        }
    }

    companion object {
        // One build at a time: a scheduled build and "Make one now" share this name.
        const val UNIQUE = "edition-build"
        const val SCHEDULED = "scheduled"
        const val STAGE = "stage"
        const val STAGE_SYNCING = "syncing"
        const val STAGE_FETCHING = "fetching"
        const val FETCHED = "fetched"
        const val EDITION_ID = "editionId"
        const val NOTHING_NEW = "nothingNew"
        const val ERROR = "error"

        fun buildNow(context: Context, scheduled: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<EditionWorker>()
                // Expedited, so a 6:30 edition is made at 6:30 rather than whenever the phone next
                // batches background work. Out of quota, it still runs, just not straight away.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(SCHEDULED to scheduled))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { it.lastOrNull() }
    }
}
