package com.app.newspaperss.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Clock

class EditionRepository(
    private val db: AppDatabase,
    val editionsDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun observeAll(): Flow<List<EditionEntity>> = db.editions().observeAll()

    fun observe(id: Long): Flow<EditionEntity?> = db.editions().observe(id)

    fun observeArticles(id: Long): Flow<List<EditionArticleEntity>> = db.editions().observeArticles(id)

    fun fileOf(edition: EditionEntity): File? = edition.fileName?.let { File(editionsDir, it) }?.takeIf { it.exists() }

    /** Delivery succeeded: only now are the edition's articles used up. */
    suspend fun markDelivered(id: Long) = db.withTransaction {
        val edition = db.editions().byId(id) ?: return@withTransaction
        db.articles().setState(db.editions().articleIds(id), ArticleState.DELIVERED)
        db.editions().update(edition.copy(status = EditionStatus.DELIVERED, deliveredAt = clock.instant(), error = null))
    }

    /** Puts articles the reader didn't get to back in the pool, ahead of newer ones. */
    suspend fun bringBack(articleIds: List<Long>) = db.articles().bringBack(articleIds)
}
