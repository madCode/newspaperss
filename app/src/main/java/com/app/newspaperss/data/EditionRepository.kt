package com.app.newspaperss.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Clock

/**
 * @param onTtrssDelivered called with an edition's id once it's delivered with tt-rss articles
 *   in it, to mark them read on the server. It must return quickly and leave the work to run
 *   elsewhere: delivery doesn't wait for tt-rss.
 */
class EditionRepository(
    private val db: AppDatabase,
    val editionsDir: File,
    private val clock: Clock = Clock.systemUTC(),
    private val onTtrssDelivered: (editionId: Long) -> Unit = {},
) {
    fun observeAll(): Flow<List<EditionEntity>> = db.editions().observeAll()

    fun observe(id: Long): Flow<EditionEntity?> = db.editions().observe(id)

    fun observeArticles(id: Long): Flow<List<EditionArticleEntity>> = db.editions().observeArticles(id)

    fun observeContents(id: Long): Flow<List<EditionContent>> = db.editions().observeContents(id)

    suspend fun byId(id: Long): EditionEntity? = db.editions().byId(id)

    fun fileOf(edition: EditionEntity): File? = edition.fileName?.let { File(editionsDir, it) }?.takeIf { it.exists() }

    /**
     * The reader handed a ready edition to an app. An edition already delivered, or released
     * because it wasn't sent in time, is left alone: its articles may be in a newer one now.
     */
    suspend fun markSent(id: Long) = markDelivered(id, onlyIfReady = true)

    /** Delivery succeeded: only now are the edition's articles used up. */
    suspend fun markDelivered(id: Long, onlyIfReady: Boolean = false) {
        val delivered = db.withTransaction {
            val edition = db.editions().byId(id) ?: return@withTransaction false
            // Checked inside the transaction: a build may be releasing unsent editions at the same
            // time, and the reader may have deleted this one while a folder copy was finishing.
            if (edition.status == EditionStatus.DELETED) return@withTransaction false
            if (onlyIfReady && edition.status != EditionStatus.READY) return@withTransaction false
            val articleIds = db.editions().articleIds(id)
            db.articles().setState(articleIds, ArticleState.DELIVERED)
            db.articles().rememberDelivered(articleIds, clock.instant())
            db.articles().deliverCopies(articleIds)
            db.editions().update(edition.copy(status = EditionStatus.DELIVERED, deliveredAt = clock.instant(), error = null))
            true
        }
        if (!delivered || db.articles().ttrssInEdition(id).isEmpty()) return
        try {
            onTtrssDelivered(id)
        } catch (e: Exception) {
            // The edition is delivered either way; its tt-rss articles just stay unread there.
            Log.w(TAG, "Couldn't schedule marking tt-rss articles read: ${e.javaClass.name}")
        }
    }

    /**
     * Puts delivered articles the reader didn't get to back in the pool, ahead of newer ones.
     * Returns how many actually went back.
     */
    suspend fun bringBack(articleIds: List<Long>): Int = db.articles().bringBackDelivered(articleIds)

    /**
     * Deletes an edition's contents and EPUB. One that was never sent gives its articles back to
     * the next edition first; a delivered one's stay used. An edition still being made is left
     * alone. The row stays as [EditionStatus.DELETED], keeping its title taken.
     *
     * @return false if there was nothing to delete.
     */
    suspend fun delete(id: Long): Boolean {
        val deleted = db.withTransaction {
            val edition = db.editions().byId(id)
                ?.takeIf { it.status != EditionStatus.BUILDING && it.status != EditionStatus.DELETED } ?: return@withTransaction null
            if (edition.status == EditionStatus.READY) db.articles().bringBack(db.editions().articleIds(id))
            db.editions().deleteArticles(id)
            db.editions().update(edition.copy(status = EditionStatus.DELETED, fileName = null, articleCount = 0, minutes = 0.0, error = null))
            edition
        } ?: return false
        deleted.fileName?.let { File(editionsDir, it) }?.delete()
        return true
    }

    private companion object {
        const val TAG = "EditionRepository"
    }
}
