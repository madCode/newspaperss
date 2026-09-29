package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListTitleWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container

    private suspend fun worker(ids: List<Long>, attempt: Int) = TestListenableWorkerBuilder<ReadingListTitleWorker>(app)
        .setInputData(workDataOf(ReadingListTitleWorker.ARTICLE_IDS to ids.toLongArray()))
        .setRunAttemptCount(attempt)
        .build()
        .doWork()

    private suspend fun title() = container.db.articles().candidates().single().title

    @Test
    fun retriesAnUnreachablePageAFewTimesThenLeavesTheLinkUntitled() = runTest {
        container.readingList.save("https://a.example/story")
        app.http.unreachable += "https://a.example/story"

        assertTrue(worker(app.titlesRequested, attempt = 0) is ListenableWorker.Result.Retry)
        // Giving up is a success: a failure would cancel the batches queued behind it.
        assertTrue(worker(app.titlesRequested, attempt = ReadingListTitleWorker.MAX_ATTEMPTS - 1) is ListenableWorker.Result.Success)
        assertEquals("", title())

        app.http.unreachable.clear()
        app.http.page("https://a.example/story", "<html><head><meta property=\"og:title\" content=\"The story\"></head></html>")
        assertTrue(worker(app.titlesRequested, attempt = 1) is ListenableWorker.Result.Success)
        assertEquals("The story", title())
    }

    @Test
    fun enqueuedWorkRunsOnceTheresANetwork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        app.http.page("https://a.example/story", "<html><head><title>The story</title></head></html>")
        runBlocking { container.readingList.save("https://a.example/story") }

        ReadingListTitleWorker.enqueue(app, app.titlesRequested)
        val work = WorkManager.getInstance(app)
        val info = work.getWorkInfosForUniqueWork("reading-list-titles").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        runBlocking { assertEquals("", title()) }

        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(info.id)
        idleUntil { work.getWorkInfoById(info.id).get()!!.state == WorkInfo.State.SUCCEEDED }
        runBlocking { assertEquals("The story", title()) }
    }
}
