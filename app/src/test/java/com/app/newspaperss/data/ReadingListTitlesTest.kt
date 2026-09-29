package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
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
class ReadingListTitlesTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val untitled = mutableListOf<Long>()
    private val list = ReadingListRepository(db, onUntitled = { untitled += it })
    private val titles = ReadingListTitles(db, http)

    private fun page(title: String) =
        "<html><head><title>$title</title><meta property=\"og:site_name\" content=\"Example Weekly\"></head><body><p>Text</p></body></html>"

    private suspend fun titleOf(url: String) = db.articles().allForSource(list.sourceId()).single { it.url == url }.title

    @Test
    fun aLinkSavedWithoutATitleGetsThePagesTitle() = runTest {
        http.page("https://a.example/tides", page("How tides work | Example Weekly"))
        list.save("https://a.example/tides")
        assertTrue(titles.fetch(untitled))
        assertEquals("How tides work", titleOf("https://a.example/tides"))
    }

    @Test
    fun everySavedLinkIsMeasuredForItsReadingTime() = runTest {
        val words = "word ".repeat(476)
        http.page("https://a.example/long", "<html><head><title>A long read</title></head><body><article><p>$words</p></article></body></html>")
        list.save("https://a.example/long", "Given a title already")
        assertTrue(titles.fetch(untitled))
        val saved = db.articles().allForSource(list.sourceId()).single()
        assertEquals("Given a title already", saved.title)
        assertTrue("about two minutes' worth", (saved.pageWords ?: 0) in 400..500)
    }

    @Test
    fun aTitleTheReaderGaveIsKept() = runTest {
        http.page("https://a.example/tides", page("How tides work"))
        list.save("https://a.example/tides", "Tides, for Sam")
        val id = db.articles().candidates().single().id
        assertTrue(titles.fetch(listOf(id)))
        assertEquals("Tides, for Sam", titleOf("https://a.example/tides"))
    }

    @Test
    fun anArticleAnEditionTakesWhileThePageLoadsKeepsItsTitle() = runTest {
        http.page("https://a.example/tides", page("How tides work"))
        list.save("https://a.example/tides")
        // The edition shows the title it extracted itself; renaming the article now would disagree with it.
        http.beforeResponse = { db.articles().setState(untitled, ArticleState.IN_EDITION) }
        titles.fetch(untitled)
        assertEquals("", titleOf("https://a.example/tides"))
    }

    @Test
    fun anUnreachablePageIsWorthRetryingAMissingOneIsNot() = runTest {
        http.unreachable += "https://a.example/offline"
        list.save("https://a.example/offline")
        list.save("https://a.example/gone")
        http.page("https://a.example/later", page("Worth the wait"))
        list.save("https://a.example/later")

        assertFalse(titles.fetch(untitled))
        assertEquals("", titleOf("https://a.example/offline"))
        assertEquals("", titleOf("https://a.example/gone"))
        // One unreachable page doesn't stop the rest.
        assertEquals("Worth the wait", titleOf("https://a.example/later"))

        assertTrue(titles.fetch(untitled - untitled.first()))
    }
}
