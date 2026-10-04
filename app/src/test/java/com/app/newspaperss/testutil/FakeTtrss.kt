package com.app.newspaperss.testutil

import com.app.newspaperss.data.AesGcmCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.spec.SecretKeySpec

/** A software AES key standing in for the Android Keystore, which Robolectric doesn't have. */
fun testCipher() = AesGcmCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }

/**
 * A tt-rss server behind [FakeHttp]: it checks the login, serves [unread] articles and marks
 * articles read as the real API does.
 */
class FakeTtrss(http: FakeHttp, val apiUrl: String = "https://rss.example.com/tt-rss/api/") {
    data class Item(val id: Long, val title: String, val feedId: Int, val feedTitle: String, val content: String = "<p>Text of $title.</p>", val categoryId: Int = 0, val link: String = "https://news.example/$id")

    var user = "reader"
    var password = "secret"
    var apiEnabled = true
    val unread = mutableListOf<Item>()
    /** Articles tt-rss still has but as read: marked read through the API, or by [readThere]. */
    val read = mutableListOf<Item>()
    /** updateArticle calls that answer OK without changing anything, as a broken server might. */
    var ignoreUpdates = false
    /** Run once a getHeadlines for unread articles has been answered: something else happening mid-sync. */
    var afterUnreadHeadlines: (() -> Unit)? = null
    /** [fetched]: tt-rss has fetched it at least once; a feed just subscribed to hasn't. */
    data class Feed(val title: String, val url: String, val categoryId: Int = 0, val fetched: Boolean = true)
    /** getFeeds for every feed, read or not, answers with an HTTP 500. */
    var failFeedList = false
    /** Feeds subscribed to, by id: listed by getFeeds with their address, whether or not they have unread articles. */
    val feeds = mutableMapOf<Int, Feed>()
    /** Subcategories, child id to parent id. */
    val subcategories = mutableMapOf<Int, Int>()
    /** A feed whose getHeadlines answers with an HTTP 500. */
    var brokenFeed: Int? = null
    /** The reader's categories by id; 0 is tt-rss's Uncategorized. */
    val categories = mutableMapOf(0 to "Uncategorized")
    /** Ids passed to updateArticle to clear the unread flag. */
    val markedRead = mutableListOf<Long>()
    /** Ids passed to updateArticle to set it again. */
    val markedUnread = mutableListOf<Long>()
    val ops = mutableListOf<String>()
    /** An HTTP status to answer every request with, instead of the API's reply. */
    var failWith: Int? = null
    var apiLevel = 18
    /** catchupFeed calls: feed or category id, whether it's a category, and the mode. */
    val caughtUp = mutableListOf<Triple<Int, Boolean, String>>()
    /** subscribeToFeed answers with this status code instead of subscribing. */
    var subscribeCode: Int? = null
    /** Answer subscribeToFeed as tt-rss before 2021 did, without the feed's id. */
    var subscribeWithoutId = false
    /** subscribeToFeed waits for this before answering, as tt-rss does while it fetches the feed. */
    var subscribeGate: CompletableDeferred<Unit>? = null
    /** Names tt-rss gives feeds subscribed to, by address; otherwise their host. */
    val titles = mutableMapOf<String, String>()
    /** subscribeToFeed answers with these status codes for these addresses instead of subscribing. */
    val refuse = mutableMapOf<String, Int>()
    /** Run once each subscribeToFeed has been decided: something else happening while tt-rss answers. */
    var afterSubscribe: (suspend (url: String) -> Unit)? = null
    /** subscribeToFeed calls: address and category id. */
    val subscribed = mutableListOf<Pair<String, Int>>()
    /** unsubscribeFeed calls, by feed id. */
    val unsubscribed = mutableListOf<Int>()
    private var nextFeedId = 1000
    private var sessions = 0
    private val live = mutableSetOf<String>()

    fun add(id: Long, title: String, feedId: Int, feedTitle: String, categoryId: Int = 0) {
        unread += Item(id, title, feedId, feedTitle, categoryId = categoryId)
    }

    /** The reader reads [id] in tt-rss itself. */
    fun readThere(id: Long) {
        unread.filter { it.id == id }.forEach { unread -= it; read += it }
    }

    /** The reader marks [id] unread in tt-rss itself. */
    fun unreadThere(id: Long) {
        read.filter { it.id == id }.forEach { read -= it; unread += it }
    }

    init {
        http.onPost = { url, body -> if (url == apiUrl) handle(Json.parseToJsonElement(body).jsonObject) else 404 to "" }
    }

    /** tt-rss fetches its feeds on its own schedule; this is that happening. */
    fun fetch(id: Int) {
        feeds[id] = feeds.getValue(id).copy(fetched = true)
    }

