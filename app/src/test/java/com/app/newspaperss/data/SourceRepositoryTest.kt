package com.app.newspaperss.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.testutil.DbRule
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

        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        assertEquals(2, SourceRepository(other).importOpml(repo.exportOpml()))
        other.close()
    }

    private var day = 20_000L

    /** [times] pieces of evidence, one a day. */
    private suspend fun record(id: Long, evidence: FullTextEvidence, times: Int, originId: String? = null) =
        repeat(times) { repo.recordFullText(id, originId, evidence, day = day++) }

    private suspend fun source(id: Long) = db.sources().byId(id)!!

    private suspend fun learned(id: Long, key: String = PublicationEntity.OWN) = db.sources().publication(id, key)?.contentMode ?: ContentMode.AUTO

    @Test
    fun threeTeasersInARowSwitchAFeedToFetchingPages() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        record(id, FullTextEvidence.PAGE_LONGER, 2)
        assertEquals(ContentMode.AUTO, learned(id))
        record(id, FullTextEvidence.PAGE_LONGER, 1)
        assertEquals(ContentMode.PAGE, learned(id))
    }

    @Test
    fun aModeTheReaderChoseIsNeverChangedByTheCheck() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        repo.chooseContentMode(id, PublicationEntity.OWN, ContentMode.FEED)
        record(id, FullTextEvidence.PAGE_LONGER, 5)
        val publication = db.sources().publication(id, PublicationEntity.OWN)!!
        assertEquals(ContentMode.FEED, publication.chosenMode)
        assertEquals("nothing learned against the reader's choice", null, publication.fullTextEvidence)
    }

    @Test
    fun choosingAutomaticAgainStartsTheCheckOver() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        record(id, FullTextEvidence.BLOCKED, 3)
        assertEquals(ContentMode.FEED, learned(id))

        repo.chooseContentMode(id, PublicationEntity.OWN, ContentMode.AUTO)

        assertEquals(ContentMode.AUTO, learned(id))
        assertEquals(null, db.sources().publication(id, PublicationEntity.OWN)?.chosenMode)
        record(id, FullTextEvidence.PAGE_LONGER, 2)
        assertEquals("earlier evidence doesn't count towards the new run", ContentMode.AUTO, learned(id))
    }

    @Test
    fun eachTtrssFeedLearnsOnItsOwn() = runTest {
        val ttrss = repo.addTtrss("https://rss.example/api/")
        record(ttrss, FullTextEvidence.PAGE_LONGER, 3, originId = "7")
        record(ttrss, FullTextEvidence.FEED_FULL, 3, originId = "8")
        record(ttrss, FullTextEvidence.PAGE_LONGER, 3, originId = null)

        assertEquals(ContentMode.PAGE, learned(ttrss, "7"))
        assertEquals(ContentMode.FEED, learned(ttrss, "8"))
        assertEquals("an article not from any one feed says nothing", ContentMode.AUTO, learned(ttrss))
        assertEquals(ContentMode.AUTO, source(ttrss).contentMode)
    }

    @Test
    fun theReadingListIsLeftAlone() = runTest {
        val list = db.sources().all().firstOrNull { it.kind == SourceKind.READING_LIST }?.id
            ?: db.sources().insert(SourceEntity(kind = SourceKind.READING_LIST, url = "newspaperss:reading-list", title = "Saved", contentMode = ContentMode.PAGE))
        record(list, FullTextEvidence.FEED_FULL, 3)
        assertTrue(db.sources().allPublications().isEmpty())
    }

    /** A check whose page couldn't be read still counts as the day's check; otherwise it would come back every edition. */
    @Test
    fun aCheckThatShowedNothingIsStillRememberedAndChangesNothingElse() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        record(id, FullTextEvidence.PAGE_LONGER, 2)
        repo.recordFullText(id, null, null, checked = true, day = 20_200)
        val publication = db.sources().publication(id, PublicationEntity.OWN)!!
        assertEquals(20_200L, publication.checkedDay)
        assertEquals(FullTextEvidence.PAGE_LONGER, publication.fullTextEvidence)
        assertEquals(2, publication.fullTextStreak)
    }

    @Test
    fun aCheckIsRememberedAsTheDayOfTheLastCheck() = runTest {
        val id = repo.addFeed("https://a.example/feed", "A")
        repo.recordFullText(id, null, FullTextEvidence.FEED_FULL, checked = true, day = 20_100)
        repo.recordFullText(id, null, FullTextEvidence.FEED_FULL, day = 20_101)
        assertEquals(20_100L, db.sources().publication(id, PublicationEntity.OWN)!!.checkedDay)
    }

    /** One row holds both, so neither a check nor taking a feed back may write over the other. */
    @Test
    fun leavingAFeedOutAndWhatTheCheckLearnedKeepEachOther() = runTest {
        val ttrss = repo.addTtrss("https://rss.example/api/")
        val feed = FeedChoice("7", "Teasers", inPaper = true)
        record(ttrss, FullTextEvidence.PAGE_LONGER, 3, originId = "7")
        repo.setFeedInPaper(ttrss, feed, inPaper = false)
        assertEquals(ContentMode.PAGE, learned(ttrss, "7"))

        record(ttrss, FullTextEvidence.PAGE_LONGER, 1, originId = "7")
        assertEquals(listOf("7"), db.sources().allLeftOut().map { it.key })

        repo.setFeedInPaper(ttrss, feed, inPaper = true)
        assertTrue(db.sources().allLeftOut().isEmpty())
        assertEquals(ContentMode.PAGE, learned(ttrss, "7"))
    }

    /** A tt-rss feed's cap is its own: another feed in the account keeps the edition's. */
    @Test
    fun aTtrssFeedsCapIsItsOwnAndKeepsItLeftOut() = runTest {
        val ttrss = repo.addTtrss("https://rss.example/api/")
        repo.setFeedInPaper(ttrss, FeedChoice("7", "Teasers", inPaper = true), inPaper = false)

        repo.stepMaxArticles(ttrss, "7", delta = 1, default = 1, limit = 3)
        repo.stepMaxArticles(ttrss, "7", delta = 5, default = 1, limit = 3)

        val seven = db.sources().publication(ttrss, "7")!!
        assertEquals(3, seven.maxArticles)
        assertTrue("still left out", seven.leftOut)
        assertEquals(null, db.sources().publication(ttrss, "8")?.maxArticles)

        repo.setMaxArticles(ttrss, "7", null)
        assertEquals(null, db.sources().publication(ttrss, "7")!!.maxArticles)
    }

    /** The account page's checklist falls back to a feed's id for a name; that isn't saved as one. */
    @Test
    fun leavingOutAFeedKnownOnlyByItsIdSavesNoName() = runTest {
        val ttrss = repo.addTtrss("https://rss.example/api/")
        repo.setFeedInPaper(ttrss, FeedChoice("7", "7", inPaper = true), inPaper = false)
        assertEquals(null, db.sources().publication(ttrss, "7")!!.title)
        db.sources().savePublication(db.sources().publication(ttrss, "7")!!.copy(title = "Quarterly Review"))
        repo.setFeedInPaper(ttrss, FeedChoice("7", "A feed", inPaper = false), inPaper = true)
        assertEquals("the listed name stays", "Quarterly Review", db.sources().publication(ttrss, "7")!!.title)
    }

    /** Articles by guid, each inserted in [states]' state, with the star given. */
    private suspend fun articles(vararg states: Pair<String, ArticleState>, starred: Map<String, java.time.Instant> = emptyMap()): Map<String, Long> {
        val id = repo.addFeed("https://a.example/feed", "A")
        db.articles().insertNew(states.map { (guid, state) -> ArticleEntity(sourceId = id, guid = guid, url = "https://a.example/$guid", title = guid, state = state, starredAt = starred[guid]) })
        return db.articles().allForSource(id).associate { it.guid to it.id }
    }

    private suspend fun article(id: Long) = db.articles().byId(id)!!

    @Test
    fun markingSeveralReadTakesOnlyWaitingOnesAndOneUndoPutsThemAllBackWithTheirStars() = runTest {
        val starredAt = java.time.Instant.parse("2026-09-01T08:00:00Z")
        val ids = articles("a" to ArticleState.NEW, "b" to ArticleState.NEW, "sent" to ArticleState.DELIVERED, "going" to ArticleState.IN_EDITION, starred = mapOf("b" to starredAt))

        val batch = repo.markRead(ids.values)

        assertEquals(setOf(ids["a"], ids["b"]), batch.marked.map { it.articleId }.toSet())
        assertEquals(0, batch.heldBack)
        assertEquals(ArticleState.SKIPPED, article(ids.getValue("b")).state)
        assertEquals("marking read takes the star with it", null, article(ids.getValue("b")).starredAt)
        assertEquals(ArticleState.DELIVERED, article(ids.getValue("sent")).state)
        assertEquals(ArticleState.IN_EDITION, article(ids.getValue("going")).state)

        assertEquals(2, repo.undoMarkRead(batch.marked))
        assertEquals(ArticleState.NEW, article(ids.getValue("a")).state)
        assertEquals(starredAt, article(ids.getValue("b")).starredAt)
    }

    /** A build takes the waiting articles as candidates, so a batch is held whole, never half-applied. */
    @Test
    fun whileAnEditionIsBeingMadeBatchesOfMarkingAndUnstarringAreHeldButStarringIsNot() = runTest {
        val ids = articles("a" to ArticleState.NEW, "b" to ArticleState.DELIVERED, starred = mapOf("b" to java.time.Instant.now()))
        db.editions().insert(EditionEntity(title = "Being made", createdAt = java.time.Instant.now()))

        val read = repo.markRead(ids.values)
        assertEquals(emptyList<MarkedRead>(), read.marked)
        assertEquals(1, read.heldBack)
        assertEquals(ArticleState.NEW, article(ids.getValue("a")).state)

        val unstar = repo.setStarred(ids.values, starred = false)
        assertEquals(1, unstar.heldBack)
        assertTrue(article(ids.getValue("b")).starredAt != null)

        val star = repo.setStarred(ids.values, starred = true)
        assertEquals(listOf(ids["a"]), star.changed.map { it.articleId })
        assertEquals("undoing a star is unstarring, which waits too", 1, repo.undoStars(star))
        assertTrue(article(ids.getValue("a")).starredAt != null)
    }

    @Test
    fun undoingABatchUnstarPutsBackEachStarsOwnTimeSoTheyKeepTheirPlaceInLine() = runTest {
        val first = java.time.Instant.parse("2026-09-01T08:00:00Z")
        val second = java.time.Instant.parse("2026-09-02T08:00:00Z")
        val ids = articles("a" to ArticleState.NEW, "b" to ArticleState.DELIVERED, "c" to ArticleState.NEW, starred = mapOf("a" to first, "b" to second))

        val batch = repo.setStarred(ids.values, starred = false)
        assertEquals("c wasn't starred, so there's nothing to undo for it", setOf(ids["a"], ids["b"]), batch.changed.map { it.articleId }.toSet())
        assertEquals(null, article(ids.getValue("a")).starredAt)

        assertEquals(0, repo.undoStars(batch))
        assertEquals(first, article(ids.getValue("a")).starredAt)
        assertEquals(second, article(ids.getValue("b")).starredAt)
        assertEquals(null, article(ids.getValue("c")).starredAt)
    }
}
