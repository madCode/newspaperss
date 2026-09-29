package com.app.newspaperss.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.testutil.TestApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test
    fun thePeriodicSyncRunsTwiceADayOnlyWithBatteryToSpare() {
        SyncWorker.schedulePeriodic(context)

        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.PERIODIC).get().single()
        assertEquals(TimeUnit.HOURS.toMillis(12), work.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(work.constraints.requiresBatteryNotLow())
    }
}
