package com.app.newspaperss.edition

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ExtractInput
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity

class ExtractorContentProvider(private val extractor: ArticleExtractor) : ArticleContentProvider {
    override suspend fun contentFor(article: ArticleEntity, source: SourceEntity): ArticleContent {
        val extracted = extractor.extract(
            ExtractInput(
                url = article.url,
                feedTitle = article.title,
                feedHtml = article.feedHtml,
                feedAuthor = article.author,
                mode = source.contentMode,
            ),
        )
        return ArticleContent(
            title = extracted.title,
            author = extracted.author,
            bodyHtml = extracted.html,
            minutes = extracted.minutes,
            note = extracted.note,
        )
    }
}
