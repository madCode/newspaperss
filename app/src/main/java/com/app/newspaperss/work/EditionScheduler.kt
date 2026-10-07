package com.app.newspaperss.work

import com.app.newspaperss.container
import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.core.edition.ScheduleTimer
import com.app.newspaperss.core.edition.TimerAction
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Timed editions as a chain of one-off work: each firing starts a build and
 * arms the next. Periodic work can't do "6:30 on weekdays", and its start
 * time drifts.
 *
 * A timer fires [LEAD] before its edition is due. Android can hold delayed work
 * (Doze on an unplugged phone, battery savers), so starting early turns most of
 * that delay into lead time instead of a late paper. With the podcast in use it
 * fires earlier still, by the time this phone takes to make it ([lead]).
 */
object EditionScheduler {
    internal const val UNIQUE = "edition-schedule"
    internal const val PREFS = "edition-schedule"
    internal const val PENDING = "pending_epoch_ms"
    /** When the armed timer fires: [PENDING] less the [lead] then. */
    internal const val PENDING_START = "pending_start_epoch_ms"
    /** The due time of the last edition a timer started, so it isn't scheduled again around that time. */
    internal const val LAST_DUE = "last_due_epoch_ms"
    /** When the timer that started the last edition fired. */
    internal const val LAST_START = "last_start_epoch_ms"
    internal const val DUE = "due_epoch_ms"
    val LEAD: Duration = Duration.ofMinutes(30)

    /** How long before it's due a scheduled edition starts: [LEAD], plus the time to make its podcast if there's to be one. */
    fun lead(settings: Settings): Duration {
        val pace = settings.podcastPace
        if (settings.listenVoice != ListenVoice.PODCAST || pace == null) return LEAD
        return LEAD.plusMinutes(PodcastPace.earlier(settings.edition.minutes, pace.toDouble(), settings.podcastPaceMeasured).toLong())
    }

    /** The time of day scheduled editions start for their podcast, or null with no podcast to make. */
    fun podcastStart(settings: Settings): LocalTime? =
        lead(settings).takeIf { it != LEAD }?.let { settings.schedule.time.minus(it) }

    /** When the edition due at [due] starts. */
    fun startOf(due: Instant, settings: Settings): Instant = due.minus(lead(settings))

    // Callers fire and forget (app start, every settings change). Interleaved, an older
    // change's run could finish last and leave the timer set for settings no longer current.
    private val lock = Mutex()

    suspend fun reschedule(context: Context, settings: Settings, now: ZonedDateTime = ZonedDateTime.now()) = lock.withLock {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val work = WorkManager.getInstance(context)
        // The stored time can outlive its timer, e.g. restored from a backup onto a device
        // whose WorkManager database wasn't. ScheduleTimer keeps an overdue pending time, so
        // a stale one would stop timed editions for good: it only counts while the work exists.
        val armed = work.getWorkInfosForUniqueWorkFlow(UNIQUE).first().any { !it.state.isFinished }
        val pending = prefs.getLong(PENDING, 0L).takeIf { armed && it > 0 }?.let(Instant::ofEpochMilli)
        val target = if (settings.scheduleEnabled) nextDue(settings, now, prefs.getLong(LAST_DUE, 0L), prefs.getLong(LAST_START, prefs.getLong(LAST_DUE, 0L))) else null
        val action = ScheduleTimer.decide(pending, target, now.toInstant())
        val start = target?.let { startOf(it, settings) }
        // The same edition, starting at another time: the podcast's pace, or turning it on or off,
        // moves the start of an edition whose due time stays put.
        val moved = action == TimerAction.Keep && target != null && pending == target &&
            prefs.getLong(PENDING_START, 0L) != start!!.toEpochMilli()
        when {
            action is TimerAction.Arm || moved -> {
                work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, timer(target!!, start!!, now.toInstant()))
                prefs.edit {
                    putLong(PENDING, target.toEpochMilli())
                    putLong(PENDING_START, start.toEpochMilli())
                }
            }
            action == TimerAction.Cancel -> {
                work.cancelUniqueWork(UNIQUE)
                prefs.edit {
                    remove(PENDING)
                    remove(PENDING_START)
                }
            }
        }
    }

    fun lastDue(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_DUE, 0L)

    fun lastStart(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { it.getLong(LAST_START, it.getLong(LAST_DUE, 0L)) }

    /**
     * The next due time, skipping the last edition started while it's being made: from when its
     * timer fired until [LEAD] after it was due. Otherwise, with the time moved from 6:30 to 6:45
     * during its lead, 6:45 would be a second paper; and so would 6:30 again, with the podcast
     * turned off during a lead it had made longer. Outside that span, [lastDueMs] is ignored, so
     * a clock set far ahead and then corrected can't hold back timed editions until that date.
     *
     * @param lastStartMs when the last timer fired; before that was kept, [LEAD] before its due time.
     */
    internal fun nextDue(settings: Settings, now: ZonedDateTime, lastDueMs: Long, lastStartMs: Long = lastDueMs): Instant? {
        val lastDue = Instant.ofEpochMilli(lastDueMs)
        val from = minOf(Instant.ofEpochMilli(lastStartMs), lastDue.minus(LEAD))
        val until = lastDue.plus(LEAD)
        val nearLast = !now.toInstant().isBefore(from) && !now.toInstant().isAfter(until)
        val after = if (nearLast) until.atZone(now.zone) else now
        return settings.schedule.nextAfter(after)?.toInstant()
    }

    private fun timer(due: Instant, start: Instant, now: Instant) = OneTimeWorkRequestBuilder<Timer>()
        .setInitialDelay(Duration.between(now, start).coerceAtLeast(Duration.ZERO).toMillis(), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(DUE to due.toEpochMilli()))
        .build()

    class Timer(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Locked: a reschedule between clearing PENDING and arming the next timer would see
            // no timer and arm one with REPLACE, cancelling this worker.
            lock.withLock {
                // Read in the lock, so a change made while waiting for it is the one used.
                val settings = applicationContext.container.settings.current()
                val now = ZonedDateTime.now()
                val due = inputData.getLong(DUE, now.toInstant().toEpochMilli())
                EditionWorker.buildNow(applicationContext, scheduled = true, dueAt = due)
                prefs.edit {
                    putLong(LAST_DUE, due)
                    putLong(LAST_START, now.toInstant().toEpochMilli())
                }
                val next = if (settings.scheduleEnabled) nextDue(settings, now, due, now.toInstant().toEpochMilli()) else null
                if (next != null) {
                    val start = startOf(next, settings)
                    // Appended rather than replaced: REPLACE on our own name would cancel this running worker.
                    WorkManager.getInstance(applicationContext)
                        .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, timer(next, start, now.toInstant()))
                    prefs.edit {
                        putLong(PENDING, next.toEpochMilli())
                        putLong(PENDING_START, start.toEpochMilli())
                    }
                } else {
                    prefs.edit {
                        remove(PENDING)
                        remove(PENDING_START)
                    }
                }
            }
            return Result.success()
        }
    }
}
