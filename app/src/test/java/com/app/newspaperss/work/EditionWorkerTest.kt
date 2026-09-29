package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Notification
import android.content.pm.ServiceInfo
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.notify.Notifier
import org.junit.Assert.assertEquals
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun anUnexpectedErrorFailsTheWorkWithAReasonToShow() = runTest {
        // A broken database stands in for any error nothing below the worker handles.
        app.container.db.openHelper.writableDatabase.execSQL("DROP TABLE sources")

        val result = TestListenableWorkerBuilder<EditionWorker>(app).build().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val error = result.outputData.getString(EditionWorker.ERROR)
        assertTrue(error, error!!.startsWith("Something went wrong"))
    }

    @Test
    fun aBuildShowsThatAnEditionIsBeingMadeWhileItRuns() = runTest {
        val info = TestListenableWorkerBuilder<EditionWorker>(app).build().getForegroundInfo()

        assertEquals(Notifier.BUILDING_ID, info.notificationId)
        assertEquals(Notifier.EDITIONS, info.notification.channelId)
        assertEquals("Making your edition\u2026", info.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }

    /** Ordinary background work can wait for the phone's next batch; a scheduled edition shouldn't. */
    @Test
    fun editionBuildsAskToRunStraightAway() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        EditionWorker.buildNow(app, scheduled = true)

        val id = WorkManager.getInstance(app).getWorkInfosForUniqueWork(EditionWorker.UNIQUE).get().single().id
        assertTrue(WorkManagerImpl.getInstance(app).workDatabase.workSpecDao().getWorkSpec(id.toString())!!.expedited)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
}
