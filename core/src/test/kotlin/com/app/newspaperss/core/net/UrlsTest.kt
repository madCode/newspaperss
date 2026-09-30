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

    @Test
    fun trackingComesOffAndEverythingElseStaysAsItWas() {
        val longreads = setOf("longreads")
        val cases = listOf(
            "https://news.example/story?src=longreads" to "https://news.example/story",
            "https://news.example/story/?utm_source=rss&utm_medium=rss&utm_campaign=story" to "https://news.example/story/",
            "https://news.example/story?id=7&utm_source=longreads.com&page=2#part-2" to "https://news.example/story?id=7&page=2#part-2",
            "https://news.example/story?fbclid=abc#top" to "https://news.example/story#top",
            // A src naming another site might be how that site finds the page: only this one's own tag goes.
            "https://news.example/story?src=homepage" to "https://news.example/story?src=homepage",
            "https://news.example/search?q=a%20b&ref=Longreads" to "https://news.example/search?q=a%20b",
            "https://news.example/story" to "https://news.example/story",
            "https://app.example/#/story?utm_source=x" to "https://app.example/#/story?utm_source=x",
        )
        for ((url, expected) in cases) assertEquals(url, expected, withoutTracking(url, longreads))
    }

    @Test
    fun aSiteIsKnownByTheNameItsRegisteredUnder() {
        assertEquals("theguardian.co.uk", registrableDomainOf("https://www.theguardian.co.uk/world"))
        assertEquals("longreads.com", registrableDomainOf("https://shop.longreads.com/x"))
        assertEquals(null, registrableDomainOf("mailto:someone@example.com"))
        assertEquals("longreads", siteNameOf("https://www.longreads.com/feed/"))
        assertEquals("theguardian", siteNameOf("https://theguardian.co.uk/"))
        assertEquals("writer", siteNameOf("https://writer.substack.com/feed"))
    }
}
