package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourcesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private var syncRequests = 0

    @Before
    fun show() {
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(http)) { syncRequests++ }
        compose.setContent { SourcesScreen(vm) }
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
    fun addingASiteFindsItsFeedAndStartsASync() {
        http.page("https://example.com", "<html><head><link rel=alternate type=application/rss+xml href=/feed title=Posts></head></html>")
        http.page("https://example.com/feed", rss("Example"))

        addSource("example.com")

        waitFor("Posts")
        compose.onNodeWithText("Posts").assertIsDisplayed()
        assertEquals(1, syncRequests)
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
}
