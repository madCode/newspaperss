package com.app.newspaperss.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.FeedChoice
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.sources.NotInPaperScreen
import com.app.newspaperss.ui.sources.accountProblem
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.settings.FeedsFrom
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import java.time.ZoneOffset
import java.util.Locale
import com.app.newspaperss.ui.sources.LeftOutScreen
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.feedNote
import com.app.newspaperss.data.sameFeed
import com.app.newspaperss.core.extract.ContentMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** A tt-rss account's feeds: on Sources under their categories in the server setup, each with a page of its own. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TtrssFeedsTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = SourceRepository(db)

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * An account listed by tt-rss: feeds in two categories, one left out, two uncategorized (one
     * with no category at all, one in tt-rss's own Uncategorized).
     */
    private fun account(): Long = runBlocking {
        val account = repo.addTtrss("https://rss.example/api/")
        fun listed(key: String, title: String, url: String, category: String?) =
            PublicationEntity(account, key, title = title, feedUrl = url, category = category, listed = true)
        db.sources().savePublication(listed("7", "Quarterly Review", "https://quarterly.example/feed", "Essays"))
        db.sources().savePublication(listed("8", "Morning Wire", "https://wire.example/rss", "News").copy(maxArticles = 2))
        db.sources().savePublication(listed("9", "The Bay Dispatch", "https://bay.example/rss", "News").copy(skipPaidPosts = true))
        db.sources().savePublication(listed("42", "Press Office", "https://press.example/feed", "News").copy(leftOut = true))
        db.sources().savePublication(listed("50", "Garden Log", "https://garden.example/rss", " "))
        db.sources().savePublication(listed("51", "A Friend's Journal", "https://friend.example/rss", "Uncategorized"))
        db.sources().setFeedsListed(account, Instant.now())
        db.articles().insertNew(
            listOf("7" to "Quarterly Review", "8" to "Morning Wire").flatMap { (feed, title) ->
                (1..2).map { ArticleEntity(sourceId = account, guid = "ttrss:$feed$it", url = "https://news.example/$feed/$it", title = "$title story $it", originId = feed, originTitle = title) }
            },
        )
        account
    }

    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") }) }

    private fun sources(from: FeedsFrom): SourcesViewModel {
        runBlocking { settings.update { it.copy(feedsFrom = from) } }
        return SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = settings) {}
    }

    @Test
    fun aFoldedCategoryHidesItsFeedsAndStaysFolded() {
        account()
        val vm = sources(FeedsFrom.SERVER)
        compose.setContent { SourcesScreen(vm) }
        idleUntil { compose.waitForIdle(); visible("The Bay Dispatch") }

        compose.onNode(isHeading("News, 2 feeds")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).performClick()
        idleUntil { compose.waitForIdle(); !visible("The Bay Dispatch") }
        assertFalse(visible("Morning Wire"))
        assertTrue("the others stay open", visible("Quarterly Review") && visible("Garden Log"))
        compose.onNode(isHeading("News, 2 feeds")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        assertEquals("kept for next time", setOf("News"), runBlocking { settings.current().foldedCategories })

        compose.onNode(isHeading("Uncategorized, 2 feeds")).performClick()
        idleUntil { compose.waitForIdle(); !visible("Garden Log") }
        assertEquals("Uncategorized is folded by its own key", setOf("News", ""), runBlocking { settings.current().foldedCategories })

        compose.onNode(isHeading("News, 2 feeds")).performClick()
        idleUntil { compose.waitForIdle(); visible("The Bay Dispatch") && visible("Morning Wire") }
        assertEquals(setOf(""), runBlocking { settings.current().foldedCategories })
    }

    private fun isHeading(description: String) =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasContentDescription(description)

    // Tall enough for the whole list, so every row is composed.
    @Config(qualifiers = "w411dp-h1400dp")
    @Test
    fun withAServerTheFeedsAreListedOpenUnderTheirCategories() {
        val account = account()
        runBlocking {
            repo.addFeed("http://www.wire.example/rss/", "The Wire, on the phone")
            repo.addList(com.app.newspaperss.core.lists.CuratedLists.all.first())
        }
        val vm = sources(FeedsFrom.SERVER)
        var feed: Pair<Long, String>? = null
        var leftOut: Long? = null
        compose.setContent {
            SourcesScreen(vm, onOpenFeed = { id, key -> feed = id to key }, onOpenLeftOut = { leftOut = it })
        }
        idleUntil { compose.waitForIdle(); visible("Quarterly Review") }

        assertEquals(
            "A to Z, Uncategorized last, holding the feeds with no category too",
            listOf("Essays" to listOf("Quarterly Review"), "News" to listOf("The Bay Dispatch", "Morning Wire"), null to listOf("A Friend's Journal", "Garden Log")),
            vm.screen.value!!.server!!.categories.map { c -> c.name to c.feeds.map { it.title } },
        )
        compose.onNode(isHeading("News, 2 feeds")).assertExists()
        compose.onNode(isHeading("Uncategorized, 2 feeds")).assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Still on this phone")).assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Curated lists")).assertExists()
        assertTrue("its own settings, and nothing about the phone feed with its address", visible("At most 2") && visible("Skips paid posts"))
        assertFalse(visible("Also"))
        assertFalse("a left-out feed is counted, not listed", visible("Press Office"))
        assertFalse("nothing splits tt-rss from the phone", visible("From your tt-rss") || visible("On this phone"))
        assertFalse("with nothing wrong, nothing about the account", visible("Your tt-rss") || visible("Settings"))

        fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot.top
        val order = listOf("Your reading list", "Still on this phone", "The Wire, on the phone", "Essays", "Uncategorized", "Curated lists", "Not in your paper", "Left out")
        assertEquals("the reading list, the phone's feeds to move, the server's, curated lists, then what's not in the paper", order, order.sortedBy(::top))

        compose.onNodeWithText("Quarterly Review").performClick()
        assertEquals(account to "7", feed)
        compose.onNodeWithText("Left out").performClick()
        assertEquals(account, leftOut)
    }

    @Test
    fun aProblemWithTheAccountIsABannerThatGoesWhenItsPutRight() {
        val account = account()
        runBlocking {
            db.sources().recordSuccess(account, Instant.now(), null, null, SourceRepository.TTRSS_TITLE)
            db.sources().recordFailure(account, Instant.now(), "Couldn't reach tt-rss.")
        }
        val vm = sources(FeedsFrom.SERVER)
        var settingsOpened = false
        compose.setContent { SourcesScreen(vm, onOpenAccount = { settingsOpened = true }) }
        idleUntil { compose.waitForIdle(); visible("⚠ Couldn't reach tt-rss.") }
        assertTrue("it says what's listed is from before", visible("Showing what it last listed.") && visible("Quarterly Review"))
        compose.onNodeWithContentDescription("Your tt-rss settings").performClick()
        assertTrue(settingsOpened)

        runBlocking { db.sources().recordSuccess(account, Instant.now(), null, null, SourceRepository.TTRSS_TITLE) }
        idleUntil { compose.waitForIdle(); !visible("Couldn't reach") }
        assertFalse(visible("Settings"))

        runBlocking { db.sources().setPaused(account, true) }
        idleUntil { compose.waitForIdle(); visible("Your tt-rss is paused") }
        runBlocking { db.sources().setServerNote(account, "Couldn't mark articles read") }
        runBlocking { db.sources().setPaused(account, false) }
        idleUntil { compose.waitForIdle(); visible("⚠ Couldn't mark articles read") }
    }

    @Test
    fun thePhoneSetupShowsNothingOfTtrss() {
        account()
        runBlocking { repo.addFeed("https://phone.example/feed", "A phone feed") }
        val vm = sources(FeedsFrom.PHONE)
        compose.setContent { SourcesScreen(vm) }
        idleUntil { compose.waitForIdle(); visible("A phone feed") }
        assertNull(vm.screen.value!!.server)
        assertEquals(listOf("A phone feed"), vm.screen.value!!.rows.map { it.source.title })
        assertFalse(visible("tt-rss"))
        assertFalse(visible("on this phone"))
    }

    /**
     * With one category chosen, the account's other feeds are counted in a row of their own,
     * from the list drawn for that category only.
     */
    @Config(qualifiers = "w411dp-h1400dp")
    @Test
    fun feedsOutsideTheChosenCategoryAreNotInYourPaper() {
        val account = account()
        runBlocking {
            db.sources().setTtrssCategory(account, 3, "News")
            listOf(Triple("20", "Field Station", "Science"), Triple("21", "Deep Time", "Science"), Triple("22", "Someone's Blog", null)).forEach { (key, title, category) ->
                db.sources().savePublication(PublicationEntity(account, key, title = title, category = category, outsideCategory = true))
            }
            db.sources().setFeedsListed(account, Instant.now())
        }
        val vm = sources(FeedsFrom.SERVER)
        var notIn: Long? = null
        compose.setContent { SourcesScreen(vm, onOpenNotInPaper = { notIn = it }) }
        idleUntil { compose.waitForIdle(); visible("Not in your paper") }

        assertTrue(visible("Outside News") && visible("3 feeds"))
        compose.onNodeWithText("Outside News").performClick()
        assertEquals(account, notIn)

        // Until the next list, the feeds known are the old category's: none are shown as in the paper.
        runBlocking { db.sources().setTtrssCategory(account, 4, "Science") }
        idleUntil { compose.waitForIdle(); visible("Your feeds in Science show here after the next check.") }
        assertFalse(visible("Quarterly Review"))
        assertFalse(visible("Outside News"))
        assertTrue("a left-out feed can still be brought back", visible("Left out") && visible("1 feed"))
    }

    @Test
    fun notInYourPaperListsTheOtherCategoriesAndPointsToSettings() {
        val account = account()
        runBlocking {
            db.sources().setTtrssCategory(account, 3, "News")
            db.sources().savePublication(PublicationEntity(account, "20", title = "Field Station", category = "Science", outsideCategory = true))
            db.sources().savePublication(PublicationEntity(account, "21", title = "Deep Time", category = "Science", outsideCategory = true, leftOut = true))
            db.sources().setFeedsListed(account, Instant.now())
        }
        val vm = SourceDetailViewModel(repo, account, flowOf(1))
        var opened = false
        compose.setContent { NotInPaperScreen(vm, onBack = {}, onOpenAccount = { opened = true }) }
        idleUntil { compose.waitForIdle(); visible("2 feeds: Deep Time (left out), Field Station") }
        assertTrue(visible("Your paper takes articles from News only"))
        compose.onNodeWithText("Change Articles from in Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun theAccountsProblemIsSaidInWords() {
        val now = Instant.parse("2026-10-03T09:00:00Z")
        val account = SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE, lastFetchedAt = now)
        fun problem(source: SourceEntity) = accountProblem(source, Locale.US, is24Hour = false, now = now, zone = ZoneOffset.UTC)
        assertNull(problem(account))
        val down = problem(account.copy(lastError = "Couldn't reach tt-rss.", failingSince = Instant.parse("2026-10-03T06:10:00Z")))!!
        assertTrue(down, down.startsWith("⚠ Couldn't reach tt-rss. Since 6:10") && down.endsWith("Showing what it last listed."))
        assertTrue(problem(account.copy(lastError = "Couldn't reach tt-rss.", failingSince = Instant.parse("2026-09-30T06:10:00Z")))!!.contains("Since Sep 30."))
        assertTrue("paused says so rather than the error", problem(account.copy(paused = true, lastError = "Couldn't reach tt-rss."))!!.startsWith("Your tt-rss is paused"))
        assertEquals("⚠ Couldn't mark articles read", problem(account.copy(serverNote = "Couldn't mark articles read")))
    }

    @Test
    fun aFeedsPageHasItsOwnSettingsAndArticles() {
        val account = account()
        val vm = SourceDetailViewModel(repo, account, flowOf(1), key = "7")
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { compose.waitForIdle(); visible("In your tt-rss · category Essays") }

        assertTrue(visible("quarterly.example"))
        assertEquals("only this feed's articles", setOf("7"), vm.detail.value!!.articles.map { it.originId }.toSet())
        assertFalse("no Pause or Remove: those are the account's", visible("Pause"))

        compose.onNodeWithContentDescription("More articles from this site").performClick()
        idleUntil { runBlocking { db.sources().publication(account, "7")!!.maxArticles } == 2 }


        compose.onNodeWithText("Article text: Automatic").performClick()
        compose.onNodeWithText("Always fetch the full page").performClick()
        idleUntil { runBlocking { db.sources().publication(account, "7")!!.chosenMode } == ContentMode.PAGE }

        compose.onNodeWithText("Leave out").performClick()
        idleUntil { compose.waitForIdle(); visible("its waiting articles go") }
        compose.onAllNodes(hasText("Leave out"))[1].performClick()
        idleUntil { compose.waitForIdle(); visible("Left out of the paper") }
        val left = runBlocking { db.sources().publication(account, "7")!! }
        assertTrue(left.leftOut)
        assertEquals("its settings stay for when it comes back", 2, left.maxArticles)

        compose.onNodeWithText("Bring back").performClick()
        idleUntil { runBlocking { db.sources().publication(account, "7")!!.leftOut } == false }
    }

    /** Skipping paid posts is each feed's own: its page has the switch, the account's page doesn't. */
    @Test
    fun aFeedSkipsPaidPostsOnItsOwn() {
        val account = account()
        runBlocking { repo.markPaidOnly(db.articles().allForSource(account).first { it.originId == "7" }.id, skip = false) }
        val vm = SourceDetailViewModel(repo, account, flowOf(1), key = "7")
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { compose.waitForIdle(); visible("Skip paid posts with nothing free") }

        compose.onNodeWithText("Skip paid posts with nothing free").performClick()
        idleUntil { runBlocking { db.sources().publication(account, "7")!!.skipPaidPosts } }
        assertFalse(runBlocking { db.sources().publication(account, "8")!!.skipPaidPosts })
    }

    /** A left-out feed's page shows none of the settings about its writing, its paid posts included. */
    @Test
    fun aLeftOutFeedHasNoPaidPostsSwitch() {
        val account = account()
        runBlocking { repo.setSkipPaidPosts(account, "42", true) }
        val vm = SourceDetailViewModel(repo, account, flowOf(1), key = "42")
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { compose.waitForIdle(); visible("Bring back") }
        assertFalse(visible("Skip paid posts"))
    }

    @Test
    fun theLeftOutListOpensEachFeed() {
        val account = account()
        val vm = SourceDetailViewModel(repo, account, flowOf(1))
        var opened: String? = null
        var back = false
        compose.setContent { LeftOutScreen(vm, onBack = { back = true }, onOpenFeed = { opened = it }) }
        idleUntil { compose.waitForIdle(); visible("Press Office") }

        assertFalse(visible("Quarterly Review"))
        compose.onNodeWithText("Press Office").performClick()
        assertEquals("42", opened)

        runBlocking { repo.remove(db.sources().byId(account)!!) }
        idleUntil { compose.waitForIdle(); back }
    }

    @Test
    fun aFeedsLineSaysOnlyWhatsWorthSaying() {
        fun feed(publication: PublicationEntity?) = FeedChoice("7", "Q", inPaper = true, publication)
        assertNull(feedNote(feed(null)))
        assertNull(feedNote(feed(PublicationEntity(1, "7", title = "Q", listed = true))))
        assertEquals(
            "Feed's text · At most 1 · Skips paid posts",
            feedNote(feed(PublicationEntity(1, "7", chosenMode = ContentMode.FEED, maxArticles = 1, skipPaidPosts = true))),
        )
        assertEquals("Full page", feedNote(feed(PublicationEntity(1, "7", chosenMode = ContentMode.PAGE))))
        assertTrue(sameFeed("https://www.Example.com/feed/", "http://example.com/feed"))
        assertFalse(sameFeed("https://example.com/feed", "https://example.com/rss"))
    }
}
