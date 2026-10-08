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

/**
 * One of the reader's feeds, as getFeeds reports it.
 *
 * @property lastUpdated when tt-rss last fetched it, in seconds since the epoch: 0 before its
 *   first fetch, null from a version that doesn't say.
 * @property lastError why tt-rss's latest try to fetch it failed, as tt-rss words it ("HTTP Code: 404");
 *   null when it worked, or from a version that doesn't say.
 */
data class TtrssFeed(
    val id: Int,
    val title: String,
    val unread: Int,
    val feedUrl: String? = null,
    val categoryId: Int? = null,
    val lastUpdated: Long? = null,
    val lastError: String? = null,
)

/** What tt-rss answered when asked to subscribe to a feed. */
sealed interface TtrssSubscription {
    /** [feedId] is null from versions that don't say it. */
    data class Added(val feedId: Int?) : TtrssSubscription
    data class AlreadySubscribed(val feedId: Int?) : TtrssSubscription
    /**
     * Not subscribed, with [reason] fit to show the reader. [couldntFetch] when tt-rss couldn't
     * download or read the feed, which the phone just could: some sites block servers.
     */
    data class Refused(val code: Int, val reason: String, val couldntFetch: Boolean = false) : TtrssSubscription
}

/** A category of the reader's own feeds, as getCategories reports it. */
data class TtrssCategory(val id: Int, val title: String)

/** A tt-rss API error, with a message fit to show the reader. */
sealed class TtrssException(message: String) : Exception(message) {
    class LoginFailed : TtrssException("tt-rss didn't accept that username and password.")
    class ApiDisabled : TtrssException("Enable the API in tt-rss preferences (Preferences > General > Enable API).")
    class HttpError(val code: Int) : TtrssException("The tt-rss server answered with error $code.")
    class Redirected(val to: String) : TtrssException("The server sent us to $to. Try that address instead.")
    class NotTtrss : TtrssException("That address doesn't look like a tt-rss server.")
    class ApiError(val code: String) : TtrssException("tt-rss reported an error ($code).")
    class TooOld(message: String = "Your tt-rss is too old for this. Update it, or use Mark as read in tt-rss itself.") : TtrssException(message)
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
    /** Reported at login; null before it or from a server too old to say. */
    private var apiLevel: Int? = null

