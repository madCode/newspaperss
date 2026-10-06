package com.app.newspaperss.core.listen

/**
 * Splits text into sentences for reading aloud: a sentence is what ↶ goes back to and what the
 * screen tints, so a split in the wrong place is heard and seen.
 *
 * Not `java.text.BreakIterator`: the JVM's breaks after "Mr." and "U.S." before a capital, and
 * Android's differs from it, so tests on one wouldn't speak for the other.
 */
internal object Sentences {
    /** Speech engines refuse long input (Android's limit is about 4,000 characters). */
    const val MAX_LENGTH = 1_000

    fun split(text: String): List<String> {
        val clean = text.replace(SPACE, " ").trim()
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        for (match in END.findAll(clean)) {
            val stop = match.range.last + 1
            if (stop >= clean.length) break
            // Chinese, Japanese and Hindi full stops end a sentence wherever they are: no space follows.
            if (match.value.first() !in WIDE_ENDS) {
                if (!startsSentence(clean, stop)) continue
                if (match.value.first() == '.' && abbreviation(clean, match.range.first)) continue
            }
            out += clean.substring(start, stop).trim()
            start = stop
        }
        out += clean.substring(start).trim()
        return out.filter { it.isNotEmpty() }.flatMap(::limit)
    }

    // A sentence ends with . ! ? or …, then any closing quotes or brackets, then a space.
    // From the start of a run, and possessive (++, *+): a long run of dots with no space after is
    // tried once, not again from each dot and through every split of it.
    private val END = Regex("(?<![.!?…])[.!?…]++[\"'”’)\\]]*+(?=\\s)|[。！？।]++[”’」』）)]*+")
    private const val WIDE_ENDS = "。！？।"
    private val SPACE = Regex("\\s+")

    // What follows the space: a capital, a digit or an opening quote or bracket. "e.g. the" is
    // one sentence, and so is "3 p.m. today".
    private fun startsSentence(text: String, at: Int): Boolean {
        var i = at
        while (i < text.length && text[i] == ' ') i++
        if (i >= text.length) return false
        val c = text[i]
        return c.isUpperCase() || c.isDigit() || c in "\"'“‘([¿¡" || !c.isLetter()
    }

    private fun abbreviation(text: String, dot: Int): Boolean {
        var i = dot
        while (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '.')) i--
        val word = text.substring(i, dot)
        if (word.isEmpty()) return false
        // An initial ("J. R. R. Tolkien") or dotted letters ("U.S.", "a.m.").
        if (word.length == 1 && word[0].isUpperCase()) return true
        if (word.contains('.') && word.split('.').all { it.length <= 1 }) return true
        // "No. 5", but "I said no. Then…".
        if (word.lowercase() == "no" || word.lowercase() == "nos") return text.getOrNull(dot + 2)?.isDigit() == true
        return word.lowercase() in ABBREVIATIONS
    }

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "ft", "gen", "gov", "sen", "rep", "rev", "lt", "col", "capt", "sgt",
        "vs", "etc", "inc", "ltd", "co", "corp", "vol", "fig", "approx", "est", "dept", "univ",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    // A very long "sentence" (a list run together, a page without full stops) is cut at a
    // semicolon, comma or space, so the engine takes it and ↶ doesn't go back a whole page.
    private fun limit(sentence: String): List<String> {
        if (sentence.length <= MAX_LENGTH) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_LENGTH) {
            val window = rest.substring(0, MAX_LENGTH)
            val cut = listOf("; ", ", ", " ").firstNotNullOfOrNull { sep -> window.lastIndexOf(sep).takeIf { it > MAX_LENGTH / 2 }?.let { it + sep.length } }
                ?: MAX_LENGTH.let { if (rest[it - 1].isHighSurrogate()) it - 1 else it }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
