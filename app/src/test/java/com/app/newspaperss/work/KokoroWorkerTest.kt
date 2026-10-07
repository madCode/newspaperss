package com.app.newspaperss.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.listen.PodcastSetup
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class KokoroWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as TestApp
    private val server = MockWebServer().apply { start() }

    private val voice = "voice.bin" to "a voice, in a few bytes".toByteArray()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        app.kokoroFiles = listOf(fileOf(voice.first, voice.second))
        app.kokoroBaseUrl = server.url("/").toString().removeSuffix("/")
    }

    @After fun stop() {
        server.close()
        app.kokoroDir.deleteRecursively()
    }

    private fun fileOf(path: String, bytes: ByteArray): KokoroFile {
        val stub = KokoroFile(path, bytes.size.toLong(), "0".repeat(40))
        val hash = stub.digest().apply { update(bytes) }.digest().joinToString("") { "%02x".format(it) }
        return stub.copy(hash = hash)
    }

    /** Serves the voice, or [code] for everything when set. */
    private fun serve(code: Int? = null, body: ByteArray = voice.second) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (code != null) return MockResponse.Builder().code(code).build()
                val path = URLDecoder.decode(request.url.encodedPath.removePrefix("/"), "UTF-8")
                if (path != voice.first) return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(okio.Buffer().write(body)).build()
            }
        }
    }

    private suspend fun work() = TestListenableWorkerBuilder<KokoroWorker>(context).build().doWork()

    @Test fun theVoiceArrivesEvenWhereItCantBeRun() = runTest {
        serve()
        // The files arrive and are checked against their hashes. Measuring the phone's pace needs
        // sherpa-onnx's native code, which Robolectric hasn't got, so the run can only get as far
        // as the "couldn't start" failure: the download half is what this covers.
        val result = work()
        assertTrue(app.kokoroInstall.complete)
        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            "Kokoro couldn't start on this phone.",
            failure.outputData.getString(PodcastSetup.ERROR),
        )
    }

    @Test fun aServerThatIsntThereIsWorthAnotherTry() = runTest {
        serve(code = 500)
        assertTrue(work() is ListenableWorker.Result.Retry)
    }

    @Test fun enoughFailuresInARowGiveUpWithSomethingToRead() = runTest {
        serve(code = 500)
        // The count is kept on disk, so the runs add up as Android would run them.
        var last: ListenableWorker.Result = ListenableWorker.Result.retry()
        repeat(7) { last = work() }
        val failure = last as ListenableWorker.Result.Failure
        assertEquals(
            "Couldn't download Kokoro. Check your connection and try again.",
            failure.outputData.getString(PodcastSetup.ERROR),
        )
    }

    @Test fun aVoiceArrivingDamagedTwiceIsntTheConnection() = runTest {
        serve(body = "not the voice at all".toByteArray())
        assertTrue(work() is ListenableWorker.Result.Retry)
        val failure = work() as ListenableWorker.Result.Failure
        assertEquals(
            "Kokoro kept arriving damaged. Try again later.",
            failure.outputData.getString(PodcastSetup.ERROR),
        )
    }
}
