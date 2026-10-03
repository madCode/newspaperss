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
    @Query("SELECT * FROM publications WHERE sourceId = :sourceId AND leftOut = 1")
    fun observeLeftOut(sourceId: Long): Flow<List<PublicationEntity>>

    @Query("SELECT * FROM publications WHERE leftOut = 1")
    suspend fun allLeftOut(): List<PublicationEntity>

    /**
     * The feeds an aggregator's articles came from since [since], each with the name it was last
     * seen under (SQLite takes a bare column from the row that gave the MAX).
     */
    @Query(
        "SELECT originId, originTitle AS title, MAX(discoveredAt) AS lastSeen FROM articles " +
            "WHERE sourceId = :sourceId AND originId IS NOT NULL AND discoveredAt >= :since GROUP BY originId",
    )
    fun observeFeeds(sourceId: Long, since: Instant): Flow<List<FeedName>>

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

    @Query("SELECT * FROM publications WHERE sourceId = :sourceId AND `key` = :key")
    suspend fun publication(sourceId: Long, key: String): PublicationEntity?

    @Query("SELECT * FROM publications")
    suspend fun allPublications(): List<PublicationEntity>

    @Query("SELECT * FROM publications")
    fun observePublications(): Flow<List<PublicationEntity>>

    @Query("SELECT * FROM publications WHERE sourceId = :sourceId AND `key` = :key")
    fun observePublication(sourceId: Long, key: String): Flow<PublicationEntity?>

    @Query("SELECT * FROM publications WHERE sourceId = :sourceId")
    fun observePublicationsOf(sourceId: Long): Flow<List<PublicationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePublication(publication: PublicationEntity)

    @Query("DELETE FROM publications WHERE sourceId = :sourceId")
    suspend fun clearPublications(sourceId: Long)

    /** A new category, or a sign-in, lists the account's feeds again at the next sync. */
    @Query("UPDATE sources SET ttrssCategoryId = :categoryId, ttrssCategoryTitle = :title, feedsListedAt = NULL WHERE id = :id")
    suspend fun setTtrssCategory(id: Long, categoryId: Int?, title: String?)

    @Query("UPDATE sources SET feedsListedAt = :at WHERE id = :id")
    suspend fun setFeedsListed(id: Long, at: Instant)

    @Query("UPDATE publications SET listed = 0, outsideCategory = 0 WHERE sourceId = :sourceId")
    suspend fun unlistPublications(sourceId: Long)

    /** tt-rss has fetched these feeds: they have unread articles there. */
    @Query("UPDATE publications SET awaitingFirstFetch = 0 WHERE sourceId = :sourceId AND `key` IN (:keys) AND awaitingFirstFetch = 1")
    suspend fun fetchedByServer(sourceId: Long, keys: List<String>)

    @Query("UPDATE sources SET markReadOnServer = :markRead WHERE id = :id")
    suspend fun setMarkReadOnServer(id: Long, markRead: Boolean)

    @Query("SELECT * FROM sources WHERE kind = :kind")
    suspend fun ofKind(kind: SourceKind): List<SourceEntity>

    @Delete
    suspend fun delete(source: SourceEntity)

    /**
     * Deletes the phone feeds among [ids], moved to tt-rss and paused, that have nothing left to
     * give: no starred article, none waiting, none in an unsent edition. Deleting a source deletes
     * its articles, stars and all, so the check and the delete are one statement: an article
     * starred or picked for an edition in between keeps its source.
     */
    @Query(
        """DELETE FROM sources WHERE id IN (:ids) AND paused = 1 AND kind = 'FEED' AND NOT EXISTS (
               SELECT 1 FROM articles WHERE articles.sourceId = sources.id
               AND (articles.starredAt IS NOT NULL OR articles.state IN ('NEW', 'IN_EDITION')))""",
    )
    suspend fun deleteSpent(ids: Collection<Long>): Int
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

    /**
     * What a source's page says happened to its articles: when a delivered one's link went out,
     * and which unsent edition holds one that's in an edition. An article can sit in two unsent
     * editions (see [release]); the newest is named.
     */
    @Query(
        """SELECT a.id AS articleId, d.deliveredAt AS sentAt, NULL AS editionTitle
           FROM articles a JOIN delivered_urls d ON d.url = a.url
           WHERE a.sourceId = :sourceId AND a.state = 'DELIVERED' AND a.url != ''
           UNION ALL
           SELECT ea.articleId AS articleId, NULL AS sentAt, e.title AS editionTitle
           FROM edition_articles ea JOIN editions e ON e.id = ea.editionId JOIN articles a ON a.id = ea.articleId
           WHERE a.sourceId = :sourceId AND a.state = 'IN_EDITION' AND e.status IN ('READY', 'BUILDING')
             AND e.id = (SELECT e2.id FROM edition_articles ea2 JOIN editions e2 ON e2.id = ea2.editionId
                         WHERE ea2.articleId = ea.articleId AND e2.status IN ('READY', 'BUILDING')
                         ORDER BY e2.createdAt DESC, e2.id DESC LIMIT 1)""",
    )
    fun observeHistory(sourceId: Long): Flow<List<ArticleHistory>>

    /**
     * A paid post an edition found next to nothing free in; [skip] leaves it out for good, as
     * [ArticleEntity.paidSkipped]. Not a starred one, nor one that's already left the waiting articles:
     * a star given while the edition was being made wins.
     */
    @Query(
        """UPDATE articles SET paidOnly = 1,
               paidSkipped = CASE WHEN :skip AND state = 'NEW' AND starredAt IS NULL THEN 1 ELSE paidSkipped END,
               state = CASE WHEN :skip AND state = 'NEW' AND starredAt IS NULL THEN 'EXPIRED' ELSE state END
           WHERE id = :id""",
    )
    suspend fun markPaidOnly(id: Long, skip: Boolean)

    /**
     * How many of a publication's articles were paid posts with nothing free, and how many of them
     * are left out. [key] as [PublicationEntity.key].
     */
    @Query(
        """SELECT COUNT(*) AS found, COALESCE(SUM(CASE WHEN paidSkipped = 1 AND state = 'EXPIRED' THEN 1 ELSE 0 END), 0) AS skipped
           FROM articles WHERE sourceId = :sourceId AND COALESCE(originId, '') = :key AND paidOnly = 1""",
    )
    fun observePaidOnly(sourceId: Long, key: String): Flow<PaidOnlyCount>

    @Query("SELECT COUNT(*) FROM articles WHERE id IN (:ids) AND paidSkipped = 1 AND state = 'EXPIRED'")
    suspend fun countPaidSkipped(ids: Collection<Long>): Int

    /** Newest first by the date a source's page shows ([ArticleEntity.shownDate]). */
    @Query(
        """SELECT * FROM articles WHERE sourceId = :sourceId
           ORDER BY CASE WHEN published IS NULL OR published > discoveredAt + 86400000 THEN discoveredAt ELSE published END DESC, id DESC
           LIMIT :limit""",
    )
    fun observeRecentForSource(sourceId: Long, limit: Int): Flow<List<ArticleEntity>>

    /** [observeRecentForSource] for one of an aggregator's feeds. */
    @Query(
        """SELECT * FROM articles WHERE sourceId = :sourceId AND originId = :originId
           ORDER BY CASE WHEN published IS NULL OR published > discoveredAt + 86400000 THEN discoveredAt ELSE published END DESC, id DESC
           LIMIT :limit""",
    )
    fun observeRecentForFeed(sourceId: Long, originId: String, limit: Int): Flow<List<ArticleEntity>>

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
     * their stars. Not one also in another unsent edition: after a delivered edition is marked as
     * not sent, an article starred from it may be in a newer one too.
     */
    @Query(
        """UPDATE articles SET state = COALESCE(
               (SELECT stateBefore FROM edition_articles WHERE editionId = :editionId AND articleId = articles.id), 'NEW')
           WHERE state = 'IN_EDITION' AND id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId)
           AND id NOT IN (SELECT ea.articleId FROM edition_articles ea JOIN editions e ON e.id = ea.editionId
                          WHERE ea.editionId != :editionId AND ea.articleId IS NOT NULL AND e.status IN ('READY', 'BUILDING'))""",
    )
    suspend fun release(editionId: Long)

    /**
     * Puts a delivered edition's articles back in it, with the stars they went in with: delivery
     * cleared them. Not ones taken into a newer edition since: still unsent they aren't delivered,
     * and delivered they went out with it.
     */
    @Query(
        """UPDATE articles SET starredAt = COALESCE(starredAt, :at) WHERE state = 'DELIVERED'
           AND id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId AND starred = 1)
           AND id NOT IN ($DELIVERED_SINCE)""",
    )
    suspend fun restoreStars(editionId: Long, at: Instant)

    @Query(
        """UPDATE articles SET state = 'IN_EDITION' WHERE state = 'DELIVERED'
           AND id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId)
           AND id NOT IN ($DELIVERED_SINCE)""",
    )
    suspend fun undeliver(editionId: Long)

    /**
     * Forgets that the edition's links went out, matched on the time it was delivered: a link
     * remembered later went out again in another edition, and still counts.
     */
    @Query(
        """DELETE FROM delivered_urls WHERE deliveredAt = :deliveredAt AND url IN (
               SELECT url FROM articles WHERE id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId))""",
    )
    suspend fun forgetDelivered(editionId: Long, deliveredAt: Instant)

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

    /**
     * Back to waiting, keeping any star. Found again [now], so it gets a week like a new article
     * rather than expiring at the next sync. A delivered one was marked read on tt-rss, so the next
     * sync marks it unread (see [unreportedUnread]).
     */
    @Query(
        """UPDATE articles SET state = 'NEW', discoveredAt = :now, paidSkipped = 0,
               reportedRead = CASE WHEN state = 'DELIVERED' THEN 1 ELSE reportedRead END
           WHERE id = :id AND state IN ('SKIPPED', 'EXPIRED', 'DELIVERED')""",
    )
    suspend fun markUnread(id: Long, now: Instant): Int

    /** A source's articles the reader marked read (and hasn't starred since) that the server hasn't confirmed. */
    @Query(
        """SELECT guid, originId FROM articles WHERE sourceId = :sourceId AND state = 'SKIPPED' AND starredAt IS NULL
           AND reportedRead = 0 ORDER BY id LIMIT :limit""",
    )
    suspend fun unreportedRead(sourceId: Long, limit: Int): List<TtrssRef>

    /** A source's waiting articles the server last had as read: marked unread here since. */
    @Query("SELECT guid, originId FROM articles WHERE sourceId = :sourceId AND state = 'NEW' AND reportedRead = 1 ORDER BY id LIMIT :limit")
    suspend fun unreportedUnread(sourceId: Long, limit: Int): List<TtrssRef>

    /** A source's waiting articles the server last had as unread, to check whether they've been read there since. Not starred ones: a star wins. */
    @Query("SELECT guid, originId FROM articles WHERE sourceId = :sourceId AND state = 'NEW' AND starredAt IS NULL AND reportedRead = 0")
    suspend fun waitingUnread(sourceId: Long): List<TtrssRef>

    /** Of [guids], a source's read or delivered articles tt-rss last confirmed as read. */
    @Query("SELECT guid, originId FROM articles WHERE sourceId = :sourceId AND guid IN (:guids) AND state IN ('SKIPPED', 'DELIVERED') AND reportedRead = 1")
    suspend fun confirmedReadAmong(sourceId: Long, guids: List<String>): List<TtrssRef>

    /** Read in tt-rss since the last sync, with nothing changed here: read here too. */
    @Query(
        """UPDATE articles SET state = 'SKIPPED', reportedRead = 1 WHERE sourceId = :sourceId AND guid IN (:guids)
           AND state = 'NEW' AND starredAt IS NULL AND reportedRead = 0""",
    )
    suspend fun readOnServer(sourceId: Long, guids: List<String>)

    /**
     * Marked unread in tt-rss since the last sync, with nothing changed here: waiting again, and found
     * again [now] so it doesn't expire at once, as when marked unread here.
     */
    @Query(
        """UPDATE articles SET state = 'NEW', reportedRead = 0, discoveredAt = :now WHERE sourceId = :sourceId AND guid IN (:guids)
           AND state IN ('SKIPPED', 'DELIVERED') AND reportedRead = 1""",
    )
    suspend fun unreadOnServer(sourceId: Long, guids: List<String>, now: Instant)

    /** Of [guids], those of the source's articles waiting or in an unsent edition: unread here. */
    @Query("SELECT guid FROM articles WHERE sourceId = :sourceId AND state IN ('NEW', 'IN_EDITION') AND guid IN (:guids)")
    suspend fun unreadGuids(sourceId: Long, guids: List<String>): List<String>

    @Query("UPDATE articles SET reportedRead = :read WHERE sourceId = :sourceId AND guid IN (:guids)")
    suspend fun setReportedRead(sourceId: Long, guids: List<String>, read: Boolean = true)

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

    /**
     * The edition's own tt-rss articles that are no longer used up, to mark unread on the server
     * after it was marked as not sent. Not ones delivered since in another edition, or marked
     * read here.
     */
    @Query(
        """SELECT articles.* FROM articles JOIN sources ON sources.id = articles.sourceId
           WHERE sources.kind = 'TTRSS' AND sources.markReadOnServer = 1
           AND articles.state NOT IN ('DELIVERED', 'SKIPPED')
           AND articles.id IN (SELECT articleId FROM edition_articles WHERE editionId = :editionId)""",
    )
    suspend fun ttrssUnsentInEdition(editionId: Long): List<ArticleEntity>
}

/** Articles of edition :editionId delivered again in an edition sent after it. */
private const val DELIVERED_SINCE = """SELECT ea.articleId FROM edition_articles ea JOIN editions e ON e.id = ea.editionId
    WHERE ea.editionId != :editionId AND ea.articleId IS NOT NULL AND e.status = 'DELIVERED'
    AND e.deliveredAt > COALESCE((SELECT deliveredAt FROM editions WHERE id = :editionId), 0)"""

/** A source's paid posts with next to nothing free: how many were found, and how many are left out. */
data class PaidOnlyCount(val found: Int, val skipped: Int)

/** One fact about what happened to an article: when it went out, or which unsent edition holds it. */
data class ArticleHistory(val articleId: Long, val sentAt: Instant?, val editionTitle: String?)

/** A tt-rss article's guid and the tt-rss feed it came from. */
data class TtrssRef(val guid: String, val originId: String?)

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
