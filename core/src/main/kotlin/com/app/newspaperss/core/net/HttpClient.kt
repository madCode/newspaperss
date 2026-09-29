package com.app.newspaperss.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

interface HttpClient {
    /** Fetches [url]; throws IOException on network failure, never on an HTTP error status. */
    suspend fun get(url: String): HttpResponse
}

class OkHttpHttpClient(
    private val client: OkHttpClient = defaultClient(),
) : HttpClient {
    override suspend fun get(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a web address: $url", e)
        }
        client.newCall(request).execute().use { r ->
            val source = r.body.source()
            // Someone may paste a link to a video or a huge file; don't read it all into memory.
            if (source.request(MAX_BYTES + 1)) throw IOException("Too large to be a feed or an article.")
            val bytes = source.readByteArray()
            HttpResponse(r.code, r.request.url.toString(), r.header("Content-Type"), decode(bytes, r.body.contentType()?.charset()))
        }
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024

        private val xmlEncoding = Regex("""^\s*<\?xml[^>]*encoding=["']([A-Za-z0-9._-]+)["']""")

        /**
         * The header's charset, else the XML prolog's (feeds served as bare
         * text/xml often declare ISO-8859-1 or windows-1252 only there), else UTF-8.
         */
        internal fun decode(bytes: ByteArray, headerCharset: Charset?): String {
            if (headerCharset != null) return String(bytes, headerCharset)
            val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
            val declared = xmlEncoding.find(head)?.groupValues?.get(1)
            val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, charset).removePrefix("\uFEFF")
        }

        // A browser-like agent: some sites refuse unknown clients outright.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36 newspaperss"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
