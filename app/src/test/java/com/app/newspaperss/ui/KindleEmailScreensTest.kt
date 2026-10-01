package com.app.newspaperss.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.delivery.KindleSends
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.testutil.MAIL_APP
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.edition.KINDLE_EMAIL_NOTE
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Send on Today and an edition's page when editions are emailed to a Kindle. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class KindleEmailScreensTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val editionsDir by lazy { File(app.filesDir, "editions").apply { mkdirs() } }
    private val kindleSends = KindleSends()
    private val repo by lazy { EditionRepository(db, editionsDir, kindleSends = kindleSends) }
    private val settings = Settings(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com", mailApp = MAIL_APP)

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun readyEdition(): Pair<Long, Long> = runBlocking {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "A story", state = ArticleState.IN_EDITION))
        editionsDir.resolve("e.epub").writeText("epub")
        val id = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, fileName = "e.epub", articleCount = 1, minutes = 4.0))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = article, position = 0, title = "A story", sourceTitle = "Example News", minutes = 4.0)))
        id to article
    }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id) }?.status

    private fun assertMailAppOpened() {
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals(MAIL_APP, started.`package`)
    }

    @Test
    fun todaysSendOpensTheMailAppCountsAsSentAndSaysItWasEmailed() {
        installApp(app)
        val (id, article) = readyEdition()
        val vm = TodayViewModel(repo, flowOf(null), settings = flowOf(settings), sentToKindle = kindleSends.recent) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("Send opens your mail app, ready to go. Opening it counts as sent.")
        compose.onNodeWithText("I've sent it").assertExists()

        compose.onNodeWithText("Send").performClick()

        assertMailAppOpened()
        idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(article) }?.state)
        waitFor(KINDLE_EMAIL_NOTE)

        // It didn't arrive after all: the note would contradict the edition waiting to be sent.
        compose.onNodeWithText("Didn't arrive? Mark as not sent").performClick()
        compose.onNode(hasText("Mark as not sent") and hasAnyAncestor(isDialog())).performClick()
        waitFor("I've sent it")
        compose.onNodeWithText(KINDLE_EMAIL_NOTE).assertDoesNotExist()
    }

    @Test
    fun withTheMailAppGoneTodaysSendOffersTheShareSheetAndWaitsForAPick() {
        val (id, _) = readyEdition()
        val vm = TodayViewModel(repo, flowOf(null), settings = flowOf(settings)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("I've sent it")
        // Send will be the share sheet, so the line says what counts there.
        compose.onNodeWithText("Choosing an app to send it with counts as sent", substring = true).assertExists()
        compose.onNodeWithText("Send opens your mail app", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Send").performClick()

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)
        compose.waitForIdle()
        assertEquals(EditionStatus.READY, statusOf(id))
    }

    @Test
    fun anEditionsSendOpensTheMailAppAndCountsAsSent() {
        installApp(app)
        val (id, _) = readyEdition()
        val vm = EditionDetailViewModel(repo, id, EditionNotes(db, File(app.filesDir, "notes")), sentToKindle = kindleSends.recent) {}
        compose.setContent { EditionDetailScreen(vm, onBack = {}, offerOpen = false, kindleEmail = KindleEmail("me_42@kindle.com", MAIL_APP)) }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }

        compose.onNodeWithText("Send").performClick()

        assertMailAppOpened()
        idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        waitFor(KINDLE_EMAIL_NOTE)
    }
}
