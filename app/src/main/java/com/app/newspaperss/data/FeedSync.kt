package com.app.newspaperss.data

import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.net.withoutTracking
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.core.lists.ListLayoutChangedException
import com.app.newspaperss.core.net.HttpClient
import androidx.room.withTransaction
import org.jsoup.nodes.Entities
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import com.app.newspaperss.core.ttrss.TtrssFeed
import com.app.newspaperss.core.ttrss.TtrssHeadline
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Duration

/** @property sources how many sources were synced (paused ones and the reading list aren't). */
data class SyncResult(val newArticles: Int, val failedSources: Int, val sources: Int)

class FeedSync(
    private val db: AppDatabase,
    private val http: HttpClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Unpicked feed articles older than this expire, so there's never a backlog to feel behind on. */
    private val keepFor: Duration = Duration.ofDays(7),
    private val ttrssAccounts: TtrssAccountStore? = null,
    /** How many unpicked links a curated list keeps; older ones expire as new ones arrive. */
    private val listKeep: Int = 12,
) {
    suspend fun syncAll(): SyncResult = coroutineScope {
        val feeds = db.sources().all().filter { it.kind != SourceKind.READING_LIST && !it.paused }
        val limit = Semaphore(4)
        val results = feeds.map { source -> async { limit.withPermit { sync(source) } } }.awaitAll()
        db.articles().expireOlderThan(clock.instant().minus(keepFor))
        db.articles().forgetDeliveredBefore(clock.instant().minus(REMEMBER_DELIVERED))
        val trimmed = db.articles().dropOldFeedHtml(clock.instant().minus(KEEP_FEED_TEXT))
        // Freed pages stay in the file until a VACUUM, and Auto Backup copies the file (25 MB quota).
        if (trimmed >= VACUUM_AFTER) runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        SyncResult(results.sumOf { it ?: 0 }, results.count { it == null }, feeds.size)
    }

    /** Returns the number of new articles, or null if the source failed. */
    suspend fun sync(source: SourceEntity): Int? {
        if (source.kind == SourceKind.TTRSS) return syncTtrss(source)
        if (source.kind == SourceKind.LIST) return syncList(source)
        val now = clock.instant()
        val error = try {
            val response = http.get(source.url)
            if (response.isSuccessful) {
                val feed = FeedParser.parse(response.body, response.finalUrl)
                val added = db.articles().insertFetched(
                    feed.items.map {
                        ArticleEntity(
                            sourceId = source.id, guid = it.guid, url = it.url, title = it.title,
                            author = it.author, published = it.published, feedHtml = it.contentHtml, discoveredAt = now,
                        ).linkedToStory(feed.siteUrl ?: source.siteUrl) to it.url
                    },
                )
                // A title that is still the host-name placeholder gives way to the feed's own.
                db.sources().recordSuccess(source.id, now, feed.title, feed.siteUrl, SourceRepository.hostOf(source.url))
                return added
            }
            "The site answered with error ${response.code}."
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            "Couldn't reach the site."
        } catch (e: FeedParseException) {
            "We can't get new articles from this site any more. It may have moved; try adding it again."
        } catch (e: SQLiteConstraintException) {
            // The source was removed while it was being fetched.
            return null
        } catch (e: Exception) {
            // One bad feed mustn't stop the others from syncing.
            "Something went wrong reading this site."
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    /**
     * The newest few unread articles of each feed. One feed tt-rss can't serve (a 500 on a bad
     * article, a timeout) is skipped rather than failing the others; a problem with the login
     * or the whole server still fails the sync, and so does every feed failing.
     */
    private suspend fun fromEachFeed(client: TtrssClient, feeds: List<TtrssFeed>): List<TtrssHeadline> {
        var failure: Exception? = null
        val headlines = feeds.flatMap { feed ->
            try {
                client.unreadHeadlines(feedId = feed.id, limit = TTRSS_PER_FEED, newestFirst = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtrssException.LoginFailed) {
                throw e
            } catch (e: TtrssException.ApiDisabled) {
                throw e
            } catch (e: TtrssException.ApiError) {
                if (e.code == "NOT_LOGGED_IN") throw e
                failure = failure ?: e
                emptyList()
            } catch (e: Exception) {
                failure = failure ?: e
                emptyList()
            }
        }
        failure?.let { if (headlines.isEmpty()) throw it }
        return headlines
    }

    private suspend fun syncTtrss(source: SourceEntity): Int? {
        val now = clock.instant()
        val account = (ttrssAccounts?.load() as? StoredAccount.Ready)?.account
        val error = if (account == null || account.apiUrl != source.url) {
            SIGN_IN_AGAIN
        } else {
            val client = account.client(http)
            try {
                val category = source.ttrssCategoryId
                // A few from each feed rather than the newest 200 overall: busy news feeds would
                // fill those 200, and a feed that posts monthly would never reach the paper.
                val leftOut = db.sources().allLeftOut().filter { it.sourceId == source.id }.map { it.originId }.toSet()
                val headlines = fromEachFeed(client, client.unreadFeeds(category).filter { it.unread > 0 && it.id.toString() !in leftOut })
                val articles = headlines.map {
                    it to ArticleEntity(
                        sourceId = source.id, guid = "$TTRSS_GUID_PREFIX${it.id}", url = it.link, title = it.title,
                        author = it.author, published = it.updated?.let(Instant::ofEpochSecond), feedHtml = it.content,
                        discoveredAt = now, originId = it.feedId, originTitle = it.feedTitle,
                    ).linkedToStory(siteUrl = null)
                }
                // A link already delivered (from a feed, or before the account was reconnected) is
                // skipped like any other, and tt-rss is told it's read unless the reader said not to:
                // otherwise it would sit unread there for good. Articles the reader marked read in
                // the app are told here too, rather than when marked, so Undo never reaches the server.
                // They're taken from the database, not these headlines: a few per feed may no longer
                // include them.
                if (source.markReadOnServer) {
                    // Chunked: SQLite before 3.32 (Android before 11) allows at most 999 query parameters.
                    // The headline's own link as well as the stored one (a link post's story, tracking
                    // removed): delivered_urls holds whichever went out.
                    fun linksOf(h: TtrssHeadline, a: ArticleEntity) = listOf(h.link, a.url)
                    val delivered = articles.flatMap { (h, a) -> linksOf(h, a) }.distinct().chunked(500).flatMap { db.articles().deliveredAmong(it) }.toSet()
                    val markedRead = db.articles().unreportedRead(source.id, limit = 500)
                    client.markRead(
                        (articles.filter { (h, a) -> linksOf(h, a).any { it in delivered } }.map { it.first.id } +
                            markedRead.mapNotNull { it.removePrefix(TTRSS_GUID_PREFIX).toLongOrNull() }).distinct(),
                    )
                    if (markedRead.isNotEmpty()) db.articles().setReportedRead(source.id, markedRead)
                }
                val added = db.articles().insertFetched(articles.map { (h, a) -> a to h.link })
                // tt-rss answers a deleted (or another user's) category with no articles and no error.
                if (headlines.isEmpty() && category != null && client.categories().none { it.id == category }) {
                    CATEGORY_GONE
                } else {
                    db.sources().recordSuccess(source.id, now, null, null, source.title)
                    return added
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtrssException) {
                e.message ?: "tt-rss reported an error."
            } catch (e: IOException) {
                "Couldn't reach tt-rss."
            } catch (e: SQLiteConstraintException) {
                return null
            } catch (e: Exception) {
                "Something went wrong reading from tt-rss."
            } finally {
                logOut(client)
            }
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    private suspend fun syncList(source: SourceEntity): Int? {
        val now = clock.instant()
        val list = CuratedLists.forSourceUrl(source.url)
        val error = if (list == null) {
            LIST_UNSUPPORTED
        } else {
            try {
                val response = http.get(list.pageUrl)
                if (response.isSuccessful) {
                    val links = list.links(response.body, response.finalUrl)
                    val added = db.withTransaction {
                        db.articles().insertFetched(
                            links.map {
                                ArticleEntity(
                                    sourceId = source.id, guid = it.url, url = withoutTracking(it.url, LinkPosts.siteNames(list.pageUrl)),
                                    title = it.title.orEmpty(),
                                    // The teaser is what the edition shows if the page can't be fetched.
                                    feedHtml = it.summary?.let { s -> "<p>${Entities.escape(s)}</p>" }, discoveredAt = now,
                                ) to it.url
                            },
                        ).also { db.articles().keepNewest(source.id, listKeep) }
                    }
                    db.sources().recordSuccess(source.id, now, null, null, source.title)
                    return added
                }
                "The site answered with error ${response.code}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "Couldn't reach the site."
            } catch (e: ListLayoutChangedException) {
                LIST_LAYOUT_CHANGED
            } catch (e: SQLiteConstraintException) {
                return null
            } catch (e: Exception) {
                "Something went wrong reading this list."
            }
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }

    companion object {
        const val LIST_LAYOUT_CHANGED =
            "This page has changed its layout, so newspapeRSS can't tell which links are new and took none. An app update should fix it."
        const val LIST_UNSUPPORTED = "This version of newspapeRSS can't read this list any more. Remove it or update the app."

        val REMEMBER_DELIVERED: Duration = Duration.ofDays(365)
        // A month: long enough to bring an article back from a recent edition with its text.
        private val KEEP_FEED_TEXT: Duration = Duration.ofDays(30)
        private const val VACUUM_AFTER = 500

        /** tt-rss articles are stored with this guid prefix followed by their tt-rss id. */
        const val TTRSS_GUID_PREFIX = "ttrss:"
        /** Unread articles taken from each tt-rss feed per sync; a paper takes one or two per feed. */
        const val TTRSS_PER_FEED = 5
        const val CATEGORY_GONE = "Your chosen tt-rss category isn't there any more. Choose another on this source's page."
        const val SIGN_IN_AGAIN = "Sign in to tt-rss again: tap the menu at the top of Sources."
    }
}

/**
 * The article as stored: a link post's url becomes the story it points to, with its own page kept
 * as [ArticleEntity.viaUrl]. Tracking parameters come off the stored url either way, so the same
 * story from two sources is one link to the planner and to the delivered-links memory. The guid
 * is left as the feed gave it: it's how the feed's next copy of the item is recognised.
 */
internal fun ArticleEntity.linkedToStory(siteUrl: String?): ArticleEntity {
    val story = LinkPosts.storyUrl(url, feedHtml, siteUrl)
    return if (story != null) copy(url = story, viaUrl = url) else copy(url = withoutTracking(url, LinkPosts.siteNames(url, siteUrl)))
}

/**
 * Ends a tt-rss session; the server expires it anyway if this fails. NonCancellable: it runs
 * in `finally` blocks, where a cancelled caller would otherwise skip it.
 */
internal suspend fun logOut(client: TtrssClient) = withContext(NonCancellable) {
    try {
        client.logout()
    } catch (e: Exception) {
    }
}
