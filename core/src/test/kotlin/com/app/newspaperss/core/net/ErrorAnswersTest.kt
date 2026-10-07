package com.app.newspaperss.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorAnswersTest {
    // A site already added has no address to check or feed link to try: the advice has to be
    // something its reader can do from the source's page.
    @Test
    fun aSiteAlreadyAddedGetsAdviceThatFitsItsPage() {
        assertEquals(
            "The site's feed isn't there any more (error 404). It may have moved: add the site's home page again to find its new feed.",
            ErrorAnswers.message(404),
        )
        // A curated list's address is built in: adding it again can't help, so the reader isn't asked to.
        assertEquals(
            "The list's page isn't where NewspapeRSS expects it any more (error 410). An update to NewspapeRSS should fix it.",
            ErrorAnswers.message(410, curatedList = true),
        )
        assertEquals("The site turned NewspapeRSS away (error 429). Some sites block apps: try again later.", ErrorAnswers.message(429))
        assertEquals("The site isn't working right now (error 502). Try again later.", ErrorAnswers.message(502))
        assertEquals("The site answered with error 418.", ErrorAnswers.message(418))
    }
}
