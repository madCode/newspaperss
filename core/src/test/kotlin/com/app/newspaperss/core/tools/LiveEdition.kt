package com.app.newspaperss.core.tools

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Budget
import com.app.newspaperss.core.edition.Candidate
import com.app.newspaperss.core.edition.EditionPlanner
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.edition.PlanRules
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.ExtractInput
import com.app.newspaperss.core.feed.FeedItem
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.feed.StarterFeed
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Not a test: a developer tool that builds a real edition from the live starter-pack feeds with
 * the same planner, extractor and EPUB writer the app uses (no images, no cover), so its output
 * can be read and run through epubcheck. `./gradlew :core:liveEdition` writes
 * core/build/live-edition.epub; `--args="out.epub https://…/feed …"` uses those feeds instead.
 * It needs the network.
 */
fun main(args: Array<String>) = runBlocking {
    val out = File(args.firstOrNull() ?: "build/live-edition.epub")
    val http = OkHttpHttpClient()
    val extractor = ArticleExtractor(http)
    // Any further arguments are feed URLs to use instead of the starter packs.
    val feeds = args.drop(1).map { StarterFeed(it.substringAfter("//").substringBefore('/'), it) }
        .ifEmpty { StarterPacks.all.flatMap { it.feeds } }
    val items = mutableMapOf<String, Pair<String, FeedItem>>()
    for (feed in feeds) {
        val response = runCatching { http.get(feed.url) }.getOrNull()
        if (response == null || !response.isSuccessful) { println("skip ${feed.title}: unreachable"); continue }
        val parsed = runCatching { FeedParser.parse(response.body, response.finalUrl) }.getOrNull() ?: continue
        parsed.items.take(3).forEach { items["${feed.title}|${it.guid}"] = feed.title to it }
    }
    val candidates = items.map { (id, v) -> Candidate(id, v.first, v.second.published) }
    val rules = PlanRules(Budget.Minutes(40.0), maxPerSource = 1)
    val ordered = EditionPlanner.order(candidates, feeds.map { it.title }, Ordering.TAKE_TURNS)
    val picked = EditionPlanner.fill<EditionArticle>(ordered, rules, { it.minutes }) { c ->
        val (source, item) = items.getValue(c.id)
        val e = extractor.extract(ExtractInput(item.url, item.title, item.contentHtml, item.author))
        println("${"%-26s".format(source)} ${"%4d".format(e.wordCount)} words  ${if (e.usedFeedContent) "feed" else "page"}  ${e.title}")
        EditionArticle(
            title = e.title, sourceTitle = source, url = item.url, bodyHtml = e.html,
            minutes = ReadingTime.minutes(e.wordCount), author = e.author,
            published = item.published?.atZone(ZoneOffset.UTC)?.toLocalDate(), note = e.note, language = e.language,
        )
    }
    out.parentFile?.mkdirs()
    out.outputStream().use {
        EpubWriter.write(EditionDoc("Live Test Edition", LocalDate.now(), "urn:uuid:${UUID.randomUUID()}", listOf(EditionSection(null, picked))), it)
    }
    println("wrote ${picked.size} articles, ${"%.0f".format(picked.sumOf { it.minutes })} min, to ${out.absolutePath}")
}