    /** Logs in and returns the session id. */
    suspend fun login(): String {
        val content = post(buildJsonObject {
            put("op", "login")
            put("user", user)
            put("password", password)
        }) as? JsonObject
        val sid = content?.get("session_id")?.jsonPrimitive?.contentOrNull ?: throw TtrssException.NotTtrss()
        sessionId = sid
        apiLevel = (content["api_level"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return sid
    }

    /**
     * Unread articles, in tt-rss's order: by score, then newest first.
     *
     * @param feedId -4 for all feeds, or a feed or category id.
     * @param isCategory true when [feedId] is a category.
     * @param limit tt-rss caps this at 200 itself.
     * @param sinceId only articles with a larger id.
     * @param newestFirst by date alone; otherwise tt-rss puts higher-scored articles first.
     */
    suspend fun unreadHeadlines(
        feedId: Int = ALL_ARTICLES,
        isCategory: Boolean = false,
        limit: Int = MAX_LIMIT,
        sinceId: Long? = null,
        newestFirst: Boolean = false,
    ): List<TtrssHeadline> {
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
                if (newestFirst) put("order_by", "feed_dates")
            })
        }
        val items = content as? JsonArray ?: throw TtrssException.NotTtrss()
        return items.mapNotNull { (it as? JsonObject)?.let(::headline) }
    }

    /**
     * The reader's feeds that have unread articles.
     *
     * @param categoryId a category, its subcategories included, or null for every feed.
     */
    suspend fun unreadFeeds(categoryId: Int? = null): List<TtrssFeed> = feeds(categoryId, unreadOnly = true, mutableSetOf())

    /**
     * Every one of the reader's feeds, read or not, with its address and category.
     *
     * @param categoryId a category, its subcategories included, or null for every feed.
     */
    suspend fun allFeeds(categoryId: Int? = null): List<TtrssFeed> = feeds(categoryId, unreadOnly = false, mutableSetOf())

    private suspend fun feeds(categoryId: Int?, unreadOnly: Boolean, seen: MutableSet<Int>): List<TtrssFeed> {
        if (categoryId != null && !seen.add(categoryId)) return emptyList()
        val content = withSession { sid ->
            post(buildJsonObject {
                put("sid", sid)
                put("op", "getFeeds")
                put("cat_id", categoryId ?: ALL_FEEDS)
                put("unread_only", unreadOnly)
                if (categoryId != null) put("include_nested", true)
            })
        }
        val items = content as? JsonArray ?: throw TtrssException.NotTtrss()
        return items.flatMap { item ->
            val o = item as? JsonObject ?: return@flatMap emptyList()
            // Ints, or strings in older versions. Negative ids are tt-rss's own virtual feeds.
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return@flatMap emptyList()
            // include_nested lists a subcategory as an item of its own, with an id from the
            // category sequence: fetched as a feed, it would be some unrelated feed.
            if ((o["is_cat"] as? JsonPrimitive)?.contentOrNull == "true") {
                return@flatMap if (categoryId != null) feeds(id, unreadOnly, seen) else emptyList()
            }
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            listOf(
                TtrssFeed(
                    id, text("title") ?: "", text("unread")?.toIntOrNull() ?: 0,
                    feedUrl = text("feed_url")?.takeIf { it.isNotBlank() }, categoryId = text("cat_id")?.toIntOrNull(),
                    lastUpdated = text("last_updated")?.toLongOrNull(),
                    lastError = text("last_error")?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_ERROR_CHARS)?.ifBlank { null },
                ),
            )
        }
    }

    /**
     * The reader's categories, "Uncategorized" included. tt-rss's own Special and Labels
     * groups have negative ids and aren't categories of feeds, so they're left out.
     *
     * @param includeEmpty also categories with no feeds yet, which tt-rss otherwise leaves out:
     *   a category made in tt-rss to subscribe a feed into is empty until then.
     */
    suspend fun categories(includeEmpty: Boolean = false): List<TtrssCategory> {
        val content = withSession { sid ->
            post(buildJsonObject {
                put("sid", sid)
                put("op", "getCategories")
                if (includeEmpty) put("include_empty", true)
            })
        }
        val items = content as? JsonArray ?: throw TtrssException.NotTtrss()
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            // The id is an int or, in older versions, a string.
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TtrssCategory(id, title)
        }
    }

    /**
     * Whether each of a feed's articles with an id above [sinceId] is unread, read or not: id to
     * unread. Headlines without their text, so checking a few dozen costs little. Up to [pages]
     * pages of [MAX_LIMIT]; an article past them, or gone from tt-rss, is missing from the map.
     */
    suspend fun unreadStates(feedId: Int, sinceId: Long, pages: Int = 5): Map<Long, Boolean> {
        val states = mutableMapOf<Long, Boolean>()
        for (page in 0 until pages) {
            val content = withSession { sid ->
                post(buildJsonObject {
                    put("sid", sid)
                    put("op", "getHeadlines")
                    put("feed_id", feedId)
                    put("view_mode", "all_articles")
                    put("show_content", false)
                    put("limit", MAX_LIMIT)
                    put("skip", page * MAX_LIMIT)
                    put("since_id", sinceId)
                })
            }
            val items = content as? JsonArray ?: throw TtrssException.NotTtrss()
            for (item in items) {
                val o = item as? JsonObject ?: continue
                val id = (o["id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: continue
                // A boolean, or "t"/"1" from older versions on PostgreSQL or MySQL.
                val unread = (o["unread"] as? JsonPrimitive)?.contentOrNull ?: continue
                states[id] = unread == "true" || unread == "t" || unread == "1"
            }
            if (items.size < MAX_LIMIT) break
        }
        return states
    }

    /** Marks [ids] as read. */
    suspend fun markRead(ids: Collection<Long>) = setUnread(ids, false)

    /** Marks [ids] as unread. */
    suspend fun markUnread(ids: Collection<Long>) = setUnread(ids, true)

    private suspend fun setUnread(ids: Collection<Long>, unread: Boolean) {
        if (ids.isEmpty()) return
        withSession { sid ->
            post(buildJsonObject {
                put("sid", sid)
                put("op", "updateArticle")
                put("article_ids", ids.joinToString(","))
                put("mode", if (unread) 1 else 0)
                put("field", FIELD_UNREAD)
            })
        }
    }

    /**
     * Marks unread articles that reached tt-rss more than two weeks ago as read.
     *
     * @param feedId [ALL_ARTICLES], or a category id with [isCategory].
     * @throws TtrssException.TooOld before API level 15, which ignores the two weeks and would
     *   mark everything read.
     */
    suspend fun markReadOlderThanTwoWeeks(feedId: Int = ALL_ARTICLES, isCategory: Boolean = false) {
        withSession { sid ->
            if ((apiLevel ?: 0) < CATCHUP_MODE_LEVEL) throw TtrssException.TooOld()
            post(buildJsonObject {
                put("sid", sid)
                put("op", "catchupFeed")
                put("feed_id", feedId)
                put("is_cat", isCategory)
                put("mode", "2week")
            })
        }
    }

    /**
     * Subscribes the reader to [feedUrl] in [categoryId] (0 is Uncategorized). tt-rss downloads
     * the feed before it answers, so this can take as long as a slow site does.
     *
     * @throws TtrssException.TooOld before API level 5, which has no subscribeToFeed.
     */
    suspend fun subscribeToFeed(feedUrl: String, categoryId: Int): TtrssSubscription {
        val content = withSession { sid ->
            if ((apiLevel ?: 0) < SUBSCRIBE_LEVEL) throw TtrssException.TooOld(TOO_OLD_TO_SUBSCRIBE)
            post(buildJsonObject {
                put("sid", sid)
                put("op", "subscribeToFeed")
                put("feed_url", feedUrl)
                put("category_id", categoryId)
            })
        }
        // {"status": {"code": 1, "feed_id": 12}}; versions before 2021 send no feed_id.
        val status = (content as? JsonObject)?.get("status") ?: throw TtrssException.NotTtrss()
        fun int(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val code = int((status as? JsonObject)?.get("code") ?: status) ?: throw TtrssException.NotTtrss()
        val feedId = int((status as? JsonObject)?.get("feed_id"))?.takeIf { it > 0 }
        return when (code) {
            0 -> TtrssSubscription.AlreadySubscribed(feedId)
            1 -> TtrssSubscription.Added(feedId)
            2 -> TtrssSubscription.Refused(code, "tt-rss says that isn't a valid address.")
            3 -> TtrssSubscription.Refused(code, "tt-rss found a web page there, not a feed.", couldntFetch = true)
            4 -> TtrssSubscription.Refused(code, "tt-rss found more than one feed there.")
            5 -> TtrssSubscription.Refused(code, "tt-rss couldn't download it.", couldntFetch = true)
            6 -> TtrssSubscription.Refused(code, "tt-rss couldn't read the feed it downloaded.", couldntFetch = true)
            7 -> TtrssSubscription.Refused(code, "tt-rss couldn't save it.")
            8 -> TtrssSubscription.Refused(code, "Your tt-rss account can only read feeds, not subscribe to them.")
            else -> TtrssSubscription.Refused(code, "tt-rss didn't subscribe to it (code $code).")
        }
    }

    /**
     * Unsubscribes the reader from the feed [feedId]; tt-rss deletes its articles with it.
     *
     * @throws TtrssException.TooOld before API level 5, which has no unsubscribeFeed.
     */
    suspend fun unsubscribeFeed(feedId: Int) {
        withSession { sid ->
            if ((apiLevel ?: 0) < SUBSCRIBE_LEVEL) throw TtrssException.TooOld(TOO_OLD_TO_SUBSCRIBE)
            post(buildJsonObject {
                put("sid", sid)
                put("op", "unsubscribeFeed")
                put("feed_id", feedId)
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
        /** getFeeds' "every feed, without tt-rss's virtual ones". */
        private const val ALL_FEEDS = -3
        const val MAX_LIMIT = 200
        // tt-rss keeps up to its fetcher's whole message; a line on a phone is enough.
        private const val MAX_ERROR_CHARS = 200
        private const val FIELD_UNREAD = 2
        /** catchupFeed takes a `mode` from here on; before, it marks everything read. */
        const val CATCHUP_MODE_LEVEL = 15
        /** subscribeToFeed and unsubscribeFeed arrived in API level 5 (tt-rss 1.7.6). */
        const val SUBSCRIBE_LEVEL = 5
        const val TOO_OLD_TO_SUBSCRIBE = "Your tt-rss is too old to add feeds from NewspapeRSS. Update it, or add the feed in tt-rss itself."

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
