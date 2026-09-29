package com.app.newspaperss.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class HttpResponse(
    val code: Int,
    /** Where the request ended up after redirects. */
    val finalUrl: String,
    val contentType: String?,
    val body: String,
) {
    val isSuccessful get() = code in 200..299
}

class HttpBytes(
    val code: Int,
    val contentType: String?,
    val body: ByteArray,
) {
    val isSuccessful get() = code in 200..299
}

interface HttpClient {
    /** Fetches [url]; throws IOException on network failure, never on an HTTP error status. */
    suspend fun get(url: String): HttpResponse

    /**
     * Fetches [url] as raw bytes, for images, sending [headers] as well; throws IOException on
     * network failure or a body over the implementation's image size limit, never on an HTTP error status.
     */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): HttpBytes

    /**
     * POSTs [body] as JSON to [url]; throws IOException on network failure, never on an HTTP error
     * status. Redirects are not followed: a 3xx comes back as is, with its Location as `finalUrl`.
     */
    suspend fun postJson(url: String, body: String): HttpResponse
}

class OkHttpHttpClient(
    private val client: OkHttpClient = defaultClient(),
) : HttpClient {
    // Images get a shorter deadline: an article can have 20, and one slow image host
    // mustn't push a whole edition past WorkManager's ten-minute limit.
    // No cache for images: a few would push every feed out of it, and each is fetched once anyway.
    private val imageClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).cache(null).build()

    // A POST carries credentials (tt-rss logins). OkHttp re-sends the body on a 307/308, to any
    // host and even from https to http, so redirects are handed back to the caller instead.
    private val postClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun get(url: String): HttpResponse = text(request(url, emptyMap()))

    override suspend fun postJson(url: String, body: String): HttpResponse =
        text(request(url, emptyMap()) { post(body.toRequestBody(JSON)) }, postClient)

    private suspend fun text(request: Request, via: OkHttpClient = client): HttpResponse = withContext(Dispatchers.IO) {
        via.newCall(request).execute().use { r ->
            if (r.isRedirect) {
                val location = r.header("Location")?.let { r.request.url.resolve(it)?.toString() } ?: r.request.url.toString()
                return@use HttpResponse(r.code, location, r.header("Content-Type"), "")
            }
            val source = r.body.source()
            // Someone may paste a link to a video or a huge file; don't read it all into memory.
            if (source.request(MAX_BYTES + 1)) throw IOException("Too large to be a feed or an article.")
            val bytes = source.readByteArray()
            HttpResponse(r.code, r.request.url.toString(), r.header("Content-Type"), decode(bytes, r.body.contentType()?.charset()))
        }
    }

    override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = withContext(Dispatchers.IO) {
        imageClient.newCall(request(url, headers)).execute().use { r ->
            val source = r.body.source()
            if (source.request(MAX_IMAGE_BYTES + 1)) throw IOException("Too large for an image.")
            HttpBytes(r.code, r.header("Content-Type"), source.readByteArray())
        }
    }

    private fun request(url: String, headers: Map<String, String>, configure: Request.Builder.() -> Unit = {}) = try {
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply(configure)
            .build()
    } catch (e: IllegalArgumentException) {
        throw IOException("Not a web address: $url", e)
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val xmlEncoding = Regex("""^\s*<\?xml[^>]*encoding=["']([A-Za-z0-9._-]+)["']""")

        // <meta charset="…"> or <meta http-equiv="Content-Type" content="…; charset=…">.
        private val htmlCharset = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

        /**
         * The header's charset, else the XML prolog's (feeds served as bare
         * text/xml often declare ISO-8859-1 or windows-1252 only there), else
         * an HTML page's meta charset, else UTF-8. A byte-order mark wins over
         * everything but the header.
         */
        internal fun decode(bytes: ByteArray, headerCharset: Charset?): String {
            if (headerCharset != null) return String(bytes, headerCharset)
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            }
            // The meta tag is required to be in the first 1024 bytes.
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            val declared = xmlEncoding.find(head)?.groupValues?.get(1) ?: htmlCharset.find(head)?.groupValues?.get(1)
            val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }?.let(::browserEquivalent) ?: Charsets.UTF_8
            return String(bytes, charset).removePrefix("\uFEFF")
        }

        /**
         * What browsers actually use for a declared charset (the WHATWG encoding rules): pages
         * labelled Latin-1 or ASCII are really windows-1252, whose curly quotes and dashes Latin-1
         * decodes as control characters; a UTF-16 label on bytes read this way can only be wrong.
         */
        private fun browserEquivalent(declared: Charset): Charset = when (declared) {
            Charsets.ISO_8859_1, Charsets.US_ASCII -> Charset.forName("windows-1252")
            Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_16LE -> Charsets.UTF_8
            else -> declared
        }

        // A browser-like agent: some sites refuse unknown clients outright.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36 newspaperss"

        /**
         * @param cacheDir where to keep an HTTP cache. With one, OkHttp revalidates a feed it has
         *   seen (If-None-Match / If-Modified-Since), and most answer 304 instead of the whole feed.
         */
        fun defaultClient(cacheDir: File? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .apply { cacheDir?.let { cache(Cache(it, CACHE_BYTES)) } }
            .build()

        private const val CACHE_BYTES = 10L * 1024 * 1024
    }
}
