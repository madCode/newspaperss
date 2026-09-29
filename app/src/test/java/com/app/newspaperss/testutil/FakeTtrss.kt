package com.app.newspaperss.testutil

import com.app.newspaperss.data.AesGcmCipher
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
    data class Item(val id: Long, val title: String, val feedId: Int, val feedTitle: String, val content: String = "<p>Text of $title.</p>", val categoryId: Int = 0)

    var user = "reader"
    var password = "secret"
    var apiEnabled = true
    val unread = mutableListOf<Item>()
    /** The reader's categories by id; 0 is tt-rss's Uncategorized. */
    val categories = mutableMapOf(0 to "Uncategorized")
    /** Ids passed to updateArticle to clear the unread flag. */
    val markedRead = mutableListOf<Long>()
    val ops = mutableListOf<String>()
    /** An HTTP status to answer every request with, instead of the API's reply. */
    var failWith: Int? = null
    private var sessions = 0
    private val live = mutableSetOf<String>()

    fun add(id: Long, title: String, feedId: Int, feedTitle: String, categoryId: Int = 0) {
        unread += Item(id, title, feedId, feedTitle, categoryId = categoryId)
    }

    init {
        http.onPost = { url, body -> if (url == apiUrl) handle(Json.parseToJsonElement(body).jsonObject) else 404 to "" }
    }

    private fun handle(request: JsonObject): Pair<Int, String> {
        failWith?.let { return it to "<html>Bad gateway</html>" }
        fun str(key: String) = (request[key] as? JsonPrimitive)?.contentOrNull
        val op = str("op") ?: ""
        ops += op
        if (!apiEnabled) return error("API_DISABLED")
        if (op == "login") {
            if (str("user") != user || str("password") != password) return error("LOGIN_ERROR")
            val sid = "sid-${++sessions}"
            live += sid
            return ok(buildJsonObject { put("session_id", sid); put("api_level", 18) })
        }
        if (str("sid") !in live) return error("NOT_LOGGED_IN")
        return when (op) {
            "getHeadlines" -> ok(
                buildJsonArray {
                    val category = str("feed_id")?.toInt()?.takeIf { str("is_cat") == "true" }
                    unread.filter { category == null || it.categoryId == category }.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("title", item.title)
                                put("link", "https://news.example/${item.id}")
                                put("content", item.content)
                                put("author", "")
                                put("updated", 1_759_125_600L)
                                put("feed_id", item.feedId)
                                put("feed_title", item.feedTitle)
                                put("unread", true)
                            },
                        )
                    }
                },
            )
            "getCategories" -> ok(
                buildJsonArray {
                    categories.forEach { (id, title) -> add(buildJsonObject { put("id", id.toString()); put("title", title) }) }
                    // tt-rss's own groups, which aren't categories of feeds.
                    add(buildJsonObject { put("id", -1); put("title", "Special") })
                },
            )
            "updateArticle" -> {
                check(request["field"]!!.jsonPrimitive.content == "2" && request["mode"]!!.jsonPrimitive.content == "0")
                val ids = str("article_ids")!!.split(",").map { it.toLong() }
                markedRead += ids
                unread.removeAll { it.id in ids }
                ok(buildJsonObject { put("status", "OK"); put("updated", ids.size) })
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
