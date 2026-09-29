package com.app.newspaperss.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

enum class SourceKind { FEED, READING_LIST }

/** Whether to use the feed's own text or fetch the article page; AUTO decides per article. */
enum class ContentMode { AUTO, FEED, PAGE }

@Entity(tableName = "sources", indices = [Index(value = ["url"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SourceKind = SourceKind.FEED,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val section: String? = null,
    val position: Int = 0,
    val contentMode: ContentMode = ContentMode.AUTO,
    val paused: Boolean = false,
    val addedAt: Instant = Instant.now(),
    val lastFetchedAt: Instant? = null,
    /** The last sync error, cleared by the next successful sync. */
    val lastError: String? = null,
)

enum class ArticleState {
    NEW,
    DELIVERED,
    /** The reader dismissed it. */
    SKIPPED,
    /** Never picked and older than the keep window. */
    EXPIRED,
}

@Entity(
    tableName = "articles",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sourceId", "guid"], unique = true), Index("url"), Index("state")],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val guid: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val published: Instant? = null,
    /** The feed's own HTML for the article, if it has any. */
    val feedHtml: String? = null,
    val discoveredAt: Instant = Instant.now(),
    val state: ArticleState = ArticleState.NEW,
    val broughtBack: Boolean = false,
)

enum class EditionStatus { BUILDING, READY, DELIVERED, FAILED }

@Entity(tableName = "editions")
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Instant = Instant.now(),
    val status: EditionStatus = EditionStatus.BUILDING,
    /** The EPUB, relative to the app's editions directory. */
    val fileName: String? = null,
    val articleCount: Int = 0,
    val minutes: Double = 0.0,
    val deliveredAt: Instant? = null,
    val error: String? = null,
)

@Entity(
    tableName = "edition_articles",
    primaryKeys = ["editionId", "articleId"],
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("articleId")],
)
data class EditionArticleEntity(
    val editionId: Long,
    val articleId: Long,
    val position: Int,
    // Copied so an edition's contents survive its source being deleted.
    val title: String,
    val sourceTitle: String,
    val minutes: Double,
)
