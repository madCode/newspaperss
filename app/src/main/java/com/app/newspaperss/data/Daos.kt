package com.app.newspaperss.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Delete
    suspend fun delete(source: SourceEntity)
}

data class SourceCount(val sourceId: Long, val count: Int)

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

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY COALESCE(published, discoveredAt) DESC LIMIT :limit")
    fun observeForSource(sourceId: Long, limit: Int = 50): Flow<List<ArticleEntity>>

    @Query("SELECT sourceId, COUNT(*) AS count FROM articles WHERE state = 'NEW' GROUP BY sourceId")
    fun observeWaitingCounts(): Flow<List<SourceCount>>

    @Query("UPDATE articles SET state = :state, broughtBack = 0 WHERE id IN (:ids)")
    suspend fun setState(ids: List<Long>, state: ArticleState)

    @Query("UPDATE articles SET state = 'NEW', broughtBack = 1 WHERE id IN (:ids)")
    suspend fun bringBack(ids: List<Long>)

    /** Expires unpicked articles discovered before [before], except reading-list items. */
    @Query(
        """UPDATE articles SET state = 'EXPIRED' WHERE state = 'NEW' AND broughtBack = 0 AND discoveredAt < :before
           AND sourceId IN (SELECT id FROM sources WHERE kind = 'FEED')""",
    )
    suspend fun expireOlderThan(before: Instant): Int
}

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

    @Query("SELECT articleId FROM edition_articles WHERE editionId = :editionId AND articleId IS NOT NULL ORDER BY position")
    suspend fun articleIds(editionId: Long): List<Long>
}
