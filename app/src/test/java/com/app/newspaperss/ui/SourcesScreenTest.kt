package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private var syncRequests = 0
    private var opened: Long? = null

    @Before
    fun show() {
        val sources = SourceRepository(db)
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val vm = SourcesViewModel(
            sources, FeedFinder(http), TtrssRepository(db, http, accounts, sources),
            saveToReadingList = { com.app.newspaperss.data.ReadingListRepository(db).save(it) },
        ) { syncRequests++ }
        compose.setContent { SourcesScreen(vm, onOpenSource = { opened = it }) }
        // Room delivers on its own executor, which Compose's idling doesn't track.
        waitFor("No sources yet")
    }

    private fun waitFor(text: String, present: Boolean = true) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() == present
    }

    @After fun close() = db.close()

    private fun addSource(input: String) {
        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(input)
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
    }

    @Test
    fun aTtrssAccountIsAddedFromTheMenuAndAWrongPasswordIsExplained() {
        val server = FakeTtrss(http)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add tt-rss account").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput("wrong")
        compose.onNodeWithText("Test and add").performClick()
        waitFor("didn't accept that username and password")

        compose.onAllNodes(hasSetTextAction())[2].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[2].performTextInput(server.password)
        compose.onNodeWithText("Test and add").performClick()
        waitFor("Test and add", present = false)
        waitFor(SourceRepository.TTRSS_TITLE)
        compose.onNodeWithText("rss.example.com").assertIsDisplayed()
        assertEquals(1, syncRequests)
    }

    private fun signInToTtrss(server: FakeTtrss) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add tt-rss account").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput(server.password)
        compose.onNodeWithText("Test and add").performClick()
        waitFor("Which articles?")
    }

    @Test
    fun aTtrssAccountWithCategoriesAsksWhichArticlesBeforeItsAdded() {
        val server = FakeTtrss(http)
        server.categories[4] = "Ideas"
        signInToTtrss(server)
        assertTrue("nothing saved before the choice", runBlocking { db.sources().all() }.none { it.kind == SourceKind.TTRSS })

        compose.onNodeWithText("Ideas").performClick()
        compose.onNodeWithText("Add").performClick()

        waitFor("Which articles?", present = false)
        compose.waitUntil(5_000) { syncRequests == 1 }
        assertEquals(4, runBlocking { db.sources().all().single { it.kind == SourceKind.TTRSS }.ttrssCategoryId })
    }

    @Test
    fun cancellingTheTtrssChoiceAddsNothing() {
        val server = FakeTtrss(http)
        server.categories[4] = "Ideas"
        signInToTtrss(server)

        compose.onNodeWithText("Cancel").performClick()

        waitFor("Which articles?", present = false)
        assertTrue(runBlocking { db.sources().all() }.none { it.kind == SourceKind.TTRSS })
        assertEquals(0, syncRequests)
    }

    @Test
    fun addingASiteFindsItsFeedAndStartsASync() {
        http.page("https://example.com", "<html><head><link rel=alternate type=application/rss+xml href=/feed title=Posts></head></html>")
        http.page("https://example.com/feed", rss("Example"))

        addSource("example.com")

        waitFor("Posts")
        compose.onNodeWithText("Posts").assertIsDisplayed()
        assertEquals(1, syncRequests)
    }

    @Test
    fun anArticleFromASiteWithNoFeedCanBeSavedToReadLater() {
        http.page("https://example.com/2026/a-story", "<html><body><p>No feed on this site.</p></body></html>")

        addSource("https://example.com/2026/a-story")
        waitFor("Save this page to your reading list instead")
        compose.onNodeWithText("Save this page to your reading list instead").performClick()

        waitFor("Saved to your reading list")
        assertEquals(1, runBlocking { com.app.newspaperss.data.ReadingListRepository(db).observe().first().size })
    }

    @Test
    fun tappingASourceOpensItAndRemovingOneAsksFirst() {
        val id = runBlocking { SourceRepository(db).addFeed("https://example.com/feed", "Posts") }
        waitFor("Posts")

        compose.onNodeWithText("Posts").performClick()
        assertEquals(id, opened)

        compose.onNodeWithContentDescription("More for Posts").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(1, runBlocking { db.sources().all().size })

        compose.onNodeWithContentDescription("More for Posts").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNode(hasText("Remove source") and hasAnyAncestor(isDialog())).performClick()
        waitFor("Posts", present = false)
    }

    @Test
    fun aSiteWithSeveralFeedsAsksWhichOne() {
        http.page(
            "https://example.com",
            """<html><head>
                 <link rel=alternate type=application/rss+xml href=/news title=News>
                 <link rel=alternate type=application/atom+xml href=/essays title=Essays>
               </head></html>""",
        )
        addSource("example.com")
        waitFor("Which part of this site?")
        compose.onNodeWithText("Essays").performClick()
        waitFor("Which part of this site?", present = false)
        compose.onNodeWithText("Essays").assertIsDisplayed()
    }

    @Test
    fun aSiteWithoutAFeedExplainsWhy() {
        http.page("https://example.com", "<html></html>")
        addSource("example.com")
        waitFor("No feed found")
    }

    @Test
    fun aSitesArticleTextIsShownAndTheReadersChoiceIsSaved() {
        val id = runBlocking {
            db.sources().insert(
                SourceEntity(url = "https://walled.example/feed", title = "Walled", contentMode = ContentMode.FEED, fullTextEvidence = FullTextEvidence.BLOCKED, fullTextStreak = 3),
            )
        }
        waitFor("Site blocks fetching")

        compose.onNodeWithContentDescription("More for Walled").performClick()
        compose.onNodeWithText("Article text").performClick()
        compose.onNodeWithText("Always fetch the full page").performClick()

        waitFor("Always fetches the full page (your choice)")
        val saved = runBlocking { db.sources().byId(id)!! }
        assertEquals(ContentMode.PAGE, saved.contentMode)
        assertTrue(saved.contentModeChosen)
    }

    @Test
    fun aCuratedListIsAddedWithATapAndIsNotOfferedAgain() {
        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Three picks a day from essays and reviews").assertIsDisplayed()
        compose.onNodeWithText("Arts & Letters Daily").performClick()

        waitFor("Paste a website or feed address.", present = false)
        waitFor("aldaily.com")
        assertEquals(1, syncRequests)
        assertEquals(SourceKind.LIST, runBlocking { db.sources().all().single().kind })

        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Paste a website or feed address.").assertIsDisplayed()
        waitFor("Three picks a day", present = false)
    }
}
