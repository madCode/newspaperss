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
import androidx.work.impl.foreground.SystemForegroundService
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        PodcastWorker.refused = false
        app.container.notifier.createChannels()
    }

    @After fun tearDown() {
        PodcastWorker.refused = false
    }

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
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo(app.packageName, Process.myPid(), arrayOf(app.packageName)).apply {
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                },
            ),
        )

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        // Asked and refused, WorkManager would ignore the phone being unplugged.
        assertNull(shown)
    }

    @Test
    fun refusedItRetriesAndTheNextRunStaysInTheBackground() = runTest {
        unrestricted(true)
        // What Android 12 and later say when foreground work starts from the background.
        val refused = ForegroundUpdater { _: Context, _: UUID, _: ForegroundInfo ->
            Futures.immediateFailedFuture<Void>(IllegalStateException("startForegroundService() not allowed"))
                as ListenableFuture<Void>
        }
        assertTrue(run(refused) is ListenableWorker.Result.Retry)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertNull(shown)
    }
}
