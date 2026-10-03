package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MoveFeedsWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container

    // The app's own DataStore outlives the test; nothing of this move is left for the next.
    @After fun clear() = runBlocking { container.feedMoves.restore() }

    private fun worker() = TestListenableWorkerBuilder<MoveFeedsWorker>(app).build()

    @Test
    fun aMoveStoppedPartWayIsFinishedByTheNextRun() = runTest {
        val server = FakeTtrss(app.http)
        assertNull(container.ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        val first = container.sources.addFeed("https://first.example/feed", "First")
        val second = container.sources.addFeed("https://second.example/feed", "Second")
        server.afterSubscribe = { url -> if (url == "https://first.example/feed") server.subscribeGate = CompletableDeferred() }
        assertTrue(container.feedMoves.start(listOf(first, second), TtrssCategory(0, "Uncategorized")))
        assertEquals("the work is asked for", 1, app.movesRequested)

        // Stopped while tt-rss fetches the second, as when the app dies or the system's time is up.
        val stopped = launch { worker().doWork() }
        val deadline = System.currentTimeMillis() + 5_000
        while (server.subscribed.size < 2) {
            check(System.currentTimeMillis() < deadline)
            Thread.sleep(5)
            yield()
        }
        stopped.cancelAndJoin()
        server.subscribeGate = null
        server.afterSubscribe = null

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)

        assertEquals("the first isn't asked about again", 1, server.subscribed.count { it.first == "https://first.example/feed" })
        assertEquals(2, container.feedMoves.current().moved)
        assertTrue("both are gone from the phone", container.db.sources().all().none { it.kind == SourceKind.FEED })
    }
}
