package com.app.newspaperss.work

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun worker(updater: ForegroundUpdater) = TestListenableWorkerBuilder<PodcastWorker>(app)
        .setForegroundUpdater(updater)
        .build()

    @Test
    fun goesForegroundWithAQuietNotification() = runTest {
        app.container.notifier.createChannels()
        var shown: ForegroundInfo? = null
        val result = worker { _, _, info -> shown = info; Futures.immediateFuture(null) }.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val notification: Notification = shown!!.notification
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)
        assertEquals(Notifier.PODCAST, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun refusedTheForegroundItStillRunsInTheBackground() = runTest {
        // What Android 12 and later say when foreground work starts from the background.
        val refused = ForegroundUpdater { _: Context, _: UUID, _: ForegroundInfo ->
            Futures.immediateFailedFuture<Void>(IllegalStateException("startForegroundService() not allowed"))
                as ListenableFuture<Void>
        }
        assertTrue(worker(refused).doWork() is ListenableWorker.Result.Success)
    }
}
