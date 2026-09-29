package com.app.newspaperss.data

import com.app.newspaperss.core.feed.Opml
import com.app.newspaperss.core.feed.OpmlFeed
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class SourceRepository(private val db: AppDatabase) {
    private val sources = db.sources()

    fun observe(): Flow<List<SourceEntity>> = sources.observeAll()

    fun observeWaitingCounts(): Flow<List<SourceCount>> = db.articles().observeWaitingCounts()

    /** Adds a feed unless one with this URL exists; returns its id either way. */
    suspend fun addFeed(url: String, title: String?, section: String? = null): Long {
        sources.byUrl(url)?.let { return it.id }
        val id = sources.insert(
            SourceEntity(url = url, title = title?.takeIf { it.isNotBlank() } ?: hostOf(url), section = section, position = sources.nextPosition()),
        )
        return if (id == -1L) sources.byUrl(url)!!.id else id
    }

    /**
     * Adds the source for the tt-rss account at [apiUrl] unless it exists; returns its id either
     * way. Only one account is supported, so another account's source is removed.
     */
    suspend fun addTtrss(apiUrl: String): Long = db.withTransaction {
        sources.ofKind(SourceKind.TTRSS).filter { it.url != apiUrl }.forEach { sources.delete(it) }
        sources.byUrl(apiUrl)?.id
            ?: sources.insert(SourceEntity(kind = SourceKind.TTRSS, url = apiUrl, title = TTRSS_TITLE, position = sources.nextPosition()))
    }

    suspend fun update(source: SourceEntity) = sources.update(source)

    suspend fun setPaused(id: Long, paused: Boolean) = sources.setPaused(id, paused)

    suspend fun remove(source: SourceEntity) = sources.delete(source)

    /** Returns how many feeds were new. */
    suspend fun importOpml(xml: String): Int {
        var added = 0
        for (feed in Opml.parse(xml)) {
            if (sources.byUrl(feed.url) == null) {
                addFeed(feed.url, feed.title, feed.folder)
                added++
            }
        }
        return added
    }

    suspend fun exportOpml(): String = Opml.write(
        "newspaperss sources",
        sources.all().filter { it.kind == SourceKind.FEED }.map { OpmlFeed(it.url, it.title, it.section) },
    )

    companion object {
        // The tt-rss API has no name for an installation; the list shows the host beneath it.
        const val TTRSS_TITLE = "Tiny Tiny RSS"

        /**
         * The name a source gets when it's added without a title, and the placeholder
         * [FeedSync] passes to [SourceDao.recordSuccess]: a source whose title still equals
         * `hostOf(source.url)` takes the feed's own title on its next sync (so does one the
         * reader renamed to exactly that). Changing what this returns for a URL leaves sources
         * already named by the old result stuck with it.
         */
        fun hostOf(url: String): String = com.app.newspaperss.core.net.hostOf(url)
    }
}
