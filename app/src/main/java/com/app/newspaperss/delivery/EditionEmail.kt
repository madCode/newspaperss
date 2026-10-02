package com.app.newspaperss.delivery

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.data.EditionArticleEntity

/**
 * The body of the email that sends an edition to a Kindle. Amazon ignores it; it's there so the
 * reader's Sent folder shows what each edition held.
 */
object EditionEmail {
    fun body(title: String, articles: List<EditionArticleEntity>): String {
        val count = if (articles.size == 1) "1 article" else "${articles.size} articles"
        val summary = "$title: $count, about ${ReadingTime.format(articles.sumOf { it.minutes })}."
        if (articles.isEmpty()) return summary
        return summary + "\n\n" + articles.joinToString("\n") { "• ${it.title} — ${it.sourceTitle}" }
    }
}
