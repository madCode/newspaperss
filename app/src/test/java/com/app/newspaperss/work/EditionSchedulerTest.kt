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
import com.app.newspaperss.settings.ListenVoice
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

    private val day = 86_400_000L
    private val sixTen = morning.withHour(6).withMinute(10)

    /** As the 6:30 timer leaves things when it fires at 6:00: the build started, tomorrow's timer armed. */
    private suspend fun firedEarly() {
        prefs.edit { putLong(EditionScheduler.LAST_DUE, sixThirty) }
        EditionScheduler.reschedule(context, settings, morning.withHour(6))
    }

    @Test
    fun rescheduleDuringTheLeadDoesntArmTheEditionAlreadyStartedAgain() = runTest {
        firedEarly()
        val armed = timers().single().id

        EditionScheduler.reschedule(context, settings, sixTen)

        assertEquals(armed, timers().single().id)
        assertEquals(sixThirty + day, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun movingTheTimeDuringTheLeadDoesntMakeASecondPaperToday() = runTest {
        firedEarly()

        for (minute in listOf(45, 15)) {
            val moved = Settings(scheduleEnabled = true, schedule = Schedule(time = LocalTime.of(6, minute)))
            EditionScheduler.reschedule(context, moved, sixTen)

            val due = morning.withHour(6).withMinute(minute).toInstant().toEpochMilli() + day
            assertEquals("moved to 6:$minute", due, prefs.getLong(EditionScheduler.PENDING, 0))
        }
    }

    @Test
    fun aClockSetAheadAndCorrectedDoesntHoldBackEditions() = runTest {
        // A timer ran while the clock read a day ahead and recorded tomorrow's edition.
        prefs.edit { putLong(EditionScheduler.LAST_DUE, sixThirty + day) }

        EditionScheduler.reschedule(context, settings, morning)

        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
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
        // On the real clock, as the receiver is. A fixed time would fall inside the lead in Tokyo
        // for half an hour a day: the timer would run at once and outlive the test's work database.
        // Six hours ahead in Tokyo is 19 or 20 hours ahead in New York, far from now in both.
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val due = LocalTime.now(tokyo).plusHours(6).withSecond(0).withNano(0)
        val farOff = settings.copy(schedule = Schedule(time = due))
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        app.container.settings.update { farOff }
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            EditionScheduler.reschedule(context, farOff)
            val before = prefs.getLong(EditionScheduler.PENDING, 0)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED).setClass(context, ClockChangeReceiver::class.java))

            com.app.newspaperss.testutil.idleUntil { prefs.getLong(EditionScheduler.PENDING, 0) != before }
            val after = java.time.Instant.ofEpochMilli(prefs.getLong(EditionScheduler.PENDING, 0))
            assertEquals(due, after.atZone(tokyo).toLocalTime())
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    /** Kokoro in use on a Pixel 8, as its check found it: a 30-minute paper starts 70 minutes earlier. */
    private val podcast = settings.copy(listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f)
    private val threeAm = morning.withHour(3)

    private fun startsAt(hour: Int, minute: Int) = morning.withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    @Test
    fun withThePodcastTheTimerFiresEarlierByTheTimeToMakeIt() = runTest {
        EditionScheduler.reschedule(context, podcast, threeAm)

        // 6:30, less 30 minutes and 70 for the podcast.
        assertEquals(startsAt(4, 50) - threeAm.toInstant().toEpochMilli(), timers().single().initialDelayMillis)
        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
        assertEquals(LocalTime.of(4, 50), EditionScheduler.podcastStart(podcast))
        assertEquals(null, EditionScheduler.podcastStart(settings))
    }

    @Test
    fun aPaceLearnedMovesTheStartOfTheSameEdition() = runTest {
        EditionScheduler.reschedule(context, podcast, threeAm)
        val armed = timers().single().id

        // Real podcasts took longer than the check suggested: 1.6, with the smaller margin, 80 minutes.
        EditionScheduler.reschedule(context, podcast.copy(podcastPace = 1.6f, podcastPaceMeasured = true), threeAm.plusMinutes(5))

        val timer = timers().single()
        assertTrue(timer.id != armed)
        assertEquals(startsAt(4, 40) - threeAm.plusMinutes(5).toInstant().toEpochMilli(), timer.initialDelayMillis)
        assertEquals(sixThirty, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun turningThePodcastOffStartsTheEditionAtItsUsualTime() = runTest {
        EditionScheduler.reschedule(context, podcast, threeAm)

        EditionScheduler.reschedule(context, podcast.copy(listenVoice = ListenVoice.PHONE), threeAm)

        assertEquals(startsAt(6, 0) - threeAm.toInstant().toEpochMilli(), timers().single().initialDelayMillis)
    }

    @Test
    fun aShorterStartLearnedWhileTheEditionIsBeingMadeDoesntStartItAgain() = runTest {
        // Started at 4:50 for 6:30; at 5:00 a podcast teaches a pace that would start it at 5:50.
        prefs.edit {
            putLong(EditionScheduler.LAST_DUE, sixThirty)
            putLong(EditionScheduler.LAST_START, startsAt(4, 50))
        }
        EditionScheduler.reschedule(context, podcast.copy(podcastPace = 0.5f, podcastPaceMeasured = true), morning)

        assertEquals(sixThirty + day, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun turningThePodcastOffWhileItsEditionIsBeingMadeDoesntStartItAgain() = runTest {
        // Started at 4:50 for 6:30; at 5:15, Kokoro is removed, and the lead is 30 minutes again.
        prefs.edit {
            putLong(EditionScheduler.LAST_DUE, sixThirty)
            putLong(EditionScheduler.LAST_START, startsAt(4, 50))
        }
        EditionScheduler.reschedule(context, settings, morning.withMinute(15))

        assertEquals(sixThirty + day, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun movingTheTimeAfterTodaysPaperCameDoesntMakeAnotherNow() = runTest {
        // Today's came at 6:30; at 9:00 the reader moves the time to 8:00.
        prefs.edit {
            putLong(EditionScheduler.LAST_DUE, sixThirty)
            putLong(EditionScheduler.LAST_START, startsAt(4, 50))
        }
        val eight = podcast.copy(schedule = Schedule(time = LocalTime.of(8, 0)))
        EditionScheduler.reschedule(context, eight, morning.withHour(9))

        assertEquals(morning.withHour(8).withMinute(0).toInstant().toEpochMilli() + day, prefs.getLong(EditionScheduler.PENDING, 0))
    }

    @Test
    fun movingTheTimeLaterDuringALongLeadDoesntMakeASecondPaperToday() = runTest {
        // Started at 4:50 for 6:30; at 5:30 the reader moves the time to 7:15.
        prefs.edit {
            putLong(EditionScheduler.LAST_DUE, sixThirty)
            putLong(EditionScheduler.LAST_START, startsAt(4, 50))
        }
        val later = podcast.copy(schedule = Schedule(time = LocalTime.of(7, 15)))
        EditionScheduler.reschedule(context, later, morning.withMinute(30))

        assertEquals(morning.withHour(7).withMinute(15).toInstant().toEpochMilli() + day, prefs.getLong(EditionScheduler.PENDING, 0))
    }
}
