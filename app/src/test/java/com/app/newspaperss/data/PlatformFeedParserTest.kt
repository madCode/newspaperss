package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The :core parser tests run on kxml2; the app runs on Android's own XmlPullParser. This runs
 * the parser through the platform's, for the cases where they could differ: relaxed mode for HTML
 * entities, namespaces, and CDATA.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PlatformFeedParserTest {
    @Test
    fun rssWithHtmlEntitiesNamespacesAndCdata() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
              <channel><title>Caf&eacute; &amp; Co</title><link>https://example.com/</link>
                <item>
                  <title>A&nbsp;story</title>
                  <link>https://example.com/1</link>
                  <content:encoded><![CDATA[<p>Full text</p>]]></content:encoded>
                  <media:content url="https://example.com/1.jpg"><media:title>Photo</media:title></media:content>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val feed = FeedParser.parse(rss, "https://example.com/feed")
        val item = feed.items.single()
        assertEquals("https://example.com/1", item.url)
        assertEquals("<p>Full text</p>", item.contentHtml)
        assertTrue(item.title.startsWith("A") && item.title.endsWith("story"))
    }

    @Test
    fun atomWithXhtmlContent() {
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>A</title>
              <entry><title>E</title><link href="https://a.example/1"/><id>1</id>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hi <b>there</b></p></div></content>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals("<p>Hi <b>there</b></p>", FeedParser.parse(atom, "https://a.example/feed").items.single().contentHtml)
    }
}
