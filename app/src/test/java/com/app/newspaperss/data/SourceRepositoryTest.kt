package com.app.newspaperss.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourceRepositoryTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val repo = SourceRepository(db)

    @Test
    fun addingTheSameFeedTwiceKeepsOne() = runTest {
        val first = repo.addFeed("https://a.example/feed", "A")
        val second = repo.addFeed("https://a.example/feed", "A again")
        assertEquals(first, second)
        assertEquals(listOf("A"), db.sources().all().map { it.title })
    }

    @Test
    fun newSourcesGoToTheEnd() = runTest {
        repo.addFeed("https://a.example/feed", "A")
        repo.addFeed("https://b.example/feed", "B")
        assertEquals(listOf(0, 1), db.sources().all().map { it.position })
    }

    @Test
    fun opmlImportSkipsKnownFeedsAndExportRoundTrips() = runTest {
        repo.addFeed("https://a.example/feed", "A")
        val opml = """
            <opml version="2.0"><body>
              <outline text="A" xmlUrl="https://a.example/feed"/>
              <outline text="Science"><outline text="B" xmlUrl="https://b.example/rss"/></outline>
            </body></opml>
        """.trimIndent()
        assertEquals(1, repo.importOpml(opml))
        assertEquals("Science", db.sources().byUrl("https://b.example/rss")!!.section)

        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        assertEquals(2, SourceRepository(other).importOpml(repo.exportOpml()))
        other.close()
    }

    private suspend fun record(id: Long, evidence: FullTextEvidence, times: Int) = repeat(times) { repo.recordFullText(id, evidence) }

    private suspend fun source(id: Long) = db.sources().byId(id)!!

    @Test
    fun threeTeasersInARowSwitchAFeedToFetchingPages() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        record(id, FullTextEvidence.PAGE_LONGER, 2)
        assertEquals(ContentMode.AUTO, source(id).contentMode)
        record(id, FullTextEvidence.PAGE_LONGER, 1)
        assertEquals(ContentMode.PAGE, source(id).contentMode)
    }

    @Test
    fun aModeTheReaderChoseIsNeverChangedByTheCheck() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        repo.chooseContentMode(id, ContentMode.FEED)
        record(id, FullTextEvidence.PAGE_LONGER, 5)
        assertEquals(ContentMode.FEED, source(id).contentMode)
        assertTrue(source(id).contentModeChosen)
    }

    @Test
    fun choosingAutomaticAgainStartsTheCheckOver() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        record(id, FullTextEvidence.BLOCKED, 3)
        assertEquals(ContentMode.FEED, source(id).contentMode)

        repo.chooseContentMode(id, ContentMode.AUTO)

        assertEquals(ContentMode.AUTO, source(id).contentMode)
        assertFalse(source(id).contentModeChosen)
        record(id, FullTextEvidence.PAGE_LONGER, 2)
        assertEquals("earlier evidence doesn't count towards the new run", ContentMode.AUTO, source(id).contentMode)
    }

    @Test
    fun sourcesMixingManySitesAreLeftAlone() = runTest {
        val ttrss = repo.addTtrss("https://rss.example/api/")
        record(ttrss, FullTextEvidence.PAGE_LONGER, 3)
        assertEquals(ContentMode.AUTO, source(ttrss).contentMode)
    }
}
