package com.app.newspaperss.edition

import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity

/** An article ready to go into an edition. */
data class ArticleContent(
    val title: String,
    val author: String?,
    val bodyHtml: String,
    val minutes: Double,
    val note: String? = null,
    val images: List<EpubImage> = emptyList(),
)

fun interface ArticleContentProvider {
    /** The article's readable content, or null if there's nothing worth including. */
    suspend fun contentFor(article: ArticleEntity, source: SourceEntity): ArticleContent?
}
