package com.app.newspaperss.data

import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.net.withoutTracking
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.core.lists.ListLayoutChangedException
import com.app.newspaperss.core.net.ErrorAnswers
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
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration

/** @property sources how many sources were synced (paused ones and the reading list aren't). */
data class SyncResult(val newArticles: Int, val failedSources: Int, val sources: Int)

class FeedSync(
    private val db: AppDatabase,
    private val http: HttpClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Unpicked feed articles older than this expire, so there's never a backlog to feel behind on. */
    private val keepFor: Duration = KEEP_WAITING,
    private val ttrssAccounts: TtrssAccountStore? = null,
    /** How many unpicked links a curated list keeps; older ones expire as new ones arrive. */
    private val listKeep: Int = 12,
    /**
     * Given the ids of curated-list links that came without a title, to look up in the background
     * (see [ReadingListTitles]): Arts & Letters Daily gives only a teaser, and a source's page
     * would otherwise list its picks by site name. Must return quickly.
     */
    private val onUntitled: (articleIds: List<Long>) -> Unit = {},
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
            ErrorAnswers.message(response.code)
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
            } catch (e: Exception) {
                if (e is TtrssException && e.failsWholeAccount) throw e
                failure = failure ?: e
                emptyList()
            }
        }
        failure?.let { if (headlines.isEmpty()) throw it }
        return headlines
    }

    /**
     * Keeps read and unread the same here and in tt-rss, the newest change winning. tt-rss doesn't say
     * when a flag changed, so "newest" is told by what changed since the last sync: the app's own
     * changes are sent first and read back, and a flag that differs from what tt-rss last confirmed
     * was changed in tt-rss. Changed on both sides between two syncs, the app's change wins. A star
     * here beats a read in tt-rss, which is often just opening it; an article in an unsent edition is
     * left alone. [fetched] are this sync's unread headlines and the articles made of them.
     */
    private suspend fun syncReadState(
        client: TtrssClient,
        source: SourceEntity,
        fetched: List<Pair<TtrssHeadline, ArticleEntity>>,
        now: Instant,
    ) {
        val articles = db.articles()
        fun ids(refs: List<TtrssRef>) = refs.mapNotNull { it.guid.removePrefix(TTRSS_GUID_PREFIX).toLongOrNull() }
        val toRead = articles.unreportedRead(source.id, limit = 500)
        val toUnread = articles.unreportedUnread(source.id, limit = 500)
        client.markRead(ids(toRead))
        client.markUnread(ids(toUnread))

        // Waiting here and unread in tt-rss at the last sync: missing from this sync's few unread
        // headlines per feed, it may have been read there, or just not be among the newest. Asked,
        // not inferred: the headlines don't say how many unread a feed has left.
        val unreadNow = fetched.map { it.second.guid }.toSet()
        val unsure = articles.waitingUnread(source.id).filter { it.guid !in unreadNow }
        // Read here and unread in this sync's headlines: marked unread in tt-rss since, unless the
        // delivery's own marking landed after the headlines were fetched. Asked again below, with
        // everything else, after the headlines.
        val unreadThere = unreadNow.toList().chunked(500).flatMap { articles.confirmedReadAmong(source.id, it) }
        val states = client.readStates(toRead + toUnread + unsure + unreadThere)
        // Chunked: SQLite before 3.32 (Android before 11) allows at most 999 query parameters.
        states.confirmed(toRead, read = true).chunked(500).forEach { articles.setReportedRead(source.id, it, read = true) }
        states.confirmed(toUnread, read = false).chunked(500).forEach { articles.setReportedRead(source.id, it, read = false) }
        unsure.filter { states.unread[it.guid] == false }.map { it.guid }.chunked(500).forEach { articles.readOnServer(source.id, it) }
        // Only from this sync's few unread per feed: one marked unread in tt-rss further back waits
        // until it's among them.
        unreadThere.filter { states.unread[it.guid] == true }.map { it.guid }.chunked(500).forEach { articles.unreadOnServer(source.id, it, now) }

        // A link already delivered (from a feed, or before the account was reconnected) is skipped
        // like any other, and tt-rss is told it's read: otherwise it would sit unread there for good.
        // Not one unread here: it may be one the reader marked unread after it went out. The
        // headline's own link as well as the stored one (a link post's story, tracking removed):
        // delivered_urls holds whichever went out.
        fun linksOf(h: TtrssHeadline, a: ArticleEntity) = listOf(h.link, a.url)
        val delivered = fetched.flatMap { (h, a) -> linksOf(h, a) }.distinct().chunked(500).flatMap { articles.deliveredAmong(it) }.toSet()
        val unreadHere = fetched.map { it.second.guid }.chunked(500).flatMap { articles.unreadGuids(source.id, it) }.toSet()
        client.markRead(fetched.filter { (h, a) -> a.guid !in unreadHere && linksOf(h, a).any { it in delivered } }.map { it.first.id })
    }

    private suspend fun syncTtrss(source: SourceEntity): Int? {
        val now = clock.instant()
        val account = ttrssAccounts?.ready()
        val error = if (account == null || account.apiUrl != source.url) {
            SIGN_IN_AGAIN
        } else {
            val client = account.client(http)
            try {
                val category = source.ttrssCategoryId
                // A few from each feed rather than the newest 200 overall: busy news feeds would
                // fill those 200, and a feed that posts monthly would never reach the paper.
                val leftOut = db.sources().leftOut(source.id).map { it.key }.toSet()
                val withUnread = client.unreadFeeds(category).filter { it.unread > 0 }
                // A feed with articles has been fetched, without waiting for tomorrow's feed list to say so.
                db.sources().fetchedByServer(source.id, withUnread.map { it.id.toString() })
                val unreadFeeds = withUnread.filter { it.id.toString() !in leftOut }
                val headlines = fromEachFeed(client, unreadFeeds)
                val articles = headlines.map {
                    it to ArticleEntity(
                        sourceId = source.id, guid = "$TTRSS_GUID_PREFIX${it.id}", url = it.link, title = it.title,
                        author = it.author, published = it.updated?.let(Instant::ofEpochSecond), feedHtml = it.content,
                        discoveredAt = now, originId = it.feedId, originTitle = it.feedTitle,
                    ).linkedToStory(siteUrl = null)
                }
                if (source.markReadOnServer) syncReadState(client, source, articles, now)
                val added = db.articles().insertFetched(articles.map { (h, a) -> a to h.link })
                // tt-rss answers a deleted (or another user's) category with no articles and no error.
                if (headlines.isEmpty() && category != null && client.categories().none { it.id == category }) {
                    CATEGORY_GONE
                } else {
                    db.sources().recordSuccess(source.id, now, null, null, source.title)
                    listFeeds(client, source, now)
                    return added
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtrssException) {
                e.reason
            } catch (e: IOException) {
                // The periodic sync, and every edition, tries again.
                ttrssUnreachable(e) + if (tooSlow(e)) " It'll be tried again at the next sync." else ""
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

    /** Lists the account's feeds once a day (see [listTtrssFeeds]). */
    private suspend fun listFeeds(client: TtrssClient, source: SourceEntity, now: Instant) {
        if (source.feedsListedAt?.let { Duration.between(it, now) < LIST_FEEDS_EVERY } == true) return
        listTtrssFeeds(db, client, source, now)
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
                    // Not just new ones, so a lookup that failed is tried again, but only for a couple of
                    // days: a paywalled page or a PDF never gives a title, and would be fetched every sync.
                    val untitled = db.articles().untitledWaiting(source.id, now.minus(UNTITLED_LOOKUP_FOR))
                    // Scheduling the lookup failing mustn't turn a good sync into a failed one.
                    if (untitled.isNotEmpty()) runCatching { onUntitled(untitled) }
                    return added
                }
                ErrorAnswers.message(response.code, curatedList = true)
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
        private val UNTITLED_LOOKUP_FOR: Duration = Duration.ofDays(2)
        private val LIST_FEEDS_EVERY: Duration = Duration.ofDays(1)

        /** How long an unpicked article waits, from when it was found, before it expires. */
        val KEEP_WAITING: Duration = Duration.ofDays(7)

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
        const val CATEGORY_GONE = "Your chosen tt-rss category isn't there any more. Choose another in Settings › Where your feeds live."
        const val SIGN_IN_AGAIN = "Sign in to tt-rss again: Settings › Where your feeds live."

        /**
         * What to tell the reader when tt-rss couldn't be read. A slow answer gets its own words: a
         * home server on a slow line answers eventually, and "couldn't reach" sends people to check a
         * connection that works. A connect timeout isn't slowness: a switched-off VPN or a wrong
         * address behind a firewall drops packets rather than refusing them.
         */
        fun ttrssUnreachable(e: IOException): String =
            if (tooSlow(e)) "tt-rss took too long to answer." else "Couldn't reach tt-rss."

        /** A read or whole-call timeout; OkHttp reports a connect timeout as one too, told apart by its message. */
        fun tooSlow(e: IOException): Boolean =
            e is InterruptedIOException && e.message?.contains("connect", ignoreCase = true) != true
    }
}

/**
 * Fills in the account's publications from its full list of feeds: their names, addresses and
 * categories, and which are still there to show. Only unread feeds are fetched, so the articles
 * alone would miss a feed read in tt-rss itself. With a category chosen, the account's other feeds
 * are listed too, as outside it, so Sources can say what isn't in the paper; they're asked for apart
 * because a category takes in its subcategories' feeds. Returns whether it listed them; a failure
 * is left for the next try.
 *
 * [source] must be as read before asking tt-rss: a new category or sign-in since then clears
 * [SourceEntity.feedsListedAt], and this list would be for the wrong feeds, or another user's.
 */
internal suspend fun listTtrssFeeds(db: AppDatabase, client: TtrssClient, source: SourceEntity, now: Instant): Boolean {
    val category = source.ttrssCategoryId
    val feeds: List<TtrssFeed>
    val outside: List<TtrssFeed>
    val categories: Map<Int, String>
    try {
        feeds = client.allFeeds(category)
        val ids = feeds.map { it.id }.toSet()
        outside = if (category == null) emptyList() else client.allFeeds().filter { it.id !in ids }
        categories = client.categories().associate { it.id to it.title }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    val sources = db.sources()
    return db.withTransaction {
        val current = sources.byId(source.id) ?: return@withTransaction false
        if (current.ttrssCategoryId != category || current.feedsListedAt != source.feedsListedAt) return@withTransaction false
        sources.unlistPublications(source.id)
        for ((feed, inPaperCategory) in feeds.map { it to true } + outside.map { it to false }) {
            val key = feed.id.toString()
            val publication = sources.publication(source.id, key) ?: PublicationEntity(source.id, key)
            sources.savePublication(
                publication.copy(
                    title = feed.title.ifBlank { publication.title.orEmpty() }.ifBlank { null },
                    feedUrl = feed.feedUrl ?: publication.feedUrl,
                    // Category 0 is tt-rss's Uncategorized, under whatever name the server's language gives it.
                    category = when (val id = feed.categoryId) {
                        null -> publication.category
                        0 -> null
                        else -> categories[id] ?: publication.category
                    },
                    listed = inPaperCategory,
                    outsideCategory = !inPaperCategory,
                    awaitingFirstFetch = feed.lastUpdated == 0L,
                ),
            )
        }
        sources.setFeedsListed(source.id, now)
        true
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
