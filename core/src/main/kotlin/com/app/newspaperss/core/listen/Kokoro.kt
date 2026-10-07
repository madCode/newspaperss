package com.app.newspaperss.core.listen

import java.security.MessageDigest

/**
 * One file of the Kokoro voice, as the app's manifest pins it: [hash] is the SHA-256 of a large
 * file, or the git blob SHA-1 of a small one, which is what Hugging Face lists for each.
 */
data class KokoroFile(val path: String, val size: Long, val hash: String) {
    /** A digest primed for this file: feed it the file's bytes, then ask [matches]. */
    fun digest(): MessageDigest =
        if (hash.length == SHA256_HEX) {
            MessageDigest.getInstance("SHA-256")
        } else {
            // git hashes a blob with a header: "blob <size>\0", then the bytes.
            MessageDigest.getInstance("SHA-1").apply { update("blob $size\u0000".toByteArray()) }
        }

    // Locale.ROOT: hex digits must be ASCII whatever the phone's language.
    fun matches(digest: MessageDigest): Boolean = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) } == hash

    private companion object {
        const val SHA256_HEX = 64
    }
}

object KokoroManifest {
    /** Tab-separated path, size and hash, one file a line; lines starting with # are comments. */
    fun parse(text: String): List<KokoroFile> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (path, size, hash) = line.split('\t')
            KokoroFile(path, size.toLong(), hash)
        }
        .toList()
}

/**
 * Whether a phone can make the podcast in time. [pace] is minutes of making per minute of speech,
 * on a phone that has warmed up: a podcast keeps it busy long enough to.
 */
object PodcastPace {
    /**
     * A 20-second sample runs on a cool phone; a podcast doesn't. A Pixel 8 made speech at 0.8×
     * real time cool and 1.4× after 7 minutes, so a sample's pace is scaled up by this much.
     */
    const val WARMING = 1.5

    /** Up to this long to make your paper, the phone can do it. */
    const val CAN_MINUTES = 60.0

    /** Beyond this, too slow: the paper would have to start more than about 3 hours early. */
    const val LIMIT_MINUTES = 150.0

    enum class Verdict { CAN, SLOW, TOO_SLOW }

    /** The warm pace a sample's cool pace suggests. */
    fun fromSample(coolPace: Double): Double = coolPace * WARMING

    /** Minutes to make a paper that takes [readingMinutes] to read. */
    fun minutesToMake(readingMinutes: Int, pace: Double): Double = ListenTime.fromReading(readingMinutes.toDouble()) * pace

    fun verdict(readingMinutes: Int, pace: Double): Verdict {
        val minutes = minutesToMake(readingMinutes, pace)
        return when {
            minutes <= CAN_MINUTES -> Verdict.CAN
            minutes <= LIMIT_MINUTES -> Verdict.SLOW
            else -> Verdict.TOO_SLOW
        }
    }

    /** The longest paper, in reading minutes, this phone can make within [LIMIT_MINUTES]. */
    fun longestPaper(pace: Double): Int = (LIMIT_MINUTES / pace / ListenTime.fromReading(1.0)).toInt()
}
