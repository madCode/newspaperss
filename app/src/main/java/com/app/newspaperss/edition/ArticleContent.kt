package com.app.newspaperss.edition

import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.images.ImageAllowance
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity

/** An article ready to go into an edition. */
data class ArticleContent(
    val title: String,
    val author: String?,
    val bodyHtml: String,
    val wordCount: Int,
    val note: String? = null,
    val images: List<EpubImage> = emptyList(),
    /** BCP 47 language tag, if known. */
    val language: String? = null,
    /** The name of the site the text came from, if its page says: a link post's story is credited to it. */
    val siteName: String? = null,
    /** A link post whose page wasn't the story it pitched: the article is the item itself, from its own page. */
    val notTheStory: Boolean = false,
)

/**
 * How to get an article's text beyond its source's own setting.
 *
 * @property chosen the article text the reader chose for its publication, which wins over the rest.
 * @property learned what the full-text check has learned about the article's publication, if anything.
 * @property check fetch the page even for a long item and compare, as one of the edition's checks.
 * @property day the edition's epoch day, which evidence and checks are counted under; null for today.
 */
data class TextChoice(val learned: ContentMode? = null, val check: Boolean = false, val day: Long? = null, val chosen: ContentMode? = null)

fun interface ArticleContentProvider {
    /** The article's readable content, or null if there's nothing worth including. */
    suspend fun contentFor(article: ArticleEntity, source: SourceEntity, images: ImageAllowance, text: TextChoice): ArticleContent?
}
