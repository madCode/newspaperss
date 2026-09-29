package com.app.newspaperss.testutil

import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import java.io.IOException

class FakeHttp : HttpClient {
    /** url -> (status, body); a missing url is a 404. */
    val pages = mutableMapOf<String, Pair<Int, String>>()
    val unreachable = mutableSetOf<String>()
    /** Runs after the request is "sent" and before the response returns, to simulate edits made meanwhile. */
    var beforeResponse: suspend (url: String) -> Unit = {}

    fun page(url: String, body: String, code: Int = 200) { pages[url] = code to body }

    override suspend fun get(url: String): HttpResponse {
        if (url in unreachable) throw IOException("unreachable")
        beforeResponse(url)
        val (code, body) = pages[url] ?: (404 to "")
        return HttpResponse(code, url, null, body)
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
