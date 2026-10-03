package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesScreenTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

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

    private fun addSource(input: String) {
        compose.onNodeWithText("Add a source", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(input)
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
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
    fun tappingASourceOpensIt() {
        val id = runBlocking { SourceRepository(db).addFeed("https://example.com/feed", "Posts") }
        waitFor("Posts")

        compose.onNodeWithText("Posts").performClick()
        assertEquals(id, opened)
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
    fun aSitesArticleTextIsShownAndTheReadersChoiceToo() {
        val id = runBlocking {
            db.sources().insert(SourceEntity(url = "https://walled.example/feed", title = "Walled")).also { id ->
                db.sources().savePublication(PublicationEntity(id, PublicationEntity.OWN, ContentMode.FEED, FullTextEvidence.BLOCKED, 3))
            }
        }
        waitFor("Site blocks fetching")

        runBlocking { SourceRepository(db).chooseContentMode(id, PublicationEntity.OWN, ContentMode.PAGE) }
        waitFor("Always fetches the full page (your choice)")
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
