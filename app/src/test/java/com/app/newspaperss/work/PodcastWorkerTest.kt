package com.app.newspaperss.work

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.foreground.SystemForegroundService
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var shown: ForegroundInfo? = null
    private val allowed = ForegroundUpdater { _: Context, _: UUID, info: ForegroundInfo ->
        shown = info
        Futures.immediateFuture(null)
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        PodcastWorker.holdOffUntil = 0
        app.container.notifier.createChannels()
    }

    @After fun tearDown() {
        PodcastWorker.holdOffUntil = 0
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun queued() = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get()
        .count { it.state == WorkInfo.State.ENQUEUED }

    private fun onScreen(yes: Boolean) = shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(
        listOf(
            ActivityManager.RunningAppProcessInfo(app.packageName, Process.myPid(), arrayOf(app.packageName)).apply {
                importance = if (yes) ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND else ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            },
        ),
    )

    private fun unrestricted(yes: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, yes)

    private suspend fun run(updater: ForegroundUpdater) =
        TestListenableWorkerBuilder<PodcastWorker>(app).setForegroundUpdater(updater).build().doWork()

    @Test
    fun unrestrictedItGoesForegroundQuietlyAsAKindTheManifestDeclares() = runTest {
        unrestricted(true)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)

        val info = shown!!
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(info.notification.channelId)
        assertEquals(Notifier.PODCAST, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        // A kind the service doesn't declare crashes the app inside WorkManager's service.
        val declared = app.packageManager.getServiceInfo(ComponentName(app, SystemForegroundService::class.java), 0).foregroundServiceType
        assertTrue(info.foregroundServiceType != 0)
        assertEquals(info.foregroundServiceType, info.foregroundServiceType and declared)
    }

    @Test
    fun restrictedAndOffScreenItDoesntAskButStillMakesIt() = runTest {
        unrestricted(false)
        onScreen(false)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        // Asked and refused, WorkManager would ignore the phone being unplugged.
        assertNull(shown)
    }

    @Test
    fun restrictedButOnScreenItAsks() = runTest {
        unrestricted(false)
        onScreen(true)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertTrue(shown != null)
    }

    @Test
    fun refusedItEndsAndTheNextRunStaysInTheBackground() = runTest {
        unrestricted(true)
        // What Android 12 and later say when foreground work starts from the background.
        val refused = ForegroundUpdater { _: Context, _: UUID, _: ForegroundInfo ->
            Futures.immediateFailedFuture<Void>(IllegalStateException("startForegroundService() not allowed"))
                as ListenableFuture<Void>
        }
        // Ended, so WorkManager lets go of the half-foreground work, and queued to go on.
        assertTrue(run(refused) is ListenableWorker.Result.Success)
        assertEquals(1, queued())

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertNull(shown)
    }

    @Test
    fun makingStopsWhenItShouldntGoOnAndSaysSo() = runTest {
        var going = true
        var made = false
        val stopped = async {
            PodcastWorker.makeWhile(1_000, {
                delay(10_000)
                made = true
            }, {}) { going }
        }
        advanceTimeBy(2_500)
        going = false
        advanceTimeBy(1_000)
        assertTrue(stopped.await())
        assertFalse(made)
    }

    @Test
    fun makingThatFinishesIsntStopped() = runTest {
        assertFalse(PodcastWorker.makeWhile(1_000, { delay(2_500) }, {}) { true })
    }

    @Test
    fun stoppedFromOutsideItSaysSoBeforeTheSentenceEnds() = runTest {
        var told = false
        var sentenceDone = false
        val work = launch {
            PodcastWorker.makeWhile(1_000, {
                // Kokoro's native code finishes its sentence whatever the coroutine says.
                withContext(NonCancellable) { delay(10_000) }
                sentenceDone = true
            }, { told = true }) { true }
        }
        advanceTimeBy(2_500)
        work.cancel()
        runCurrent()
        assertTrue(told)
        assertFalse(sentenceDone)
        work.join()
    }
}
