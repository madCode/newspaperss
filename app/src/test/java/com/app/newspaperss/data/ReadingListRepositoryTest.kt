package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.ReadingListFile
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListRepositoryTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val untitled = mutableListOf<Long>()
    private val list = ReadingListRepository(db, onUntitled = { untitled += it })

    private suspend fun byUrl(url: String) = db.articles().allForSource(list.sourceId()).single { it.url == url }

    @Test
    fun savingTwiceKeepsOne() = runTest {
        assertTrue(list.save("https://a.example/1", "One"))
        assertFalse(list.save("https://a.example/1"))
        assertEquals(listOf("One"), list.observe().first().map { it.title })
    }

    @Test
    fun everyLinkSavedIsLookedUpOnce() = runTest {
        // Titled ones too: the lookup also measures the page for its reading time.
        list.save("https://a.example/titled", "The reader's title")
        list.save("https://a.example/bare")
        list.save("https://a.example/bare")
        assertEquals(listOf(byUrl("https://a.example/titled").id, byUrl("https://a.example/bare").id), untitled)
    }

    @Test
    fun aPocketExportKeepsTitlesAndArrivesWithTheArchiveAlreadyRead() = runTest {
        val csv = "title,url,time_added,tags,status\n" +
            "\"Tides, explained\",https://a.example/tides,1712345678,,unread\n" +
            ",https://a.example/untitled,1712345000,,unread\n" +
            "Old news,https://a.example/old,1600000000,,archive\n" +
            ",https://a.example/old-untitled,1600000000,,archive\n"
        val result = list.import(csv)
        assertEquals(ReadingListRepository.Imported(ReadingListFile.Format.POCKET_CSV, added = 4, unread = 2), result)
        assertEquals("Tides, explained", byUrl("https://a.example/tides").title)
        assertEquals(ArticleState.DELIVERED, byUrl("https://a.example/old").state)
        assertEquals(setOf("https://a.example/tides", "https://a.example/untitled"), db.articles().candidates().map { it.url }.toSet())
        assertEquals("only unread ones are waiting (enough to start onboarding with)", 2, list.observeWaiting().first())
        // Read ones won't be in an edition, so there's no point fetching their pages.
        assertEquals(listOf(byUrl("https://a.example/untitled").id), untitled)
    }

    @Test
    fun aLinkArchivedInInstapaperIsMarkedReadHere() = runTest {
        list.save("https://a.example/1", "One")
        list.import("URL,Title,Selection,Folder,Timestamp\nhttps://a.example/1,One,,Archive,1712345678\n")
        assertTrue(db.articles().candidates().isEmpty())
    }

    @Test
    fun importedTickedItemsDontComeBackAndExportTicksWhatWasDelivered() = runTest {
        val added = list.import("- [ ] https://a.example/1\n- [x] https://a.example/2\n- [ ] https://a.example/3\n").added
        assertEquals(3, added)
        assertEquals(setOf("https://a.example/1", "https://a.example/3"), db.articles().candidates().map { it.url }.toSet())

        val one = db.articles().candidates().first { it.url == "https://a.example/1" }
        db.articles().setState(listOf(one.id), ArticleState.DELIVERED)

        assertEquals(
            "- [x] https://a.example/1\n- [x] https://a.example/2\n- [ ] https://a.example/3\n",
            list.exportMarkdown(),
        )
    }

    @Test
    fun aLinkTickedElsewhereIsMarkedReadOnReimport() = runTest {
        list.import("- [ ] https://a.example/1\n")
        list.import("- [x] https://a.example/1 (error 404)\n")
        assertTrue(db.articles().candidates().isEmpty())
    }

    @Test
    fun savedLinksNeverExpire() = runTest {
        list.save("https://a.example/1")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.now().plus(Duration.ofDays(30))
        }
        FeedSync(db, FakeHttp(), clock).syncAll()
        assertEquals(1, db.articles().candidates().size)
    }
}
