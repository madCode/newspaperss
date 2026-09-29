package com.app.newspaperss.core.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class OpmlTest {
    @Test
    fun parsesFoldersAndSkipsDuplicates() {
        val opml = """
            <?xml version="1.0"?>
            <opml version="1.0"><head><title>Subs</title></head><body>
              <outline text="Loose" xmlUrl="https://a.example/feed"/>
              <outline text="Science">
                <outline text="B &amp; C" type="rss" xmlUrl="https://b.example/rss" htmlUrl="https://b.example"/>
                <outline title="Again" xmlUrl="https://a.example/feed"/>
              </outline>
            </body></opml>
        """.trimIndent()
        assertEquals(
            listOf(
                OpmlFeed("https://a.example/feed", "Loose", null),
                OpmlFeed("https://b.example/rss", "B & C", "Science"),
            ),
            Opml.parse(opml),
        )
    }

    @Test
    fun roundTrips() {
        val feeds = listOf(
            OpmlFeed("https://a.example/feed?x=1&y=2", "A \"quoted\" <name>", null),
            OpmlFeed("https://b.example/rss", "B", "Arts & Letters"),
        )
        assertEquals(feeds, Opml.parse(Opml.write("My feeds", feeds)))
    }
}
