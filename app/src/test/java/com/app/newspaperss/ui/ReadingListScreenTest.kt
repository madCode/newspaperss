package com.app.newspaperss.ui

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.ui.readinglist.ReadingListScreen
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aSavedLinkShowsItsReadingTimeAndOpensInTheBrowser() {
        val list = app.container.readingList
        runBlocking {
            list.save("https://a.example/2026/09/why-tides-matter/123", null)
            list.save("https://b.example/long-read", "A long read")
            val long = app.container.db.articles().allForSource(list.sourceId()).single { it.url.endsWith("long-read") }
            app.container.db.articles().setPageWords(long.id, 2380)
        }
        val vm = ReadingListViewModel(list)
        compose.setContent { ReadingListScreen(vm, onBack = {}) }

        // Before its title is found, a title made from the address, not the bare domain.
        waitFor("Why tides matter")
        waitFor("b.example · 10 min read")

        compose.onNodeWithText("A long read").performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://b.example/long-read", opened.dataString)
    }

    @Test
    fun aRemovedLinkCanBeUndoneAndComesBackAsItWas() {
        val list = app.container.readingList
        runBlocking { list.save("https://b.example/long-read", "A long read") }
        val before = runBlocking { app.container.db.articles().allForSource(list.sourceId()).single() }
        val vm = ReadingListViewModel(list)
        compose.setContent { ReadingListScreen(vm, onBack = {}) }
        waitFor("A long read")

        // Named, so TalkBack doesn't read "Remove" on every row alike.
        compose.onNodeWithContentDescription("Remove A long read").performClick()
        waitFor("Removed “A long read”")
        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(5_000) { runBlocking { app.container.db.articles().allForSource(list.sourceId()) } == listOf(before) }
    }

    @Test
    fun aLinkRemovedFromAnUnsentEditionComesBackWaiting() = runBlocking {
        // Removing it unlinked it from that edition, which would never deliver or release it.
        val list = app.container.readingList
        list.save("https://b.example/long-read", "A long read")
        val article = app.container.db.articles().allForSource(list.sourceId()).single().copy(state = ArticleState.IN_EDITION)
        list.remove(article)

        list.restore(article)

        assertEquals(ArticleState.NEW, app.container.db.articles().allForSource(list.sourceId()).single().state)
    }
}
