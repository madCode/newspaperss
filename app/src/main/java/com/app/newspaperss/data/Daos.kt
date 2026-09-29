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
import java.time.Instant

@Dao
interface SourceDao {
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

data class SourceActivity(val sourceId: Long, val lastNew: Instant?)

@Dao
interface ArticleDao {
    /**
     * Inserts articles not already known for their source; returns how many were new. One whose
     * link was already delivered (see [DeliveredUrlEntity]) is left out: stored, it would count as
     * new activity on the Sources screen.
     */
    @Transaction
    suspend fun insertNew(articles: List<ArticleEntity>): Int {
        // Chunked: SQLite before 3.32 (Android before 11) allows at most 999 query parameters.
        val delivered = articles.map { it.url }.filter { it.isNotBlank() }.distinct().chunked(500).flatMap { deliveredAmong(it) }.toSet()
        return articles.filter { it.url !in delivered }.count { insertIgnoring(it) != -1L }
    }

    @Query("SELECT url FROM delivered_urls WHERE url IN (:urls)")
    suspend fun deliveredAmong(urls: List<String>): List<String>

    @Query("INSERT OR REPLACE INTO delivered_urls (url, deliveredAt) SELECT url, :at FROM articles WHERE id IN (:ids) AND url != ''")
    suspend fun rememberDelivered(ids: List<Long>, at: Instant)

    /**
     * Uses up other waiting copies of the articles' links, from a second feed or the reading
     * list, so they aren't delivered again. tt-rss copies stay: marking them read on the
     * server is how they leave tt-rss, and that only happens for articles in an edition.
     */
    @Query(
        """UPDATE articles SET state = 'DELIVERED' WHERE state = 'NEW' AND broughtBack = 0
           AND url IN (SELECT url FROM articles WHERE id IN (:ids) AND url != '')
           AND sourceId NOT IN (SELECT id FROM sources WHERE kind = 'TTRSS')""",
    )
    suspend fun deliverCopies(ids: List<Long>)

    /** Feeds drop items long before this, so a link this old won't be offered again. */
    @Query("DELETE FROM delivered_urls WHERE deliveredAt < :before")
    suspend fun forgetDeliveredBefore(before: Instant)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(article: ArticleEntity): Long

    @Query("SELECT * FROM articles WHERE state = 'NEW'")
    suspend fun candidates(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt DESC, id DESC")
    fun observeAllForSource(sourceId: Long): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt DESC, id DESC LIMIT :limit")
    fun observeRecentForSource(sourceId: Long, limit: Int): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt, id")
    suspend fun allForSource(sourceId: Long): List<ArticleEntity>

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Only while the article is untitled and waiting: never over a title the reader gave, or one in an edition. */
    @Query("UPDATE articles SET title = :title WHERE id = :id AND title = '' AND state = 'NEW'")
    suspend fun setTitleIfUntitled(id: Long, title: String): Int

    @Query("UPDATE articles SET pageWords = :words WHERE id = :id")
    suspend fun setPageWords(id: Long, words: Int)

    @Query("SELECT sourceId, MAX(discoveredAt) AS lastNew FROM articles GROUP BY sourceId")
    fun observeActivity(): Flow<List<SourceActivity>>

    @Query("UPDATE articles SET state = :state, broughtBack = 0 WHERE id IN (:ids)")
    suspend fun setState(ids: List<Long>, state: ArticleState)

    @Query("UPDATE articles SET state = 'NEW', broughtBack = 1 WHERE id IN (:ids)")
    suspend fun bringBack(ids: List<Long>)

    /** Only articles still marked delivered: one already back in the pool or in a newer edition stays put. */
    @Query("UPDATE articles SET state = 'NEW', broughtBack = 1 WHERE id IN (:ids) AND state = 'DELIVERED'")
    suspend fun bringBackDelivered(ids: List<Long>): Int

    /** Expires a source's unpicked articles; brought-back ones are the reader's and stay. */
    @Query("UPDATE articles SET state = 'EXPIRED' WHERE sourceId = :sourceId AND state = 'NEW' AND broughtBack = 0")
    suspend fun expireWaiting(sourceId: Long)

    /** Expires unpicked articles discovered before [before], except reading-list items. */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE state = 'NEW' AND broughtBack = 0 AND discoveredAt < :before
           AND sourceId IN (SELECT id FROM sources WHERE kind != 'READING_LIST')""",
    )
    suspend fun expireOlderThan(before: Instant): Int

    /**
     * Old articles keep their row, whose guid stops a feed offering them again, but not the
     * feed's copy of their text, which would otherwise grow the database without end. Counted
     * from delivery where there was one: a link saved long ago and sent today can still be
     * brought back with its text.
     */
    @Query(
        """UPDATE articles SET feedHtml = NULL WHERE feedHtml IS NOT NULL
           AND state IN ('DELIVERED', 'EXPIRED', 'SKIPPED') AND discoveredAt < :before
           AND url NOT IN (SELECT url FROM delivered_urls WHERE deliveredAt >= :before)""",
    )
    suspend fun dropOldFeedHtml(before: Instant): Int

    /**
     * Expires all but the newest [keep] unpicked articles of a source, so a list that grows
     * faster than it's read stays bounded. Brought-back articles are the reader's and stay.
     */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE sourceId = :sourceId AND state = 'NEW' AND broughtBack = 0
           AND id NOT IN (SELECT id FROM articles WHERE sourceId = :sourceId AND state = 'NEW' AND broughtBack = 0
                          ORDER BY discoveredAt DESC, id DESC LIMIT :keep)""",
    )
    suspend fun keepNewest(sourceId: Long, keep: Int): Int

    /** The edition's tt-rss articles to mark read on the server: none from an account set to leave them unread. */
    @Query(
        """SELECT articles.* FROM articles
           JOIN edition_articles ON edition_articles.articleId = articles.id
           JOIN sources ON sources.id = articles.sourceId
           WHERE edition_articles.editionId = :editionId AND sources.kind = 'TTRSS' AND sources.markReadOnServer = 1""",
    )
    suspend fun ttrssInEdition(editionId: Long): List<ArticleEntity>
}

data class EditionContent(
    @Embedded val entry: EditionArticleEntity,
    /** The article's current state; null once its source has been removed. */
    val state: ArticleState?,
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
        """SELECT edition_articles.*, articles.state AS state FROM edition_articles
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
