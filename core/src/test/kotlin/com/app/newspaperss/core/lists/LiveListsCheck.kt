package com.app.newspaperss.core.lists

import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads each curated list's real page, so a site redesign fails a scheduled CI run
 * (.github/workflows/live-check.yml) instead of first showing up as an error on someone's phone.
 * The unit tests can't catch that: they read pages saved on the day each parser was written.
 * Skipped unless LIVE_CHECK=1, so ordinary builds never touch the network.
 */
class LiveListsCheck {
    @Test
    fun everyCuratedListStillReadsItsLivePage() = runBlocking {
        assumeTrue(System.getenv("LIVE_CHECK") == "1")
        val http = OkHttpHttpClient()
        for (list in CuratedLists.all) {
            val page = http.get(list.pageUrl)
            assertTrue("${list.title}: HTTP ${page.code}", page.isSuccessful)
            val links = list.links(page.body, page.finalUrl)
            assertEquals("${list.title}: ${links.map { it.url }}", 3, links.size)
            assertTrue(links.all { it.url.startsWith("https://") || it.url.startsWith("http://") })
        }
    }
}
