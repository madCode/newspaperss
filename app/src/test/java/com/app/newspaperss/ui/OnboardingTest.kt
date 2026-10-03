package com.app.newspaperss.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.testutil.MAIL_APP
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.onboarding.Step
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class OnboardingTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = FakeHttp()
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }
    private var finished: Settings? = null
    private val vm by lazy { OnboardingViewModel(store, SourceRepository(db), FeedFinder(http)) { finished = it } }

    @After fun close() = db.close()

    private fun click(text: String) = compose.onNodeWithText(text).performClick()
    private fun scrollAndClick(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test
    fun aKindleReaderSetsUpEmailToTheirKindleAndGetsADailyEdition() {
        installApp(ApplicationProvider.getApplicationContext())
        compose.setContent { OnboardingScreen(vm) }
        click("Get started")
        scrollAndClick("Kindle")
        compose.onNodeWithText("Send it straight to your Kindle").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        compose.onNodeWithText("Needed to go on.", substring = true).assertExists()
        val field = compose.onNode(hasSetTextAction() and hasText("Your Kindle's email address"))
        field.performTextInput("me_42@kindle")
        // Still typing: no error yet, but it says why Next waits.
        compose.onNodeWithText("That isn't a whole email address yet.").assertDoesNotExist()
        compose.onNodeWithText("Needed to go on.", substring = true).assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        field.performImeAction()
        compose.onNodeWithText("That isn't a whole email address yet.").assertExists()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "That isn't a whole email address yet."))
        compose.onNode(hasText("That isn't a whole email address yet.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()

        field.performTextInput(".com")
        compose.onNodeWithText("That isn't a whole email address yet.").assertDoesNotExist()
        compose.onNodeWithText("Find it on Amazon", substring = true).assertExists()
        compose.onNodeWithText("Needed to go on.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Next").assertIsEnabled()

        compose.onNodeWithText("Ask each time").performScrollTo().performClick()
        click("Example Mail")
        click("Next")
        val science = StarterPacks.all.first { it.name == "Science" }
        compose.onNodeWithContentDescription("All of Science").performScrollTo().performClick()
        click("Next")
        click("Make my first edition")

        idleUntil { finished != null }
        val s = finished!!
        assertEquals(true, s.onboarded)
        assertEquals(Device.KINDLE, s.device)
        assertEquals(DeliveryMethod.KINDLE_EMAIL, s.delivery)
        assertEquals(KindleEmail("me_42@kindle.com", MAIL_APP), s.kindleEmailTarget)
        assertEquals(true, s.scheduleEnabled)
        val urls = runBlocking { db.sources().all() }.map { it.url }.toSet()
        assertEquals(science.feeds.map { it.url }.toSet(), urls)
        assertEquals("starter feeds keep their names", "Quanta Magazine", runBlocking { db.sources().byUrl(science.feeds.first().url)!!.title })
    }

    @Test
    fun anAddressThatIsntAKindlesIsQueriedButAllowed() {
        compose.setContent { OnboardingScreen(vm) }
        click("Get started")
        scrollAndClick("Kindle")
        compose.onNode(hasSetTextAction() and hasText("Your Kindle's email address")).performTextInput("me@example.org")
        compose.onNodeWithText("Kindle addresses end in @kindle.com", substring = true).assertExists()
        compose.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun aKindleReaderCanUseTheKindleAppInstead() {
        compose.setContent { OnboardingScreen(vm) }
        click("Get started")
        scrollAndClick("Kindle")
        compose.onNode(hasSetTextAction() and hasText("Your Kindle's email address")).performTextInput("me_42@kindle.com")
        scrollAndClick("Use the Kindle app instead")

        compose.onNodeWithText("Send to Kindle", substring = true).assertExists()
        compose.onNodeWithText("Send it straight to your Kindle").assertDoesNotExist()
        vm.toggleFeed(StarterPacks.all.first().feeds.first().url)
        click("Next")
        click("Next")
        click("Make my first edition")

        idleUntil { finished != null }
        assertEquals(DeliveryMethod.SHARE, finished!!.delivery)
        assertNull("an address typed before changing their mind isn't kept", finished!!.kindleEmail)
    }

    @Test
    fun aKindleReaderWhoChoseTheAppCanComeBackToEmail() {
        vm.next()
        vm.chooseDevice(Device.KINDLE)
        vm.useKindleApp()
        assertTrue(vm.state.value.canContinue)
        compose.setContent { OnboardingScreen(vm) }
        scrollAndClick("Email it to your Kindle instead")
        compose.onNodeWithText("Send it straight to your Kindle").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun someoneLeavingPocketCanStartWithTheirSavedLinksAlone() {
        val pocketVm = OnboardingViewModel(store, SourceRepository(db), FeedFinder(http), savedLinks = flowOf(2)) { finished = it }
        val readingVm = com.app.newspaperss.ui.readinglist.ReadingListViewModel(com.app.newspaperss.data.ReadingListRepository(db))
        compose.setContent { OnboardingScreen(pocketVm, readingList = readingVm) }
        click("Get started")
        scrollAndClick("Kobo")
        click("Next")

        compose.onNodeWithText("2 saved links waiting", substring = true).performScrollTo().assertExists()
        click("Next")
        click("Make my first edition")

        idleUntil { finished != null }
        assertEquals(true, finished!!.onboarded)
        assertEquals("no feeds needed", emptyList<String>(), runBlocking { db.sources().all() }.map { it.url })
    }

    @Test
    fun withNeitherSitesNorSavedLinksTheresNothingToMakeAPaperFrom() {
        vm.chooseDevice(Device.KOBO)
        vm.next(); vm.next()
        assertEquals(Step.SOURCES, vm.state.value.step)
        assertFalse(vm.state.value.canContinue)
    }

    @Test
    fun aTtrssReaderConnectsTheirAccountAndNeedsNoStarterFeeds() {
        val server = FakeTtrss(http)
        val sources = SourceRepository(db)
        val accounts = TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher())
        val sourcesVm = SourcesViewModel(sources, FeedFinder(http), TtrssRepository(db, http, accounts, sources)) {}
        compose.setContent { OnboardingScreen(vm, sourcesVm) }
        click("Get started")
        scrollAndClick("Kobo")
        click("Next")
        compose.onNodeWithText("Next").assertIsNotEnabled()

        scrollAndClick("Connect tt-rss")
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields[0].performTextInput("rss.example.com/tt-rss")
        fields[1].performTextInput(server.user)
        fields[2].performTextInput(server.password)
        click("Test and add")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("1 source added", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        click("Next")
        click("Make my first edition")
        idleUntil { finished != null }
        assertEquals(listOf(SourceKind.TTRSS), runBlocking { db.sources().all() }.map { it.kind })
    }

    @Test
    fun anOpmlImportCountsAsSourcesAndAnEarlierResultDoesntHideTheCount() {
        val sources = SourceRepository(db)
        val sourcesVm = SourcesViewModel(sources, FeedFinder(http)) {}
        compose.setContent { OnboardingScreen(vm, sourcesVm) }
        click("Get started")
        scrollAndClick("Kobo")
        click("Next")
        compose.onNodeWithText("Connect tt-rss").assertDoesNotExist()

        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        sourcesVm.importOpml(resolver, android.net.Uri.fromFile(tmp.newFile("empty.opml").apply { writeText("<opml version=\"2.0\"><body/></opml>") }))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No new sites in that file", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val opml = tmp.newFile("feeds.opml").apply {
            writeText("""<opml version="2.0"><body><outline type="rss" text="A" xmlUrl="https://a.example/feed"/><outline type="rss" text="B" xmlUrl="https://b.example/feed"/></body></opml>""")
        }
        sourcesVm.importOpml(resolver, android.net.Uri.fromFile(opml))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("2 sources added", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        click("Next")
        click("Make my first edition")
        idleUntil { finished != null }
        assertEquals(setOf("https://a.example/feed", "https://b.example/feed"), runBlocking { db.sources().all() }.map { it.url }.toSet())
    }

    @Test
    fun youCantGoOnWithoutADeviceOrASource() {
        vm.next()
        vm.next()
        assertEquals(Step.DEVICE, vm.state.value.step)
        vm.chooseDevice(Device.OTHER)
        vm.next()
        vm.next()
        assertEquals(Step.SOURCES, vm.state.value.step)
        assertFalse(vm.state.value.canContinue)
    }

    @Test
    fun aPastedWebsiteIsFoundAndChosen() {
        http.page("https://blog.example", "<html><head><link rel=alternate type=application/rss+xml href=/feed title=Blog></head></html>")
        http.page("https://blog.example/feed", rss("Blog"))
        vm.editPasted("blog.example")
        vm.findPasted()
        idleUntil { vm.state.value.found.isNotEmpty() }
        assertEquals(setOf("https://blog.example/feed"), vm.state.value.chosen)
    }

    @Test
    fun talkBackHearsWhichStepThisIsNotAPercentage() {
        compose.setContent { OnboardingScreen(vm) }
        vm.next()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Step 1 of 3")).assertExists()
        vm.chooseDevice(Device.KOBO)
        vm.next()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Step 2 of 3")).assertExists()
    }

    @Test
    fun koreaderWithAFolderGetsFolderDelivery() {
        vm.chooseDevice(Device.KOREADER)
        vm.chooseFolder("content://tree/books", "Books")
        vm.toggleFeed(StarterPacks.all.first().feeds.first().url)
        vm.finish()
        idleUntil { finished != null }
        assertEquals(DeliveryMethod.FOLDER, finished!!.delivery)
        assertEquals("Books", finished!!.folderName)
    }

    @Test
    fun choicesSurviveTheAppBeingKilled() {
        val handle = androidx.lifecycle.SavedStateHandle()
        val first = OnboardingViewModel(store, SourceRepository(db), FeedFinder(http), handle) {}
        first.next()
        first.chooseDevice(Device.KOREADER)
        first.chooseFolder("content://tree/books", "Books")
        first.editKindleEmail("me_42@kindle.com")
        first.chooseMailApp(MAIL_APP)
        first.useKindleApp()
        first.toggleFeed(StarterPacks.all.first().feeds.first().url)
        first.setMinutes(45)
        idleUntil { handle.get<Int>("onboarding.minutes") == 45 }

        // A new process gets the same saved handle and nothing else.
        val restored = OnboardingViewModel(store, SourceRepository(db), FeedFinder(http), androidx.lifecycle.SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })) {}.state.value

        assertEquals(Step.DEVICE, restored.step)
        assertEquals(Device.KOREADER, restored.device)
        assertEquals("Books", restored.folderName)
        assertEquals(setOf(StarterPacks.all.first().feeds.first().url), restored.chosen)
        assertEquals(45, restored.minutes)
        assertEquals("me_42@kindle.com", restored.kindleEmail)
        assertEquals(MAIL_APP, restored.mailApp)
        assertFalse(restored.kindleByEmail)
    }

    @Test
    fun anAddressThatLooksFinishedButIsntIsFlaggedWithoutLeavingTheField() {
        compose.setContent { OnboardingScreen(vm) }
        click("Get started")
        scrollAndClick("Kindle")
        compose.onNode(hasSetTextAction() and hasText("Your Kindle's email address")).performTextInput("me@@kindle.com")
        compose.onNodeWithText("That isn't a whole email address yet.").assertExists()
    }
}
