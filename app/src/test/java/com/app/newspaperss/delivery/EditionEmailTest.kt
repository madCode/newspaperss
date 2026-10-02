package com.app.newspaperss.delivery

import com.app.newspaperss.data.EditionArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class EditionEmailTest {
    private fun article(title: String, source: String, minutes: Double) =
        EditionArticleEntity(editionId = 1, articleId = null, position = 0, title = title, sourceTitle = source, minutes = minutes)

    @Test
    fun summarisesTheEditionThenListsEachArticleInOrder() {
        val body = EditionEmail.body(
            "Tuesday Morning Edition",
            listOf(article("Why bridges hum", "Aeon", 50.0), article("A short one", "Kottke", 0.2), article("Night trains", "The Guardian", 20.0)),
        )
        assertEquals(
            "Tuesday Morning Edition: 3 articles, about 1 hr 10 min.\n\n• Why bridges hum — Aeon\n• A short one — Kottke\n• Night trains — The Guardian",
            body,
        )
    }

    @Test
    fun oneArticleIsSingular() {
        assertEquals("Wednesday Edition: 1 article, about 1 min.\n\n• A short one — Kottke", EditionEmail.body("Wednesday Edition", listOf(article("A short one", "Kottke", 0.2))))
    }
}
