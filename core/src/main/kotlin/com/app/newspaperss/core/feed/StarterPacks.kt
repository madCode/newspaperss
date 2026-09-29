package com.app.newspaperss.core.feed

data class StarterFeed(val title: String, val url: String)

data class StarterPack(val name: String, val blurb: String, val feeds: List<StarterFeed>)

/**
 * Well-known public feeds to get a first edition going without typing any
 * addresses. Each was checked to serve a feed; the reader can remove any.
 */
object StarterPacks {
    val all = listOf(
        StarterPack(
            "News", "The world, from several angles",
            listOf(
                StarterFeed("BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
                StarterFeed("The Guardian: World", "https://www.theguardian.com/world/rss"),
                StarterFeed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
                StarterFeed("ProPublica", "https://www.propublica.org/feeds/propublica/main"),
                StarterFeed("Rest of World", "https://restofworld.org/feed/latest/"),
            ),
        ),
        StarterPack(
            "Science", "Discoveries, explained well",
            listOf(
                StarterFeed("Quanta Magazine", "https://www.quantamagazine.org/feed/"),
                StarterFeed("Nautilus", "https://nautil.us/feed/"),
                StarterFeed("NASA", "https://www.nasa.gov/news-release/feed/"),
                StarterFeed("Ars Technica: Science", "https://feeds.arstechnica.com/arstechnica/science"),
                StarterFeed("ScienceDaily", "https://www.sciencedaily.com/rss/top.xml"),
            ),
        ),
        StarterPack(
            "Technology", "What's changing and why it matters",
            listOf(
                StarterFeed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
                StarterFeed("MIT Technology Review", "https://www.technologyreview.com/feed/"),
                StarterFeed("The Verge", "https://www.theverge.com/rss/index.xml"),
                StarterFeed("Wired", "https://www.wired.com/feed/rss"),
                StarterFeed("Hacker News: Best", "https://hnrss.org/best"),
            ),
        ),
        StarterPack(
            "Essays & ideas", "Long reads worth your time",
            listOf(
                StarterFeed("Aeon", "https://aeon.co/feed.rss"),
                StarterFeed("Psyche", "https://psyche.co/feed"),
                StarterFeed("The Marginalian", "https://www.themarginalian.org/feed/"),
                StarterFeed("Longreads", "https://longreads.com/feed/"),
                StarterFeed("The New Yorker", "https://www.newyorker.com/feed/everything"),
            ),
        ),
        StarterPack(
            "Culture & curiosities", "Arts, history and the wonderfully odd",
            listOf(
                StarterFeed("The Guardian: Culture", "https://www.theguardian.com/culture/rss"),
                StarterFeed("Smithsonian", "https://www.smithsonianmag.com/rss/latest_articles/"),
                StarterFeed("Atlas Obscura", "https://www.atlasobscura.com/feeds/latest"),
                StarterFeed("The Atlantic", "https://www.theatlantic.com/feed/all/"),
            ),
        ),
    )
}
