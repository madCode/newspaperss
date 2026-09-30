package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: SettingsStore
    private val rescheduled = mutableListOf<Settings>()

    @Before
    fun show() {
        store = SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") })
        val vm = SettingsViewModel(store) { rescheduled += it }
        compose.setContent { SettingsScreen(vm) }
        waitFor("Your edition")
    }

    private fun waitFor(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun turningOnTheScheduleShowsDaysAndReschedules() {
        compose.onNodeWithText("Make an edition automatically").performScrollTo().performClick()
        idleUntil { rescheduled.isNotEmpty() }
        waitFor("Mon")
        assertEquals(true, rescheduled.last().scheduleEnabled)
    }

    @Test
    fun orderingIsSaved() {
        compose.onNodeWithText("Shuffle").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.ordering } == Ordering.SHUFFLE }
        compose.onNodeWithText("Shuffle").assertIsSelected()
    }

    @Test
    fun notesWithEachEditionCanBeTurnedOnForFolderDelivery() {
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        waitFor("Also save notes with each edition")

        compose.onNodeWithText("Also save notes with each edition").performScrollTo().performClick()

        idleUntil { runBlocking { store.current().notesWithEdition } }
    }

    @Test
    fun theEReaderCanBeChangedAfterOnboarding() {
        compose.onNodeWithText("Boox or another Android e-reader").performScrollTo().performClick()

        idleUntil { runBlocking { store.current().device } == com.app.newspaperss.settings.Device.BOOX }
        compose.onNodeWithText("Boox or another Android e-reader").assertIsSelected()
    }

    @Test
    fun theBuildIsNamedAtTheBottomSoFeedbackCanSayWhichOne() {
        compose.onNodeWithText("newspapeRSS 0.1.0", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun perSourceCapCanBeRaised() {
        compose.onNodeWithText("+").performClick()
        waitFor("2 articles from each site, then more")
    }
}
