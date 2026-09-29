package com.app.newspaperss.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    @Test
    fun hostOfNamesTheSite() {
        val cases = listOf(
            "https://www.example.com/feed.xml" to "example.com",
            "https://blog.example.com/feed" to "blog.example.com",
            "http://example.com" to "example.com",
            "https://example.com:8443/rss?format=xml" to "example.com",
            "https://user@example.com/feed" to "example.com",
            "https://example.com?feed=rss" to "example.com",
            "https://wwwexample.com/" to "wwwexample.com",
            " https://www.example.com/feed " to "example.com",
            // Not parseable as a URI, or no host: the text after the scheme still names the site.
            "https://example.com/a feed with spaces" to "example.com",
            "example.com/feed" to "example.com",
            "www.example.com" to "example.com",
            // Nothing that looks like a host: keep what the reader typed rather than a blank name.
            "https:///feed" to "https:///feed",
        )
        for ((url, expected) in cases) assertEquals(url, expected, hostOf(url))
    }
}
