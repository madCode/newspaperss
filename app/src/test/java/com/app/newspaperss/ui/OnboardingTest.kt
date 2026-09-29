package com.app.newspaperss.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.rss
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.onboarding.Step
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aKindleReaderPicksAPackAndGetsADailySharedEdition() {
        compose.setContent { OnboardingScreen(vm) }
        click("Get started")
        scrollAndClick("Kindle")
        compose.onNodeWithText("Send to Kindle", substring = true).assertExists()
        click("Next")
        val science = StarterPacks.all.first { it.name == "Science" }
        vm.togglePack(science.name)
        compose.waitForIdle()
        click("Next")
        click("Make my first edition")

        idleUntil { finished != null }
        val s = finished!!
        assertEquals(true, s.onboarded)
        assertEquals(Device.KINDLE, s.device)
        assertEquals(DeliveryMethod.SHARE, s.delivery)
        assertEquals(true, s.scheduleEnabled)
        val urls = runBlocking { db.sources().all() }.map { it.url }.toSet()
        assertEquals(science.feeds.map { it.url }.toSet(), urls)
        assertEquals("starter feeds keep their names", "Quanta Magazine", runBlocking { db.sources().byUrl(science.feeds.first().url)!!.title })
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
    fun koreaderWithAFolderGetsFolderDelivery() {
        vm.chooseDevice(Device.KOREADER)
        vm.chooseFolder("content://tree/books", "Books")
        vm.toggleFeed(StarterPacks.all.first().feeds.first().url)
        vm.finish()
        idleUntil { finished != null }
        assertEquals(DeliveryMethod.FOLDER, finished!!.delivery)
        assertEquals("Books", finished!!.folderName)
    }
}
