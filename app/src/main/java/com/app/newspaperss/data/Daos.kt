package com.app.newspaperss.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import java.time.Duration
import java.time.Instant

@Dao
interface SourceDao {
    @Query("SELECT * FROM left_out_feeds WHERE sourceId = :sourceId")
    fun observeLeftOut(sourceId: Long): Flow<List<LeftOutFeedEntity>>

    @Query("SELECT * FROM left_out_feeds")
    suspend fun allLeftOut(): List<LeftOutFeedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun leaveOut(feed: LeftOutFeedEntity)

    @Query("DELETE FROM left_out_feeds WHERE sourceId = :sourceId AND originId = :originId")
    suspend fun takeBack(sourceId: Long, originId: String)

    /**
     * The feeds an aggregator's articles came from since [since], each with the name it was last
     * seen under (SQLite takes a bare column from the row that gave the MAX).
     */
    @Query(
        "SELECT originId, originTitle AS title, MAX(discoveredAt) AS lastSeen FROM articles " +
            "WHERE sourceId = :sourceId AND originId IS NOT NULL AND discoveredAt >= :since GROUP BY originId",
    )
    fun observeFeeds(sourceId: Long, since: Instant): Flow<List<FeedName>>

    @Query("DELETE FROM left_out_feeds WHERE sourceId = :sourceId")
    suspend fun clearLeftOut(sourceId: Long)

