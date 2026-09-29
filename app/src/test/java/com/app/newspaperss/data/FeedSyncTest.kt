package com.app.newspaperss.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FeedSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private var now = Instant.parse("2026-09-29T06:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val sync = FeedSync(db, http, clock, keepFor = Duration.ofDays(7))
    private val repo = SourceRepository(db)
    private val url = "https://example.com/feed"

    @After fun close() = db.close()

    @Test
    fun newArticlesAreAddedOnceAndThePlaceholderTitleIsReplaced() = runTest {
        val id = repo.addFeed(url, title = null)
        assertEquals("example.com", db.sources().byId(id)!!.title)
        http.page(url, rss("Example Blog", "1" to "One", "2" to "Two"))

        assertEquals(SyncResult(newArticles = 2, failedSources = 0), sync.syncAll())
        http.page(url, rss("Example Blog", "2" to "Two", "3" to "Three"))
        assertEquals(1, sync.syncAll().newArticles)

        assertEquals(setOf("One", "Two", "Three"), db.articles().candidates().map { it.title }.toSet())
        assertEquals("Example Blog", db.sources().byId(id)!!.title)
    }

    @Test
    fun aTitleTheReaderChoseIsKept() = runTest {
        val id = repo.addFeed(url, title = "My name for it")
        http.page(url, rss("Example Blog", "1" to "One"))
        sync.syncAll()
        assertEquals("My name for it", db.sources().byId(id)!!.title)
    }

    @Test
    fun failuresAreRecordedAndClearedByTheNextSuccess() = runTest {
        val id = repo.addFeed(url, "Blog")
        http.page(url, "oops", code = 500)
        assertEquals(1, sync.syncAll().failedSources)
        assertEquals("The site answered with error 500.", db.sources().byId(id)!!.lastError)

        http.page(url, "<html>not a feed</html>")
        sync.syncAll()
        assertEquals("This address no longer gives a feed.", db.sources().byId(id)!!.lastError)

        http.unreachable += url
        sync.syncAll()
        assertEquals("Couldn't reach the site.", db.sources().byId(id)!!.lastError)

        http.unreachable.clear()
        http.page(url, rss("Blog", "1" to "One"))
        sync.syncAll()
        assertNull(db.sources().byId(id)!!.lastError)
    }

    @Test
    fun pausedSourcesAreNotFetched() = runTest {
        val id = repo.addFeed(url, "Blog")
        repo.update(db.sources().byId(id)!!.copy(paused = true))
        http.page(url, rss("Blog", "1" to "One"))
        assertEquals(0, sync.syncAll().newArticles)
    }

    @Test
    fun oldUnpickedFeedArticlesExpireButBroughtBackOnesStay() = runTest {
        repo.addFeed(url, "Blog")
        http.page(url, rss("Blog", "old" to "Old", "kept" to "Kept"))
        sync.syncAll()
        val kept = db.articles().candidates().first { it.guid == "kept" }
        db.articles().bringBack(listOf(kept.id))

        now = now.plus(Duration.ofDays(8))
        http.page(url, rss("Blog", "new" to "New"))
        sync.syncAll()

        assertEquals(setOf("kept", "new"), db.articles().candidates().map { it.guid }.toSet())
    }
}
