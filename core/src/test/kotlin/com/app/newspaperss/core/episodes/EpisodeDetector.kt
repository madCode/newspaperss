package com.app.newspaperss.core.episodes

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * What a post is, as the paper would treat it (docs/research/podcast-episodes.md). A write-up
 * or a narrated article is an [ARTICLE]: it reads well and is printed as one.
 */
enum class Kind(val label: String) {
    ARTICLE("article"),
    /** An episode's notes: listed under Episodes, not printed. */
    NOTES("notes"),
    /** An episode's words in full. */
    TRANSCRIPT("transcript"),
    /** A stub carrying the audio of a written post beside it. */
    AUDIO_VERSION("audio-version"),
    ;

    companion object {
        fun of(label: String) = entries.first { it.label == label }
    }
}

data class Enclosure(val url: String, val type: String, val length: Long)

/** One feed item, with what detection reads from it. */
data class EpisodePost(
    val guid: String,
    val title: String,
    val link: String,
    val html: String,
    val enclosures: List<Enclosure>,
    /** itunes:duration in seconds, when the feed gives one. */
    val duration: Int?,
    /** podcast:transcript links. */
    val transcripts: List<String>,
) {
    val text: String by lazy { Jsoup.parseBodyFragment(html).text() }
    val words: Int by lazy { WORD.findAll(text).count() }
    val body: Element by lazy { Jsoup.parseBodyFragment(html).body() }

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}]+")
    }
}

/**
 * A feed's posts in the order it lists them.
 *
 * @property podcast the channel says it is one, with Apple Podcasts' tags.
 */
data class EpisodeFeed(val title: String, val posts: List<EpisodePost>, val podcast: Boolean = false) {
    companion object {
        fun parse(xml: String): EpisodeFeed {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = doc.select("item").ifEmpty { doc.select("entry") }
            val title = doc.selectFirst("channel > title, feed > title")?.text().orEmpty()
            val podcast = doc.select("channel > itunes|category, channel > itunes|type").isNotEmpty()
            // A post is known by its guid; a blank or repeated one would merge two posts' verdicts.
            val seen = mutableSetOf<String>()
            val posts = items.mapIndexed { i, item -> post(item).let { if (it.guid.isBlank() || !seen.add(it.guid)) it.copy(guid = "${it.guid}#$i") else it } }
            return EpisodeFeed(title, posts, podcast)
        }

        private fun post(item: Element): EpisodePost {
            fun child(vararg names: String) = names.firstNotNullOfOrNull { n -> item.children().firstOrNull { it.tagName().equals(n, true) } }
            val html = listOf("content:encoded", "content", "description", "summary", "itunes:summary")
                .mapNotNull { child(it)?.wholeText()?.trim() }
                .maxByOrNull { it.length }.orEmpty()
            // Atom lists several links; the post's own is the one without a rel, or rel="alternate".
            val link = item.children().filter { it.tagName() == "link" && it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }
                .firstOrNull()?.let { it.attr("href").ifBlank { it.text() } }.orEmpty()
            val enclosures = item.children().filter { it.tagName() == "enclosure" || it.tagName() == "media:content" || (it.tagName() == "link" && it.attr("rel") == "enclosure") }
                .map { Enclosure(it.attr("url").ifBlank { it.attr("href") }, it.attr("type"), it.attr("length").toLongOrNull() ?: 0) }
                .filter { it.url.isNotBlank() }
            return EpisodePost(
                guid = child("guid", "id")?.text()?.ifBlank { null } ?: link,
                title = child("title")?.text().orEmpty(),
                link = link,
                html = html,
                enclosures = enclosures,
                duration = child("itunes:duration")?.text()?.let(::seconds),
                transcripts = item.children().filter { it.tagName() == "podcast:transcript" }.map { it.attr("url") },
            )
        }

        private fun seconds(s: String): Int? {
            val parts = s.trim().split(':').map { it.toIntOrNull() ?: return null }
            return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
        }
    }
}

/** A post's kind, why, and the other post of its episode if there is one. */
data class Verdict(val kind: Kind, val pair: String?, val why: String, val signals: Signals)

