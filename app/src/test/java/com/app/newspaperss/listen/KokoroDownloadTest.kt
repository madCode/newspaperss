package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.KokoroFile
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLDecoder

class KokoroDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.close()

    private val model = "the model's weights ".repeat(200).toByteArray()
    private val content = mapOf(
        "model.onnx" to model,
        "tokens.txt" to "a b c\n".toByteArray(),
        "espeak-ng-data/voices/!v/Mr serious" to "name Mr serious\n".toByteArray(),
    )

    private fun fileOf(path: String, bytes: ByteArray, big: Boolean): KokoroFile {
        val stub = KokoroFile(path, bytes.size.toLong(), if (big) "0".repeat(64) else "0".repeat(40))
        val hash = stub.digest().apply { update(bytes) }.digest().joinToString("") { "%02x".format(it) }
        return stub.copy(hash = hash)
    }

    private val files = content.map { (path, bytes) -> fileOf(path, bytes, big = path == "model.onnx") }
    private val install by lazy { KokoroInstall(tmp.newFolder("kokoro")) { files } }

    /** Serves [content], honouring Range unless [ranges] is off; [served] counts requests by path. */
    private val served = mutableMapOf<String, Int>()
    private var ranges = true
    private var damaged: String? = null
    private val asked = mutableMapOf<String, String?>()

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = URLDecoder.decode(request.url.encodedPath.removePrefix("/"), "UTF-8")
                synchronized(served) {
                    served[path] = (served[path] ?: 0) + 1
                    asked[path] = request.headers["Range"]
                }
                var bytes = content[path] ?: return MockResponse.Builder().code(404).build()
                if (path == damaged) bytes = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
                val range = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                return if (range != null && ranges) {
                    MockResponse.Builder().code(206).body(Buffer().write(bytes, range, bytes.size - range)).build()
                } else {
                    MockResponse.Builder().body(Buffer().write(bytes)).build()
                }
            }
        }
    }

    private fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }) = runBlocking {
        KokoroDownload(OkHttpClient(), install, server.url("/").toString().removeSuffix("/")).run(onProgress)
    }

    @Test
    fun everyFileArrivesCheckedAndTheProgressReachesTheWhole() {
        serve()
        var last = 0L to 0L
        download { got, total -> last = got to total }
        assertTrue(install.complete)
        content.forEach { (path, bytes) -> assertTrue(path, install.file(path).readBytes().contentEquals(bytes)) }
        assertEquals(install.bytes to install.bytes, last)
    }

    @Test
    fun aDamagedFileIsntKeptAndSaysSo() {
        serve()
        damaged = "tokens.txt"
        val failed = runCatching { download() }.exceptionOrNull()
        assertTrue(failed is KokoroDownload.DamagedException)
        assertFalse(install.file("tokens.txt").exists())
        assertFalse(java.io.File(install.file("tokens.txt").path + ".part").exists())
        assertFalse(install.complete)
    }

    @Test
    fun aStoppedFileCarriesOnFromWhereItWas() {
        serve()
        java.io.File(install.dir, "model.onnx.part").writeBytes(model.copyOf(1000))
        download()
        assertEquals("bytes=1000-", asked["model.onnx"])
        assertTrue(install.file("model.onnx").readBytes().contentEquals(model))
        assertTrue(install.complete)
    }

    @Test
    fun aServerThatSendsTheWholeFileAgainStillEndsUpWithIt() {
        serve()
        ranges = false
        java.io.File(install.dir, "model.onnx.part").writeBytes(model.copyOf(1000))
        download()
        assertTrue(install.file("model.onnx").readBytes().contentEquals(model))
    }

    @Test
    fun onlyAFileThatsGoneIsFetchedAgain() {
        serve()
        download()
        served.clear()
        install.file("tokens.txt").delete()
        download()
        assertEquals(mapOf("tokens.txt" to 1), served)
        assertTrue(install.complete)
    }

    @Test
    fun aPartThatArrivedWholeIsCheckedNotAskedForAgain() {
        serve()
        java.io.File(install.dir, "model.onnx.part").writeBytes(model)
        download()
        assertEquals(null, served["model.onnx"])
        assertTrue(install.file("model.onnx").readBytes().contentEquals(model))
    }

    @Test
    fun aFileFinishingAfterRemoveDoesntBringTheFolderBack() {
        install.dir.deleteRecursively()
        install.markVerified("tokens.txt")
        assertFalse(install.dir.exists())
    }

    @Test
    fun failuresCountUntilAFileArrives() {
        serve()
        assertEquals(1, install.failed())
        assertEquals(2, install.failed())
        download()
        assertEquals(1, install.failed())
    }

    @Test
    fun removingDeletesItAll() {
        serve()
        download()
        install.remove()
        assertFalse(install.dir.exists())
        assertFalse(install.complete)
    }
}
