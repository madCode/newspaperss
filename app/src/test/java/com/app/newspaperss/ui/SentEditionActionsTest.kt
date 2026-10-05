package com.app.newspaperss.ui

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.offersOpen
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.edition.KINDLE_NOTE
import com.app.newspaperss.ui.edition.MARK_NOT_SENT
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/** What a sent edition offers, on Today's card and its own page: reading it first, the rare actions in a menu. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class SentEditionActionsTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val editionsDir by lazy { File(app.filesDir, "editions").apply { mkdirs() } }
    private val repo by lazy { EditionRepository(db, editionsDir) }
    private val options = "More options for Tuesday Morning Edition"

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    private fun edition(status: EditionStatus, error: String? = null): Long = runBlocking {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        val state = if (status == EditionStatus.DELIVERED) ArticleState.DELIVERED else ArticleState.IN_EDITION
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "A story", state = state))
        editionsDir.resolve("e.epub").writeText("epub")
        val id = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = status, fileName = "e.epub", articleCount = 1, minutes = 4.0, error = error))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = article, position = 0, title = "A story", sourceTitle = "Example News", minutes = 4.0)))
        id
    }

    private fun installKindle() = installApp(
        app,
        packageName = EditionIntents.KINDLE_PACKAGE,
        label = "Kindle",
        filters = listOf(IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }),
    )

    private fun today(device: Device, sentToKindle: Map<Long, KindleSend> = emptyMap(), onOpenEdition: (Long) -> Unit = {}) {
        val vm = TodayViewModel(repo, flowOf(null), settings = flowOf(Settings(device = device)), sentToKindle = flowOf(sentToKindle)) {}
        compose.setContent { TodayScreen(vm, onOpenEdition = onOpenEdition) }
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasText("Tuesday Morning Edition")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun page(id: Long, device: Device) {
        val vm = EditionDetailViewModel(repo, id, EditionNotes(db, File(app.filesDir, "notes"))) {}
        compose.setContent {
            EditionDetailScreen(vm, onBack = {}, preferOpen = device == Device.BOOX, offerOpen = device.offersOpen, kindleReader = device == Device.KINDLE)
        }
        idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun leftOf(first: String, second: String) =
        button(first).fetchSemanticsNode().boundsInRoot.left < button(second).fetchSemanticsNode().boundsInRoot.left

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id) }?.status

    @Test
    fun aKindleReadersSentCardLeadsWithWhatsInsideThenTheKindleApp() {
        installKindle()
        val id = edition(EditionStatus.DELIVERED)
        var opened: Long? = null
        today(Device.KINDLE) { opened = it }

        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText(MARK_NOT_SENT).assertDoesNotExist()
        compose.onNodeWithText("Open").assertDoesNotExist()
        assertTrue(leftOf("See what's inside", "Open Kindle"))

        button("Open Kindle").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(EditionIntents.KINDLE_PACKAGE, started.component?.packageName ?: started.`package`)

        button("See what's inside").performClick()
        assertEquals(id, opened)
    }

    @Test
    fun tappingASentCardAnywhereOpensTheEdition() {
        val id = edition(EditionStatus.DELIVERED)
        var opened: Long? = null
        today(Device.KOBO) { opened = it }

        compose.onNode(hasText("Tuesday Morning Edition") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(id, opened)
    }

    @Test
    fun withoutTheKindleAppThereIsNoOpenKindle() {
        edition(EditionStatus.DELIVERED)
        today(Device.KINDLE)

        button("See what's inside").assertExists()
        compose.onNodeWithText("Open Kindle").assertDoesNotExist()
    }

    @Test
    fun openKindleAfterTheAppWasRemovedSaysSoAndGoesAway() {
        installKindle()
        edition(EditionStatus.DELIVERED)
        today(Device.KINDLE)
        shadowOf(app.packageManager).deletePackage(EditionIntents.KINDLE_PACKAGE)
        // A real phone refuses to start a removed app.
        shadowOf(app).checkActivities(true)

        button("Open Kindle").performClick()

        assertEquals("The Kindle app isn't on this phone any more.", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("Open Kindle").assertDoesNotExist()
    }

    @Test
    fun aSentCardWhoseBookIsGoneHasNoMenu() {
        edition(EditionStatus.DELIVERED)
        editionsDir.resolve("e.epub").delete()
        today(Device.KOBO)
        compose.onNodeWithContentDescription(options).assertDoesNotExist()
    }

    @Test
    fun aBooxReadersSentCardLeadsWithOpen() {
        edition(EditionStatus.DELIVERED)
        today(Device.BOOX)

        assertTrue(leftOf("Open", "See what's inside"))
        compose.onNodeWithText("Open Kindle").assertDoesNotExist()
        compose.onNodeWithText("Send again").assertDoesNotExist()
    }

    @Test
    fun aKoboReadersSentCardHasOnlyWhatsInside() {
        installKindle()
        edition(EditionStatus.DELIVERED)
        today(Device.KOBO)

        button("See what's inside").assertExists()
        compose.onNodeWithText("Open").assertDoesNotExist()
        compose.onNodeWithText("Open Kindle").assertDoesNotExist()
    }

    @Test
    fun sendAgainFromTheCardsMenuOpensTheShareSheetAndLeavesItSent() {
        val id = edition(EditionStatus.DELIVERED)
        today(Device.KOBO)

        compose.onNodeWithContentDescription(options).performClick()
        compose.onNodeWithText("Send again").assertIsEnabled().performClick()

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)
        compose.onNodeWithText("Send again").assertDoesNotExist()
        assertEquals(EditionStatus.DELIVERED, statusOf(id))
    }

    @Test
    fun aReadyOrFailedCardKeepsItsButtonsAndHasNoMenu() {
        val id = edition(EditionStatus.READY)
        today(Device.KINDLE)
        button("Send").assertExists()
        compose.onNodeWithText("I've sent it").assertExists()
        compose.onNodeWithContentDescription(options).assertDoesNotExist()

        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = EditionStatus.FAILED, error = EditionBuilder.UNEXPECTED)) }
        idleUntil { compose.waitForIdle(); compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(options).assertDoesNotExist()
    }

    @Test
    fun theKindleNoteSaysWhereMarkAsNotSentIsAndNamesItForTalkBack() {
        val id = edition(EditionStatus.DELIVERED)
        today(Device.KINDLE, sentToKindle = mapOf(id to KindleSend.APP))

        val note = compose.onNodeWithText(KINDLE_NOTE).fetchSemanticsNode()
        val spoken = note.config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, spoken.endsWith("Use More options to mark it as not sent."))
    }

    @Test
    fun anEditionsMenuOffersSendAgainAndMarkAsNotSentOnlyOnceSent() {
        val id = edition(EditionStatus.READY)
        page(id, Device.KOBO)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete edition").assertExists()
        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText(MARK_NOT_SENT).assertDoesNotExist()
    }

    @Test
    fun aSentEditionsPageSendsAgainFromItsMenu() {
        val id = edition(EditionStatus.DELIVERED)
        page(id, Device.KOBO)
        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText("Open").assertDoesNotExist()

        compose.onNodeWithContentDescription("More options").performClick()
        val tops = listOf("Send again", MARK_NOT_SENT, "Delete edition").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("above Delete edition", tops.sorted(), tops)
        compose.onNodeWithText("Send again").performClick()

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun aSentEditionsPageOffersAKindleReaderTheKindleApp() {
        installKindle()
        page(edition(EditionStatus.DELIVERED), Device.KINDLE)
        button("Open Kindle").assertExists()
        compose.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test
    fun aSentEditionsPageOffersABooxReaderOpen() {
        installKindle()
        page(edition(EditionStatus.DELIVERED), Device.BOOX)
        button("Open").assertExists()
        compose.onNodeWithText("Open Kindle").assertDoesNotExist()
    }
}
