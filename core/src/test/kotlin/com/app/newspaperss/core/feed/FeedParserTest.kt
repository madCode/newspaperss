package com.app.newspaperss.core.feed

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom">
          <channel>
            <title>Example Blog</title>
            <atom:link rel="self" href="https://example.com/feed"/>
            <link>https://example.com/</link>
            <image><title>Logo title</title><url>https://example.com/logo.png</url></image>
            <item>
              <title>Q&amp;A: <![CDATA[Fish & chips]]></title>
              <link>/posts/1</link>
              <guid isPermaLink="false">post-1</guid>
              <description>Short teaser</description>
              <content:encoded><![CDATA[<p>The full&nbsp;body</p>]]></content:encoded>
              <dc:creator>Ada</dc:creator>
              <pubDate>Tue, 29 Sep 2026 06:30:00 GMT</pubDate>
            </item>
            <item>
              <title>Only a guid</title>
              <guid>https://example.com/posts/2</guid>
              <description>Teaser &nbsp; with an HTML entity</description>
              <pubDate>Mon, 28 Sep 2026 10:00:00 EST</pubDate>
            </item>
            <item><title>No link at all</title><guid isPermaLink="false">x</guid></item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun rss2() {
        val feed = FeedParser.parse(rss, "https://example.com/feed")
        assertEquals("Example Blog", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Q&A: Fish & chips", first.title)
        assertEquals("https://example.com/posts/1", first.url)
        assertEquals("post-1", first.guid)
        assertTrue("content:encoded wins over description", first.contentHtml!!.contains("full"))
        assertEquals("Ada", first.author)
        assertEquals(Instant.parse("2026-09-29T06:30:00Z"), first.published)

        val second = feed.items[1]
        assertEquals("https://example.com/posts/2", second.url)
        assertEquals(Instant.parse("2026-09-28T15:00:00Z"), second.published)
        assertTrue(second.contentHtml!!.startsWith("Teaser"))
    }

    @Test
    fun atom() {
        val atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title type="html">Atom &amp;amp; Eve</title>
              <link rel="self" href="https://a.example/atom.xml"/>
              <link href="https://a.example/"/>
              <entry>
                <title>First</title>
                <link rel="replies" href="https://a.example/1#comments"/>
                <link rel="alternate" href="https://a.example/1"/>
                <id>tag:a.example,2026:1</id>
                <updated>2026-09-01T12:00:00+02:00</updated>
                <author><name>Eve</name><email>eve@a.example</email></author>
                <summary>Sum</summary>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello <b>world</b> &amp; all</p></div></content>
              </entry>
            </feed>
        """.trimIndent()
        val feed = FeedParser.parse(atom, "https://a.example/atom.xml")
        assertEquals("Atom & Eve", feed.title)
        assertEquals("https://a.example/", feed.siteUrl)
        val e = feed.items.single()
        assertEquals("https://a.example/1", e.url)
        assertEquals("tag:a.example,2026:1", e.guid)
        assertEquals("Eve", e.author)
        assertEquals("<p>Hello <b>world</b> &amp; all</p>", e.contentHtml)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), e.published)
    }

    /** Shaped like sive.rs: every short post links to the page holding them all; its own page is its id. */
    @Test
    fun microblogEntriesSharingOneLinkTakeTheirIds() {
        fun entry(n: Int, id: String) = """<entry><id>$id</id><title>Post $n</title><link rel="alternate" href="https://m.example/d"/>
            <content type="html">&lt;p&gt;Short post $n.&lt;/p&gt;</content></entry>"""
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>M</title>
            ${entry(1, "https://m.example/d/1291")}${entry(2, "https://www.m.example/d/1290")}${entry(3, "tag:m.example,2026:3")}${entry(4, "https://elsewhere.example/4")}</feed>"""
        val urls = FeedParser.parse(atom, "https://m.example/feed.xml").items.map { it.url }
        assertEquals(listOf("https://m.example/d/1291", "https://www.m.example/d/1290", "https://m.example/d", "https://m.example/d"), urls)

        val ownLinks = atom.replace(Regex("<link rel=\"alternate\" href=\"https://m.example/d\"/>(\\s*)<content type=\"html\">&lt;p&gt;Short post (\\d)")) {
            "<link rel=\"alternate\" href=\"https://m.example/p/${it.groupValues[2]}\"/>${it.groupValues[1]}<content type=\"html\">&lt;p&gt;Short post ${it.groupValues[2]}"
        }
        assertEquals("a link of its own stays", "https://m.example/p/1", FeedParser.parse(ownLinks, "https://m.example/feed.xml").items.first().url)
    }

    @Test
    fun rdf() {
        val rdf = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://r.example/"><title>RDF site</title><link>https://r.example/</link></channel>
              <item rdf:about="https://r.example/a"><title>A</title><link>https://r.example/a</link>
                <dc:date>2026-09-02T08:00:00Z</dc:date><dc:creator>Rae</dc:creator></item>
            </rdf:RDF>
        """.trimIndent()
        val feed = FeedParser.parse(rdf, "https://r.example/rss")
        assertEquals("RDF site", feed.title)
        val item = feed.items.single()
        assertEquals("https://r.example/a", item.url)
        assertEquals("Rae", item.author)
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), item.published)
    }

    @Test
    fun jsonFeed() {
        val json = """
            {"version":"https://jsonfeed.org/version/1.1","title":"J","home_page_url":"https://j.example/",
             "items":[
               {"id":"1","url":"https://j.example/1","title":"One","content_text":"Para one.\n\nPara <two>.",
                "date_published":"2026-09-03T09:00:00Z","authors":[{"name":"Jo"}]},
               {"id":"2","title":"No url"}
             ]}
        """.trimIndent()
        val feed = FeedParser.parse(json, "https://j.example/feed.json")
        assertEquals("J", feed.title)
        val item = feed.items.single()
        assertEquals("<p>Para one.</p><p>Para &lt;two&gt;.</p>", item.contentHtml)
        assertEquals("Jo", item.author)
    }

    @Test
    fun mediaAndItunesElementsDontOverrideTheArticle() {
        val xml = """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:media="http://search.yahoo.com/mrss/" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel><title>News</title><itunes:title>Podcast name</itunes:title>
                <item>
                  <title>Real title</title>
                  <link>https://n.example/1</link>
                  <content:encoded><![CDATA[<p>Full text</p>]]></content:encoded>
                  <media:content url="https://n.example/1.jpg"><media:credit>Photograph: X</media:credit></media:content>
                  <media:title>Photo title</media:title>
                  <itunes:author>Someone else</itunes:author>
                  <itunes:summary>Podcast summary</itunes:summary>
                  <author>writer@n.example (Writer)</author>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val feed = FeedParser.parse(xml, "https://n.example/feed")
        assertEquals("News", feed.title)
        val item = feed.items.single()
        assertEquals("Real title", item.title)
        assertEquals("<p>Full text</p>", item.contentHtml)
        assertEquals("writer@n.example (Writer)", item.author)
    }

    @Test(expected = FeedParseException::class)
    fun htmlPageIsRejected() {
        FeedParser.parse("<!doctype html><html><body>hi</body></html>", "https://x.example/")
    }

    @Test
    fun looksLikeFeed() {
        assertTrue(FeedParser.looksLikeFeed(rss))
        assertTrue(FeedParser.looksLikeFeed("﻿<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
        assertTrue(FeedParser.looksLikeFeed("{\"version\":\"https://jsonfeed.org/version/1\"}"))
        assertFalse(FeedParser.looksLikeFeed("<html><head><link rel=alternate></head></html>"))
        assertFalse(FeedParser.looksLikeFeed("{\"not\":\"a feed\"}"))
    }

    @Test
    fun unparseableDatesAreNull() {
        assertNull(FeedDates.parse("sometime last week"))
        assertNull(FeedDates.parse(""))
        assertEquals(Instant.parse("2026-09-29T00:00:00Z"), FeedDates.parse("2026-09-29"))
        assertEquals(Instant.parse("2026-09-29T13:00:00Z"), FeedDates.parse("29 Sep 2026 06:00:00 PDT"))
        assertEquals(Instant.parse("2026-09-29T06:00:00Z"), FeedDates.parse("Tue, 29 Sep 26 06:00:00 +0000"))
    }

    @Test
    fun aFeedDeclaringItsOwnEntitiesIsRefused() {
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;\">]>" +
            "<rss><channel><title>&b;</title></channel></rss>"
        assertThrows(FeedParseException::class.java) { FeedParser.parse(bomb, "https://a.example/feed") }
    }

    @Test
    fun aFeedDeclaringPlainEntitiesIsStillRead() {
        val rss = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY nbsp \"&#160;\">]>" +
            "<rss><channel><title>Old CMS</title><item><title>A</title><link>https://a.example/a</link></item></channel></rss>"
        assertEquals("Old CMS", FeedParser.parse(rss, "https://a.example/feed").title)
    }

    @Test
    fun anItemLinkJavasUriRefusesIsStillMadeAbsolute() {
        val rss = "<rss><channel><title>T</title><item><title>Q</title><link>/search?q=a|b</link></item></channel></rss>"
        assertEquals("https://a.example/search?q=a%7Cb", FeedParser.parse(rss, "https://a.example/feed").items.single().url)
    }
}
