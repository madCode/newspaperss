package com.app.newspaperss.core.tools

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ExtractInput
import com.app.newspaperss.core.extract.ExtractedArticle
import com.app.newspaperss.core.extract.PlatformCorpusTest
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.LinkPosts
import com.app.newspaperss.core.feed.Opml
import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.io.File

/**
 * Not a test: a developer tool that runs the extractor over the newest posts of every feed in an
 * OPML file, as the app would, to find what to fix and what to add to the platform corpus (see
 * [PlatformCorpus]). Each result goes to core/build/sweep, with a summary.tsv listing what looks
 * wrong in each: no text, page furniture, links to missing ids, empty blocks, a page that failed.
 * The OPML and the results stay out of the repository: they are someone's own reading.
 *
 *   ./gradlew :core:extractionSweep --args="/path/to/feeds.opml 2"
 */
fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
    val feeds = Opml.parse(File(args[0]).readText())
    val perFeed = args.getOrNull(1)?.toInt() ?: 2
    val out = File("build/sweep").apply { deleteRecursively(); mkdirs() }
    val http = OkHttpHttpClient()
    val extractor = ArticleExtractor(http)
    val gate = Semaphore(8)
    val rows = feeds.map { feed ->
        async {
            gate.withPermit {
                val parsed = runCatching {
                    val response = http.getFeed(feed.url)
                    check(response.isSuccessful) { "feed answered ${response.code}" }
                    FeedParser.parse(response.body, response.finalUrl, response.truncated)
                }.getOrElse { return@withPermit listOf("${feed.url}\t\t\t\tfeed: ${it.message}") }
                parsed.items.take(perFeed).map { item ->
                    val site = parsed.siteUrl ?: feed.url
                    // As FeedSync stores a link post: the story is the article, the item its pitch.
                    val story = LinkPosts.storyUrl(item.url, item.contentHtml, site)
                    val input = if (story != null) ExtractInput(story, item.title, item.contentHtml, item.author, feedUrl = item.url)
                    else ExtractInput(item.url, item.title, item.contentHtml, item.author)
                    val article = runCatching { extractor.extract(input) }
                        .getOrElse { return@map "${feed.url}\t${input.url}\t\t\textract: $it" }
                    val name = input.url.substringAfter("//").replace(Regex("[^A-Za-z0-9.-]+"), "-").take(80)
                    File(out, "$name.html").writeText("<!-- ${input.url}\n${article.copy(html = "")} -->\n${article.html}")
                    val source = if (article.usedFeedContent) "feed" else "page"
                    "${feed.url}\t${input.url}\t${article.wordCount}\t$source\t${problems(article).joinToString("; ")}"
                }
            }
        }
    }.awaitAll().flatten()
    File(out, "summary.tsv").writeText("feed\tarticle\twords\ttext from\tproblems\n" + rows.joinToString("\n") + "\n")
    println("${rows.size} articles, ${rows.count { it.substringAfterLast('\t').isNotBlank() }} with problems: ${out.absolutePath}/summary.tsv")
}

private fun problems(article: ExtractedArticle): List<String> {
    val doc = Jsoup.parseBodyFragment(article.html)
    val ids = doc.select("[id]").map { it.id() }.toSet()
    return listOfNotNull(
        "no text".takeIf { article.wordCount == 0 && article.imageUrls.isEmpty() },
        article.pageFailure?.let { "page: $it" },
        "blocked".takeIf { article.pageBlocked },
        "paid".takeIf { article.paidPost },
        "not the story".takeIf { article.notTheStory },
        doc.select("a[href^=#]").map { it.attr("href").drop(1) }.filter { it !in ids }.takeIf { it.isNotEmpty() }?.let { "links to missing ids: $it" },
        doc.select("p, figure, blockquote, li, h1, h2, h3, h4, h5, h6").filter { it.text().isBlank() && it.select("img, br, hr").isEmpty() }
            .takeIf { it.isNotEmpty() }?.let { "${it.size} empty blocks" },
        doc.select("p, li, h2, h3, h4, h5, a, figcaption, div").map { it.text().trim() }
            .filter { it.split(' ').size <= 40 && PlatformCorpusTest.FURNITURE.containsMatchIn(it) }.distinct()
            .takeIf { it.isNotEmpty() }?.let { "furniture: $it" },
    )
}
