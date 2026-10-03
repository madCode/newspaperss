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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** What "Mark as read" changed, so Undo can put it back. */
data class MarkedRead(val articleId: Long, val starredAt: Instant?)

/**
 * What a batch "Mark as read" did. [heldBack] counts waiting articles left alone because an
 * edition is being made.
 */
data class MarkReadBatch(val marked: List<MarkedRead>, val heldBack: Int)

/**
 * What a batch star or unstar changed: each article's star from before, so Undo can put it back.
 * [heldBack] counts articles that would have changed but were held by a build.
 */
data class StarBatch(val starred: Boolean, val changed: List<MarkedRead>, val heldBack: Int)

/** One of an aggregator's feeds, whether its articles can go in the paper, and its settings if it has any. */
data class FeedChoice(val originId: String, val title: String, val inPaper: Boolean, val publication: PublicationEntity? = null)

/** For A to Z: a leading "The" or "A" doesn't count, as in a library. */
fun sortTitle(title: String) = title.trim().lowercase().removePrefix("the ").removePrefix("a ")

class SourceRepository(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val sources = db.sources()

    fun observe(): Flow<List<SourceEntity>> = sources.observeAll()

    fun observeActivity(): Flow<List<SourceActivity>> = db.articles().observeActivity()

    fun observe(id: Long): Flow<SourceEntity?> = sources.observe(id)

    /** The source's newest articles, newest first, whatever their state. */
    fun observeRecentArticles(id: Long, limit: Int = 30): Flow<List<ArticleEntity>> = db.articles().observeRecentForSource(id, limit)

    /** The newest articles from one of a tt-rss account's feeds, as [observeRecentArticles]. */
    fun observeRecentArticles(id: Long, key: String, limit: Int = 30): Flow<List<ArticleEntity>> =
        if (key == PublicationEntity.OWN) observeRecentArticles(id, limit) else db.articles().observeRecentForFeed(id, key, limit)

    /**
     * A tt-rss account's feeds, A to Z. Once tt-rss has listed them, the feeds it takes articles
     * from (see [SourceEntity.feedsListedAt]), and any that has sent articles since: one subscribed
     * after the day's list is in the paper already. Until then, those seen in the last month and
     * every left-out one, which stays so it can come back.
     */
    fun observeFeeds(id: Long): Flow<List<FeedChoice>> =
        combine(sources.observe(id), sources.observeFeeds(id, clock.instant().minus(FEEDS_LISTED_FOR)), sources.observePublicationsOf(id)) { source, seen, publications ->
            val byKey = publications.associateBy { it.key }
            val listedAt = source?.feedsListedAt
            val keys = if (listedAt != null) {
                publications.filter { it.listed }.map { it.key } + seen.filter { !it.lastSeen.isBefore(listedAt) }.map { it.originId }
            } else {
                seen.map { it.originId } + publications.filter { it.leftOut }.map { it.key }
            }
            val seenTitles = seen.associate { it.originId to it.title }
            keys.distinct().filter { it != PublicationEntity.OWN }.map { key ->
                val publication = byKey[key]
                val title = publication?.title ?: seenTitles[key] ?: key
                FeedChoice(key, title, inPaper = publication?.leftOut != true, publication)
            }.sortedBy { sortTitle(it.title) }
        }

    /** Leaving a feed out lets its waiting articles go too, except starred ones, so none shows as waiting in vain. */
    suspend fun setFeedInPaper(sourceId: Long, feed: FeedChoice, inPaper: Boolean) = db.withTransaction {
        val publication = sources.publication(sourceId, feed.originId) ?: PublicationEntity(sourceId, feed.originId)
        // A name the list gave stays; a feed's id standing in for a name isn't one.
        val title = publication.title ?: feed.title.takeIf { it.isNotBlank() && it != feed.originId }
        sources.savePublication(publication.copy(title = title, leftOut = !inPaper))
        if (!inPaper) db.articles().expireWaitingFromFeed(sourceId, feed.originId)
    }

    /** Adds a feed unless one with this URL exists; returns its id either way. */
    suspend fun addFeed(url: String, title: String?): Long {
        sources.byUrl(url)?.let { return it.id }
        val id = sources.insert(SourceEntity(url = url, title = title?.takeIf { it.isNotBlank() } ?: hostOf(url), position = sources.nextPosition()))
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

    /** At most [max] articles per edition from the publication; null follows the edition setting. */
    suspend fun setMaxArticles(sourceId: Long, key: String, max: Int?) = editPublication(sourceId, key) { it.copy(maxArticles = max) }

    /** Steps from the publication's own cap, or from [default] if it has none, within 1..[limit]. */
    suspend fun stepMaxArticles(sourceId: Long, key: String, delta: Int, default: Int, limit: Int) =
        editPublication(sourceId, key) { it.copy(maxArticles = ((it.maxArticles ?: default) + delta).coerceIn(1, limit)) }

    /**
     * Reads and writes in one transaction, so two quick taps each count and a change made
     * meanwhile elsewhere in the row (a check recording evidence) isn't written over.
     */
    private suspend fun editPublication(sourceId: Long, key: String, change: (PublicationEntity) -> PublicationEntity) = db.withTransaction {
        val publication = sources.publication(sourceId, key) ?: PublicationEntity(sourceId, key)
        val changed = change(publication)
        if (changed != publication) sources.savePublication(changed)
    }

    /** Removes the source with its articles, stars included. Past editions keep their contents. */
    suspend fun remove(source: SourceEntity) = sources.delete(source)

    /** See [EditionRepository.setStarred]. */
    suspend fun setStarred(articleId: Long, starred: Boolean): Boolean = db.articles().setStarred(articleId, starred, clock.instant())

    /** See [EditionRepository.observeBuilding]. */
    fun observeBuilding(): Flow<Boolean> = db.editions().observeBuilding(clock)

    /**
     * Marks waiting articles as read: they never go in an edition, and a tt-rss source marks them
     * read on the server at its next sync, so Undo never has to reach the server. All or none: a
     * build holds every waiting one or none. Articles that aren't waiting (one may have just gone
     * into an edition) are passed over and not counted as held back.
     */
    suspend fun markRead(articleIds: Collection<Long>): MarkReadBatch = db.withTransaction {
        val waiting = db.articles().byIds(articleIds).filter { it.state == ArticleState.NEW }
        if (waiting.isEmpty()) return@withTransaction MarkReadBatch(emptyList(), 0)
        val changed = db.articles().markReadAll(waiting.map { it.id }, clock.instant().minus(BUILD_HOLD))
        if (changed == 0) MarkReadBatch(emptyList(), waiting.size) else MarkReadBatch(waiting.map { MarkedRead(it.id, it.starredAt) }, 0)
    }

    /**
     * The read toggle on an article: a waiting one is marked read (see [markRead]); a read, expired
     * or delivered one goes back to waiting, keeping any star. tt-rss hears of either at the next
     * sync.
     */
    suspend fun toggleRead(articleId: Long): Toggled {
        val article = db.articles().byId(articleId) ?: return Toggled.UNCHANGED
        return when (article.state) {
            ArticleState.NEW -> markRead(listOf(articleId)).let { if (it.marked.isNotEmpty()) Toggled.CHANGED else if (it.heldBack > 0) Toggled.HELD else Toggled.UNCHANGED }
            ArticleState.IN_EDITION -> Toggled.UNCHANGED
            else -> if (db.articles().markUnread(articleId, clock.instant()) > 0) Toggled.CHANGED else Toggled.UNCHANGED
        }
    }

    /** What [toggleRead] did. HELD: an edition being made holds a waiting article. */
    enum class Toggled { CHANGED, HELD, UNCHANGED }

    /** Puts a marked-read article back to waiting, with its star, unless something has moved it on since. */
    suspend fun undoMarkRead(marked: MarkedRead): Boolean = db.articles().undoMarkRead(marked.articleId, marked.starredAt) > 0

    /** Undoes a whole [markRead] batch. Returns how many came back. */
    suspend fun undoMarkRead(marked: List<MarkedRead>): Int = db.withTransaction { marked.count { undoMarkRead(it) } }

    /**
     * Stars or unstars several articles, skipping those already so and those in an unsent edition.
     * Unstarring waits for a build, as [setStarred] does.
     */
    suspend fun setStarred(articleIds: Collection<Long>, starred: Boolean): StarBatch = db.withTransaction {
        val now = clock.instant()
        val toChange = db.articles().byIds(articleIds).filter { it.state != ArticleState.IN_EDITION && (it.starredAt == null) == starred }
        if (toChange.isEmpty()) return@withTransaction StarBatch(starred, emptyList(), 0)
        val ids = toChange.map { it.id }
        val changed = if (starred) db.articles().starAll(ids, now) else db.articles().unstarAll(ids, now.minus(BUILD_HOLD))
        if (changed == 0) StarBatch(starred, emptyList(), toChange.size) else StarBatch(starred, toChange.map { MarkedRead(it.id, it.starredAt) }, 0)
    }

    /**
     * Puts each star in a [setStarred] batch back as it was. Taking stars back off waits for a
     * build like any unstarring. Returns how many couldn't be put back.
     */
    suspend fun undoStars(batch: StarBatch): Int = db.withTransaction {
        val ids = batch.changed.map { it.articleId }
        if (batch.starred) {
            ids.size - db.articles().unstarAll(ids, clock.instant().minus(BUILD_HOLD))
        } else {
            batch.changed.count { it.starredAt == null || db.articles().star(it.articleId, it.starredAt) == 0 }
        }
    }

    /**
     * Sets how a publication's articles get their text. [ContentMode.AUTO] hands the choice back
     * to the automatic check, which starts over; any other mode is the reader's and stays.
     */
    suspend fun chooseContentMode(sourceId: Long, key: String, mode: ContentMode) = editPublication(sourceId, key) {
        it.copy(
            chosenMode = mode.takeIf { m -> m != ContentMode.AUTO },
            contentMode = ContentMode.AUTO, fullTextEvidence = null, fullTextStreak = 0, fullTextDay = null, checkedDay = null,
        )
    }

    /**
     * Adds one article's evidence to its publication's full-text check (see [FullTextCheck]): the
     * source's own feed, or for tt-rss, the feed the article came from ([originId]). [checked]
     * says the article was a long item fetched to check it, which counts as the day's check even
     * when it showed nothing ([evidence] null).
     */
    suspend fun recordFullText(
        sourceId: Long, originId: String?, evidence: FullTextEvidence?, checked: Boolean = false, day: Long = LocalDate.now(clock).toEpochDay(),
    ) = db.withTransaction {
        val source = sources.byId(sourceId) ?: return@withTransaction
        // The reading list and curated lists mix many sites: one article says nothing about the next.
        val key = when (source.kind) {
            SourceKind.FEED -> PublicationEntity.OWN
            SourceKind.TTRSS -> originId ?: return@withTransaction
            else -> return@withTransaction
        }
        val publication = sources.publication(sourceId, key) ?: PublicationEntity(sourceId, key)
        if (publication.chosenMode != null) return@withTransaction
        val state = FullTextState(publication.contentMode, publication.fullTextEvidence, publication.fullTextStreak, publication.fullTextDay)
        val next = evidence?.let { FullTextCheck.next(state, it, day) } ?: state
        val updated = publication.copy(
            contentMode = next.mode, fullTextEvidence = next.evidence, fullTextStreak = next.streak, fullTextDay = next.day,
            checkedDay = if (checked) day else publication.checkedDay,
        )
        if (updated != publication) sources.savePublication(updated)
    }

    /** Each source's own publication, by source id. */
    fun observeOwnPublications(): Flow<Map<Long, PublicationEntity>> =
        sources.observePublications().map { list -> list.filter { it.key == PublicationEntity.OWN }.associateBy { it.sourceId } }

    fun observePublication(sourceId: Long, key: String): Flow<PublicationEntity?> = sources.observePublication(sourceId, key)

    /** Returns how many feeds were new. */
    suspend fun importOpml(xml: String): Int {
        var added = 0
        for (feed in Opml.parse(xml)) {
            if (sources.byUrl(feed.url) == null) {
                addFeed(feed.url, feed.title)
                added++
            }
        }
        return added
    }

    suspend fun exportOpml(): String = Opml.write(
        "newspapeRSS sources",
        sources.all().filter { it.kind == SourceKind.FEED }.map { OpmlFeed(it.url, it.title, folder = null) },
    )

    companion object {
        private val FEEDS_LISTED_FOR: Duration = Duration.ofDays(30)

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
