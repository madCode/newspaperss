package com.app.newspaperss.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.NewspaperssApp

/**
 * Saves a delivered edition's reading notes. A worker, because a shared edition is marked
 * delivered from a broadcast, which can't wait for a cloud folder to take the file.
 */
class NotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as NewspaperssApp).container.notesSaver.save(inputData.getLong(EDITION_ID, -1))
        // Not retried: the reader is told what went wrong, and Edition › Notes still shares them.
        return Result.success()
    }

    companion object {
        const val EDITION_ID = "editionId"

        fun enqueue(context: Context, editionId: Long) {
            val request = OneTimeWorkRequestBuilder<NotesWorker>().setInputData(workDataOf(EDITION_ID to editionId)).build()
            // One notes file per edition, even if it's confirmed as delivered twice.
            WorkManager.getInstance(context).enqueueUniqueWork("notes-$editionId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
