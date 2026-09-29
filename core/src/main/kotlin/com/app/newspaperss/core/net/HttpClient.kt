package com.app.newspaperss.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
            HttpResponse(r.code, r.request.url.toString(), r.header("Content-Type"), r.body.string())
        }
    }

    companion object {
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
