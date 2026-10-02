package com.app.newspaperss.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import java.time.Duration
import java.time.Instant

enum class SourceKind {
    FEED,
    READING_LIST,
    /** A Tiny Tiny RSS account: its url is the API endpoint and its articles come from many feeds. */
    TTRSS,
    /**
     * A curated list read by a scraper ([com.app.newspaperss.core.lists.CuratedList]): its url is
     * [com.app.newspaperss.core.lists.CuratedLists.sourceUrl] and its siteUrl the page read.
     */
    LIST,
}

@Entity(tableName = "sources", indices = [Index(value = ["url"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SourceKind = SourceKind.FEED,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val section: String? = null,
    val position: Int = 0,
    /**
     * The reader's choice ([contentModeChosen]), or a mode fixed by the kind of source (the reading
     * list and curated lists fetch pages). What the automatic check learns is kept per
     * [PublicationEntity] instead, so it's [ContentMode.AUTO] here for a feed the reader left to it.
     */
    val contentMode: ContentMode = ContentMode.AUTO,
    /** The reader picked [contentMode] themselves, so the automatic full-text check leaves it alone. */
    val contentModeChosen: Boolean = false,
    /**
     * Unused: the check's state lives in [PublicationEntity]. Kept because dropping a column means
     * rebuilding this table, and dropping it with foreign keys on would delete every article.
     */
    val fullTextEvidence: FullTextEvidence? = null,
    val fullTextStreak: Int = 0,
    val fullTextDay: Long? = null,
    val paused: Boolean = false,
    /** At most this many articles per edition from this source; null follows the edition setting. Not used for tt-rss. */
    val maxArticles: Int? = null,
    /** tt-rss only: the category to take unread articles from, null for all of them. */
    val ttrssCategoryId: Int? = null,
    val ttrssCategoryTitle: String? = null,
    /** tt-rss only: mark delivered articles read on the server. */
    val markReadOnServer: Boolean = true,
    val addedAt: Instant = Instant.now(),
    val lastFetchedAt: Instant? = null,
    /** The last sync error, cleared by the next successful sync. */
    val lastError: String? = null,
    /** When the current run of failed syncs began; null while syncs succeed. */
    val failingSince: Instant? = null,
    /**
     * A problem reporting back to the service (tt-rss not marking delivered articles read).
     * Kept apart from [lastError] so a successful sync doesn't hide it; cleared when reporting
     * back succeeds.
     */
    val serverNote: String? = null,
)

/** Stored by name, and the DAO queries spell names out as SQL strings ('NEW'): renaming one breaks them. */
enum class ArticleState {
    NEW,
    /** In an edition that hasn't been delivered yet; goes back to NEW if it never is. */
    IN_EDITION,
    DELIVERED,
    /** The reader marked it as read: never in an edition, and read in tt-rss at the next sync. */
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
    /**
     * When the reader starred it for the next edition, or null. A flag beside [state], not a
     * state of its own, so unstarring leaves the article as it was. Stars never expire and are
     * cleared on delivery.
     */
    val starredAt: Instant? = null,
    /**
     * The publication an aggregator source (tt-rss) got the article from: its feed id there,
     * so the per-source cap applies per publication, and its title, for the byline. Null for
     * articles from a feed of their own.
     */
    val originId: String? = null,
    val originTitle: String? = null,
    /** Words in a saved link's article, counted when its page is first looked up: the reading list's time estimate. */
    val pageWords: Int? = null,
    /**
     * tt-rss only: the server has it as read, from a sync or delivery. Compared with [state] at each
     * sync to tell the server what the reader marked read or unread here since.
     */
    @ColumnInfo(defaultValue = "0") val reportedRead: Boolean = false,
    /**
     * A link post's own page, when [url] is the story it points to (see
     * [com.app.newspaperss.core.feed.LinkPosts]); null for an ordinary article.
     */
    val viaUrl: String? = null,
) {
    /**
     * When it was published, for showing: a date more than a day after it was fetched is a
     * feed's mistake (scheduled posts, wrong zones, two-digit years), so the fetch date stands in.
     */
    val shownDate: Instant get() = published?.takeUnless { it.isAfter(discoveredAt.plus(Duration.ofDays(1))) } ?: discoveredAt
}

/**
 * A tt-rss feed the reader left out of the paper: sync stops fetching it and the planner skips
 * any of its articles still here, unless starred. [originId] is tt-rss's feed id; [title] keeps
 * it listed, so it can come back, after its articles are gone.
 */
@Entity(
    tableName = "left_out_feeds",
    primaryKeys = ["sourceId", "originId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class LeftOutFeedEntity(val sourceId: Long, val originId: String, val title: String)

/**
 * Who wrote a source's articles, as against how they arrive: a feed's own publication has [key]
 * "", and each feed in a tt-rss account is one, keyed by its id there ([ArticleEntity.originId]).
 * Holds what the automatic full-text check has learned about it (see
 * [com.app.newspaperss.core.extract.FullTextCheck]); a mode the reader chose stays on the source.
 */
@Entity(
    tableName = "publications",
    primaryKeys = ["sourceId", "key"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class PublicationEntity(
    val sourceId: Long,
    val key: String,
    val contentMode: ContentMode = ContentMode.AUTO,
    /** The latest article's [FullTextEvidence] and the run of days behind it. */
    val fullTextEvidence: FullTextEvidence? = null,
    val fullTextStreak: Int = 0,
    /** The epoch day the last piece of evidence was counted. */
    val fullTextDay: Long? = null,
    /** The epoch day a long item was last checked against its page. */
    val checkedDay: Long? = null,
) {
    companion object {
        /** The key of a source's own feed, and of an article with no [ArticleEntity.originId]. */
        const val OWN = ""

        fun keyOf(article: ArticleEntity) = article.originId ?: OWN
    }
}

/** A feed an aggregator's articles came from, as the source page lists it. */
data class FeedName(val originId: String, val title: String?, val lastSeen: Instant)

/**
 * A link that went out in a delivered edition. Kept apart from articles, which go when their
 * source is removed, so a source removed and added again, or a story that turns up later in
 * another source, doesn't deliver it a second time.
 */
@Entity(tableName = "delivered_urls")
data class DeliveredUrlEntity(
    @PrimaryKey val url: String,
    val deliveredAt: Instant,
)

enum class EditionStatus {
    BUILDING, READY, DELIVERED, FAILED,

    /**
     * Deleted by the reader: only the row stays, holding its title, so a later edition can't
     * take the same one (Send to Kindle drops a title it has seen). Hidden everywhere else.
     */
    DELETED,
}

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
    /**
     * When it was last delivered. Kept when it's marked as not sent: sending it again then knows
     * the work done on first delivery, like saving its notes, is done.
     */
    val deliveredAt: Instant? = null,
    val error: String? = null,
)

@Entity(
    tableName = "edition_articles",
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
        // SET_NULL, not CASCADE: removing a source mustn't erase past editions' contents.
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("editionId"), Index("articleId")],
)
data class EditionArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionId: Long,
    /** Null once the article's source has been removed. */
    val articleId: Long?,
    val position: Int,
    // Copied so an edition's contents survive its source being deleted.
    val title: String,
    val sourceTitle: String,
    val minutes: Double,
    /** It went in because it was starred. */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /**
     * The article's state before it went in, which it gets back if the edition is never sent: a
     * starred article that was delivered, marked read or expired stays so once unstarred.
     */
    val stateBefore: ArticleState? = null,
)
