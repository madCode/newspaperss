package com.app.newspaperss.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterPacksTest {
    private val feeds = StarterPacks.all.flatMap { it.feeds }

    @Test
    fun everyFeedIsHttpsAndListedOnce() {
        assertTrue(feeds.all { it.url.startsWith("https://") })
        assertEquals(feeds.size, feeds.map { it.url }.toSet().size)
    }
}
