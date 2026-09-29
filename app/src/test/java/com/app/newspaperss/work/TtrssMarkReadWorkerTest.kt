package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TtrssMarkReadWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container

    private suspend fun worker(editionId: Long, attempt: Int) = TestListenableWorkerBuilder<TtrssMarkReadWorker>(app)
        .setInputData(workDataOf(TtrssMarkReadWorker.EDITION_ID to editionId))
        .setRunAttemptCount(attempt)
        .build()
        .doWork()

    @Test
    fun retriesWhileTtrssIsUnreachableThenGivesUp() = runTest {
        val server = FakeTtrss(app.http)
        assertNull(container.ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        container.feedSync.syncAll()
        val db = container.db
        val source = db.sources().ofKind(SourceKind.TTRSS).single()
        val article = db.articles().allForSource(source.id).single()
        val editionId = db.editions().insert(EditionEntity(title = "Tuesday", status = EditionStatus.READY))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = editionId, articleId = article.id, position = 0, title = "One", sourceTitle = "Example News", minutes = 1.0)))

        app.http.unreachable += server.apiUrl
        assertTrue(worker(editionId, attempt = 0) is ListenableWorker.Result.Retry)
        assertTrue(worker(editionId, attempt = TtrssMarkReadWorker.MAX_ATTEMPTS - 1) is ListenableWorker.Result.Failure)

        app.http.unreachable.clear()
        assertTrue(worker(editionId, attempt = 1) is ListenableWorker.Result.Success)
        assertEquals(listOf(10L), server.markedRead)
    }
}
