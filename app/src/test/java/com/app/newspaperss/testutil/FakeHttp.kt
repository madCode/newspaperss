package com.app.newspaperss.testutil

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import java.io.IOException

class FakeHttp : HttpClient {
    /** url -> (status, body); a missing url is a 404. */
    val pages = mutableMapOf<String, Pair<Int, String>>()
    val unreachable = mutableSetOf<String>()
    /** Urls whose POSTs time out, as OkHttp reports a slow server. */
    val timingOut = mutableSetOf<String>()
    /** Runs after the request is "sent" and before the response returns, to simulate edits made meanwhile. */
    var beforeResponse: suspend (url: String) -> Unit = {}

    /** url -> (content type, bytes) for [getBytes]; a missing url is a 404. */
    val files = mutableMapOf<String, Pair<String?, ByteArray>>()
    /** The headers each [getBytes] call sent, by url. */
    val bytesRequests = mutableMapOf<String, Map<String, String>>()

    /** Answers [postJson] with (status, body); by default every POST is a 404. */
    var onPost: suspend (url: String, body: String) -> Pair<Int, String> = { _, _ -> 404 to "" }

    fun page(url: String, body: String, code: Int = 200) { pages[url] = code to body }

    override suspend fun postJson(url: String, body: String): HttpResponse {
        if (url in unreachable) throw IOException("unreachable")
        if (url in timingOut) throw java.net.SocketTimeoutException("timeout")
        val (code, reply) = onPost(url, body)
        return HttpResponse(code, url, "application/json", reply)
    }

    override suspend fun get(url: String): HttpResponse {
        if (url in unreachable) throw IOException("unreachable")
        beforeResponse(url)
        val (code, body) = pages[url] ?: (404 to "")
        return HttpResponse(code, url, null, body)
    }

    override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes {
        bytesRequests[url] = headers
        if (url in unreachable) throw IOException("unreachable")
        beforeResponse(url)
        val (type, bytes) = files[url] ?: return HttpBytes(404, null, ByteArray(0))
        return HttpBytes(200, type, bytes)
    }
}

fun rss(title: String, vararg items: Pair<String, String>) = buildString {
    append("<rss version=\"2.0\"><channel><title>").append(title).append("</title><link>https://example.com/</link>")
    items.forEach { (guid, itemTitle) ->
        append("<item><title>").append(itemTitle).append("</title><link>https://example.com/").append(guid)
            .append("</link><guid>").append(guid).append("</guid><description>Body of ").append(itemTitle).append("</description></item>")
    }
    append("</channel></rss>")
}
