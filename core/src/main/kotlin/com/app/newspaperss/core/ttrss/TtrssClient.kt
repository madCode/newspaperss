package com.app.newspaperss.core.ttrss

import com.app.newspaperss.core.net.HttpClient
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One unread article as tt-rss's getHeadlines reports it. */
data class TtrssHeadline(
    val id: Long,
    val title: String,
    val link: String,
    val content: String?,
    val author: String?,
    /** Seconds since the epoch. */
    val updated: Long?,
    val feedTitle: String?,
    /** A string because tt-rss sends an int or, in older versions, a string. */
    val feedId: String,
)

/** A tt-rss API error, with a message fit to show the reader. */
sealed class TtrssException(message: String) : Exception(message) {
    class LoginFailed : TtrssException("tt-rss didn't accept that username and password.")
    class ApiDisabled : TtrssException("Enable the API in tt-rss preferences (Preferences > General > Enable API).")
    class HttpError(val code: Int) : TtrssException("The tt-rss server answered with error $code.")
    class Redirected(val to: String) : TtrssException("The server sent us to $to. Try that address instead.")
    class NotTtrss : TtrssException("That address doesn't look like a tt-rss server.")
    class ApiError(val code: String) : TtrssException("tt-rss reported an error ($code).")
}

/**
 * The parts of the tt-rss JSON API (https://tt-rss.org/ApiReference/) newspaperss uses.
 * Calls log in on demand and, if tt-rss has dropped the session, log in again once.
 *
 * Nothing here logs, and no error message includes a request or response body: the login
 * request carries the password and every other request the session id.
 *
 * @param apiUrl the API endpoint, as [apiUrl] makes it from what the reader typed.
 */
class TtrssClient(
    private val http: HttpClient,
    private val apiUrl: String,
    private val user: String,
    private val password: String,
) {
    private var sessionId: String? = null

    /** Logs in and returns the session id. */
    suspend fun login(): String {
        val content = post(buildJsonObject {
            put("op", "login")
            put("user", user)
            put("password", password)
        }) as? JsonObject
        val sid = content?.get("session_id")?.jsonPrimitive?.contentOrNull ?: throw TtrssException.NotTtrss()
        sessionId = sid
        return sid
    }

    /**
     * Unread articles, newest first as tt-rss sorts them.
     *
     * @param feedId -4 for all feeds, or a feed or category id.
     * @param isCategory true when [feedId] is a category.
     * @param limit tt-rss caps this at 200 itself.
     * @param sinceId only articles with a larger id.
     */
    suspend fun unreadHeadlines(feedId: Int = ALL_ARTICLES, isCategory: Boolean = false, limit: Int = MAX_LIMIT, sinceId: Long? = null): List<TtrssHeadline> {
        val content = withSession { sid ->
            post(buildJsonObject {
                put("sid", sid)
                put("op", "getHeadlines")
                put("feed_id", feedId)
                if (isCategory) put("is_cat", true)
                put("view_mode", "unread")
                put("show_content", true)
                put("limit", limit)
                if (sinceId != null) put("since_id", sinceId)
            })
        }
        val items = content as? JsonArray ?: throw TtrssException.NotTtrss()
        return items.mapNotNull { (it as? JsonObject)?.let(::headline) }
    }

    /** Marks [ids] as read. */
    suspend fun markRead(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        withSession { sid ->
            post(buildJsonObject {
                put("sid", sid)
                put("op", "updateArticle")
                put("article_ids", ids.joinToString(","))
                put("mode", 0)
                put("field", FIELD_UNREAD)
            })
        }
    }

    /** Ends the session, if there is one. */
    suspend fun logout() {
        val sid = sessionId ?: return
        sessionId = null
        post(buildJsonObject {
            put("sid", sid)
            put("op", "logout")
        })
    }

    private suspend fun <T> withSession(call: suspend (sid: String) -> T): T {
        val sid = sessionId ?: login()
        return try {
            call(sid)
        } catch (e: NotLoggedIn) {
            // tt-rss drops sessions after a while or when the password changes.
            try {
                call(login())
            } catch (e: NotLoggedIn) {
                throw TtrssException.ApiError("NOT_LOGGED_IN")
            }
        }
    }

    /** The response's `content`; throws [TtrssException] for an API error. */
    private suspend fun post(body: JsonObject): JsonElement {
        val response = http.postJson(apiUrl, body.toString())
        if (response.code in 300..399) throw TtrssException.Redirected(response.finalUrl)
        if (!response.isSuccessful) throw TtrssException.HttpError(response.code)
        val json = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw TtrssException.NotTtrss()
        val content = json["content"] ?: throw TtrssException.NotTtrss()
        val error = (content as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
        when (error) {
            null -> return content
            "NOT_LOGGED_IN" -> throw NotLoggedIn()
            "LOGIN_ERROR" -> throw TtrssException.LoginFailed()
            "API_DISABLED" -> throw TtrssException.ApiDisabled()
            else -> throw TtrssException.ApiError(error)
        }
    }

    private class NotLoggedIn : Exception()

    private fun headline(o: JsonObject): TtrssHeadline? {
        fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val id = (o["id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return null
        return TtrssHeadline(
            id = id,
            title = text("title") ?: "",
            link = text("link") ?: return null,
            content = text("content"),
            author = text("author"),
            updated = (o["updated"] as? JsonPrimitive)?.longOrNull,
            feedTitle = text("feed_title"),
            feedId = text("feed_id") ?: return null,
        )
    }

    companion object {
        const val ALL_ARTICLES = -4
        const val MAX_LIMIT = 200
        private const val FIELD_UNREAD = 2

        /**
         * The API endpoint for an address the reader typed: "rss.example.com/tt-rss" becomes
         * "https://rss.example.com/tt-rss/api/". An address already ending in /api is kept.
         */
        fun apiUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (!url.contains("://")) url = "https://$url"
            if (!url.endsWith("/api")) url += "/api"
            return "$url/"
        }

    }
}
