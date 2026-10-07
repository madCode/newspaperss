package com.app.newspaperss.listen

import android.content.Context
import android.os.Build
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.core.listen.KokoroManifest
import com.app.newspaperss.settings.PodcastVoice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the Kokoro voice lives on this phone. A file counts once its whole content matched the
 * manifest; those are listed in [verified], so a download stopped part way doesn't check them again.
 */
class KokoroInstall(val dir: File, manifest: () -> List<KokoroFile>) {
    private val verified = File(dir, ".verified")

    val files: List<KokoroFile> by lazy(manifest)

    val bytes: Long get() = files.sumOf { it.size }

    fun file(path: String) = File(dir, path)

    /** Files that matched the manifest and are still there. */
    @Synchronized
    fun verifiedPaths(): Set<String> = if (verified.exists()) verified.readLines().filter { file(it).exists() }.toSet() else emptySet()

    @Synchronized
    fun markVerified(path: String) {
        // Removed meanwhile: don't bring the folder back for one file.
        if (!dir.exists()) return
        verified.appendText(path + "\n")
    }

    val complete: Boolean get() = verifiedPaths().containsAll(files.map { it.path })

    @Synchronized
    fun remove() {
        dir.deleteRecursively()
    }

    companion object {
        fun of(context: Context) = KokoroInstall(File(context.filesDir, "kokoro")) {
            KokoroManifest.parse(context.assets.open("kokoro/files.tsv").bufferedReader().use { it.readText() })
        }

        /** sherpa-onnx ships only 64-bit code with the app, and Kokoro is too slow for 32-bit phones. */
        val supported: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    }
}

/**
 * Downloads the Kokoro voice from Hugging Face, at the revision the manifest pins, [LANES] files
 * at a time: most of them are small, and one at a time the round trips take longer than the
 * bytes. A file stopped part way carries on from where it was; each is checked against its hash.
 */
class KokoroDownload(
    private val client: OkHttpClient,
    private val install: KokoroInstall,
    private val baseUrl: String = BASE,
) {
    suspend fun run(onProgress: (got: Long, total: Long) -> Unit) = coroutineScope {
        val done = install.verifiedPaths()
        val got = AtomicLong(install.files.filter { it.path in done }.sumOf { it.size })
        install.dir.mkdirs()
        val needed = install.bytes - got.get()
        if (install.dir.usableSpace < needed + SPARE) throw NoSpaceException(needed + SPARE)
        onProgress(got.get(), install.bytes)
        val lanes = Semaphore(LANES)
        install.files.filter { it.path !in done }
            // The largest first, so the 325 MB model isn't left to download alone at the end.
            .sortedByDescending { it.size }
            .map { file -> async(Dispatchers.IO) { lanes.withPermit { fetch(file, got, onProgress) } } }
            .awaitAll()
    }

    private suspend fun fetch(file: KokoroFile, got: AtomicLong, onProgress: (Long, Long) -> Unit) =
        // A run cancelled while blocked in a read can still be writing when the next starts.
        writing.computeIfAbsent(install.file(file.path).path) { Mutex() }.withLock { fetchAlone(file, got, onProgress) }

    private suspend fun fetchAlone(file: KokoroFile, got: AtomicLong, onProgress: (Long, Long) -> Unit) {
        val target = install.file(file.path)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val have = part.length().takeIf { it in 1..file.size } ?: 0L
        var digest = file.digest()
        if (have > 0) {
            part.inputStream().use { input -> feed(input, have) { buf, n -> digest.update(buf, 0, n) } }
            got.addAndGet(have)
        }
        // A part that's all there (stopped just before it was kept) is only checked.
        if (have < file.size) download(file, part, have, digest, got, onProgress)?.let { digest = it }
        if (part.length() != file.size || !file.matches(digest)) {
            part.delete()
            throw DamagedException(file.path)
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save ${file.path}")
        install.markVerified(file.path)
    }

    /** Fetches the rest of [file] into [part], returning a fresh digest if it had to start again. */
    private suspend fun download(file: KokoroFile, part: File, had: Long, digest: java.security.MessageDigest, got: AtomicLong, onProgress: (Long, Long) -> Unit): java.security.MessageDigest? {
        var have = had
        var restarted: java.security.MessageDigest? = null
        val request = Request.Builder().url(url(file)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val call = client.newCall(request)
        coroutineScope {
            // Cancelling the work closes the connection, so a read waiting on it ends now, not at its timeout.
            val watch = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Hugging Face answered ${response.code} for ${file.path}")
                    if (have > 0 && response.code != HTTP_PARTIAL) {
                        // The whole file came back rather than the rest of it: start it again.
                        got.addAndGet(-have)
                        have = 0
                        restarted = file.digest()
                    }
                    val into = restarted ?: digest
                    FileOutputStream(part, have > 0).use { out ->
                        feed(response.body.byteStream(), Long.MAX_VALUE) { buf, n ->
                            out.write(buf, 0, n)
                            into.update(buf, 0, n)
                            onProgress(got.addAndGet(n.toLong()), install.bytes)
                        }
                    }
                }
            } finally {
                watch.cancel()
            }
        }
        return restarted
    }

    private suspend fun feed(input: java.io.InputStream, limit: Long, use: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(BUFFER)
        var left = limit
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            use(buf, n)
            left -= n
        }
    }

    private fun url(file: KokoroFile) =
        "$baseUrl/" + file.path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** A file arrived, but not as the manifest says it should be. */
    class DamagedException(path: String) : IOException("$path arrived damaged")

    class NoSpaceException(val bytes: Long) : IOException("Needs $bytes bytes free")

    companion object {
        private val writing = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        /** Room left over: a full phone misbehaves in other ways. */
        private const val SPARE = 50_000_000L

        const val BASE = "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b"
        private const val LANES = 8
        private const val BUFFER = 64 * 1024
        private const val HTTP_PARTIAL = 206
    }
}

/** Speech made ahead, as samples. */
class Speech(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

/** Makes speech in one of Kokoro's voices. Not thread-safe; [release] when done. */
interface PodcastEngine {
    fun speak(text: String): Speech

    fun release()
}

/** Kokoro through sherpa-onnx. Loading takes a couple of seconds. */
class KokoroEngine(install: KokoroInstall, voice: PodcastVoice, threads: Int = THREADS) : PodcastEngine {
    private val speaker = speakerOf(voice)
    private val british = voice == PodcastVoice.EMMA || voice == PodcastVoice.GEORGE
    private val tts = OfflineTts(
        null,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = install.file("model.onnx").path,
                    voices = install.file("voices.bin").path,
                    tokens = install.file("tokens.txt").path,
                    dataDir = install.file("espeak-ng-data").path,
                    lexicon = install.file(if (british) "lexicon-gb-en.txt" else "lexicon-us-en.txt").path,
                    // espeak's data has no British voice; plain English with the British word list is.
                    lang = if (british) "en" else "en-us",
                ),
                numThreads = threads,
            ),
        ),
    )

    override fun speak(text: String): Speech = tts.generate(text, speaker, 1f).let { Speech(it.samples, it.sampleRate) }

    override fun release() = tts.release()

    companion object {
        /** Fastest on a Pixel 8's Tensor G3: 1 and 2 threads were slower, and all 9 cores slower still. */
        const val THREADS = 4

        /** The voice's number in the model (its metadata's speaker2id). */
        fun speakerOf(voice: PodcastVoice) = when (voice) {
            PodcastVoice.HEART -> 3
            PodcastVoice.MICHAEL -> 16
            PodcastVoice.EMMA -> 21
            PodcastVoice.GEORGE -> 26
        }
    }
}
