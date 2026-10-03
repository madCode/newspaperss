package com.app.newspaperss.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.settings.PreviewTextSize
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
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.app.newspaperss.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private lateinit var vm: SettingsViewModel

    @Before
    fun makeStore() {
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") })
        vm = SettingsViewModel(store) { rescheduled += it }
    }

    /** Settings as the app shows it: the summary, or [page] opened from it, with Back to the summary. */
    private fun show(page: SettingsPage? = null) {
        compose.setContent {
            var open by remember { mutableStateOf(page) }
            when (val p = open) {
                null -> SettingsScreen(vm, onOpen = { open = it })
                else -> SettingsPageScreen(vm, p, onBack = { open = null })
            }
        }
        // The title draws before the settings load from DataStore, so wait for the content.
        idleUntil { vm.settings.value != null }
        waitFor(if (page == null) "newspapeRSS" else page.title)
    }

    @After fun stopStore() = runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin() }

    private fun waitFor(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun turningOnTheScheduleShowsDaysAndReschedules() {
        show(SettingsPage.SCHEDULE)
        compose.onNodeWithText("Make an edition automatically").performScrollTo().performClick()
        idleUntil { rescheduled.isNotEmpty() }
        waitFor("Mon")
        compose.onNodeWithText("Make an edition automatically").assertHeightIsAtLeast(48.dp)
        assertEquals(true, rescheduled.last().scheduleEnabled)
    }

    @Test
    fun theOrderRowsAreFullSizeTargets() {
        show(SettingsPage.EDITION)
        listOf("Take turns between sources", "Source by source, in list order", "Shuffle").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun screenReadersHearWhatTheStepperChangesAndTheNewNumber() {
        show(SettingsPage.EDITION)
        compose.onNodeWithContentDescription("More from each source").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.maxPerSource } == 2 }
        compose.onNode(hasText("2 articles from each source", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        compose.onNodeWithContentDescription("Fewer from each source").assertIsEnabled()
    }

    @Test
    fun theSizeSliderSaysMinutesAndOrderIsAHeading() {
        show(SettingsPage.EDITION)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "30 minutes")).assertExists()
        compose.onNode(hasText("Order") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test
    fun orderingIsSaved() {
        show(SettingsPage.EDITION)
        compose.onNodeWithText("Shuffle").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.ordering } == Ordering.SHUFFLE }
        compose.onNodeWithText("Shuffle").assertIsSelected()
    }

    @Test
    fun theArticleTextSizeKeepsAnEarlierChoiceAndSavesANewOne() {
        // Chosen with the preview's old Aa menu: the same stored value.
        runBlocking { store.update { it.copy(previewTextSize = PreviewTextSize.LARGER) } }
        show()
        waitFor("Article text size")
        compose.onNode(hasText("Larger") and hasClickAction()).assertExists()
        compose.onNodeWithText("Article text size").performClick()
        waitFor("on top of Android's own font size")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        compose.onNodeWithText("Larger").assertIsSelected()

        compose.onNodeWithText("Largest").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { runBlocking { store.current().previewTextSize } == PreviewTextSize.LARGEST }
        compose.onNodeWithText("Largest").assertIsSelected()
        compose.onNodeWithText("Larger").assertIsNotSelected()

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Largest")
    }

    @Test
    fun notesCanBeSavedWhateverTheDeliveryAndTurnedOff() {
        show(SettingsPage.NOTES)
        // A Kindle reader shares each edition, and still gets notes in her vault.
        grant("content://vault")
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.SHARE, notesFolderUri = "content://vault", notesFolderName = "Vault") } }
        waitFor("Saved to Vault when an edition is delivered.")

        compose.onNodeWithText("Save notes for each edition").performScrollTo().performClick()

        idleUntil { runBlocking { store.current().notesFolderUri } == null }
        waitFor("You'll pick the folder.")
        assertEquals("the delivery folder is left alone", DeliveryMethod.SHARE, runBlocking { store.current().delivery })
    }

    @Test
    fun theEReaderCanBeChangedAfterOnboarding() {
        runBlocking { store.update { it.copy(device = Device.KINDLE) } }
        show(SettingsPage.DELIVERY)
        waitFor("Email it to your Kindle")
        compose.onNode(hasText("Your e-reader") and hasClickAction()).performClick()
        compose.onNodeWithText("Boox or another Android e-reader").performClick()

        idleUntil { runBlocking { store.current().device } == Device.BOOX }
        waitFor("Boox or another Android e-reader")
        // A Boox reader isn't offered Kindle email.
        idleUntil { compose.onAllNodes(hasText("Email it to your Kindle")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theReaderTipIsAboutSharingSoOnlySharingShowsIt() {
        show(SettingsPage.DELIVERY)
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.SHARE) } }
        waitFor("tap Send and choose the Kindle app")
        // Under the choice it belongs to, where the reader is looking when they pick it.
        val (send, tip, folder) = listOf("Send it myself", "tap Send and choose the Kindle app", "Save to a folder").map {
            compose.onNodeWithText(it, substring = true).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(send < tip && tip < folder)

        // A Kindle owner saving to a folder isn't told to wait for a Send.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        idleUntil { compose.onAllNodes(hasText("tap Send", substring = true)).fetchSemanticsNodes().isEmpty() }

        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("tap Send", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSummarySaysHowEachPageIsSetAndOpensIt() {
        show()
        waitFor("About 30 minutes · 1 per source · take turns")
        compose.onNodeWithText("Off: make editions from Today").assertExists()
        compose.onNodeWithText("Off").assertExists()
        val rows = SettingsPage.entries.map { compose.onNodeWithText(it.title).fetchSemanticsNode().positionInRoot.y }
        assertEquals("in the order of the pages", rows.sorted(), rows)

        compose.onNodeWithText("Schedule").assertHeightIsAtLeast(48.dp).performClick()
        waitFor("Make an edition automatically")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("About 30 minutes")
    }

    @Test
    fun aChangeOnAPageShowsOnTheSummary() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.SHARE) } }
        show(SettingsPage.DELIVERY)
        waitFor("Send it myself")
        compose.onNodeWithText("Email it to your Kindle").performClick()
        waitFor("The address you send from")
        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { runBlocking { store.current().kindleEmailTarget } != null }

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Kindle · emailed to me_42@kindle.com")
    }

    @Test
    fun whatNeedsFixingShowsOnTheSummaryNotATapAway() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        grant("content://tree/books")
        runBlocking {
            store.update {
                it.copy(
                    device = Device.KOREADER, scheduleEnabled = true, delivery = DeliveryMethod.FOLDER, folderUri = "content://tree/books", folderName = "Books",
                    notesFolderUri = "content://tree/gone", notesFolderName = "Vault",
                )
            }
        }
        show()
        waitFor("KOReader · saved to Books")
        compose.onNodeWithText("Notifications are off.").assertExists()
        compose.onNodeWithText("Can't reach Vault.").assertExists()
        compose.onNodeWithText("Can't reach Books.").assertDoesNotExist()
    }

    @Test
    fun theBuildIsNamedAtTheBottomSoFeedbackCanSayWhichOne() {
        show()
        compose.onNodeWithText("newspapeRSS 0.1.0", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun perSourceCapCanBeRaised() {
        show(SettingsPage.EDITION)
        compose.onNodeWithText("+").performClick()
        waitFor("2 articles from each source, then more")
    }

    @Test
    fun aKindleReaderCanSwitchToEmailAndSetItUp() {
        show(SettingsPage.DELIVERY)
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
        show(SettingsPage.DELIVERY)
        runBlocking { store.update { it.copy(device = Device.KOBO) } }
        waitFor("Send it myself")
        compose.onNodeWithText("Email it to your Kindle").assertDoesNotExist()

        // Changed their e-reader after setting it up: the choice they made stays visible.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("Email it to your Kindle").assertIsSelected()
    }

    @Test
    fun withNotificationsOffEachDeliveryIsWarnedWhatItWontHear() {
        show(SettingsPage.SCHEDULE)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com", scheduleEnabled = true) } }
        waitFor("Notifications are off")

        waitFor("you won't hear when an edition is ready to send")

        // A folder delivery that fails is only reported by notification.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        waitFor("Notifications are off, so you won't hear if an edition fails to arrive.")
    }

    @Test
    fun emailDeliveryWithoutAnAddressSaysSendWillShareUntilOneIsAdded() {
        show(SettingsPage.DELIVERY)
        val line = "Add your Kindle's email address; until then Send opens the share sheet."
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = null) } }
        waitFor(line)

        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { compose.onAllNodes(hasText(line)).fetchSemanticsNodes().isEmpty() }

        // Still said after the e-reader changes, with an address that doesn't work.
        runBlocking { store.update { it.copy(device = Device.KOBO, kindleEmail = "me_42@kindle") } }
        waitFor(line)
    }

    private fun grant(uri: String) = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
        .takePersistableUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    @Test
    fun aFolderTheAppCanNoLongerReachSaysSoInsteadOfSavingAutomatically() {
        grant("content://tree/books")
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree/books", folderName = "Books", notesFolderUri = "content://tree/gone", notesFolderName = "Vault") } }
        show(SettingsPage.NOTES)
        // The notes folder's grant is gone (its app uninstalled, say): every save there would fail.
        waitFor("Can't reach Vault. Tap to choose it again.")
        compose.onNodeWithText("Choose another notes folder").assertExists()

        // Tapping the row, as the line says, picks a folder again rather than turning notes off.
        compose.onNodeWithText("Save notes for each edition").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity.action)
        assertEquals("content://tree/gone", runBlocking { store.current().notesFolderUri })

        compose.onNodeWithText("Turn off").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().notesFolderUri } == null }
    }

    /** At 200% the per-source count keeps the width; − and + go on the line below. */
    @Test
    @Config(application = TestApp::class, fontScale = 2f)
    fun atLargeFontSizesTheCountButtonsGoBelowItsWords() {
        show(SettingsPage.EDITION)
        val words = compose.onNodeWithText("from each source", substring = true).fetchSemanticsNode().boundsInRoot
        val fewer = compose.onNodeWithContentDescription("Fewer from each source").fetchSemanticsNode().boundsInRoot
        assertTrue("below", fewer.top >= words.bottom)
    }
}