data class Signals(
    /** The post's own audio: an attached file or a player of the host's. */
    val audio: String?,
    /** A player for someone's episode, embedded in the post: often a mention, not the post's own. */
    val embed: String?,
    val timestamps: Int,
    /**
     * Turns of a conversation: paragraphs opening with a speaker's name and a colon, a speaker's
     * name on a line of its own, or a question set as a heading.
     */
    val turns: Int,
    val transcriptTitle: Boolean,
    val transcriptHeading: Boolean,
    val spotifyEpisode: Boolean,
    val music: Boolean,
    val video: Boolean,
    val words: Int,
) {
    override fun toString() = buildList {
        audio?.let { add("audio: $it") }
        embed?.let { add("episode embed: $it") }
        if (timestamps > 0) add("$timestamps timestamps")
        if (turns > 0) add("$turns speaker turns")
        if (transcriptTitle) add("\"transcript\" in title")
        if (transcriptHeading) add("transcript heading")
        if (spotifyEpisode) add("Spotify episode")
        if (music) add("music embed")
        if (video) add("video embed")
        add("$words words")
    }.joinToString(", ")
}

/**
 * The prototype classifier: the signals measured on a real reading list, combined so that doubt
 * falls on the side of an article. Wrongly dropping reading is worse than printing one stub.
 */
object EpisodeDetector {
    /** Notes run up to about 2,500 words when they carry sponsors, links and timestamps. */
    private const val NOTES_WORDS = 700
    private const val NOTES_WORDS_WITH_TIMESTAMPS = 3_000
    private const val TIMESTAMPS = 10
    /** Shorter than this, a post can only be a transcript if its title says so. */
    private const val TRANSCRIPT_WORDS = 3_000
    /** A "Transcript" heading over a paid post's opening: shorter, but still a transcript. */
    private const val TRANSCRIPT_HEADING_WORDS = 2_500
    private const val STUB_WORDS = 300
    private const val TURNS = 20
    private const val UPLOAD = "substack-upload:"

