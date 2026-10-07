package com.app.newspaperss.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private suspend fun work() = TestListenableWorkerBuilder<PodcastWorker>(context).build().doWork()

    // Nothing asks for a podcast through a public route, so the failure branch -- Kokoro not
    // loading, turned into a Result Android can run again -- needs the engine and isn't covered.
    @Test fun nothingAskedForIsNotAFailure() = runTest {
        assertTrue(work() is ListenableWorker.Result.Success)
    }
}
