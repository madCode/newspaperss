package com.app.newspaperss.edition

import com.app.newspaperss.data.FeedChoice
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.notes.Reflection
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.MarkReadBatch
import com.app.newspaperss.data.PaidOnlyCount
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val clock = Clock.fixed(Instant.parse("2026-09-29T06:30:00Z"), ZoneOffset.UTC)
    private val sources = SourceRepository(db, clock)
    private val unreadable = mutableSetOf<String>()
    private val broken = mutableSetOf<String>()
    private val content = ArticleContentProvider { a, _, _ ->
        if (a.guid in broken) throw IllegalStateException("parser crashed on ${a.url}")
        if (a.guid in unreadable) null else ArticleContent(a.title, null, "<p>${a.title} body</p>", wordCount = 2000)
    }
    private val builder by lazy { EditionBuilder(db, content, tmp.root, clock, ZoneOffset.UTC) }
    private val editions by lazy { EditionRepository(db, tmp.root, clock) }

    private suspend fun source(name: String, section: String? = null, vararg guids: String): Long {
        val id = sources.addFeed("https://$name.example/feed", name, section)
        db.articles().insertNew(
            guids.mapIndexed { i, g ->
                ArticleEntity(
                    sourceId = id, guid = g, url = "https://$name.example/$g", title = "$name $g",
                    published = Instant.parse("2026-09-2${i}T00:00:00Z"),
                )
            },
        )
        return id
    }

    private fun stateOf(guid: String) = db.query("SELECT state FROM articles WHERE guid = ?", arrayOf(guid)).use { c ->
        c.moveToFirst(); ArticleState.valueOf(c.getString(0))
    }

    @Test
    fun aDeletedEditionsTitleIsntReused() = runTest {
        // Send to Kindle drops a title it has seen, and the deleted one may already have been sent.
        source("a", "World", "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(first.editionId)
        assertTrue(editions.delete(first.editionId))

        source("b", "World", "b1")
        val second = builder.build(EditionSettings()) as BuildResult.Built

        assertEquals("Tuesday Morning Edition, Sep 29 (2)", db.editions().byId(second.editionId)!!.title)
    }

    @Test
    fun aTimedEditionBuiltBeforeMidnightIsTitledForTheDayItsDue() = runTest {
        source("a", "World", "a1")
        val lateMonday = EditionBuilder(db, content, tmp.root, Clock.fixed(Instant.parse("2026-09-28T23:40:00Z"), ZoneOffset.UTC), ZoneOffset.UTC)

        val result = lateMonday.build(EditionSettings(), dueAt = Instant.parse("2026-09-29T00:10:00Z")) as BuildResult.Built

        assertEquals("Tuesday Evening Edition, Sep 29", db.editions().byId(result.editionId)!!.title)

        // Send to Kindle drops a document whose title it has already seen.
        source("b", "World", "b1")
        val tuesdayEvening = EditionBuilder(db, content, tmp.root, Clock.fixed(Instant.parse("2026-09-29T20:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC)
        val second = tuesdayEvening.build(EditionSettings()) as BuildResult.Built
        assertEquals("Tuesday Evening Edition, Sep 29 (2)", db.editions().byId(second.editionId)!!.title)
    }

    @Test
    fun buildsAnEpubWithinTheBudgetTakingTurnsAndGroupingBySection() = runTest {
        source("a", "World", "a1", "a2")
        source("b", "Culture", "b1")
        source("c", "World", "c1")

        val result = builder.build(EditionSettings(minutes = 25, maxPerSource = 1, wordsPerMinute = 200)) as BuildResult.Built

        val edition = db.editions().byId(result.editionId)!!
        assertEquals("Tuesday Morning Edition, Sep 29", edition.title)
        assertEquals(EditionStatus.READY, edition.status)
        assertEquals(3, edition.articleCount)
        val titles = editions.observeArticles(edition.id).first().map { it.title }
        assertEquals("sections group the reading order", listOf("a a2", "c c1", "b b1"), titles)

        ZipFile(editions.fileOf(edition)!!).use { zip ->
            assertTrue(zip.entries().toList().any { it.name.endsWith(".xhtml") })
        }
        // Send to Kindle titles the book after the file it's given.
        assertEquals("Tuesday Morning Edition, Sep 29.epub", edition.fileName)
        assertEquals(ArticleState.IN_EDITION, stateOf("a2"))
        assertEquals("the second article of a capped source waits", ArticleState.NEW, stateOf("a1"))
    }

    @Test
    fun theClosingPageAsksTheSameQuestionTheNotesStartWith() = runTest {
        // What she turned over on the Kindle is waiting in her notes app.
        source("a", null, "a1")
        val first = (builder.build(EditionSettings()) as BuildResult.Built).editionId
        val question = Reflection.forEdition(first)

        val end = ZipFile(editions.fileOf(db.editions().byId(first)!!)!!).use { zip ->
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(zip.getInputStream(zip.getEntry("OEBPS/end.xhtml"))).documentElement.textContent
        }
        assertTrue(end.contains(question))
        val notes = EditionNotes(db, tmp.newFolder("notes")).write(first)!!.readText()
        assertTrue(notes.contains("From the end of the paper: *$question*"))
    }

    @Test
    fun theNextEditionStartsWithTheSourcesTheLastOneLeftOut() = runTest {
        for (name in listOf("a", "b", "c", "d")) source(name, null, "${name}1", "${name}2", "${name}3")
        val settings = EditionSettings(minutes = 15, maxPerSource = 1, wordsPerMinute = 200)
        fun sourcesOf(id: Long) = runBlocking { editions.observeArticles(id).first().map { it.title.substringBefore(' ') }.toSet() }

        val first = builder.build(settings) as BuildResult.Built
        editions.markDelivered(first.editionId)
        val second = builder.build(settings) as BuildResult.Built

        assertEquals(setOf("a", "b"), sourcesOf(first.editionId))
        assertEquals("not b and c, sliding by one", setOf("c", "d"), sourcesOf(second.editionId))
    }

    @Test
    fun eachTtrssFeedTakesItsOwnTurnAcrossEditions() = runTest {
        val account = sources.addTtrss("https://rss.example/api/")
        db.articles().insertNew(
            listOf("f1", "f2", "f3", "f4").flatMap { feed ->
                (1..2).map { n ->
                    ArticleEntity(
                        sourceId = account, guid = "ttrss:$feed$n", url = "https://$feed.example/$n", title = "$feed $n",
                        published = Instant.parse("2026-09-2${n}T00:00:00Z"), originId = feed, originTitle = feed,
                    )
                }
            },
        )
        val settings = EditionSettings(minutes = 15, maxPerSource = 1, wordsPerMinute = 200)
        fun feedsOf(id: Long) = runBlocking { editions.observeArticles(id).first().map { it.title.substringBefore(' ') }.toSet() }

        val first = builder.build(settings) as BuildResult.Built
        editions.markDelivered(first.editionId)
        val second = builder.build(settings) as BuildResult.Built

        assertEquals(2, feedsOf(first.editionId).size)
        assertTrue("the next edition takes the other two feeds", feedsOf(first.editionId).intersect(feedsOf(second.editionId)).isEmpty())
    }

    @Test
    fun aLeftOutTtrssFeedStaysOutUnlessStarred() = runTest {
        val account = sources.addTtrss("https://rss.example/api/")
        db.articles().insertNew(
            listOf("news", "essays").flatMap { feed ->
                (1..2).map { n -> ArticleEntity(sourceId = account, guid = "ttrss:$feed$n", url = "https://$feed.example/$n", title = "$feed $n", originId = feed, originTitle = feed) }
            },
        )
        val starred = db.articles().allForSource(account).single { it.guid == "ttrss:news2" }.id
        db.articles().setStarred(starred, true, clock.instant())
        sources.setFeedInPaper(account, FeedChoice("news", "news", inPaper = true), inPaper = false)

        val built = builder.build(EditionSettings(minutes = 120)) as BuildResult.Built

        val titles = editions.observeArticles(built.editionId).first().map { it.title }.toSet()
        assertEquals(setOf("news 2", "essays 1", "essays 2"), titles)
        assertEquals(ArticleState.EXPIRED, stateOf("ttrss:news1"))
    }

    @Test
    fun onlyLeftOutFeedsWaitingMeansNothingNewRatherThanAFailure() = runTest {
        val account = sources.addTtrss("https://rss.example/api/")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = account, guid = "ttrss:1", url = "https://news.example/1", title = "news 1", originId = "news", originTitle = "news")))
        sources.setFeedInPaper(account, FeedChoice("news", "news", inPaper = true), inPaper = false)
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun anEditionThatWasNeverSentDoesntCountAsTheirTurn() = runTest {
        for (name in listOf("a", "b", "c", "d")) source(name, null, "${name}1", "${name}2", "${name}3")
        val settings = EditionSettings(minutes = 15, maxPerSource = 1, wordsPerMinute = 200)
        fun sourcesOf(id: Long) = runBlocking { editions.observeArticles(id).first().map { it.title.substringBefore(' ') }.toSet() }

        val unsent = builder.build(settings) as BuildResult.Built
        val next = builder.build(settings) as BuildResult.Built

        assertEquals(sourcesOf(unsent.editionId), sourcesOf(next.editionId))
    }

    @Test
    fun aStarredArticleDoesntUseUpItsSourcesTurn() = runTest {
        val a = source("a", null, "a1")
        val d = source("d", null, "d1", "d2")
        editions.setStarred(db.query("SELECT id FROM articles WHERE guid = 'd1'", null).use { it.moveToFirst(); it.getLong(0) }, true)

        val first = builder.build(EditionSettings(minutes = 15, maxPerSource = 1, wordsPerMinute = 200)) as BuildResult.Built
        editions.markDelivered(first.editionId)

        // d went in by its star; its own turn, for d2, still counts as never taken.
        assertEquals(listOf(a), db.editions().lastFeatured().map { it.sourceId })
        assertTrue(d !in db.editions().lastFeatured().map { it.sourceId })
    }

    /** A source's page names the edition holding an article, then the day it went out. */
    @Test
    fun anArticlesHistoryNamesItsUnsentEditionThenWhenItWentOut() = runTest {
        val id = source("a", null, "a1")
        val built = builder.build(EditionSettings()) as BuildResult.Built
        val title = db.editions().byId(built.editionId)!!.title

        val planned = sources.observeHistory(id).first().getValue(idOf("a1"))
        assertEquals(title, planned.editionTitle)
        assertNull(planned.sentAt)

        editions.markDelivered(built.editionId)
        val sent = sources.observeHistory(id).first().getValue(idOf("a1"))
        assertEquals(clock.instant(), sent.sentAt)
        assertNull(sent.editionTitle)
    }

    /** Starred from a sent edition that's then marked not sent, an article is in two unsent ones: name the newer. */
    @Test
    fun anArticleInTwoUnsentEditionsNamesTheNewer() = runTest {
        val id = source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(first.editionId)
        editions.setStarred(idOf("a1"), true)
        val second = builder.build(EditionSettings()) as BuildResult.Built
        assertTrue(editions.markNotSent(first.editionId))

        assertEquals(db.editions().byId(second.editionId)!!.title, sources.observeHistory(id).first().getValue(idOf("a1")).editionTitle)
    }

    @Test
    fun articlesAreOnlyUsedUpOnceDelivered() = runTest {
        source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        assertEquals(ArticleState.IN_EDITION, stateOf("a1"))

        editions.markDelivered(first.editionId)
        assertEquals(ArticleState.DELIVERED, stateOf("a1"))
        assertEquals(EditionStatus.DELIVERED, db.editions().byId(first.editionId)!!.status)
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun anEditionNeverSentGivesItsArticlesToTheNextOne() = runTest {
        source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        source("b", null, "b1")

        val second = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(EditionStatus.FAILED, db.editions().byId(first.editionId)!!.status)
        val titles = editions.observeArticles(second.editionId).first().map { it.title }.toSet()
        assertEquals(setOf("a a1", "b b1"), titles)
        assertEquals("Tuesday Morning Edition, Sep 29 (2)", db.editions().byId(second.editionId)!!.title)
    }

    @Test
    fun unreadableArticlesAreSkippedAndAnAllUnreadableEditionFails() = runTest {
        source("a", null, "a1", "a2")
        unreadable += "a2"
        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built
        assertEquals(listOf("a a1"), editions.observeArticles(built.editionId).first().map { it.title })

        unreadable += "b1"
        source("b", null, "b1")
        db.articles().setState(listOf(db.articles().candidates().first { it.guid == "a2" }.id), ArticleState.SKIPPED)
        editions.markDelivered(built.editionId)
        val failed = builder.build(EditionSettings()) as BuildResult.Failed
        assertEquals(EditionStatus.FAILED, db.editions().byId(failed.editionId)!!.status)
        assertEquals(ArticleState.NEW, stateOf("b1"))
    }

    @Test
    fun aSourcesOwnCapIsAHardLimitWhereTheEditionsGivesWay() = runTest {
        val a = source("a", null, "a1", "a2", "a3")
        source("b", null, "b1", "b2")
        sources.setMaxArticles(a, 2)

        val built = builder.build(EditionSettings(minutes = 600, maxPerSource = 1)) as BuildResult.Built

        val titles = editions.observeArticles(built.editionId).first().map { it.title }
        assertEquals("a's own cap of 2 holds with room left", 2, titles.count { it.startsWith("a ") })
        assertEquals("the edition's cap of 1 gives way when there's room", 2, titles.count { it.startsWith("b ") })
    }

    @Test
    fun aLinkInTwoSourcesGoesInOnce() = runTest {
        source("a", null, "a1")
        val b = sources.addFeed("https://b.example/feed", "b")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = b, guid = "b-copy", url = "https://a.example/a1", title = "b copy")))

        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(1, editions.observeArticles(built.editionId).first().size)
    }

    @Test
    fun pausedSourcesAreLeftOut() = runTest {
        val id = source("a", null, "a1")
        sources.update(db.sources().byId(id)!!.copy(paused = true))
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    private suspend fun idOf(guid: String) = db.query("SELECT id FROM articles WHERE guid = ?", arrayOf(guid)).use { c -> c.moveToFirst(); c.getLong(0) }

    private suspend fun starredAt(guid: String) = db.articles().byId(idOf(guid))!!.starredAt

    @Test
    fun aStarredDeliveredArticleComesBackAndLosesItsStarOnceDeliveredAgain() = runTest {
        source("a", null, "a1", "a2")
        val first = builder.build(EditionSettings(minutes = 5)) as BuildResult.Built
        editions.markDelivered(first.editionId)
        val delivered = db.editions().articleIds(first.editionId)
        assertTrue(editions.setStarred(delivered.single(), true))

        val second = builder.build(EditionSettings(minutes = 5)) as BuildResult.Built
        assertEquals("the star takes a's one slot ahead of its waiting article", delivered, db.editions().articleIds(second.editionId))
        assertTrue("the edition remembers it went in starred", editions.observeContents(second.editionId).first().single().entry.starred)
        editions.markDelivered(second.editionId)
        assertEquals(ArticleState.DELIVERED, db.articles().byId(delivered.single())!!.state)
        assertEquals(null, db.articles().byId(delivered.single())!!.starredAt)
    }

    @Test
    fun anArticleInAnUnsentEditionCantBeStarredOrUnstarred() = runTest {
        source("a", null, "a1")
        editions.setStarred(idOf("a1"), true)
        builder.build(EditionSettings()) as BuildResult.Built

        assertFalse(editions.setStarred(idOf("a1"), false))
        assertTrue(starredAt("a1") != null)
    }

    @Test
    fun anEditionNeverSentGivesBackItsStarsAndADeliveredLinkGoesBackToDelivered() = runTest {
        source("a", null, "a1", "a2")
        val first = builder.build(EditionSettings(minutes = 5)) as BuildResult.Built
        editions.markDelivered(first.editionId)
        editions.setStarred(idOf("a2"), true)
        editions.setStarred(idOf("a1"), true)
        val unsent = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built
        assertEquals(2, db.editions().articleIds(unsent.editionId).size)

        editions.delete(unsent.editionId)

        assertEquals("delivered before, so delivered again rather than waiting", ArticleState.DELIVERED, stateOf("a2"))
        assertEquals(ArticleState.NEW, stateOf("a1"))
        assertTrue(starredAt("a1") != null && starredAt("a2") != null)
        editions.setStarred(idOf("a2"), false)
        assertEquals(ArticleState.DELIVERED, stateOf("a2"))
    }

    @Test
    fun whenALinkWaitsInTwoSourcesTheStarredCopyGoesIn() = runTest {
        source("a", null, "a1")
        val b = sources.addFeed("https://b.example/feed", "b")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = b, guid = "b-copy", url = "https://a.example/a1", title = "b copy")))
        editions.setStarred(idOf("b-copy"), true)

        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(listOf("b copy"), editions.observeArticles(built.editionId).first().map { it.title })
    }

    @Test
    fun starringAnotherSourcesCopyOfALinkInAnUnsentEditionDoesntSendItTwice() = runTest {
        source("a", null, "a1")
        val b = sources.addFeed("https://b.example/feed", "b")
        val unsent = builder.build(EditionSettings()) as BuildResult.Built
        db.articles().insertNew(listOf(ArticleEntity(sourceId = b, guid = "b-copy", url = "https://a.example/a1", title = "b copy")))
        editions.setStarred(idOf("b-copy"), true)

        editions.markDelivered(unsent.editionId)

        assertEquals(ArticleState.DELIVERED, stateOf("b-copy"))
        assertEquals(null, starredAt("b-copy"))
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun marksAndUnstarsDuringABuildWaitSoTheBookAndTheArticlesAgree() = runTest {
        source("a", null, "a1")
        source("b", null, "b1")
        editions.setStarred(idOf("b1"), true)
        var markedDuringBuild: MarkReadBatch? = null
        var unstarredDuringBuild: Boolean? = null
        val meddling = ArticleContentProvider { a, _, _ ->
            if (a.guid == "a1") {
                markedDuringBuild = sources.markRead(listOf(a.id))
                unstarredDuringBuild = sources.setStarred(idOf("b1"), false)
            }
            ArticleContent(a.title, null, "<p>${a.title}</p>", wordCount = 200)
        }
        val built = EditionBuilder(db, meddling, tmp.root, clock, ZoneOffset.UTC).build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(MarkReadBatch(emptyList(), heldBack = 1), markedDuringBuild)
        assertEquals(false, unstarredDuringBuild)
        assertEquals(2, db.editions().articleIds(built.editionId).size)
        assertEquals(ArticleState.IN_EDITION, stateOf("a1"))
        assertTrue("still starred, as the edition says", starredAt("b1") != null)

        editions.delete(built.editionId)
        assertEquals("allowed again once the build is over", 1, sources.markRead(listOf(idOf("a1"))).marked.size)
    }

    @Test
    fun aBuildWithNothingToPickLeavesNoEditionBehindToHoldTheButtons() = runTest {
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
        assertEquals(0, db.editions().count())
        assertFalse(editions.observeBuilding().first())
    }

    @Test
    fun aBuildLeftBehindByACrashStopsHoldingTheButtonsAfterTwoHours() = runTest {
        db.editions().insert(EditionEntity(title = "Crashed", createdAt = clock.instant().minus(Duration.ofHours(3))))
        assertFalse(editions.observeBuilding().first())
        db.editions().insert(EditionEntity(title = "Running", createdAt = clock.instant().minusSeconds(60)))
        assertTrue(editions.observeBuilding().first())
    }

    @Test
    fun aReSavedLinkThatWasDeliveredBeforeGoesBackToWaitingIfItsEditionIsNeverSent() = runTest {
        val list = ReadingListRepository(db)
        list.save("https://saved.example/1")
        editions.markDelivered((builder.build(EditionSettings()) as BuildResult.Built).editionId)
        // Saved again after going out, as a new row (the old one removed from the list).
        db.articles().delete(idOf("https://saved.example/1"))
        list.save("https://saved.example/1")

        val unsent = builder.build(EditionSettings()) as BuildResult.Built
        editions.delete(unsent.editionId)

        assertEquals(ArticleState.NEW, stateOf("https://saved.example/1"))
    }

    @Test
    fun aStarredArticleThatWasMarkedReadIsStillMarkedReadIfItsEditionIsNeverSentAndItsUnstarred() = runTest {
        source("a", null, "a1")
        sources.markRead(listOf(idOf("a1")))
        editions.setStarred(idOf("a1"), true)
        val unsent = builder.build(EditionSettings()) as BuildResult.Built

        editions.delete(unsent.editionId)
        assertEquals(ArticleState.SKIPPED, stateOf("a1"))
        assertTrue(starredAt("a1") != null)
        editions.setStarred(idOf("a1"), false)

        assertEquals(ArticleState.SKIPPED, stateOf("a1"))
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))
    }

    @Test
    fun aLinkStarredInTwoSourcesCountsOnceOnToday() = runTest {
        source("a", null, "a1")
        val b = sources.addFeed("https://b.example/feed", "b")
        db.articles().insertNew(listOf(ArticleEntity(sourceId = b, guid = "b-copy", url = "https://a.example/a1", title = "b copy")))
        editions.setStarred(idOf("a1"), true)
        editions.setStarred(idOf("b-copy"), true)

        assertEquals(1, editions.observeStarredWaiting().first())
    }

    @Test
    fun aPausedSourceHoldsItsStarsAndTheyArentCountedAsWaiting() = runTest {
        val a = source("a", null, "a1")
        source("b", null, "b1")
        editions.setStarred(idOf("a1"), true)
        assertEquals(1, editions.observeStarredWaiting().first())
        sources.setPaused(a, true)
        assertEquals(0, editions.observeStarredWaiting().first())

        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(listOf("b b1"), editions.observeArticles(built.editionId).first().map { it.title })
        assertTrue(starredAt("a1") != null)
    }

    @Test
    fun starsThatDontFitWaitAndAreCountedOnToday() = runTest {
        source("a", null, "a1", "a2", "a3")
        listOf("a1", "a2", "a3").forEach { editions.setStarred(idOf(it), true) }

        builder.build(EditionSettings(minutes = 5, maxPerSource = 5, wordsPerMinute = 200)) as BuildResult.Built

        assertEquals("one 10-minute article fills a 5-minute paper", 2, editions.observeStarredWaiting().first())
    }

    @Test
    fun removingASourceTakesItsStarsWithIt() = runTest {
        val a = source("a", null, "a1")
        editions.setStarred(idOf("a1"), true)
        sources.remove(db.sources().byId(a)!!)
        assertEquals(0, editions.observeStarredWaiting().first())
    }

    @Test
    fun anArticleMarkedReadIsNeverPickedUntilUndone() = runTest {
        source("a", null, "a1")
        val marked = sources.markRead(listOf(idOf("a1"))).marked.single()
        assertEquals(BuildResult.NothingNew, builder.build(EditionSettings()))

        assertTrue(sources.undoMarkRead(marked))
        assertTrue(builder.build(EditionSettings()) is BuildResult.Built)
    }

    @Test
    fun eachArticlesLanguageReachesTheBook() = runTest {
        source("a", null, "a1")
        val french = ArticleContentProvider { a, _, _ -> ArticleContent(a.title, null, "<p>Bonjour</p>", wordCount = 238, language = "fr") }
        val built = EditionBuilder(db, french, tmp.root, clock, ZoneOffset.UTC).build(EditionSettings()) as BuildResult.Built

        ZipFile(editions.fileOf(db.editions().byId(built.editionId)!!)!!).use { zip ->
            val chapter = String(zip.getInputStream(zip.getEntry("OEBPS/article-001.xhtml")).readBytes())
            assertTrue(chapter, "<div class=\"article-body\" xml:lang=\"fr\" lang=\"fr\">" in chapter)
        }
    }

    @Test
    fun imagesPastTheEditionBudgetAreLeftOutInReadingOrder() = runTest {
        source("a", null, "a1", "a2")
        val withImage = ArticleContentProvider { a, _, _ ->
            val href = "images/a${a.id}-1.jpg"
            ArticleContent(
                a.title, null, "<p>${a.title}</p><figure><img src=\"$href\"/><figcaption>${a.guid} caption</figcaption></figure>",
                wordCount = 238, images = listOf(EpubImage(href, "image/jpeg", ByteArray(60))),
            )
        }
        val built = EditionBuilder(db, withImage, tmp.root, clock, ZoneOffset.UTC, imageBudgetBytes = 100)
            .build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        val edition = db.editions().byId(built.editionId)!!
        ZipFile(editions.fileOf(edition)!!).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(1, names.count { it.startsWith("OEBPS/images/") })
            val chapters = zip.entries().toList().filter { it.name.endsWith(".xhtml") }
                .map { String(zip.getInputStream(it).readBytes()) }
            assertEquals("only the first article in reading order keeps its image", 1, chapters.count { "<img" in it })
            assertEquals(1, chapters.count { "caption</figcaption>" in it })
        }
    }

    @Test
    fun theCoverShowsTheEditionInReadingOrderAndGoesIntoTheEpub() = runTest {
        source("a", "World", "a1")
        source("b", "Culture", "b1")
        source("c", "World", "c1")
        val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)
        var drawn: CoverInfo? = null
        val built = EditionBuilder(db, content, tmp.root, clock, ZoneOffset.UTC) { info ->
            drawn = info
            EpubImage("images/cover.jpg", "image/jpeg", coverBytes)
        }.build(EditionSettings(maxPerSource = 5, wordsPerMinute = 200)) as BuildResult.Built

        val edition = db.editions().byId(built.editionId)!!
        val info = drawn!!
        assertEquals(edition.title, info.title)
        assertEquals(java.time.LocalDate.of(2026, 9, 29), info.date)
        assertEquals(listOf(CoverHeadline("a a1", "a"), CoverHeadline("c c1", "c"), CoverHeadline("b b1", "b")), info.headlines)
        assertEquals(3, info.articleCount)
        assertEquals(edition.minutes, info.minutes, 0.001)
        ZipFile(editions.fileOf(edition)!!).use { zip ->
            assertTrue(zip.getInputStream(zip.getEntry("OEBPS/images/cover.jpg")).readBytes().contentEquals(coverBytes))
            val opf = String(zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes())
            assertTrue(opf.contains("properties=\"cover-image\""))
        }
    }

    @Test
    fun aCoverThatFailsToDrawLeavesATextCoverInsteadOfFailingTheEdition() = runTest {
        source("a", null, "a1")
        val built = EditionBuilder(db, content, tmp.root, clock, ZoneOffset.UTC) { error("no fonts") }
            .build(EditionSettings()) as BuildResult.Built

        val edition = db.editions().byId(built.editionId)!!
        assertEquals(EditionStatus.READY, edition.status)
        ZipFile(editions.fileOf(edition)!!).use { zip ->
            assertTrue(zip.entries().toList().none { it.name == "OEBPS/images/cover.jpg" })
            assertTrue(String(zip.getInputStream(zip.getEntry("OEBPS/cover.xhtml")).readBytes()).contains(edition.title))
        }
    }

    @Test
    fun theCoverCountsAgainstTheEditionsImageBudget() = runTest {
        source("a", null, "a1")
        val href = "images/a1-1.jpg"
        val withImage = ArticleContentProvider { a, _, _ ->
            ArticleContent(a.title, null, "<p><img src=\"$href\"/></p>", wordCount = 238, images = listOf(EpubImage(href, "image/jpeg", ByteArray(60))))
        }
        val built = EditionBuilder(db, withImage, tmp.root, clock, ZoneOffset.UTC, imageBudgetBytes = 100) {
            EpubImage("images/cover.jpg", "image/jpeg", ByteArray(50))
        }.build(EditionSettings()) as BuildResult.Built

        ZipFile(editions.fileOf(db.editions().byId(built.editionId)!!)!!).use { zip ->
            val images = zip.entries().toList().map { it.name }.filter { it.startsWith("OEBPS/images/") }
            assertEquals("60 + 50 bytes is over the 100 byte budget", listOf("OEBPS/images/cover.jpg"), images)
        }
    }

    @Test
    fun removingASourceKeepsPastEditionsContents() = runTest {
        val id = source("a", null, "a1")
        val built = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(built.editionId)

        sources.remove(db.sources().byId(id)!!)

        assertEquals(listOf("a a1"), editions.observeArticles(built.editionId).first().map { it.title })
    }

    @Test
    fun anArticleThatCrashesTheProviderIsSkipped() = runTest {
        source("a", null, "a1", "a2", "a3")
        broken += "a2"

        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(EditionStatus.READY, db.editions().byId(built.editionId)!!.status)
        assertEquals(setOf("a a1", "a a3"), editions.observeArticles(built.editionId).first().map { it.title }.toSet())
        assertEquals(ArticleState.NEW, stateOf("a2"))
    }

    @Test
    fun anEditionWhoseEveryArticleCrashesFailsInsteadOfStayingBuilding() = runTest {
        source("a", null, "a1", "a2")
        broken += setOf("a1", "a2")

        val failed = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Failed

        assertEquals(EditionStatus.FAILED, db.editions().byId(failed.editionId)!!.status)
        assertEquals(ArticleState.NEW, stateOf("a1"))
    }

    @Test
    fun anUnexpectedErrorFailsTheEditionAndLeavesItsArticlesForTheNextOne() = runTest {
        source("a", null, "a1")

        val failed = builder.build(EditionSettings()) { error("progress reporting broke") } as BuildResult.Failed

        val edition = db.editions().byId(failed.editionId)!!
        assertEquals(EditionStatus.FAILED, edition.status)
        assertEquals(EditionBuilder.UNEXPECTED, edition.error)
        assertEquals(ArticleState.NEW, stateOf("a1"))
        assertTrue(builder.build(EditionSettings()) is BuildResult.Built)
    }

    @Test
    fun aCancelledBuildIsMarkedFailed() = runTest {
        source("a", null, "a1")
        val fetching = CompletableDeferred<Unit>()
        val hanging = ArticleContentProvider { _, _, _ ->
            fetching.complete(Unit)
            awaitCancellation()
        }
        val job = launch { EditionBuilder(db, hanging, tmp.root, clock, ZoneOffset.UTC).build(EditionSettings()) }
        fetching.await()

        job.cancelAndJoin()

        val edition = db.editions().withStatus(EditionStatus.FAILED).single()
        assertEquals(EditionBuilder.STOPPED, edition.error)
        assertEquals(ArticleState.NEW, stateOf("a1"))
    }

    @Test
    fun anEditionLeftBuildingByADeadProcessIsMarkedFailedByTheNextBuild() = runTest {
        val stuck = db.editions().insert(EditionEntity(title = "Monday Evening Edition", status = EditionStatus.BUILDING))
        source("a", null, "a1")

        builder.build(EditionSettings()) as BuildResult.Built

        val edition = db.editions().byId(stuck)!!
        assertEquals(EditionStatus.FAILED, edition.status)
        assertEquals(EditionBuilder.INTERRUPTED, edition.error)
    }

    /** A tt-rss account's source with articles given as guid to (feed id, feed title). */
    private suspend fun ttrss(vararg articles: Pair<String, Pair<String, String>>): Long {
        val id = sources.addTtrss("https://rss.example.com/api/")
        db.articles().insertNew(
            articles.mapIndexed { i, (guid, feed) ->
                ArticleEntity(
                    sourceId = id, guid = guid, url = "https://news.example/$guid", title = "t $guid",
                    published = Instant.parse("2026-09-2${i}T00:00:00Z"), originId = feed.first, originTitle = feed.second,
                )
            },
        )
        return id
    }

    @Test
    fun eachTtrssPublicationIsCappedAndBylinedOnItsOwn() = runTest {
        source("a", null, "a1", "a2")
        ttrss("n1" to ("1" to "Example News"), "n2" to ("1" to "Example News"), "b1" to ("2" to "A Blog"))

        // Room for three 10-minute articles: one per publication, if each is capped on its own.
        val built = builder.build(EditionSettings(minutes = 30, maxPerSource = 1, wordsPerMinute = 200)) as BuildResult.Built

        val contents = editions.observeArticles(built.editionId).first()
        assertEquals(listOf("a", "Example News", "A Blog").sorted(), contents.map { it.sourceTitle }.sorted())
        assertEquals("one tt-rss publication waits its turn like any capped feed", ArticleState.NEW, stateOf("n1"))
        ZipFile(editions.fileOf(db.editions().byId(built.editionId)!!)!!).use { zip ->
            val text = zip.entries().toList().filter { it.name.endsWith(".xhtml") }.joinToString { zip.getInputStream(it).reader().readText() }
            assertTrue("the EPUB names the publication, not the account", text.contains("A Blog"))
            assertFalse(text.contains(SourceRepository.TTRSS_TITLE))
        }
    }

    @Test
    fun deliveringAnEditionWithTtrssArticlesAsksForThemToBeMarkedRead() = runTest {
        val asked = mutableListOf<Long>()
        val delivering = EditionRepository(db, tmp.root, clock) { asked += it }
        source("a", null, "a1")
        val feedOnly = builder.build(EditionSettings()) as BuildResult.Built
        delivering.markDelivered(feedOnly.editionId)
        assertTrue(asked.isEmpty())

        ttrss("n1" to ("1" to "Example News"))
        val withTtrss = builder.build(EditionSettings()) as BuildResult.Built
        delivering.markDelivered(withTtrss.editionId)
        assertEquals(listOf(withTtrss.editionId), asked)
    }

    @Test
    fun aFailureToScheduleMarkReadDoesntUndoDelivery() = runTest {
        val delivering = EditionRepository(db, tmp.root, clock) { throw IllegalStateException("WorkManager isn't initialised") }
        ttrss("n1" to ("1" to "Example News"))
        val built = builder.build(EditionSettings()) as BuildResult.Built
        delivering.markDelivered(built.editionId)
        assertEquals(EditionStatus.DELIVERED, db.editions().byId(built.editionId)!!.status)
        assertEquals(ArticleState.DELIVERED, stateOf("n1"))
    }

    /** A send that never arrived can be undone: the articles aren't lost, and the edition can go again. */
    @Test
    fun anEditionMarkedAsNotSentCanBeSentAgainAsIfItNeverWent() = runTest {
        source("a", null, "a1", "a2")
        editions.setStarred(idOf("a2"), true)
        val built = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built
        editions.markDelivered(built.editionId)
        val urls = listOf("https://a.example/a1", "https://a.example/a2")

        assertTrue(editions.markNotSent(built.editionId))

        val edition = db.editions().byId(built.editionId)!!
        assertEquals(EditionStatus.READY, edition.status)
        assertEquals(ArticleState.IN_EDITION, stateOf("a1"))
        assertEquals(ArticleState.IN_EDITION, stateOf("a2"))
        assertTrue("it went in starred, so it keeps its star", starredAt("a2") != null)
        assertNull(starredAt("a1"))
        assertTrue("a new copy of its links may come in again", db.articles().deliveredAmong(urls).isEmpty())

        editions.markSent(built.editionId)
        assertEquals(EditionStatus.DELIVERED, db.editions().byId(built.editionId)!!.status)
        assertEquals(ArticleState.DELIVERED, stateOf("a1"))
        assertEquals(urls, db.articles().deliveredAmong(urls).sorted())
    }

    @Test
    fun anEditionMarkedAsNotSentAndNotSentAgainGivesItsArticlesToTheNextOne() = runTest {
        source("a", null, "a1", "a2")
        val first = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built
        editions.markDelivered(first.editionId)
        editions.markNotSent(first.editionId)

        val second = builder.build(EditionSettings(maxPerSource = 5)) as BuildResult.Built

        assertEquals(EditionStatus.FAILED, db.editions().byId(first.editionId)!!.status)
        assertEquals(listOf(idOf("a1"), idOf("a2")).sorted(), db.editions().articleIds(second.editionId).sorted())
    }

    @Test
    fun onlyADeliveredEditionWithItsBookStillThereCanBeMarkedAsNotSent() = runTest {
        source("a", null, "a1")
        val ready = builder.build(EditionSettings()) as BuildResult.Built
        assertFalse("not sent yet", editions.markNotSent(ready.editionId))

        editions.markDelivered(ready.editionId)
        editions.fileOf(db.editions().byId(ready.editionId)!!)!!.delete()
        assertFalse("nothing to send again", editions.markNotSent(ready.editionId))
        assertEquals(EditionStatus.DELIVERED, db.editions().byId(ready.editionId)!!.status)
        assertEquals(ArticleState.DELIVERED, stateOf("a1"))
    }

    /** One article in two unsent editions: giving back the older one mustn't free it from the newer. */
    @Test
    fun anArticleBroughtBackIntoANewerEditionStaysThereWhenTheOldOneIsMarkedAsNotSent() = runTest {
        source("a", null, "a1")
        val first = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(first.editionId)
        editions.setStarred(idOf("a1"), true)
        val second = builder.build(EditionSettings()) as BuildResult.Built
        assertEquals(listOf(idOf("a1")), db.editions().articleIds(second.editionId))

        assertTrue(editions.markNotSent(first.editionId))
        editions.delete(first.editionId)

        assertEquals(ArticleState.IN_EDITION, stateOf("a1"))
        assertFalse("not offered to a third edition", db.articles().candidates().any { it.guid == "a1" })
    }

    /** An article that went out again in a newer edition was delivered: marking the old one as not sent mustn't send it a third time. */
    @Test
    fun anArticleDeliveredAgainInANewerEditionStaysDeliveredWhenTheOldOneIsMarkedAsNotSent() = runTest {
        source("a", null, "a1")
        editions.setStarred(idOf("a1"), true)
        val first = builder.build(EditionSettings()) as BuildResult.Built
        editions.markDelivered(first.editionId)
        editions.setStarred(idOf("a1"), true)
        val later = EditionRepository(db, tmp.root, Clock.offset(clock, Duration.ofHours(1)))
        val second = builder.build(EditionSettings()) as BuildResult.Built
        later.markDelivered(second.editionId)

        assertTrue(editions.markNotSent(first.editionId))

        assertEquals(ArticleState.DELIVERED, stateOf("a1"))
        assertNull("its star isn't brought back either", starredAt("a1"))
    }

    /** Sending again after marking as not sent is the same delivery: its notes are saved once. */
    @Test
    fun sendingAgainAfterMarkingAsNotSentDoesntRepeatTheWorkOfDelivery() = runTest {
        val followUps = mutableListOf<Long>()
        val delivering = EditionRepository(db, tmp.root, clock, onDelivered = { followUps += it })
        source("a", null, "a1")
        val built = builder.build(EditionSettings()) as BuildResult.Built
        delivering.markSent(built.editionId)
        delivering.markNotSent(built.editionId)
        delivering.markSent(built.editionId)

        assertEquals(EditionStatus.DELIVERED, db.editions().byId(built.editionId)!!.status)
        assertEquals(listOf(built.editionId), followUps)
    }

    @Test
    fun markingAsNotSentAsksForItsTtrssArticlesToBeMarkedUnread() = runTest {
        val asked = mutableListOf<Long>()
        val marking = EditionRepository(db, tmp.root, clock) { asked += it }
        source("a", null, "a1")
        val feedOnly = builder.build(EditionSettings()) as BuildResult.Built
        marking.markDelivered(feedOnly.editionId)
        marking.markNotSent(feedOnly.editionId)
        assertTrue(asked.isEmpty())
        marking.delete(feedOnly.editionId)

        ttrss("n1" to ("1" to "Example News"))
        val withTtrss = builder.build(EditionSettings()) as BuildResult.Built
        marking.markDelivered(withTtrss.editionId)
        assertTrue(marking.markNotSent(withTtrss.editionId))
        assertEquals(listOf(withTtrss.editionId, withTtrss.editionId), asked)
    }

    @Test
    fun aFailureToScheduleMarkUnreadDoesntStopMarkingAsNotSent() = runTest {
        var failing = false
        val marking = EditionRepository(db, tmp.root, clock) { if (failing) throw IllegalStateException("WorkManager isn't initialised") }
        ttrss("n1" to ("1" to "Example News"))
        val built = builder.build(EditionSettings()) as BuildResult.Built
        marking.markDelivered(built.editionId)
        failing = true
        assertTrue(marking.markNotSent(built.editionId))
        assertEquals(EditionStatus.READY, db.editions().byId(built.editionId)!!.status)
    }

    @Test
    fun oneMorningOfBotChecksDoesntSettleASite() = runTest {
        val http = FakeHttp()
        val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder(), onEvidence = sources::recordFullText)
        val tuned = EditionBuilder(db, provider, tmp.root, clock, ZoneOffset.UTC)
        val id = sources.addFeed("https://blocked.example/feed", "Blocked")
        db.articles().insertNew(
            listOf("1", "2", "3").map { g ->
                http.page("https://blocked.example/$g", "<html>Forbidden</html>", code = 403)
                ArticleEntity(sourceId = id, guid = g, url = "https://blocked.example/$g", title = "Story $g", feedHtml = "<p>The first lines of story $g.</p>")
            },
        )

        tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)) as BuildResult.Built

        assertEquals(ContentMode.AUTO, db.sources().byId(id)!!.contentMode)
    }

    @Test
    fun aSiteSettledOnItsSummariesIsRecheckedAndCanMoveToFullPages() = runTest {
        val http = FakeHttp()
        var day = 20_000L
        val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder()) { sourceId, e ->
            sources.recordFullText(sourceId, e, day)
        }
        val id = sources.addFeed("https://unblocked.example/feed", "Unblocked")
        db.sources().setFullText(id, ContentMode.FEED, com.app.newspaperss.core.extract.FullTextEvidence.BLOCKED, 3, day)
        val words = (1..800).joinToString(" ") { "word$it" }
        repeat(3) { i ->
            day++
            http.page("https://unblocked.example/$i", "<html><body><article><h1>Story</h1><p>$words</p></article></body></html>")
            val article = ArticleEntity(id = 100L + i, sourceId = id, guid = "$i", url = "https://unblocked.example/$i", title = "Story $i", feedHtml = "<p>A teaser.</p>")
            provider.contentFor(article, db.sources().byId(id)!!, com.app.newspaperss.core.images.ImageAllowance())
        }

        assertEquals(ContentMode.PAGE, db.sources().byId(id)!!.contentMode)
    }

    /**
     * A paid post that's only a title and a picture doesn't take a place once its source skips
     * them, and isn't fetched again; one the reader starred still goes in. With skipping off it
     * goes in, noted, and the source page learns the source has them.
     */
    @Test
    fun paidPostsWithNothingFreeAreSkippedWhereTheSourceSaysSo() = runTest {
        val http = FakeHttp()
        val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder(), onPaidOnly = sources::markPaidOnly, onEvidence = sources::recordFullText)
        val tuned = EditionBuilder(db, provider, tmp.root, clock, ZoneOffset.UTC)
        val id = sources.addFeed("https://paid.example/feed", "Paid")
        val words = (1..400).joinToString(" ") { "word$it" }
        val paywall = """<div data-testid="paywall"><h2>Keep reading with a 7-day free trial</h2></div>"""
        fun post(g: String, paid: Boolean) = ArticleEntity(sourceId = id, guid = g, url = "https://paid.example/$g", title = "Post $g", feedHtml = "<p>A line.</p>").also {
            http.page(it.url, if (paid) "<html><body><article><h1>Post $g</h1><p>A line.</p></article>$paywall</body></html>" else "<html><body><article><h1>Post $g</h1><p>$words</p></article></body></html>")
        }
        db.articles().insertNew(listOf(post("paid", true), post("starred", true), post("free", false)))
        sources.setStarred(idOf("starred"), true)
        db.sources().setSkipPaidPosts(id, true)

        val built = tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)) as BuildResult.Built

        assertEquals(setOf("Post starred", "Post free"), editions.observeArticles(built.editionId).first().map { it.title }.toSet())
        val skipped = db.articles().byId(idOf("paid"))!!
        assertEquals(ArticleState.EXPIRED, skipped.state)
        assertTrue(skipped.paidOnly)
        assertEquals(PaidOnlyCount(found = 2, skipped = 1), sources.observePaidOnly(id).first())

        editions.markDelivered(built.editionId)
        db.sources().setSkipPaidPosts(id, false)
        db.articles().insertNew(listOf(post("kept", true)))
        val next = tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)) as BuildResult.Built
        assertEquals(listOf("Post kept"), editions.observeArticles(next.editionId).first().map { it.title })
        assertTrue(db.articles().byId(idOf("kept"))!!.paidOnly)
        db.articles().setState(listOf(idOf("kept")), ArticleState.EXPIRED)
        assertEquals("one let in that then got old wasn't skipped", 1, sources.observePaidOnly(id).first().skipped)
    }

    /** Marking a skipped paid post unread is asking for it: the next edition takes it. */
    @Test
    fun aSkippedPaidPostMarkedUnreadIsntSkippedAgain() = runTest {
        val (tuned, id, post) = paidSource()
        db.articles().insertNew(listOf(post("paid", true), post("free", false)))
        val first = tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)) as BuildResult.Built
        editions.markDelivered(first.editionId)
        assertEquals(ArticleState.EXPIRED, db.articles().byId(idOf("paid"))!!.state)

        db.articles().markUnread(idOf("paid"), clock.instant())
        assertEquals(0, sources.observePaidOnly(id).first().skipped)
        val next = tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)) as BuildResult.Built
        assertEquals(listOf("Post paid"), editions.observeArticles(next.editionId).first().map { it.title })
    }

    /** All that's new being skipped paid posts is nothing new, not a failed edition. */
    @Test
    fun anEditionOfOnlySkippedPaidPostsIsNothingNew() = runTest {
        val (tuned, _, post) = paidSource()
        db.articles().insertNew(listOf(post("a", true), post("b", true)))

        assertEquals(BuildResult.NothingNew, tuned.build(EditionSettings(minutes = 600, maxPerSource = 10)))
        assertEquals("no failed edition left behind", 0, db.editions().count())
    }

    private suspend fun paidSource(): Triple<EditionBuilder, Long, (String, Boolean) -> ArticleEntity> {
        val http = FakeHttp()
        val provider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder(), onPaidOnly = sources::markPaidOnly, onEvidence = sources::recordFullText)
        val id = sources.addFeed("https://paid.example/feed", "Paid")
        db.sources().setSkipPaidPosts(id, true)
        val words = (1..400).joinToString(" ") { "word$it" }
        val paywall = """<div data-testid="paywall"><h2>Keep reading with a 7-day free trial</h2></div>"""
        val post = { g: String, paid: Boolean ->
            ArticleEntity(sourceId = id, guid = g, url = "https://paid.example/$g", title = "Post $g", feedHtml = "<p>A line.</p>").also {
                http.page(it.url, if (paid) "<html><body><article><h1>Post $g</h1><p>A line.</p></article>$paywall</body></html>" else "<html><body><article><h1>Post $g</h1><p>$words</p></article></body></html>")
            }
        }
        return Triple(EditionBuilder(db, provider, tmp.root, clock, ZoneOffset.UTC), id, post)
    }
}
