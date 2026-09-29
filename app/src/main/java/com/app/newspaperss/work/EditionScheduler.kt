package com.app.newspaperss.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.settings.Settings
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Timed editions as a chain of one-off work: each firing starts a build and
 * schedules the next. Periodic work can't do "6:30 on weekdays", and its
 * start time drifts.
 */
object EditionScheduler {
    private const val UNIQUE = "edition-schedule"

    /** Replaces any pending timer with one for the next scheduled time; cancels it if the schedule is off. */
    fun reschedule(context: Context, settings: Settings, now: ZonedDateTime = ZonedDateTime.now()) {
        val work = WorkManager.getInstance(context)
        val next = if (settings.scheduleEnabled) settings.schedule.nextAfter(now) else null
        if (next == null) {
            work.cancelUniqueWork(UNIQUE)
            return
        }
        val request = OneTimeWorkRequestBuilder<Timer>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    class Timer(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            EditionWorker.buildNow(applicationContext, scheduled = true)
            val app = applicationContext as NewspaperssApp
            reschedule(applicationContext, app.container.settings.current())
            return Result.success()
        }
    }
}
