package com.app.newspaperss.work

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
