package com.app.newspaperss.work

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(EditionScheduler.PREFS, Context.MODE_PRIVATE)
    private val settings = Settings(scheduleEnabled = true, schedule = Schedule(time = LocalTime.of(6, 30)))
    private val morning = ZonedDateTime.of(2026, 9, 29, 5, 0, 0, 0, ZoneOffset.UTC)
    private val sixThirty = morning.withHour(6).withMinute(30).toInstant().toEpochMilli()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun timers() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(EditionScheduler.UNIQUE).get()
        .filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun aStoredTimeWithNoTimerBehindItIsIgnoredAndATimerIsArmed() = runTest {
        // As after a restore: yesterday's pending time came back, the work didn't.
        prefs.edit { putLong(EditionScheduler.PENDING, sixThirty - 86_400_000) }

        EditionScheduler.reschedule(context, settings, morning)

        assertEquals(1, timers().size)
        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun aPendingTimerForTheSameTimeIsKept() = runTest {
        EditionScheduler.reschedule(context, settings, morning)
        val armed = timers().single().id

        EditionScheduler.reschedule(context, settings, morning.plusMinutes(10))

        assertEquals(armed, timers().single().id)
    }

    @Test
    fun anOverdueTimerThatStillExistsIsKeptSoTodaysEditionIsntSkipped() = runTest {
        EditionScheduler.reschedule(context, settings, morning)
        val armed = timers().single().id

        // Doze held the timer past 6:30; the app starts and reschedules meanwhile.
        EditionScheduler.reschedule(context, settings, morning.withHour(7))

        assertEquals(armed, timers().single().id)
        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun theTimerIsArmedToFireTheLeadTimeBeforeTheEditionIsDue() = runTest {
        EditionScheduler.reschedule(context, settings, morning)

        val due = java.time.Instant.ofEpochMilli(sixThirty)
        val expected = java.time.Duration.between(morning.toInstant(), due.minus(EditionScheduler.LEAD)).toMillis()
        assertEquals(expected, timers().single().initialDelayMillis)
        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun rescheduleDuringTheLeadDoesntArmTheEditionAlreadyStartedAgain() = runTest {
        // The 6:30 timer fired at 6:00 and started the build; the app opens at 6:10.
        prefs.edit { putLong(EditionScheduler.LAST_DUE, sixThirty) }

        EditionScheduler.reschedule(context, settings, morning.withHour(6).withMinute(10))

        assertEquals(sixThirty + 86_400_000, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun aTimerFiringInTheLeadArmsTheNextEditionNotTheOneItStarted() = runTest {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val now = ZonedDateTime.now()
        val soon = Settings(scheduleEnabled = true, schedule = Schedule(time = now.toLocalTime().plusMinutes(20).withSecond(0).withNano(0)))
        app.container.settings.update { soon }
        val due = soon.schedule.nextAfter(now)!!.toInstant().toEpochMilli()

        val timer = TestListenableWorkerBuilder<EditionScheduler.Timer>(app)
            .setInputData(workDataOf(EditionScheduler.DUE to due))
            .build()
        timer.doWork()

        assertEquals(due, prefs.getLong(EditionScheduler.LAST_DUE, 0))
        val next = prefs.getLong(EditionScheduler.PENDING, 0)
        assertTrue("next edition $next should come well after $due", next - due > 3_600_000)
    }

    @Test
    fun aTimeZoneChangeMovesTheTimerToTheNewLocalTime() = runTest {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        app.container.settings.update { settings }
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            EditionScheduler.reschedule(context, settings)
            val before = prefs.getLong(EditionScheduler.PENDING, 0)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED).setClass(context, ClockChangeReceiver::class.java))

            com.app.newspaperss.testutil.idleUntil { prefs.getLong(EditionScheduler.PENDING, 0) != before }
            val after = java.time.Instant.ofEpochMilli(prefs.getLong(EditionScheduler.PENDING, 0))
            assertEquals(LocalTime.of(6, 30), after.atZone(java.time.ZoneId.of("Asia/Tokyo")).toLocalTime())
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
