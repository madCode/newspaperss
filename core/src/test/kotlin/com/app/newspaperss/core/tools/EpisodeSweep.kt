package com.app.newspaperss.core.tools

import com.app.newspaperss.core.episodes.EpisodeDetector
import com.app.newspaperss.core.episodes.EpisodeFeed
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Not a test: runs the episode detector over a folder of saved feeds (one .xml file each) and
 * writes every post's verdict as JSON, for reading through by hand. For sweeping a reading list
 * locally; its results stay out of the repository.
 *
 *   ./gradlew :core:episodeSweep --args="/path/to/feeds /path/to/out.json"
 *
 * It runs in core/, so give absolute paths.
 */
fun main(args: Array<String>) {
    val files = File(args[0]).listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
    val json = buildJsonArray {
        for (file in files) {
            // One broken feed shouldn't end a sweep of a hundred.
            val (feed, verdicts) = runCatching { EpisodeFeed.parse(file.readText()).let { it to EpisodeDetector.classify(it) } }
                .onFailure { println("${file.name}: $it") }.getOrNull() ?: continue
            for (post in feed.posts) {
                val v = verdicts.getValue(post.guid)
                add(buildJsonObject {
                    put("file", file.name); put("feed", feed.title); put("guid", post.guid); put("title", post.title); put("link", post.link)
                    put("detected", v.kind.label); put("pair", v.pair); put("why", v.why); put("signals", v.signals.toString())
                    put("audio", v.signals.audio); put("words", v.signals.words)
                    put("start", post.text.take(400)); put("end", post.text.takeLast(200))
                })
            }
        }
    }
    File(args[1]).writeText(json.toString())
    println("${json.size} posts from ${files.size} feeds")
}