    private val AUDIO_FILE = Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav)(\\?|$)", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("(?<![\\p{Nd}:])(?:\\p{Nd}{1,2}:)?\\p{Nd}{1,2}:\\p{Nd}{2}(?![\\p{Nd}:])")
    /** A timestamp as a list of them gives it: opening a line, or in brackets. A clock time in a sentence isn't one. */
    private val LISTED = Regex("^\\W{0,3}${TIMESTAMP.pattern}|[\\[(]${TIMESTAMP.pattern}[\\])]")
    private val TRANSCRIPT = Regex("\\btranscripts?\\b|\\btranscription\\b", RegexOption.IGNORE_CASE)
    private val PLAYERS = listOf(
        "anchor.fm", "podcasters.spotify.com", "creators.spotify.com", "embed.podcasts.apple.com", "art19.com",
        "megaphone.fm", "simplecast.com", "buzzsprout.com", "transistor.fm", "libsyn.com", "omny.fm", "captivate.fm",
        "podbean.com", "acast.com", "pinecast.com", "player.fm", "audioboom.com", "spreaker.com",
    )
    /** Podcast hosts and the trackers in front of them, as they appear in an audio file's address. */
    private val HOSTS = listOf(
        "substack.com/feed/podcast", UPLOAD, "libsyn.com", "art19.com", "megaphone.fm", "simplecast", "podtrac.com", "buzzsprout.com",
        "transistor.fm", "anchor.fm", "d3ctxlq1ktw2nl.cloudfront.net", "acast.com", "blubrry.com", "omny.fm", "omnycontent.com",
        "captivate.fm", "podbean.com", "spreaker.com", "audioboom.com", "pdst.fm", "swap.fm", "chrt.fm", "pscrb.fm", "npr.org",
    )
    private val MUSIC = listOf("soundcloud.com", "bandcamp.com", "music.apple.com")
    private val VIDEO = listOf("youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com")
    private val TURN = Regex("^([\\p{Lu}][\\p{L}.'’-]*(?: [\\p{Lu}][\\p{L}.'’-]*){0,2})\\s?[:：]\\s")
    private val NAME_LINE = Regex("[\\p{Lu}][\\p{L}.'’-]*(?: [\\p{Lu}][\\p{L}.'’-]*){0,3}")
    /** Labels and buttons that look like a speaker's name. */
    private val NOT_NAMES = setOf(
        "update", "note", "source", "sources", "editor", "correction", "disclosure", "photo", "credit", "image", "caption",
        "share", "subscribe", "advertisement", "listen", "watch", "read more", "related", "transcript",
    )
    private val EPISODE_TITLE = Regex("\\b(podcast|episode|ep)\\b", RegexOption.IGNORE_CASE)
    private val STOPWORDS = setOf("the", "and", "with", "for", "from", "that", "this", "what", "how", "why", "are", "was", "you", "our", "your")
    private val STUB_CUES = Regex("\\b(listen to this (article|post|essay|story)|(audio|narrated) version of|audio|narrated|voiceover|listen|podcast|version|read by)\\b", RegexOption.IGNORE_CASE)

    fun signals(post: EpisodePost): Signals {
        val embeds = post.body.select("iframe[src], embed[src], a[href]").map { it.attr("src").ifBlank { it.attr("href") } }
        val iframes = post.body.select("iframe[src], embed[src], audio[src], audio source[src]").map { it.attr("src") }
        // Video files are left out: a blog's clip is attached the same way as a show's episode.
        val enclosure = post.enclosures.firstOrNull { it.type.startsWith("audio") || (it.type.isBlank() && AUDIO_FILE.containsMatchIn(it.url)) }
        // Substack's own player, for audio uploaded into a post rather than attached to it.
        val upload = post.body.selectFirst("[data-component-name=AudioPlaceholder]")?.attr("data-attrs")
            ?.let { Regex("\"mediaUploadId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let { "$UPLOAD$it" }
        val player = iframes.firstOrNull { src -> AUDIO_FILE.containsMatchIn(src) } ?: upload
        val embed = iframes.firstOrNull { src -> PLAYERS.any { it in src } || ("spotify.com" in src && ("/episode/" in src || "/show/" in src)) }
        val spotify = embeds.filter { "open.spotify.com" in it }
        val headings = post.body.select("h1, h2, h3, h4, h5, h6, strong, b, p").map { it.text().trim() }.filter { it.length <= 30 }
        return Signals(
            audio = enclosure?.url ?: player,
            embed = embed,
            timestamps = timestamps(post),
            turns = turns(post),
            transcriptTitle = TRANSCRIPT.containsMatchIn(post.title),
            transcriptHeading = headings.any { TRANSCRIPT.containsMatchIn(it) && it.split(' ').size <= 4 },
            spotifyEpisode = spotify.any { "/episode/" in it || "/show/" in it },
            music = iframes.any { src -> MUSIC.any { it in src } || ("spotify.com" in src && listOf("/track/", "/album/", "/playlist/", "/artist/").any { it in src }) },
            video = iframes.any { src -> VIDEO.any { it in src } },
            words = post.words,
        )
    }

    private fun timestamps(post: EpisodePost): Int {
        // Lines, not elements: show notes often run a list of times through one paragraph with <br>s.
        val body = post.body.clone()
        body.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return body.select("p, li:not(:has(p)), h1, h2, h3, h4, h5, h6, div:not(:has(p, li, div))")
            .sumOf { el -> el.wholeText().lines().sumOf { LISTED.findAll(it.trim()).count() } }
    }

    private fun turns(post: EpisodePost): Int {
        val paragraphs = post.body.select("p").map { it.text().trim() }
        val named = paragraphs.count { p -> TURN.find(p)?.groupValues?.get(1)?.lowercase()?.let { it !in NOT_NAMES } == true }
        // "Dwarkesh Patel" alone above what he says: a name only once it recurs.
        val nameLines = paragraphs.filter { NAME_LINE.matches(it) && it.lowercase() !in NOT_NAMES }.groupingBy { it }.eachCount().values.filter { it >= 3 }.sum()
        val questions = post.body.select("h2, h3, h4, h5, h6").count { it.text().trim().endsWith("?") }
        // An interviewer's questions in bold, whole paragraphs of them.
        val bold = post.body.select("p").count { p -> p.text().length > 40 && p.select("strong, b").text().length == p.text().length }
        return named + nameLines + questions + bold
    }

    fun classify(feed: EpisodeFeed): Map<String, Verdict> {
        val signals = feed.posts.associate { it.guid to signals(it) }
        // A blog's own mp3 is as often a song or a session as an episode; a podcast host's isn't.
        val podcast = feed.podcast || feed.posts.count { signals.getValue(it.guid).audio != null } * 2 >= feed.posts.size
        val audio = feed.posts.associate { p -> p.guid to signals.getValue(p.guid).audio?.takeIf { podcast || HOSTS.any { h -> h in it } } }
        val verdicts = mutableMapOf<String, Verdict>()

        // The same audio on two posts: notes and transcript, or a magazine's episode and the stories it reads.
        val shared = feed.posts.filter { audio[it.guid] != null }.groupBy { audioKey(audio.getValue(it.guid)!!) }.values.filter { it.size > 1 }
        // A magazine attaching each episode to the stories it reads: one post of the group is titled
        // as the episode, and at least two stories aren't. A rerun ("Encore episode: X" beside X)
        // is only two posts.
        val readsStories = shared.any { group ->
            group.none { signals.getValue(it.guid).transcriptTitle || it.words >= TRANSCRIPT_WORDS } &&
                group.count { EPISODE_TITLE.containsMatchIn(it.title) } == 1 && group.count { !EPISODE_TITLE.containsMatchIn(it.title) } >= 2
        }
        for (posts in shared) {
            val readable = posts.maxBy { it.words }
            val s = signals.getValue(readable.guid)
            if (!s.transcriptTitle && readable.words < TRANSCRIPT_WORDS) continue
            verdicts[readable.guid] = Verdict(Kind.TRANSCRIPT, other(posts, readable), "shares its audio with a shorter post", s)
            posts.filter { it !== readable }.forEach { verdicts[it.guid] = Verdict(Kind.NOTES, readable.guid, "shares its audio with a longer post", signals.getValue(it.guid)) }
        }

        // A transcript posted without audio, named after its episode's post.
        for (p in feed.posts) {
            val s = signals.getValue(p.guid)
            if (p.guid in verdicts || !s.transcriptTitle || audio[p.guid] != null) continue
            val episode = feed.posts.filter { it.guid !in verdicts && audio[it.guid] != null }
                .mapNotNull { e -> overlap(p.title, e.title)?.let { e to it } }.maxByOrNull { it.second }?.first ?: continue
            // The episode's post stays what it is otherwise (notes, or a post that opens with the episode).
            val own = withAudio(feed, episode, signals, audio)
            if (own.pair != null) continue
            verdicts[p.guid] = Verdict(Kind.TRANSCRIPT, episode.guid, "\"transcript\" in its title, named after an episode post", s)
            verdicts[episode.guid] = own.copy(pair = p.guid)
        }

        // The video an episode post carries, embedded again in a newsletter about the episode.
        val episodeVideos = feed.posts.filter { audio[it.guid] != null }.flatMap { p -> videos(p).map { it to p } }.toMap()

        for (p in feed.posts) {
            if (p.guid in verdicts) continue
            val s = signals.getValue(p.guid)
            val videos = videos(p)
            verdicts[p.guid] = when {
                audio[p.guid] != null && readsStories && !EPISODE_TITLE.containsMatchIn(p.title) ->
                    Verdict(Kind.ARTICLE, null, "a story carrying the episode that reads it", s)
                audio[p.guid] != null -> withAudio(feed, p, signals, audio)
                videos.isNotEmpty() && videos.all { it in episodeVideos } -> {
                    val episodes = videos.mapNotNull { episodeVideos[it] }.distinct()
                    Verdict(Kind.NOTES, episodes.singleOrNull()?.guid, "embeds the video of episode posts in the feed, with no audio of its own", s)
                }
                s.transcriptTitle && s.words >= TRANSCRIPT_WORDS -> Verdict(Kind.TRANSCRIPT, null, "\"transcript\" in its title", s)
                s.embed != null && s.turns >= TURNS && s.words >= TRANSCRIPT_WORDS ->
                    Verdict(Kind.TRANSCRIPT, null, "an episode player and $TURNS or more speaker turns", s)
                s.embed != null && s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS ->
                    Verdict(Kind.NOTES, null, "an episode player with a list of timestamps", s)
                s.video && s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS ->
                    Verdict(Kind.NOTES, null, "a video with a list of timestamps", s)
                else -> Verdict(Kind.ARTICLE, null, when {
                    s.audio != null -> "audio, but not from a podcast host, in a feed that isn't a podcast"
                    s.embed != null -> "only someone's episode embedded, without timestamps"
                    s.music -> "music, not an episode"
                    else -> "no episode audio"
                }, s)
            }
        }
        // A pair is a pair from both ends: the written post knows its audio stub.
        for ((guid, v) in verdicts.toMap()) {
            val other = v.pair ?: continue
            verdicts[other]?.takeIf { it.pair == null }?.let { verdicts[other] = it.copy(pair = guid) }
        }
        return verdicts
    }

    private fun withAudio(feed: EpisodeFeed, p: EpisodePost, signals: Map<String, Signals>, audio: Map<String, String?>): Verdict {
        val s = signals.getValue(p.guid)
        if (s.transcriptTitle) return Verdict(Kind.TRANSCRIPT, null, "audio, and \"transcript\" in its title", s)
        if (s.transcriptHeading && s.words >= TRANSCRIPT_HEADING_WORDS) return Verdict(Kind.TRANSCRIPT, null, "audio, and a transcript heading", s)
        // Turns alone make a written interview look like one; with the episode's audio it is one.
        if (s.turns >= TURNS && s.words >= TRANSCRIPT_WORDS) return Verdict(Kind.TRANSCRIPT, null, "audio, and $TURNS or more speaker turns", s)
        if (s.words < STUB_WORDS) {
            chapter(feed, p, audio)?.let { return Verdict(Kind.AUDIO_VERSION, it.guid, "an audio stub beside a written post of the same title", s) }
            linked(feed, p, audio)?.let { return Verdict(Kind.AUDIO_VERSION, it.guid, "an audio stub linking to a written post in the feed", s) }
        }
        // Audio uploaded into a post is a reading or a song as often as a show: not an episode on its own.
        if (s.audio!!.startsWith(UPLOAD)) return Verdict(Kind.ARTICLE, null, "audio uploaded into the post, not a show's episode", s)
        if (notesSized(s)) return Verdict(Kind.NOTES, null, if (s.timestamps >= TIMESTAMPS) "audio, timestamps and under ${NOTES_WORDS_WITH_TIMESTAMPS} words" else "audio and under $NOTES_WORDS words", s)
        return Verdict(Kind.ARTICLE, null, "audio, with a long write-up", s)
    }

    private fun notesSized(s: Signals) = s.words < NOTES_WORDS || (s.timestamps >= TIMESTAMPS && s.words < NOTES_WORDS_WITH_TIMESTAMPS)

    /** A written post without audio whose title is the stub's, less words like "audio". */
    private fun chapter(feed: EpisodeFeed, stub: EpisodePost, audio: Map<String, String?>): EpisodePost? {
        val key = titleKey(stub.title)
        if (key.isBlank()) return null
        // Only a few words: a magazine's feed may carry only a story's opening lines. But some: a
        // bare video post of the same title is another recording, not the written post.
        return feed.posts.firstOrNull { it !== stub && it.words >= 20 && audio[it.guid] == null && titleKey(it.title) == key }
    }

    /** A written post without audio that the stub links to: "To read this letter, click here". */
    private fun linked(feed: EpisodeFeed, stub: EpisodePost, audio: Map<String, String?>): EpisodePost? {
        val hrefs = stub.body.select("a[href]").map { urlKey(it.attr("href")) }.toSet()
        return feed.posts.firstOrNull { it !== stub && audio[it.guid] == null && it.words >= 4 * STUB_WORDS && urlKey(it.link) in hrefs }
    }

    /**
     * How much one title holds of the other's words, when it holds most of them: "Transcript for
     * X | Show #12" and "#12 – X". Null when it doesn't, or when their episode numbers differ.
     */
    private fun overlap(a: String, b: String): Double? {
        fun words(t: String) = Regex("[\\p{L}\\p{N}]+").findAll(t.lowercase()).map { it.value }
            .filter { (it.length > 2 || it.all(Char::isDigit)) && it !in STOPWORDS && !TRANSCRIPT.matches(it) }.toSet()
        fun numbers(w: Set<String>) = w.filter { it.all(Char::isDigit) }.toSet()
        val (x, y) = words(a) to words(b)
        val numbered = numbers(x).isNotEmpty() && numbers(y).isNotEmpty()
        if (numbered && (numbers(x) intersect numbers(y)).isEmpty()) return null
        val shorter = minOf(x.size, y.size)
        val share = (x intersect y).size.toDouble() / shorter.coerceAtLeast(1)
        // A shared episode number carries most of the weight; without one, a series' titles share
        // their stock words ("Video and transcript of talk on …") and need three in four.
        return share.takeIf { shorter >= 3 && it >= if (numbered) 0.5 else 0.75 }
    }

    private fun videos(post: EpisodePost) = post.body.select("iframe[src]").map { it.attr("src") }
        .filter { src -> VIDEO.any { it in src } }.map { it.substringBefore('?') }

    private fun urlKey(url: String) = url.substringBefore('?').substringBefore('#').substringAfter("://").removePrefix("www.").trimEnd('/')

    private fun titleKey(title: String) = STUB_CUES.replace(title.lowercase(), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // Trackers wrap the host's file in their own path (podtrac, chartable): keep only the end of the
    // path. The query stays, since some hosts name the file in it (download?id=123), but isn't
    // searched: it can hold any address (?ref=https://…).
    private fun audioKey(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val query = url.substringBefore('#').substringAfter('?', "")
        return path.substringAfterLast("://").substringAfterLast("/https/").substringAfterLast("/http/") + if (query.isEmpty()) "" else "?$query"
    }

    private fun other(posts: List<EpisodePost>, p: EpisodePost) = posts.first { it !== p }.guid
}
