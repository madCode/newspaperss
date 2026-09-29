package com.app.newspaperss.work

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.core.edition.ScheduleTimer
import com.app.newspaperss.core.edition.TimerAction
import com.app.newspaperss.settings.Settings
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Timed editions as a chain of one-off work: each firing starts a build and
 * arms the next. Periodic work can't do "6:30 on weekdays", and its start
 * time drifts.
 */
object EditionScheduler {
    private const val UNIQUE = "edition-schedule"
    private const val PREFS = "edition-schedule"
    private const val PENDING = "pending_epoch_ms"

    fun reschedule(context: Context, settings: Settings, now: ZonedDateTime = ZonedDateTime.now()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getLong(PENDING, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val target = if (settings.scheduleEnabled) settings.schedule.nextAfter(now)?.toInstant() else null
        val work = WorkManager.getInstance(context)
        when (val action = ScheduleTimer.decide(pending, target, now.toInstant())) {
            TimerAction.Keep -> {}
            TimerAction.Cancel -> {
                work.cancelUniqueWork(UNIQUE)
                prefs.edit { remove(PENDING) }
            }
            is TimerAction.Arm -> {
                work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, timer(Duration.between(now.toInstant(), action.at)))
                prefs.edit { putLong(PENDING, action.at.toEpochMilli()) }
            }
        }
    }

    private fun timer(delay: Duration) = OneTimeWorkRequestBuilder<Timer>()
        .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    class Timer(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            EditionWorker.buildNow(applicationContext, scheduled = true)
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(PENDING) }
            val settings = (applicationContext as NewspaperssApp).container.settings.current()
            // Appended rather than replaced: REPLACE on our own name would cancel this running worker.
            val next = if (settings.scheduleEnabled) settings.schedule.nextAfter(ZonedDateTime.now()) else null
            if (next != null) {
                val now = Instant.now()
                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, timer(Duration.between(now, next.toInstant())))
                applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(PENDING, next.toInstant().toEpochMilli()) }
            }
            return Result.success()
        }
    }
}
