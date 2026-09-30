package com.app.newspaperss.edition

import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.images.ImageAllowance
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
)

fun interface ArticleContentProvider {
    /** The article's readable content, or null if there's nothing worth including. */
    suspend fun contentFor(article: ArticleEntity, source: SourceEntity, images: ImageAllowance): ArticleContent?
}
