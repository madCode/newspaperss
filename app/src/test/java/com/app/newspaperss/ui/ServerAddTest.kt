package com.app.newspaperss.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.espresso.Espresso
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.data.TtrssSubscriptions
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.AddState
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Adding a site in the server setup: it goes into tt-rss, with fallbacks when it can't. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ServerAddTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private val server = FakeTtrss(http)
    // Cancelled before TemporaryFolder deletes the files: a write still running after that fails
    // its rename, and the error lands in whichever test is running then.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }) }
    private val accounts by lazy { TtrssAccountStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("ttrss.preferences_pb") }, testCipher()) }
    private val sources = SourceRepository(db)
    private val ttrss by lazy { TtrssRepository(db, http, accounts, sources) }
    // The app's own scope in the app, outliving the screen.
    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val subscriptions by lazy { TtrssSubscriptions(ttrss, appScope) }
    private val saved = mutableListOf<String>()
    private val feedUrl = "https://science.example/feed"

    @After fun stop() = runBlocking {
        appScope.coroutineContext[Job]!!.cancelAndJoin()
        storeScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = idleUntil { compose.waitForIdle(); visible(text) }

    /** Signed in to a tt-rss with Science and News, in the server setup; a site with a feed to add. */
    private fun signedIn(): Long = runBlocking {
        server.categories[4] = "Science"
        server.categories[5] = "News"
        server.titles[feedUrl] = "Science Weekly"
        http.page("https://science.example", """<html><head><link rel="alternate" type="application/rss+xml" title="Science Weekly" href="/feed"></head></html>""")
        http.page(feedUrl, rss("Science Weekly", "1" to "A comet"))
        assertNull(ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
        settings.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        db.sources().ofKind(SourceKind.TTRSS).single().id
    }

    /** The screen showing: Sources, or nothing once it's left. */
    private var shown by mutableStateOf<SourcesViewModel?>(null)
    private var contentSet = false

    private fun show(): SourcesViewModel {
        val vm = SourcesViewModel(
            sources, FeedFinder(http), ttrss, saveToReadingList = { saved += it; true }, settings = settings, subscriptions = subscriptions,
        ) {}
        shown = vm
        if (!contentSet) compose.setContent { shown?.let { SourcesScreen(it) } }
        contentSet = true
        idleUntil { compose.waitForIdle(); vm.screen.value?.server != null }
        return vm
    }

    private fun add(input: String) {
        compose.onNodeWithText("Add a site", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(input)
        compose.onNodeWithText("Add").performClick()
    }

    private fun pick(category: String) {
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasContentDescription("Category, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Category, ", substring = true)).performClick()
        compose.onNodeWithText(category).performClick()
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun aSiteGoesIntoTtrssInTheCategoryChosenAndUndoTakesItOut() {
        signedIn()
        show()
        add("science.example")
        waitFor("Subscribe in your tt-rss")
        assertTrue("its own address, not the page's", visible("science.example/feed"))
        assertTrue(visible("To add a category, make it in tt-rss first."))
        pick("Science")
        compose.onNodeWithContentDescription("Category, Science").assertExists()
        compose.onNodeWithText("Subscribe").performClick()

        waitFor("Added to your tt-rss, in Science.")
        assertEquals(listOf(feedUrl to 4), server.subscribed)
        assertFalse("the dialog has closed", visible("Subscribe in your tt-rss"))
        waitFor("Science Weekly")
        assertTrue("its row, under Science", visible("Science") && visible("Not fetched by tt-rss yet"))
        // Saved by its own coroutine, apart from tt-rss's answer.
        idleUntil { runBlocking { settings.current().lastCategoryId } == 4 }

        compose.onNodeWithText("Undo").performClick()
        waitFor("Took Science Weekly out of your tt-rss.")
        assertEquals(1, server.unsubscribed.size)
        assertTrue(server.feeds.values.none { it.url == feedUrl })
        idleUntil { compose.waitForIdle(); !visible("Not fetched by tt-rss yet") }
        assertNull(runBlocking { ttrss.feedAt(feedUrl) })
    }

    @Test
    fun theNextSiteOffersTheCategoryLastUsed() {
        signedIn()
        val vm = show()
        add("science.example")
        pick("News")
        compose.onNodeWithText("Subscribe").performClick()
        waitFor("Added to your tt-rss, in News.")
        // The next dialog reads the category last used, saved apart from tt-rss's answer.
        idleUntil { runBlocking { settings.current().lastCategoryId } == 5 }

        http.page("https://other.example/rss", rss("Other", "1" to "Story"))
        add("other.example/rss")
        waitFor("Subscribe in your tt-rss")
        idleUntil { compose.waitForIdle(); (vm.add.value as? AddState.Subscribing)?.categories != null }
        compose.onNodeWithContentDescription("Category, News").assertExists()
    }

    @Test
    fun aFeedAlreadyThereIsSaidSoWithoutAskingTtrss() {
        val account = signedIn()
        runBlocking {
            // Listed under a slightly different address: tt-rss keeps what it was given.
            db.sources().savePublication(PublicationEntity(account, "12", title = "Science Weekly", feedUrl = "http://www.science.example/feed/", category = "Science", listed = true))
            db.sources().setFeedsListed(account, Instant.now())
        }
        show()
        add("science.example")
        waitFor("Already in your tt-rss")
        assertTrue(visible("Science Weekly is in Science."))
        assertTrue(server.subscribed.isEmpty())
        compose.onNodeWithText("OK").performClick()
        idleUntil { compose.waitForIdle(); !visible("Already in your tt-rss") }
    }

    @Test
    fun ttrssSayingItsAlreadyThereIsShownTheSameWay() {
        signedIn()
        // In tt-rss, but not yet in the list the app last read.
        server.feeds[12] = FakeTtrss.Feed("Science Weekly", feedUrl, categoryId = 4)
        show()
        add("science.example")
        waitFor("Subscribe in your tt-rss")
        pick("News")
        compose.onNodeWithText("Subscribe").performClick()
        waitFor("Already in your tt-rss")
        assertTrue("the category it's really in", visible("Science Weekly is in Science."))
    }

    @Test
    fun aSiteWithNoFeedCanBeSavedToTheReadingList() {
        signedIn()
        http.page("https://quiet.example/2026/10/a-post", "<html><body><p>Words.</p></body></html>")
        show()
        add("quiet.example/2026/10/a-post")
        waitFor("No feed on this site")
        assertTrue(visible("quiet.example doesn't offer a feed, so tt-rss can't follow it."))
        compose.onNodeWithText("Save this page to your reading list").performClick()
        waitFor("Saved to your reading list")
        assertEquals(listOf("https://quiet.example/2026/10/a-post"), saved)
    }

    @Test
    fun aCuratedListsSiteOffersTheListOnThisPhone() {
        signedIn()
        show()
        add("aldaily.com")
        waitFor("A curated list")
        compose.onNodeWithText("Add Arts & Letters Daily").performClick()
        idleUntil { runBlocking { db.sources().all() }.any { it.kind == SourceKind.LIST } }
        assertTrue(server.subscribed.isEmpty())
    }

    @Test
    fun aSiteTtrssCantFetchSaysWhyAndOffersOnlyTheReadingList() {
        signedIn()
        server.subscribeCode = 5
        http.page("https://science.example/2026/10/comet", """<html><head><link rel="alternate" type="application/rss+xml" href="/feed"></head></html>""")
        show()
        add("science.example/2026/10/comet")
        waitFor("Subscribe in your tt-rss")
        compose.onNodeWithText("Subscribe").performClick()
        waitFor("tt-rss couldn't fetch it")
        assertTrue(visible("tt-rss couldn't download it.") && visible("Some sites block servers"))
        assertFalse("fetching it from the phone is the owner's call", visible("Fetch it from this phone"))
        compose.onNodeWithText("Save this page to your reading list").performClick()
        waitFor("Saved to your reading list")
        assertEquals(listOf("https://science.example/2026/10/comet"), saved)
        assertTrue("nothing added on the phone", runBlocking { db.sources().all() }.none { it.kind == SourceKind.FEED })
    }

    @Test
    fun anAccountThatCantSubscribeSaysSoInWords() {
        signedIn()
        server.apiLevel = 4
        show()
        add("science.example")
        waitFor("Subscribe in your tt-rss")
        compose.onNodeWithText("Subscribe").performClick()
        waitFor("tt-rss didn't add it")
        assertTrue(visible("Your tt-rss is too old to add feeds from NewspapeRSS."))
        assertFalse("a front page isn't offered for the reading list", visible("Save this page"))
    }

    @Test
    fun closingTheDialogMidSubscribeLetsItFinishWithUndo() {
        signedIn()
        val gate = CompletableDeferred<Unit>()
        server.subscribeGate = gate
        val vm = show()
        add("science.example")
        waitFor("Subscribe in your tt-rss")
        idleUntil { compose.waitForIdle(); (vm.add.value as? AddState.Subscribing)?.categories != null }
        compose.onNodeWithText("Subscribe").performClick()
        // A second tap, as on a slow e-ink screen, finds nothing left to do.
        vm.subscribeInTtrss()
        waitFor("Asking tt-rss to subscribe…")
        compose.onNodeWithText("Close").performClick()
        idleUntil { compose.waitForIdle(); !visible("Asking tt-rss") }

        gate.complete(Unit)
        waitFor("Added to your tt-rss, in Uncategorized.")
        assertEquals("asked once", 1, server.subscribed.size)
        compose.onNodeWithText("Undo").assertExists()
    }

    @Test
    fun anAnswerArrivingAfterSourcesClosedIsShownWhenItOpensAgain() {
        signedIn()
        server.subscribeCode = 5
        val first = show()
        add("science.example")
        idleUntil { compose.waitForIdle(); (first.add.value as? AddState.Subscribing)?.categories != null }
        val gate = CompletableDeferred<Unit>()
        server.subscribeGate = gate
        first.subscribeInTtrss()
        shown = null
        compose.waitForIdle()
        gate.complete(Unit)
        idleUntil { subscriptions.results.value.isNotEmpty() }

        show()
        waitFor("tt-rss didn't add Science Weekly. tt-rss couldn't download it.")
    }

    @Test
    fun withAServerTheMenuPointsToTheAccountNotToOpml() {
        signedIn()
        show()
        compose.onNodeWithContentDescription("More options").performClick()
        assertTrue(visible("Where your feeds live"))
        assertFalse(visible("OPML"))
    }

    @Test
    fun onThePhoneAddAndTheMenuAreAsBefore() {
        runBlocking { settings.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        http.page("https://science.example", """<html><head><link rel="alternate" type="application/rss+xml" title="Science Weekly" href="/feed"></head></html>""")
        val vm = SourcesViewModel(sources, FeedFinder(http), ttrss, settings = settings, subscriptions = subscriptions) {}
        compose.setContent { SourcesScreen(vm) }
        idleUntil { compose.waitForIdle(); vm.screen.value != null }
        compose.onNodeWithContentDescription("More options").performClick()
        assertTrue(visible("Import from another reader (OPML)") && visible("Export your sites (OPML)"))

        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        assertFalse(visible("It goes into your tt-rss"))
        compose.onNode(hasSetTextAction()).performTextInput("science.example")
        compose.onNodeWithText("Add").performClick()
        idleUntil { runBlocking { db.sources().all() }.any { it.url == feedUrl } }
        assertTrue("a phone feed, nothing asked of tt-rss", server.subscribed.isEmpty())
    }

    @Test
    fun backFromTheCategoryListGoesBackToTheSubscribeStep() {
        signedIn()
        show()
        add("science.example")
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasContentDescription("Category, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Category, ", substring = true)).performClick()
        compose.onNodeWithText("News").performClick()
        Espresso.pressBack()
        waitFor("Subscribe in your tt-rss")
        compose.onNodeWithContentDescription("Category, News").assertExists()
    }

    @Test
    fun anAnswerWaitsWhileAnotherAddIsOpenSoItsUndoCanBeReached() {
        signedIn()
        val gate = CompletableDeferred<Unit>()
        server.subscribeGate = gate
        val vm = show()
        add("science.example")
        idleUntil { compose.waitForIdle(); (vm.add.value as? AddState.Subscribing)?.categories != null }
        compose.onNodeWithText("Subscribe").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Add a site", useUnmergedTree = true).performClick()
        gate.complete(Unit)
        idleUntil { compose.waitForIdle(); subscriptions.results.value.isEmpty() && vm.notices.value.isNotEmpty() }
        assertFalse(visible("Added to your tt-rss"))

        compose.onNodeWithText("Cancel").performClick()
        waitFor("Added to your tt-rss, in Uncategorized.")
    }
}
