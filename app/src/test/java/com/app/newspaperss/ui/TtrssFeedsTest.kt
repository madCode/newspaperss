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
import com.app.newspaperss.ui.sources.FeedRow
import com.app.newspaperss.ui.sources.LeftOutScreen
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.feedNote
import com.app.newspaperss.ui.sources.feedsLine
import com.app.newspaperss.ui.sources.sameFeed
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

/** A tt-rss account's feeds: inset under its row on Sources, each with a page of its own. */
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

    /** An account listed by tt-rss with three feeds, one left out, and a phone feed that's also one of them. */
    private fun account(): Long = runBlocking {
        val account = repo.addTtrss("https://rss.example/api/")
        fun listed(key: String, title: String, url: String, category: String) =
            PublicationEntity(account, key, title = title, feedUrl = url, category = category, listed = true)
        db.sources().savePublication(listed("7", "Quarterly Review", "https://quarterly.example/feed", "Essays"))
        db.sources().savePublication(listed("8", "Morning Wire", "https://wire.example/rss", "News").copy(maxArticles = 2))
        db.sources().savePublication(listed("42", "Press Office", "https://press.example/feed", "News").copy(leftOut = true))
        db.sources().setFeedsListed(account, Instant.now())
        db.articles().insertNew(
            listOf("7" to "Quarterly Review", "8" to "Morning Wire").flatMap { (feed, title) ->
                (1..2).map { ArticleEntity(sourceId = account, guid = "ttrss:$feed$it", url = "https://news.example/$feed/$it", title = "$title story $it", originId = feed, originTitle = title) }
            },
        )
        account
    }

    // Tall enough for the open feeds under the tt-rss row.
    @Config(qualifiers = "w411dp-h1400dp")
    @Test
    fun theFeedsFoldUnderTheTtrssRowWhichComesLast() {
        val account = account()
        runBlocking { repo.addFeed("http://www.wire.example/rss/", "The Wire, on the phone") }
        val settings = SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") })
        val vm = SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = settings) {}
        var feed: Pair<Long, String>? = null
        var leftOut: Long? = null
        compose.setContent { SourcesScreen(vm, onOpenFeed = { id, key -> feed = id to key }, onOpenLeftOut = { leftOut = it }) }
        idleUntil { compose.waitForIdle(); visible("2 feeds in your paper, 1 left out") }

        assertEquals("a feed added later still lands above tt-rss", listOf("The Wire, on the phone", SourceRepository.TTRSS_TITLE), vm.rows.value!!.map { it.source.title })
        assertTrue(visible("Also in your tt-rss"))
        assertFalse("folded until opened", visible("Quarterly Review"))

        compose.onNodeWithContentDescription("Show 2 feeds in your paper").performClick()
        idleUntil { compose.waitForIdle(); visible("Quarterly Review") }
        assertTrue(visible("Also on this phone · At most 2"))
        assertFalse("a left-out feed isn't among them", visible("Press Office"))
        assertTrue("and it stays open", runBlocking { settings.current().feedsShown })

        compose.onNodeWithText("Quarterly Review").performClick()
        assertEquals(account to "7", feed)
        compose.onNodeWithText("Left out · 1").performClick()
        assertEquals(account, leftOut)

        compose.onNodeWithContentDescription("Hide the feeds").performClick()
        idleUntil { compose.waitForIdle(); !visible("Quarterly Review") }
    }

    @Test
    fun aFeedsPageHasItsOwnSettingsAndArticles() {
        val account = account()
        val vm = SourceDetailViewModel(repo, account, flowOf(1), key = "7")
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        idleUntil { compose.waitForIdle(); visible("From Tiny Tiny RSS · category Essays") }

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
        fun row(publication: PublicationEntity?, alsoOnPhone: Boolean = false) = FeedRow(FeedChoice("7", "Q", inPaper = true, publication), alsoOnPhone)
        assertNull(feedNote(row(null)))
        assertNull(feedNote(row(PublicationEntity(1, "7", title = "Q", listed = true))))
        assertEquals(
            "Also on this phone · Feed's text · At most 1",
            feedNote(row(PublicationEntity(1, "7", chosenMode = ContentMode.FEED, maxArticles = 1), alsoOnPhone = true)),
        )
        assertEquals("Full page", feedNote(row(PublicationEntity(1, "7", chosenMode = ContentMode.PAGE))))
        assertEquals("1 feed in your paper", feedsLine(listOf(row(null))))
        assertTrue(sameFeed("https://www.Example.com/feed/", "http://example.com/feed"))
        assertFalse(sameFeed("https://example.com/feed", "https://example.com/rss"))
    }
}
