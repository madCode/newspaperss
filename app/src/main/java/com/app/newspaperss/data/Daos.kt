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
    @Query("UPDATE sources SET paused = :paused WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean)

    @Query(
        """UPDATE sources SET lastFetchedAt = :at, lastError = NULL,
           siteUrl = COALESCE(siteUrl, :siteUrl),
           title = CASE WHEN title = :placeholderTitle AND :feedTitle IS NOT NULL THEN :feedTitle ELSE title END
           WHERE id = :id""",
    )
    suspend fun recordSuccess(id: Long, at: Instant, feedTitle: String?, siteUrl: String?, placeholderTitle: String)

    @Query("UPDATE sources SET lastFetchedAt = :at, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, at: Instant, error: String)

    @Query("UPDATE sources SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String)

    @Query("UPDATE sources SET serverNote = :note WHERE id = :id")
    suspend fun setServerNote(id: Long, note: String?)

    @Query("UPDATE sources SET contentMode = :mode, fullTextEvidence = :evidence, fullTextStreak = :streak, fullTextDay = :day WHERE id = :id")
    suspend fun setFullText(id: Long, mode: ContentMode, evidence: FullTextEvidence?, streak: Int, day: Long?)

    @Query("UPDATE sources SET contentMode = :mode, contentModeChosen = :chosen, fullTextEvidence = NULL, fullTextStreak = 0, fullTextDay = NULL WHERE id = :id")
    suspend fun setContentMode(id: Long, mode: ContentMode, chosen: Boolean)

    @Query("SELECT * FROM sources WHERE kind = :kind")
    suspend fun ofKind(kind: SourceKind): List<SourceEntity>

    @Delete
    suspend fun delete(source: SourceEntity)
}

data class SourceActivity(val sourceId: Long, val lastNew: Instant?)

@Dao
interface ArticleDao {
    /** Inserts articles not already known for their source; returns how many were new. */
    @Transaction
    suspend fun insertNew(articles: List<ArticleEntity>): Int = articles.count { insertIgnoring(it) != -1L }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(article: ArticleEntity): Long

    @Query("SELECT * FROM articles WHERE state = 'NEW'")
    suspend fun candidates(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt DESC, id DESC")
    fun observeAllForSource(sourceId: Long): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY discoveredAt, id")
    suspend fun allForSource(sourceId: Long): List<ArticleEntity>

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Only while the article is untitled and waiting: never over a title the reader gave, or one in an edition. */
    @Query("UPDATE articles SET title = :title WHERE id = :id AND title = '' AND state = 'NEW'")
    suspend fun setTitleIfUntitled(id: Long, title: String): Int

    @Query("SELECT sourceId, MAX(discoveredAt) AS lastNew FROM articles GROUP BY sourceId")
    fun observeActivity(): Flow<List<SourceActivity>>

    @Query("UPDATE articles SET state = :state, broughtBack = 0 WHERE id IN (:ids)")
    suspend fun setState(ids: List<Long>, state: ArticleState)

    @Query("UPDATE articles SET state = 'NEW', broughtBack = 1 WHERE id IN (:ids)")
    suspend fun bringBack(ids: List<Long>)

    /** Only articles still marked delivered: one already back in the pool or in a newer edition stays put. */
    @Query("UPDATE articles SET state = 'NEW', broughtBack = 1 WHERE id IN (:ids) AND state = 'DELIVERED'")
    suspend fun bringBackDelivered(ids: List<Long>): Int

    /** Expires unpicked articles discovered before [before], except reading-list items. */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE state = 'NEW' AND broughtBack = 0 AND discoveredAt < :before
           AND sourceId IN (SELECT id FROM sources WHERE kind != 'READING_LIST')""",
    )
    suspend fun expireOlderThan(before: Instant): Int

    @Query(
        """SELECT articles.* FROM articles
           JOIN edition_articles ON edition_articles.articleId = articles.id
           JOIN sources ON sources.id = articles.sourceId
           WHERE edition_articles.editionId = :editionId AND sources.kind = 'TTRSS'""",
    )
    suspend fun ttrssInEdition(editionId: Long): List<ArticleEntity>
}

data class EditionContent(
    @Embedded val entry: EditionArticleEntity,
    /** The article's current state; null once its source has been removed. */
    val state: ArticleState?,
)

@Dao
interface EditionDao {
    @Query("SELECT * FROM editions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EditionEntity>>

    @Query("SELECT * FROM editions WHERE id = :id")
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

    @Query("SELECT articleId FROM edition_articles WHERE editionId = :editionId AND articleId IS NOT NULL ORDER BY position")
    suspend fun articleIds(editionId: Long): List<Long>
}
