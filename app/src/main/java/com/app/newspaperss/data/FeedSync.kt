package com.app.newspaperss.data

import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.core.lists.ListLayoutChangedException
import com.app.newspaperss.core.net.HttpClient
import androidx.room.withTransaction
import org.jsoup.nodes.Entities
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Clock
import java.time.Duration

data class SyncResult(val newArticles: Int, val failedSources: Int)

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
        SyncResult(results.sumOf { it ?: 0 }, results.count { it == null })
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
                val added = db.articles().insertNew(
                    feed.items.map {
                        ArticleEntity(
                            sourceId = source.id, guid = it.guid, url = it.url, title = it.title,
                            author = it.author, published = it.published, feedHtml = it.contentHtml, discoveredAt = now,
                        )
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

    private suspend fun syncTtrss(source: SourceEntity): Int? {
        val now = clock.instant()
        val account = (ttrssAccounts?.load() as? StoredAccount.Ready)?.account
        val error = if (account == null || account.apiUrl != source.url) {
            SIGN_IN_AGAIN
        } else {
            val client = account.client(http)
            try {
                val added = db.articles().insertNew(
                    client.unreadHeadlines().map {
                        ArticleEntity(
                            sourceId = source.id, guid = "$TTRSS_GUID_PREFIX${it.id}", url = it.link, title = it.title,
                            author = it.author, published = it.updated?.let(Instant::ofEpochSecond), feedHtml = it.content,
                            discoveredAt = now, originId = it.feedId, originTitle = it.feedTitle,
                        )
                    },
                    // Unread on the server means not yet delivered from it; skipped, it would stay unread there.
                    skipDelivered = false,
                )
                db.sources().recordSuccess(source.id, now, null, null, source.title)
                return added
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
                        db.articles().insertNew(
                            links.map {
                                ArticleEntity(
                                    sourceId = source.id, guid = it.url, url = it.url, title = it.title.orEmpty(),
                                    // The teaser is what the edition shows if the page can't be fetched.
                                    feedHtml = it.summary?.let { s -> "<p>${Entities.escape(s)}</p>" }, discoveredAt = now,
                                )
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
            "This page has changed its layout, so newspaperss can't tell which links are new and took none. An app update should fix it."
        const val LIST_UNSUPPORTED = "This version of newspaperss can't read this list any more. Remove it or update the app."

        val REMEMBER_DELIVERED: Duration = Duration.ofDays(365)

        /** tt-rss articles are stored with this guid prefix followed by their tt-rss id. */
        const val TTRSS_GUID_PREFIX = "ttrss:"
        const val SIGN_IN_AGAIN = "Sign in to tt-rss again: tap the menu at the top of Sources."
    }
}

/** Ends a tt-rss session; the server expires it anyway if this fails. */
internal suspend fun logOut(client: TtrssClient) {
    try {
        client.logout()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
    }
}
