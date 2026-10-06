package com.app.newspaperss.listen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class PodcastSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }
    private val file = KokoroFile("tokens.txt", 6, "ce013625030ba8dba906f756967f9e9ca394464a")
    private val install by lazy { KokoroInstall(tmp.newFolder("kokoro")) { listOf(file) } }
    private val work = MutableStateFlow<WorkInfo?>(null)
    private val started = mutableListOf<Boolean>()
    private var stopped = 0
    private var clock = 0L
    private var voiceUsed: PodcastVoice? = null

    /** A Kokoro that takes 18 s of the fake clock to make 15 s of speech: 1.2× cool. */
    private val engine = object : PodcastEngine {
        var released = false
        override fun speak(text: String): Speech {
            clock += 18_000_000_000L
            return Speech(FloatArray(15 * 24_000), 24_000)
        }
        override fun release() { released = true }
    }

    private fun setup(supported: Boolean = true) = PodcastSetup(
        install, store, work,
        start = { started += it },
        stop = { stopped++ },
        supported = supported,
        engine = { voiceUsed = it; engine },
        now = { clock },
    )

    private fun info(state: WorkInfo.State, progress: Map<String, Any> = emptyMap(), output: Map<String, Any> = emptyMap(), network: NetworkType = NetworkType.UNMETERED) = WorkInfo(
        UUID.randomUUID(), state, emptySet(),
        outputData = workDataOf(*output.toList().toTypedArray()),
        progress = workDataOf(*progress.toList().toTypedArray()),
        constraints = Constraints.Builder().setRequiredNetworkType(network).build(),
    )

    private fun installed() {
        install.file("tokens.txt").writeText("hello\n")
        install.markVerified("tokens.txt")
    }

    @Test
    fun aThirtyTwoBitPhoneCantHaveIt() = runTest {
        assertEquals(KokoroState.Unsupported, setup(supported = false).state.first())
    }

    @Test
    fun theWorksStateIsWhatSettingsShows() = runTest {
        val setup = setup()
        assertEquals(KokoroState.Absent, setup.state.first())
        work.value = info(WorkInfo.State.ENQUEUED)
        assertEquals(KokoroState.Waiting(wifi = true), setup.state.first())
        work.value = info(WorkInfo.State.ENQUEUED, network = NetworkType.CONNECTED)
        assertEquals(KokoroState.Waiting(wifi = false), setup.state.first())
        work.value = info(WorkInfo.State.RUNNING, progress = mapOf(PodcastSetup.GOT to 154_000_000L, PodcastSetup.TOTAL to 384_000_000L))
        assertEquals(KokoroState.Downloading(154_000_000L, 384_000_000L), setup.state.first())
        work.value = info(WorkInfo.State.RUNNING, progress = mapOf(PodcastSetup.PHASE to PodcastSetup.PHASE_CHECK))
        assertEquals(KokoroState.Checking, setup.state.first())
        work.value = info(WorkInfo.State.FAILED, output = mapOf(PodcastSetup.ERROR to "Couldn't download Kokoro."))
        assertEquals(KokoroState.Failed("Couldn't download Kokoro."), setup.state.first())
    }

    @Test
    fun theCheckKeepsAWarmPaceAndItsReadyThen() = runTest {
        installed()
        val setup = setup()
        var checking = false
        setup.downloadAndCheck(KokoroDownload(okhttp3.OkHttpClient(), install, "http://unused"), onProgress = { _, _ -> }, onChecking = { checking = true })
        assertTrue(checking)
        // 1.2× cool, × 1.5 for a phone that has warmed up.
        assertEquals(1.8f, store.current().podcastPace!!, 0.001f)
        assertTrue(engine.released)
        assertEquals(PodcastVoice.HEART, voiceUsed)
        work.value = info(WorkInfo.State.SUCCEEDED)
        assertEquals(KokoroState.Ready(1.8f.toDouble()), setup.state.first())
    }

    @Test
    fun aPaceWithoutTheFilesIsntReady() = runTest {
        store.update { it.copy(podcastPace = 1.2f) }
        assertEquals(KokoroState.Absent, setup().state.first())
    }

    @Test
    fun usingItAndRemovingIt() = runTest {
        installed()
        store.update { it.copy(podcastPace = 1.2f) }
        val setup = setup()
        setup.use()
        assertEquals(ListenVoice.PODCAST, store.current().listenVoice)
        setup.remove()
        assertEquals(1, stopped)
        assertFalse(install.dir.exists())
        assertEquals(ListenVoice.PHONE, store.current().listenVoice)
        assertNull(store.current().podcastPace)
        assertEquals(KokoroState.Absent, setup.state.first())
    }

    @Test
    fun downloadingAsksForWifiUnlessToldOtherwise() {
        val setup = setup()
        setup.download(mobileData = false)
        setup.download(mobileData = true)
        assertEquals(listOf(false, true), started)
    }
}