    private suspend fun handle(request: JsonObject): Pair<Int, String> {
        failWith?.let { return it to "<html>Bad gateway</html>" }
        fun str(key: String) = (request[key] as? JsonPrimitive)?.contentOrNull
        val op = str("op") ?: ""
        ops += op
        if (!apiEnabled) return error("API_DISABLED")
        if (op == "login") {
            if (str("user") != user || str("password") != password) return error("LOGIN_ERROR")
            val sid = "sid-${++sessions}"
            live += sid
            return ok(buildJsonObject { put("session_id", sid); put("api_level", apiLevel) })
        }
        if (str("sid") !in live) return error("NOT_LOGGED_IN")
        return when (op) {
            "getFeeds" -> if (failFeedList && str("unread_only") == "false") 500 to "<html>Internal error</html>" else ok(
                buildJsonArray {
                    val category = str("cat_id")!!.toInt().takeIf { it >= 0 }
                    val withUnread = unread.filter { category == null || it.categoryId == category }.groupBy { it.feedId }
                    withUnread.forEach { (id, items) ->
                        add(
                            buildJsonObject {
                                put("id", id); put("title", items.first().feedTitle); put("unread", items.size); put("cat_id", items.first().categoryId)
                                feeds[id]?.let { put("feed_url", it.url) }
                            },
                        )
                    }
                    if (str("unread_only") == "false") {
                        feeds.filter { (id, f) -> id !in withUnread && (category == null || f.categoryId == category) }.forEach { (id, f) ->
                            add(
                                buildJsonObject {
                                    put("id", id); put("title", f.title); put("unread", 0); put("cat_id", f.categoryId); put("feed_url", f.url)
                                    put("last_updated", if (f.fetched) 1_759_125_600L else 0L)
                                },
                            )
                        }
                    }
                    // As tt-rss does with include_nested: direct subcategories as items of their own.
                    if (category != null && str("include_nested") == "true") {
                        subcategories.filterValues { it == category }.keys.forEach { child ->
                            add(buildJsonObject { put("id", child); put("title", "Sub $child"); put("unread", 1); put("is_cat", true) })
                        }
                    }
                    // A virtual feed, which isn't one of the reader's.
                    add(buildJsonObject { put("id", -4); put("title", "All articles"); put("unread", unread.size) })
                },
            )
            "getHeadlines" -> if (brokenFeed != null && str("feed_id")?.toInt() == brokenFeed) 500 to "<html>Internal error</html>" else ok(
                buildJsonArray {
                    val feed = str("feed_id")!!.toInt()
                    val isCategory = str("is_cat") == "true"
                    val limit = str("limit")?.toInt() ?: 200
                    val all = str("view_mode") == "all_articles"
                    val since = str("since_id")?.toLong() ?: 0
                    val skip = str("skip")?.toInt() ?: 0
                    val withContent = str("show_content") != "false"
                    // Newest first, as tt-rss sorts them; a higher id is newer here.
                    (if (all) unread + read else unread).filter { it.id > since }.filter {
                        when {
                            isCategory -> it.categoryId == feed
                            feed > 0 -> it.feedId == feed
                            else -> true
                        }
                    }.sortedByDescending { it.id }.drop(skip).take(limit).forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("title", item.title)
                                put("link", item.link)
                                if (withContent) put("content", item.content)
                                put("author", "")
                                put("updated", 1_759_125_600L)
                                put("feed_id", item.feedId)
                                put("feed_title", item.feedTitle)
                                put("unread", item in unread)
                            },
                        )
                    }
                },
            ).also { if (str("view_mode") == "unread") afterUnreadHeadlines?.invoke() }
            "getCategories" -> ok(
                buildJsonArray {
                    categories.forEach { (id, title) -> add(buildJsonObject { put("id", id.toString()); put("title", title) }) }
                    // tt-rss's own groups, which aren't categories of feeds.
                    add(buildJsonObject { put("id", -1); put("title", "Special") })
                },
            )
            "updateArticle" -> {
                val mode = request["mode"]!!.jsonPrimitive.content
                check(request["field"]!!.jsonPrimitive.content == "2" && (mode == "0" || mode == "1"))
                val ids = str("article_ids")!!.split(",").map { it.toLong() }
                if (mode == "0") {
                    markedRead += ids
                    if (!ignoreUpdates) ids.forEach(::readThere)
                } else {
                    markedUnread += ids
                    if (!ignoreUpdates) ids.forEach(::unreadThere)
                }
                ok(buildJsonObject { put("status", "OK"); put("updated", ids.size) })
            }
            "catchupFeed" -> {
                caughtUp += Triple(str("feed_id")!!.toInt(), str("is_cat") == "true", str("mode") ?: "all")
                ok(buildJsonObject { put("status", "OK") })
            }
            "subscribeToFeed" -> {
                val url = str("feed_url")!!
                val category = str("category_id")!!.toInt()
                subscribed += url to category
                subscribeGate?.await()
                val existing = feeds.entries.firstOrNull { it.value.url == url }?.key
                val (code, id) = when {
                    subscribeCode != null -> subscribeCode!! to null
                    url in refuse -> refuse.getValue(url) to null
                    existing != null -> 0 to existing
                    else -> {
                        val id = nextFeedId++
                        feeds[id] = Feed(titles[url] ?: java.net.URI(url).host, url, category, fetched = false)
                        1 to id
                    }
                }
                afterSubscribe?.invoke(url)
                ok(buildJsonObject { put("status", buildJsonObject { put("code", code); if (id != null && !subscribeWithoutId) put("feed_id", id) }) })
            }
            "unsubscribeFeed" -> {
                val id = str("feed_id")!!.toInt()
                unsubscribed += id
                // tt-rss deletes the feed's articles with it.
                unread.removeAll { it.feedId == id }
                read.removeAll { it.feedId == id }
                if (feeds.remove(id) == null) error("E_OPERATION_FAILED") else ok(buildJsonObject { put("status", "OK") })
            }
            "logout" -> {
                live -= str("sid")!!
                ok(buildJsonObject { put("status", "OK") })
            }
            else -> error("UNKNOWN_METHOD")
        }
    }

    private fun ok(content: kotlinx.serialization.json.JsonElement) =
        200 to buildJsonObject { put("seq", 0); put("status", 0); put("content", content) }.toString()

    private fun error(code: String) =
        200 to buildJsonObject { put("seq", 0); put("status", 1); put("content", buildJsonObject { put("error", code) }) }.toString()
}
