package com.app.newspaperss.core.episodes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Runs the prototype [EpisodeDetector] over the episode corpus (collected by
 * [com.app.newspaperss.core.tools.EpisodeSnapshot], labelled by hand in each feed's labels.json)
 * and checks it against the research plan's bar: no article classed as anything else, and at
 * least 9 in 10 notes and transcripts caught. Results are tallied per shape, so a gain in one
 * shape can't hide a loss in another, and written to core/build/episode-corpus/results.json.
 */
class EpisodeCorpusTest {
    class Label(val feed: String, val guid: String, val title: String, val kind: Kind, val shape: String, val pair: String?, val why: String)

    class Result(val label: Label, val verdict: Verdict) {
        val right get() = verdict.kind == label.kind && verdict.pair == label.pair
    }

    companion object {
        private val root = File(EpisodeCorpusTest::class.java.getResource("/episodes")!!.toURI())

        // Read once for every test: JUnit makes a new instance per test method.
        private val results: List<Result> by lazy { read() }

        private fun read(): List<Result> = root.listFiles().orEmpty().filter { File(it, "labels.json").exists() }.sortedBy { it.name }.flatMap { dir ->
            val xml = GZIPInputStream(File(dir, "feed.xml.gz").inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            val feed = EpisodeFeed.parse(xml)
            val verdicts = EpisodeDetector.classify(feed)
            val labels = Json.parseToJsonElement(File(dir, "labels.json").readText()).jsonObject
            val items = labels.getValue("items").jsonObject
            check(items.size == feed.posts.size && items.keys == feed.posts.map { it.guid }.toSet()) { "${dir.name}: labels.json doesn't label exactly the feed's items" }
            feed.posts.map { post ->
                val l = items.getValue(post.guid).jsonObject
                fun s(k: String) = l[k]?.jsonPrimitive?.contentOrNull
                Result(Label(dir.name, post.guid, post.title, Kind.of(s("kind")!!), s("shape")!!, s("pair"), s("why")!!), verdicts.getValue(post.guid))
            }
        }
    }

    @Test
    fun corpusIsLabelled() {
        assertTrue("the corpus is empty", results.isNotEmpty())
        write()
        print(report())
    }

    @Test
    fun noArticleIsClassedAsAnythingElse() {
        assertTrue("the corpus is empty", results.isNotEmpty())
        val wrong = results.filter { it.label.kind == Kind.ARTICLE && it.verdict.kind != Kind.ARTICLE }
        assertTrue("articles misclassed:\n" + wrong.joinToString("\n") { line(it) }, wrong.isEmpty())
    }

    @Test
    fun catchesNineInTenNotesTranscriptsAndAudioVersions() {
        for (kind in listOf(Kind.NOTES, Kind.TRANSCRIPT, Kind.AUDIO_VERSION)) {
            val of = results.filter { it.label.kind == kind }
            assertTrue("no $kind in the corpus", of.isNotEmpty())
            val caught = of.count { it.verdict.kind == kind }
            assertTrue("$kind: $caught of ${of.size} caught:\n" + of.filter { it.verdict.kind != kind }.joinToString("\n") { line(it) }, caught * 10 >= of.size * 9)
        }
    }

    @Test
    fun findsPairsAndInventsNone() {
        val wrong = results.filter { it.verdict.pair != it.label.pair }
        assertTrue("pairs missed or invented:\n" + wrong.joinToString("\n") { line(it) + ", paired with ${it.verdict.pair}" }, wrong.isEmpty())
    }

    private fun line(r: Result) = "  ${r.label.feed} \"${r.label.title.take(50)}\" (${r.label.shape}): labelled ${r.label.kind.label}, " +
        "detected ${r.verdict.kind.label} because ${r.verdict.why} [${r.verdict.signals}]"

    private fun report() = buildString {
        appendLine("Episode corpus: ${results.size} posts, ${results.count { it.right }} right")
        results.groupBy { it.label.shape }.toSortedMap().forEach { (shape, rs) ->
            appendLine("  $shape: ${rs.count { it.right }}/${rs.size}")
            rs.filter { !it.right }.forEach { appendLine("  " + line(it)) }
        }
    }

    private fun write() {
        // core/build, from core/build/resources/test/episodes.
        val out = File(root, "../../../episode-corpus").canonicalFile.apply { mkdirs() }
        val json = buildJsonArray {
            results.forEach { r ->
                add(buildJsonObject {
                    put("feed", r.label.feed); put("guid", r.label.guid); put("title", r.label.title)
                    put("shape", r.label.shape); put("label", r.label.kind.label); put("labelPair", r.label.pair); put("labelWhy", r.label.why)
                    put("detected", r.verdict.kind.label); put("pair", r.verdict.pair); put("why", r.verdict.why)
                    put("signals", r.verdict.signals.toString()); put("audio", r.verdict.signals.audio); put("right", r.right)
                })
            }
        }
        File(out, "results.json").writeText(json.toString())
    }
}