    @Query("SELECT * FROM sources ORDER BY position, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY position, id")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: Long): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE url = :url")
    suspend fun byUrl(url: String): SourceEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM sources")
    suspend fun nextPosition(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    // Targeted updates: a sync holds a source row for up to a minute, and
    // writing the whole row back would undo a pause or rename made meanwhile.
    // A pause ends a run of failures: time spent paused isn't time spent failing.
    @Query("UPDATE sources SET paused = :paused, failingSince = NULL WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean)

    @Query(
        """UPDATE sources SET lastFetchedAt = :at, lastError = NULL, failingSince = NULL,
           siteUrl = COALESCE(siteUrl, :siteUrl),
           title = CASE WHEN title = :placeholderTitle AND :feedTitle IS NOT NULL THEN :feedTitle ELSE title END
           WHERE id = :id""",
    )
    suspend fun recordSuccess(id: Long, at: Instant, feedTitle: String?, siteUrl: String?, placeholderTitle: String)

    @Query("UPDATE sources SET lastFetchedAt = :at, lastError = :error, failingSince = COALESCE(failingSince, :at) WHERE id = :id")
    suspend fun recordFailure(id: Long, at: Instant, error: String)

    @Query("UPDATE sources SET serverNote = :note WHERE id = :id")
    suspend fun setServerNote(id: Long, note: String?)

    @Query("UPDATE sources SET contentMode = :mode, fullTextEvidence = :evidence, fullTextStreak = :streak, fullTextDay = :day WHERE id = :id")
    suspend fun setFullText(id: Long, mode: ContentMode, evidence: FullTextEvidence?, streak: Int, day: Long?)

    @Query("UPDATE sources SET contentMode = :mode, contentModeChosen = :chosen, fullTextEvidence = NULL, fullTextStreak = 0, fullTextDay = NULL WHERE id = :id")
    suspend fun setContentMode(id: Long, mode: ContentMode, chosen: Boolean)

    @Query("UPDATE sources SET maxArticles = :max WHERE id = :id")
    suspend fun setMaxArticles(id: Long, max: Int?)

    /** Steps from the source's own cap, or from [default] if it has none, within 1..[limit]; in SQL so quick taps each count. */
    @Query("UPDATE sources SET maxArticles = MAX(1, MIN(:limit, COALESCE(maxArticles, :default) + :delta)) WHERE id = :id")
    suspend fun stepMaxArticles(id: Long, delta: Int, default: Int, limit: Int)

    @Query("UPDATE sources SET ttrssCategoryId = :categoryId, ttrssCategoryTitle = :title WHERE id = :id")
    suspend fun setTtrssCategory(id: Long, categoryId: Int?, title: String?)

    @Query("UPDATE sources SET markReadOnServer = :markRead WHERE id = :id")
    suspend fun setMarkReadOnServer(id: Long, markRead: Boolean)

    @Query("SELECT * FROM sources WHERE kind = :kind")
    suspend fun ofKind(kind: SourceKind): List<SourceEntity>

    @Delete
    suspend fun delete(source: SourceEntity)
}

/**
 * How long a BUILDING edition holds off "Mark as read" and unstarring. A build takes minutes;
 * one older than this was cut off by the process dying and is only marked failed by the next
 * build, which mustn't lock the buttons until then.
 */
internal val BUILD_HOLD: Duration = Duration.ofHours(2)

data class Featured(val sourceId: Long, val originId: String?, val createdAt: Instant)

/**
 * Whether an edition is being made, counting one started more than [BUILD_HOLD] ago as a crashed
 * build. It turns false by itself when the hold runs out, since nothing in the database changes
 * then.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun EditionDao.observeBuilding(clock: java.time.Clock): Flow<Boolean> =
    observeBuildingSince().transformLatest { started ->
        val left = started?.let { Duration.between(clock.instant(), it.plus(BUILD_HOLD)) }
        if (left != null && !left.isNegative && !left.isZero) {
            emit(true)
            kotlinx.coroutines.delay(left.toMillis())
        }
        emit(false)
    }.distinctUntilChanged()

data class SourceActivity(val sourceId: Long, val lastNew: Instant?)

@Dao
interface ArticleDao {
    /**
     * Inserts articles not already known for their source; returns how many were new. One whose
     * link was already delivered (see [DeliveredUrlEntity]) is left out: stored, it would count as
     * new activity on the Sources screen.
     */
    suspend fun insertNew(articles: List<ArticleEntity>): Int = insertFetched(articles.map { it to it.url })

    /**
     * [insertNew], each article with the link it was fetched with. That link, and a link post's own
     * page, count as delivered too: stored links have tracking removed and link posts point to
     * their story, while delivered_urls may hold the link as it went out before, or as a page
     * saved to the reading list.
     */
    @Transaction
    suspend fun insertFetched(articles: List<Pair<ArticleEntity, String>>): Int {
        fun linksOf(fetched: Pair<ArticleEntity, String>) = listOfNotNull(fetched.first.url, fetched.first.viaUrl, fetched.second).filter { it.isNotBlank() }
        // Chunked: SQLite before 3.32 (Android before 11) allows at most 999 query parameters.
        val delivered = articles.flatMap(::linksOf).distinct().chunked(500).flatMap { deliveredAmong(it) }.toSet()
        return articles.filter { fetched -> linksOf(fetched).none { it in delivered } }.count { insertIgnoring(it.first) != -1L }
    }

    @Query("SELECT url FROM delivered_urls WHERE url IN (:urls)")
    suspend fun deliveredAmong(urls: List<String>): List<String>

    @Query("INSERT OR REPLACE INTO delivered_urls (url, deliveredAt) SELECT url, :at FROM articles WHERE id IN (:ids) AND url != ''")
    suspend fun rememberDelivered(ids: List<Long>, at: Instant)

    /**
     * Uses up other waiting copies of the articles' links, from a second feed, tt-rss or the
     * reading list, so they aren't delivered again. A tt-rss copy is marked read on the server
     * with the edition's own articles (see [ttrssInEdition]).
     */
    @Query(
        """UPDATE articles SET state = 'DELIVERED' WHERE state = 'NEW'
           AND url IN (SELECT url FROM articles WHERE id IN (:ids) AND url != '')""",
    )
    suspend fun deliverCopies(ids: List<Long>)

    /**
     * Clears the stars of other copies of the articles' links, in any source: the link has just
     * gone out, which is what the star asked for, and a star left on a copy would send it again.
     */
    @Query(
        """UPDATE articles SET starredAt = NULL WHERE starredAt IS NOT NULL AND state != 'IN_EDITION'
           AND url IN (SELECT url FROM articles WHERE id IN (:ids) AND url != '')""",
    )
    suspend fun unstarCopies(ids: List<Long>)

    /** Feeds drop items long before this, so a link this old won't be offered again. */
    @Query("DELETE FROM delivered_urls WHERE deliveredAt < :before")
    suspend fun forgetDeliveredBefore(before: Instant)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(article: ArticleEntity): Long

    /** Waiting articles, and starred ones in any state but already in an unsent edition. */
    @Query("SELECT * FROM articles WHERE state = 'NEW' OR (starredAt IS NOT NULL AND state != 'IN_EDITION')")
    suspend fun candidates(): List<ArticleEntity>

    /** Starred links not yet in an edition, from sources that aren't paused; a link starred in two sources counts once. */
    @Query(
        """SELECT COUNT(DISTINCT CASE WHEN articles.url != '' THEN articles.url ELSE '#' || articles.id END)
           FROM articles JOIN sources ON sources.id = articles.sourceId
           WHERE articles.starredAt IS NOT NULL AND articles.state != 'IN_EDITION' AND sources.paused = 0""",
    )
    fun observeStarredWaiting(): Flow<Int>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    suspend fun byIds(ids: Collection<Long>): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt DESC, id DESC")
    fun observeAllForSource(sourceId: Long): Flow<List<ArticleEntity>>

    /** Newest first by the date a source's page shows ([ArticleEntity.shownDate]). */
    @Query(
        """SELECT * FROM articles WHERE sourceId = :sourceId
           ORDER BY CASE WHEN published IS NULL OR published > discoveredAt + 86400000 THEN discoveredAt ELSE published END DESC, id DESC
           LIMIT :limit""",
    )
    fun observeRecentForSource(sourceId: Long, limit: Int): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt, id")
    suspend fun allForSource(sourceId: Long): List<ArticleEntity>

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM articles WHERE sourceId = :sourceId AND state = 'NEW' AND title = '' AND discoveredAt >= :since")
    suspend fun untitledWaiting(sourceId: Long, since: Instant): List<Long>

    /** Only while the article is untitled and waiting: never over a title the reader gave, or one in an edition. */
    @Query("UPDATE articles SET title = :title WHERE id = :id AND title = '' AND state = 'NEW'")
    suspend fun setTitleIfUntitled(id: Long, title: String): Int

    @Query("UPDATE articles SET pageWords = :words WHERE id = :id")
    suspend fun setPageWords(id: Long, words: Int)

    @Query("SELECT sourceId, MAX(discoveredAt) AS lastNew FROM articles GROUP BY sourceId")
    fun observeActivity(): Flow<List<SourceActivity>>

    @Query("UPDATE articles SET state = :state WHERE id IN (:ids)")
    suspend fun setState(ids: List<Long>, state: ArticleState)

    /**
     * Link posts whose page turned out not to be their story become the post itself, at its own
     * page: delivering one must remember and use up that page, not the one it linked to, which the
     * edition didn't carry.
     */
    @Query("UPDATE articles SET url = viaUrl, viaUrl = NULL WHERE id IN (:ids) AND viaUrl IS NOT NULL")
    suspend fun unlinkFromStory(ids: List<Long>)

    @Query("UPDATE articles SET state = 'DELIVERED', starredAt = NULL WHERE id IN (:ids)")
    suspend fun setDelivered(ids: List<Long>)

    /**
     * Gives back the articles of an edition that won't be sent, in the state each had before it
     * went in (waiting, unless it was starred from delivered, marked read or expired). They keep
     * their stars.
     */
    @Query(
        """UPDATE articles SET state = COALESCE(
               (SELECT stateBefore FROM edition_articles WHERE editionId = :editionId AND articleId = articles.id), 'NEW')
           WHERE state = 'IN_EDITION' AND id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId)""",
    )
    suspend fun release(editionId: Long)

    /** Keeps the first star's time, so repeated taps don't move it back in line. Not while it's in an unsent edition. */
    @Query("UPDATE articles SET starredAt = COALESCE(starredAt, :at) WHERE id = :id AND state != 'IN_EDITION'")
    suspend fun star(id: Long, at: Instant): Int

    /**
     * Refused while an edition started after [buildingSince] is being made: it has taken the
     * article as a candidate and may already have written it into the book.
     */
    @Query(
        """UPDATE articles SET starredAt = NULL WHERE id = :id AND state != 'IN_EDITION'
           AND NOT EXISTS (SELECT 1 FROM editions WHERE status = 'BUILDING' AND createdAt > :buildingSince)""",
    )
    suspend fun unstar(id: Long, buildingSince: Instant): Int

    /** [star] for several articles at once. */
    @Query("UPDATE articles SET starredAt = COALESCE(starredAt, :at) WHERE id IN (:ids) AND state != 'IN_EDITION'")
    suspend fun starAll(ids: Collection<Long>, at: Instant): Int

    /** [unstar] for several articles at once, with the same hold during a build. */
    @Query(
        """UPDATE articles SET starredAt = NULL WHERE id IN (:ids) AND state != 'IN_EDITION'
           AND NOT EXISTS (SELECT 1 FROM editions WHERE status = 'BUILDING' AND createdAt > :buildingSince)""",
    )
    suspend fun unstarAll(ids: Collection<Long>, buildingSince: Instant): Int

    suspend fun setStarred(id: Long, starred: Boolean, now: Instant): Boolean =
        (if (starred) star(id, now) else unstar(id, now.minus(BUILD_HOLD))) > 0

    /** Only waiting articles, and their stars go with them. Refused during a build, as for [unstar]. */
    @Query(
        """UPDATE articles SET state = 'SKIPPED', starredAt = NULL WHERE id IN (:ids) AND state = 'NEW'
           AND NOT EXISTS (SELECT 1 FROM editions WHERE status = 'BUILDING' AND createdAt > :buildingSince)""",
    )
    suspend fun markReadAll(ids: Collection<Long>, buildingSince: Instant): Int

    /** Keeps a star given since it was marked read. */
    @Query("UPDATE articles SET state = 'NEW', starredAt = COALESCE(starredAt, :starredAt) WHERE id = :id AND state = 'SKIPPED'")
    suspend fun undoMarkRead(id: Long, starredAt: Instant?): Int

    /** Guids of a source's articles the reader marked read (and hasn't starred since) that the server hasn't been told of. */
    @Query(
        """SELECT guid FROM articles WHERE sourceId = :sourceId AND state = 'SKIPPED' AND starredAt IS NULL
           AND reportedRead = 0 ORDER BY id LIMIT :limit""",
    )
    suspend fun unreportedRead(sourceId: Long, limit: Int): List<String>

    @Query("UPDATE articles SET reportedRead = 1 WHERE sourceId = :sourceId AND guid IN (:guids)")
    suspend fun setReportedRead(sourceId: Long, guids: List<String>)

    /** Expires one of an aggregator's feeds' unpicked articles; starred ones stay. */
    @Query("UPDATE articles SET state = 'EXPIRED' WHERE sourceId = :sourceId AND originId = :originId AND state = 'NEW' AND starredAt IS NULL")
    suspend fun expireWaitingFromFeed(sourceId: Long, originId: String)

    /** Expires a source's unpicked articles; starred ones are the reader's and stay. */
    @Query("UPDATE articles SET state = 'EXPIRED' WHERE sourceId = :sourceId AND state = 'NEW' AND starredAt IS NULL")
    suspend fun expireWaiting(sourceId: Long)

    /** Expires unpicked articles discovered before [before], except reading-list items and stars. */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE state = 'NEW' AND starredAt IS NULL AND discoveredAt < :before
           AND sourceId IN (SELECT id FROM sources WHERE kind != 'READING_LIST')""",
    )
    suspend fun expireOlderThan(before: Instant): Int

    /**
     * Old articles keep their row, whose guid stops a feed offering them again, but not the
     * feed's copy of their text, which would otherwise grow the database without end. Counted
     * from delivery where there was one: a link saved long ago and sent today can still be
     * brought back with its text. A starred article keeps it, since it's going out again.
     */
    @Query(
        """UPDATE articles SET feedHtml = NULL WHERE feedHtml IS NOT NULL AND starredAt IS NULL
           AND state IN ('DELIVERED', 'EXPIRED', 'SKIPPED') AND discoveredAt < :before
           AND url NOT IN (SELECT url FROM delivered_urls WHERE deliveredAt >= :before)""",
    )
    suspend fun dropOldFeedHtml(before: Instant): Int

    /**
     * Expires all but the newest [keep] unpicked articles of a source, so a list that grows
     * faster than it's read stays bounded. Starred articles are the reader's and stay.
     */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE sourceId = :sourceId AND state = 'NEW' AND starredAt IS NULL
           AND id NOT IN (SELECT id FROM articles WHERE sourceId = :sourceId AND state = 'NEW' AND starredAt IS NULL
                          ORDER BY discoveredAt DESC, id DESC LIMIT :keep)""",
    )
    suspend fun keepNewest(sourceId: Long, keep: Int): Int

    /**
     * The edition's tt-rss articles to mark read on the server, and tt-rss copies of its links (a
     * story that went out from another source is read too): none from an account set to leave
     * them unread. A copy only while it's still used up: this runs when the marking worker does,
     * possibly hours later, and a copy starred since is going out again.
     */
    @Query(
        """SELECT articles.* FROM articles JOIN sources ON sources.id = articles.sourceId
           WHERE sources.kind = 'TTRSS' AND sources.markReadOnServer = 1
           AND (articles.id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId)
                OR (articles.state = 'DELIVERED' AND articles.starredAt IS NULL AND articles.url != '' AND articles.url IN (
                    SELECT a.url FROM edition_articles ea JOIN articles a ON a.id = ea.articleId
                    WHERE ea.editionId = :editionId AND a.url != '')))""",
    )
    suspend fun ttrssInEdition(editionId: Long): List<ArticleEntity>
}

