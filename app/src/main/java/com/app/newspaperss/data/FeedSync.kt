package com.app.newspaperss.data

import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.net.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
) {
    suspend fun syncAll(): SyncResult = coroutineScope {
        val feeds = db.sources().all().filter { it.kind == SourceKind.FEED && !it.paused }
        val limit = Semaphore(4)
        val results = feeds.map { source -> async { limit.withPermit { sync(source) } } }.awaitAll()
        db.articles().expireOlderThan(clock.instant().minus(keepFor))
        SyncResult(results.sumOf { it ?: 0 }, results.count { it == null })
    }

    /** Returns the number of new articles, or null if the source failed. */
    suspend fun sync(source: SourceEntity): Int? {
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
                // Replace the placeholder (host name) title with the feed's own once it's known.
                val title = if (source.title == SourceRepository.hostOf(source.url)) feed.title ?: source.title else source.title
                db.sources().update(source.copy(title = title, siteUrl = source.siteUrl ?: feed.siteUrl, lastFetchedAt = now, lastError = null))
                return added
            }
            "The site answered with error ${response.code}."
        } catch (e: IOException) {
            "Couldn't reach the site."
        } catch (e: FeedParseException) {
            "This address no longer gives a feed."
        }
        db.sources().update(source.copy(lastFetchedAt = now, lastError = error))
        return null
    }
}
