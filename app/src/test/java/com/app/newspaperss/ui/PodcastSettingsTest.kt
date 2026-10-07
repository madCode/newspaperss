package com.app.newspaperss.ui

import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.listen.KokoroInstall
import com.app.newspaperss.listen.PodcastEngine
import com.app.newspaperss.listen.PodcastSetup
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installVoice
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.UUID

/** Settings › Listening with the podcast offered: getting Kokoro, its check, using and removing it. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastSettingsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    // Cancelled before TemporaryFolder deletes the file, as in SettingsScreenTest.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }) }
    private val install by lazy { KokoroInstall(tmp.newFolder("kokoro")) { listOf(KokoroFile("tokens.txt", 6, "ce013625030ba8dba906f756967f9e9ca394464a")) } }
    private val work = MutableStateFlow<WorkInfo?>(null)
    private val started = mutableListOf<Boolean>()
    private var stopped = 0

    @After fun stopStore() {
        // DataStore runs an update's transform in the caller's context, the screen's main thread: let
        // one the last tap started finish, or the store waits on a main thread waiting on it.
        compose.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private lateinit var setup: PodcastSetup

    private fun show(supported: Boolean = true): SettingsViewModel {
        installVoice(context)
        setup = PodcastSetup(
            install, store, work, start = { started += it }, stop = { stopped++ }, supported = supported,
            engine = { error("not in these tests") },
            podcasts = com.app.newspaperss.listen.PodcastStore(java.io.File(context.filesDir, "podcasts")), makePodcasts = {},
        )
        val vm = SettingsViewModel(store, podcast = setup) {}
        compose.setContent { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
        idleUntil { vm.settings.value != null }
        return vm
    }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun on(type: Int) = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        .setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED))

    private fun running(vararg progress: Pair<String, Any>) = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet(), progress = workDataOf(*progress))

    private fun ready(pace: Float) {
        install.file("tokens.txt").writeText("hello\n")
        install.markVerified("tokens.txt")
        runBlocking { store.update { it.copy(podcastPace = pace) } }
    }

    private fun pickPodcast() = compose.onNodeWithText("Make a podcast in a natural voice").performScrollTo().performClick()

    @Test
    fun offWifiItAsksBeforeUsingMobileData() {
        on(ConnectivityManager.TYPE_MOBILE)
        show()
        pickPodcast()
        waitFor("You're not on Wi-Fi")
        compose.onNodeWithText("Use mobile data").performClick()
        assertEquals(listOf(true), started)
    }

    @Test
    fun offWifiItCanWaitForWifi() {
        on(ConnectivityManager.TYPE_MOBILE)
        show()
        pickPodcast()
        compose.onNodeWithText("Wait for Wi-Fi").performClick()
        assertEquals(listOf(false), started)
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        waitFor("download by itself on the next Wi-Fi")
    }

    @Test
    fun onWifiItDownloadsStraightAwayWithProgressAndCancel() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        pickPodcast()
        assertEquals(listOf(false), started)
        work.value = running(PodcastSetup.GOT to 154_000_000L, PodcastSetup.TOTAL to 384_077_374L)
        waitFor("154 of 384 MB")
        compose.onNodeWithText("Make a podcast in a natural voice").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun afterTheCheckItSaysWhatYourPaperCostsAndCanBeUsed() {
        on(ConnectivityManager.TYPE_WIFI)
        val vm = show()
        work.value = running(PodcastSetup.PHASE to PodcastSetup.PHASE_CHECK)
        waitFor("Seeing how fast this phone is")
        // A Pixel 8: 0.8× cool, so 1.2 warm.
        ready(1.2f)
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet())
        waitFor("This phone can do it")
        waitFor("Your 30-minute paper takes about 55 minutes to make here")
        compose.onNodeWithText("Use it").performClick()
        idleUntil { vm.settings.value?.listenVoice == ListenVoice.PODCAST }
        // In use: its voices, each with a sample.
        compose.onNodeWithText("Emma").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { vm.settings.value?.podcastVoice == PodcastVoice.EMMA }
        waitFor("Listen plays an article from its podcast once it's made")
    }

    @Test
    fun aTooSlowPhoneIsToldSoAndOfferedOnlyTheSpaceBack() {
        ready(4.0f)
        val vm = show()
        pickPodcast()
        waitFor("Too slow on this phone")
        waitFor("25 minutes would take about 2½ hours")
        compose.onAllNodes(hasText("Use it")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText("Remove Kokoro (0 MB)").performClick()
        // Waited for on the screen's own state: blocking the main thread for the store would hold
        // up the removal, which finishes there.
        idleUntil { vm.settings.value?.podcastPace == null }
        assertEquals(1, stopped)
        assertFalse(install.dir.exists())
        assertEquals(ListenVoice.PHONE, vm.settings.value?.listenVoice)
    }

    @Test
    fun aFailureSaysWhyAndCanBeTriedAgain() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), outputData = workDataOf(PodcastSetup.ERROR to "Kokoro couldn't start on this phone."))
        waitFor("Kokoro couldn't start on this phone.")
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(false), started)
    }

    @Test
    fun comingBackToADownloadedKokoroStillShowsWhatItCosts() {
        // Checked while the reader was elsewhere: the verdict waits for them.
        ready(2.5f)
        show()
        waitFor("This phone is slow at it, but it can")
        waitFor("Your 30-minute paper takes about 2 hours to make here")
        compose.onNodeWithText("Read live in this phone's voice").assertIsSelected()
    }

    @Test
    fun choosingToReadLiveDuringTheDownloadStopsIt() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        work.value = running(PodcastSetup.GOT to 1_000_000L, PodcastSetup.TOTAL to 384_077_374L)
        waitFor("1 of 384 MB")
        compose.onNodeWithText("Read live in this phone's voice").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun aRetryIsntWaitingForWifiButCanStillUseMobileData() {
        show()
        setup.failed()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), runAttemptCount = 1, constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        waitFor("Trying again shortly")
        assertEquals(0, compose.onAllNodes(hasText("Waiting for Wi-Fi")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Use mobile data").performClick()
        assertEquals(listOf(true), started)
    }

    @Test
    fun choosingToReadLivePutsAFailureAside() {
        show()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), outputData = workDataOf(PodcastSetup.ERROR to "Couldn't download Kokoro."))
        waitFor("Couldn't download Kokoro.")
        compose.onNodeWithText("Read live in this phone's voice").performClick()
        idleUntil { compose.onAllNodes(hasText("Couldn't download Kokoro.")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aThirtyTwoBitPhoneSeesWhyNot() {
        show(supported = false)
        waitFor("Kokoro needs a 64-bit phone")
        compose.onNodeWithText("Make a podcast in a natural voice").assertIsNotEnabled()
    }

    @Test
    fun withoutTheSetupThereIsNoPodcast() {
        installVoice(context)
        val vm = SettingsViewModel(store) {}
        compose.setContent { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
        idleUntil { vm.settings.value != null }
        waitFor("This phone's voice")
        assertEquals(0, compose.onAllNodes(hasText("Make a podcast", substring = true)).fetchSemanticsNodes().size)
    }
}