data class EditionContent(
    @Embedded val entry: EditionArticleEntity,
    /** The article's current state; null once its source has been removed. */
    val state: ArticleState?,
    /** The article's current star, as against [EditionArticleEntity.starred], its star when the edition was made. */
    val starredAt: Instant? = null,
)

/** An edition article with what its notes need; the article's own fields are null once its source is removed. */
data class NotesRow(
    val title: String,
    val sourceTitle: String,
    val url: String?,
    val author: String?,
    val published: Instant?,
)

@Dao
interface EditionDao {
    @Query("SELECT * FROM editions WHERE status != 'DELETED' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EditionEntity>>

    @Query("SELECT * FROM editions WHERE id = :id AND status != 'DELETED'")
    fun observe(id: Long): Flow<EditionEntity?>

    @Query("SELECT * FROM editions WHERE id = :id")
    suspend fun byId(id: Long): EditionEntity?

    @Query("SELECT title FROM editions WHERE createdAt >= :since")
    suspend fun titlesSince(since: Instant): List<String>

    @Query("SELECT * FROM editions WHERE status = :status")
    suspend fun withStatus(status: EditionStatus): List<EditionEntity>

    @Query("SELECT COUNT(*) FROM editions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM editions WHERE status = 'DELIVERED'")
    suspend fun countDelivered(): Int

    /**
     * When each source (and each tt-rss publication) last had an article in a ready or delivered
     * edition. Starred articles don't count: they go in whatever the turns, and counting them
     * would push the source's other articles to the back.
     */
    @Query(
        """SELECT a.sourceId AS sourceId, a.originId AS originId, MAX(e.createdAt) AS createdAt
           FROM edition_articles ea JOIN articles a ON a.id = ea.articleId JOIN editions e ON e.id = ea.editionId
           WHERE e.status IN ('READY', 'DELIVERED') AND ea.starred = 0 GROUP BY a.sourceId, a.originId""",
    )
    suspend fun lastFeatured(): List<Featured>

    /** When the newest edition being made was started, or null if none is. */
    @Query("SELECT MAX(createdAt) FROM editions WHERE status = 'BUILDING'")
    fun observeBuildingSince(): Flow<Instant?>

    /** Only for an edition that turned out to have nothing in it, before anything refers to it. */
    @Query("DELETE FROM editions WHERE id = :id")
    suspend fun deleteEmpty(id: Long)

    @Insert
    suspend fun insert(edition: EditionEntity): Long

    @Update
    suspend fun update(edition: EditionEntity)

    @Query("SELECT * FROM editions WHERE fileName IS NOT NULL ORDER BY createdAt DESC")
    suspend fun withFiles(): List<EditionEntity>

    @Query("UPDATE editions SET fileName = NULL WHERE id = :id")
    suspend fun clearFile(id: Long)

    @Query("UPDATE editions SET fileName = :fileName WHERE id = :id")
    suspend fun setFile(id: Long, fileName: String)

    @Query("DELETE FROM edition_articles WHERE editionId = :editionId")
    suspend fun deleteArticles(editionId: Long)

    @Insert
    suspend fun insertArticles(articles: List<EditionArticleEntity>)

    @Query("SELECT * FROM edition_articles WHERE editionId = :editionId ORDER BY position")
    fun observeArticles(editionId: Long): Flow<List<EditionArticleEntity>>

    @Query(
        """SELECT edition_articles.*, articles.state AS state, articles.starredAt AS starredAt FROM edition_articles
           LEFT JOIN articles ON articles.id = edition_articles.articleId
           WHERE editionId = :editionId ORDER BY position""",
    )
    fun observeContents(editionId: Long): Flow<List<EditionContent>>

    @Query(
        """SELECT edition_articles.title, edition_articles.sourceTitle, articles.url, articles.author, articles.published
           FROM edition_articles LEFT JOIN articles ON articles.id = edition_articles.articleId
           WHERE editionId = :editionId ORDER BY position""",
    )
    suspend fun notesRows(editionId: Long): List<NotesRow>

    @Query("SELECT articleId FROM edition_articles WHERE editionId = :editionId AND articleId IS NOT NULL ORDER BY position")
    suspend fun articleIds(editionId: Long): List<Long>
}
