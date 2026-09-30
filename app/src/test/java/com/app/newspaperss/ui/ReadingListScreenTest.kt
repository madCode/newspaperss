package com.app.newspaperss.ui

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun aRemovedLinkCanBeUndoneAndIsUntouched() {
        val list = app.container.readingList
        runBlocking { list.save("https://b.example/long-read", "A long read") }
        val before = runBlocking { app.container.db.articles().allForSource(list.sourceId()).single() }
        val vm = ReadingListViewModel(list)
        compose.setContent { ReadingListScreen(vm, onBack = {}) }
        waitFor("A long read")

        // Named, so TalkBack doesn't read "Remove" on every row alike.
        compose.onNodeWithContentDescription("Remove A long read").performClick()
        waitFor("Removed “A long read”")
        compose.onNodeWithText("A long read").assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()

        waitFor("A long read")
        assertEquals(listOf(before), runBlocking { app.container.db.articles().allForSource(list.sourceId()) })
    }

    @Test
    fun aRemovalIsKeptOnceUndoIsGoneEvenIfTheScreenIsLeft() {
        val list = app.container.readingList
        runBlocking { list.save("https://a.example/one", "One"); list.save("https://b.example/two", "Two") }
        val vm = ReadingListViewModel(list, outlive = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        compose.setContent { ReadingListScreen(vm, onBack = {}) }
        waitFor("One")

        compose.onNodeWithContentDescription("Remove One").performClick()
        // A second removal settles the first; leaving settles the second.
        compose.onNodeWithContentDescription("Remove Two").performClick()
        compose.waitUntil(5_000) { runBlocking { app.container.db.articles().allForSource(list.sourceId()) }.map { it.title } == listOf("Two") }
        vm.commitRemove()
        compose.waitUntil(5_000) { runBlocking { app.container.db.articles().allForSource(list.sourceId()) }.isEmpty() }
    }
}
