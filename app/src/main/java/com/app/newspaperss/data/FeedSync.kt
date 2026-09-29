package com.app.newspaperss.data

import com.app.newspaperss.core.feed.FeedParseException
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.net.HttpClient
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
            "This address no longer gives a feed."
        } catch (e: SQLiteConstraintException) {
            // The source was removed while it was being fetched.
            return null
        } catch (e: Exception) {
            // One bad feed mustn't stop the others from syncing.
            "Something went wrong reading this feed."
        }
        db.sources().recordFailure(source.id, now, error)
        return null
    }
}
