package com.app.newspaperss.data

import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextCheck
import com.app.newspaperss.core.extract.FullTextEvidence
import com.app.newspaperss.core.extract.FullTextState
import com.app.newspaperss.core.feed.Opml
import com.app.newspaperss.core.feed.OpmlFeed
import com.app.newspaperss.core.lists.CuratedList
import com.app.newspaperss.core.lists.CuratedLists
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** What "Mark as read" changed, so Undo can put it back. */
data class MarkedRead(val articleId: Long, val starredAt: Instant?)

class SourceRepository(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val sources = db.sources()

    fun observe(): Flow<List<SourceEntity>> = sources.observeAll()

    fun observeActivity(): Flow<List<SourceActivity>> = db.articles().observeActivity()

    fun observe(id: Long): Flow<SourceEntity?> = sources.observe(id)

    /** The source's newest articles, newest first, whatever their state. */
    fun observeRecentArticles(id: Long, limit: Int = 30): Flow<List<ArticleEntity>> = db.articles().observeRecentForSource(id, limit)

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

    /** Adds the source for a curated list unless it exists; returns its id either way. */
    suspend fun addList(list: CuratedList): Long {
        val url = CuratedLists.sourceUrl(list)
        sources.byUrl(url)?.let { return it.id }
        val id = sources.insert(
            SourceEntity(
                kind = SourceKind.LIST, url = url, title = list.title, siteUrl = list.pageUrl, position = sources.nextPosition(),
                // A list's links are to pages of all kinds; there's no feed text to weigh against them.
                contentMode = ContentMode.PAGE,
            ),
        )
        return if (id == -1L) sources.byUrl(url)!!.id else id
    }

    suspend fun update(source: SourceEntity) = sources.update(source)

    suspend fun setPaused(id: Long, paused: Boolean) = sources.setPaused(id, paused)

    suspend fun setMaxArticles(id: Long, max: Int?) = sources.setMaxArticles(id, max)

    suspend fun stepMaxArticles(id: Long, delta: Int, default: Int, limit: Int) = sources.stepMaxArticles(id, delta, default, limit)

    /** Removes the source with its articles, stars included. Past editions keep their contents. */
    suspend fun remove(source: SourceEntity) = sources.delete(source)

    /** See [EditionRepository.setStarred]. */
    suspend fun setStarred(articleId: Long, starred: Boolean): Boolean = db.articles().setStarred(articleId, starred, clock.instant())

    /** See [EditionRepository.observeBuilding]. */
    fun observeBuilding(): Flow<Boolean> = db.editions().observeBuilding(clock)

    /**
     * Marks a waiting article as read: it never goes in an edition, and a tt-rss source marks it
     * read on the server at its next sync, so Undo never has to reach the server. Returns what
     * [undoMarkRead] needs, or null if the article wasn't waiting (it may have just gone into an
     * edition) or an edition is being made.
     */
    suspend fun markRead(articleId: Long): MarkedRead? = db.withTransaction {
        val article = db.articles().byId(articleId) ?: return@withTransaction null
        if (db.articles().markRead(articleId, clock.instant().minus(BUILD_HOLD)) == 0) null else MarkedRead(articleId, article.starredAt)
    }

    /** Puts a marked-read article back to waiting, with its star, unless something has moved it on since. */
    suspend fun undoMarkRead(marked: MarkedRead): Boolean = db.articles().undoMarkRead(marked.articleId, marked.starredAt) > 0

    /**
     * Sets how a source's articles get their text. [ContentMode.AUTO] hands the choice back to
     * the automatic check, which starts over; any other mode is the reader's and stays.
     */
    suspend fun chooseContentMode(id: Long, mode: ContentMode) = sources.setContentMode(id, mode, chosen = mode != ContentMode.AUTO)

    /** Adds one article's evidence to its source's full-text check (see [FullTextCheck]). */
    suspend fun recordFullText(sourceId: Long, evidence: FullTextEvidence, day: Long = LocalDate.now().toEpochDay()) = db.withTransaction {
        val source = sources.byId(sourceId) ?: return@withTransaction
        // A reading list or tt-rss account mixes many sites: one article says nothing about the next.
        if (source.kind != SourceKind.FEED || source.contentModeChosen) return@withTransaction
        val state = FullTextState(source.contentMode, source.fullTextEvidence, source.fullTextStreak, source.fullTextDay)
        val next = FullTextCheck.next(state, evidence, day)
        if (next != state) sources.setFullText(sourceId, next.mode, next.evidence, next.streak, next.day)
    }

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
        "newspapeRSS sources",
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
