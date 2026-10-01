package com.app.newspaperss.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.testutil.MAIL_APP
import com.app.newspaperss.testutil.installApp
import org.robolectric.Shadows.shadowOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
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
    // Cancelled before TemporaryFolder deletes the file: a write still running after that fails
    // its rename, and the error lands in whichever test is running then.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rescheduled = mutableListOf<Settings>()

    @Before
    fun show() {
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") })
        val vm = SettingsViewModel(store) { rescheduled += it }
        compose.setContent { SettingsScreen(vm) }
        waitFor("Your edition")
    }

    @After fun stopStore() = runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin() }

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
    fun theOrderAndScheduleRowsAreFullSizeTargets() {
        listOf("Take turns between sources", "Source by source, in list order", "Shuffle", "Make an edition automatically").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun screenReadersHearWhatTheStepperChangesAndTheNewNumber() {
        compose.onNodeWithContentDescription("More from each source").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.maxPerSource } == 2 }
        compose.onNode(hasText("2 articles from each source", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        compose.onNodeWithContentDescription("Fewer from each source").assertIsEnabled()
    }

    @Test
    fun theSizeSliderSaysMinutesAndSectionsAreHeadings() {
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "30 minutes")).assertExists()
        compose.onNode(hasText("Your edition") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test
    fun orderingIsSaved() {
        compose.onNodeWithText("Shuffle").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.ordering } == Ordering.SHUFFLE }
        compose.onNodeWithText("Shuffle").assertIsSelected()
    }

    @Test
    fun notesCanBeSavedWhateverTheDeliveryAndTurnedOff() {
        // A Kindle reader shares each edition, and still gets notes in her vault.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.SHARE, notesFolderUri = "content://vault", notesFolderName = "Vault") } }
        waitFor("Saved to Vault when an edition is delivered.")

        compose.onNodeWithText("Save notes for each edition").performScrollTo().performClick()

        idleUntil { runBlocking { store.current().notesFolderUri } == null }
        waitFor("You'll pick the folder.")
        assertEquals("the delivery folder is left alone", DeliveryMethod.SHARE, runBlocking { store.current().delivery })
    }

    @Test
    fun theEReaderCanBeChangedAfterOnboarding() {
        compose.onNodeWithText("Boox or another Android e-reader").performScrollTo().performClick()

        idleUntil { runBlocking { store.current().device } == com.app.newspaperss.settings.Device.BOOX }
        compose.onNodeWithText("Boox or another Android e-reader").assertIsSelected()
    }

    @Test
    fun theReaderTipIsAboutSharingSoOnlySharingShowsIt() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.SHARE) } }
        waitFor("tap Send and choose the Kindle app")

        // A Kindle owner saving to a folder isn't told to wait for a Send.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        idleUntil { compose.onAllNodes(hasText("tap Send", substring = true)).fetchSemanticsNodes().isEmpty() }

        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("tap Send", substring = true).assertDoesNotExist()
    }

    @Test
    fun theEReaderComesBeforeDeliveryWhichItsChoicesDependOn() {
        val order = listOf("Your edition", "Schedule", "Your e-reader", "Delivery", "Reading notes").map {
            compose.onNode(hasText(it) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun theBuildIsNamedAtTheBottomSoFeedbackCanSayWhichOne() {
        compose.onNodeWithText("newspapeRSS 0.1.0", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun perSourceCapCanBeRaised() {
        compose.onNodeWithText("+").performClick()
        waitFor("2 articles from each source, then more")
    }

    @Test
    fun aKindleReaderCanSwitchToEmailAndSetItUp() {
        installApp(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(device = Device.KINDLE) } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("Arrives on your Kindle by itself.").assertExists()
        compose.onNodeWithText("Kindle's email address").assertDoesNotExist()

        compose.onNodeWithText("Email it to your Kindle").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { runBlocking { store.current().delivery } == DeliveryMethod.KINDLE_EMAIL }
        waitFor("The address you send from must be on Amazon's approved list.")

        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { runBlocking { store.current().kindleEmail } == "me_42@kindle.com" }
        compose.onNodeWithText("Ask each time").performScrollTo().performClick()
        compose.onNodeWithText("Example Mail").performClick()

        idleUntil { runBlocking { store.current().kindleEmailTarget } == KindleEmail("me_42@kindle.com", MAIL_APP) }
    }

    @Test
    fun emailToKindleIsOfferedOnlyToKindleReadersOrWhoeverAlreadyUsesIt() {
        runBlocking { store.update { it.copy(device = Device.KOBO) } }
        waitFor("Send it myself")
        compose.onNodeWithText("Email it to your Kindle").assertDoesNotExist()

        // Changed their e-reader after setting it up: the choice they made stays visible.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("Email it to your Kindle").assertIsSelected()
    }

    @Test
    fun withNotificationsOffEmailReadersAreWarnedTheirSendWontShowUp() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com", scheduleEnabled = true) } }
        waitFor("Notifications are off")

        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        idleUntil { compose.onAllNodes(hasText("Notifications are off", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun emailDeliveryWithoutAnAddressSaysSendWillShareUntilOneIsAdded() {
        val line = "Add your Kindle's email address; until then Send opens the share sheet."
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = null) } }
        waitFor(line)

        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { compose.onAllNodes(hasText(line)).fetchSemanticsNodes().isEmpty() }

        // Still said after the e-reader changes, with an address that doesn't work.
        runBlocking { store.update { it.copy(device = Device.KOBO, kindleEmail = "me_42@kindle") } }
        waitFor(line)
    }

    @Test
    fun theTwoFolderButtonsSayWhichFolderAndLineUp() {
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books", notesFolderUri = "content://vault", notesFolderName = "Vault") } }
        waitFor("Choose another notes folder")
        val starts = listOf("Choose another delivery folder", "Choose another notes folder").map {
            compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.x
        }
        assertEquals(starts[0], starts[1])
    }
}
