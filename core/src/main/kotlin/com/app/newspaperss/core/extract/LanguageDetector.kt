package com.app.newspaperss.core.extract

import java.lang.Character.UnicodeScript

/**
 * An article's language as a BCP 47 tag, for `xml:lang` in the book: e-readers pick hyphenation
 * patterns, fonts and text direction from it.
 *
 * The text decides, with the page's declared language as a tiebreaker. Many sites declare the
 * same `lang` on every page whatever the article's language, and a feed's text comes with no
 * declaration at all. Detection is deliberately small: the writing system for non-Latin text,
 * and common function words for a few Latin-script languages. When it can't tell (a short caption,
 * a language it doesn't know), the declared language stands.
 */
object LanguageDetector {
    /**
     * @param text the article's text, title included.
     * @param declared what the page said (`<html lang>`, `og:locale` and the like), if anything.
     * @return a language tag, or null if neither the text nor the page says.
     */
    fun detect(text: String, declared: String?): String? {
        val page = normalize(declared)
        val guess = guess(text) ?: return page
        // The page's tag is kept when it agrees: it can be more precise ("pt-BR", "fa" for Arabic script).
        return if (page != null && primary(page) in familyOf(guess)) page else guess
    }

    /** A usable tag from a declaration: "en_US" becomes "en-US"; junk and "und" become null. */
    fun normalize(tag: String?): String? {
        val t = tag?.trim()?.replace('_', '-') ?: return null
        if (!TAG.matches(t)) return null
        val parts = t.split('-')
        val primary = parts.first().lowercase()
        if (primary == "und" || primary == "x" || primary == "i") return null
        return (listOf(primary) + parts.drop(1).map { if (it.length == 2) it.uppercase() else it }).joinToString("-")
    }

    /** Right-to-left languages, for `dir="rtl"`. */
    fun isRightToLeft(tag: String): Boolean = primary(tag) in RTL

    internal fun guess(text: String): String? {
        val sample = text.take(SAMPLE_CHARS)
        val scripts = HashMap<UnicodeScript, Int>()
        var letters = 0
        var i = 0
        while (i < sample.length) {
            val cp = sample.codePointAt(i)
            if (Character.isLetter(cp)) {
                letters++
                val script = UnicodeScript.of(cp)
                scripts[script] = (scripts[script] ?: 0) + 1
            }
            i += Character.charCount(cp)
        }
        if (letters < MIN_LETTERS) return null
        val latin = scripts[UnicodeScript.LATIN] ?: 0
        if (latin * 2 >= letters) return latinLanguage(sample)
        val kana = (scripts[UnicodeScript.HIRAGANA] ?: 0) + (scripts[UnicodeScript.KATAKANA] ?: 0)
        val han = scripts[UnicodeScript.HAN] ?: 0
        val hangul = scripts[UnicodeScript.HANGUL] ?: 0
        if (kana + han + hangul > 0 && (kana + han + hangul) * 2 >= letters) {
            return when {
                hangul >= kana + han -> "ko"
                kana * 10 >= kana + han -> "ja"
                else -> "zh"
            }
        }
        val (top, count) = scripts.maxByOrNull { it.value } ?: return null
        return SCRIPT_LANGUAGE[top]?.takeIf { count * 2 >= letters }
    }

    private fun latinLanguage(text: String): String? {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < MIN_WORDS) return null
        val scores = HashMap<String, Double>()
        for (word in words) {
            val languages = STOPWORD_LANGUAGES[word] ?: continue
            // A word several languages share ("de", "que", "en") counts for less in each.
            for (language in languages) scores[language] = (scores[language] ?: 0.0) + 1.0 / languages.size
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        return best.key.takeIf { best.value >= MIN_SHARE * words.size && best.value >= MARGIN * runnerUp }
    }

    private fun primary(tag: String) = tag.substringBefore('-').lowercase()

    private fun familyOf(guess: String): Set<String> = FAMILIES.firstOrNull { guess in it } ?: setOf(guess)

    private val TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
    private val WORD = Regex("\\p{L}+")
    private const val SAMPLE_CHARS = 6_000
    private const val MIN_LETTERS = 40
    private const val MIN_WORDS = 20
    // Function words make up a third or more of running text in these languages; well under that
    // and the text is probably something else.
    private const val MIN_SHARE = 0.08
    private const val MARGIN = 1.5

    private val RTL = setOf("ar", "fa", "ur", "ps", "sd", "ug", "he", "iw", "yi", "dv")

    // Languages written in the same script, so a page's more precise tag is kept.
    private val FAMILIES = listOf(
        setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg"),
        setOf("ar", "fa", "ur", "ps", "sd", "ug"),
        setOf("he", "iw", "yi"),
        setOf("zh", "yue", "cmn"),
        setOf("hi", "mr", "ne", "sa"),
    )

    private val SCRIPT_LANGUAGE = mapOf(
        UnicodeScript.CYRILLIC to "ru",
        UnicodeScript.ARABIC to "ar",
        UnicodeScript.HEBREW to "he",
        UnicodeScript.GREEK to "el",
        UnicodeScript.THAI to "th",
        UnicodeScript.DEVANAGARI to "hi",
        UnicodeScript.BENGALI to "bn",
        UnicodeScript.TAMIL to "ta",
        UnicodeScript.GEORGIAN to "ka",
        UnicodeScript.ARMENIAN to "hy",
    )

    private val STOPWORDS = mapOf(
        "en" to "the and of to is that in it was for with as on are this be by have not but",
        "fr" to "le la les et des est que une pour dans qui pas sur au du ce il avec sont mais",
        "de" to "der die und das ist nicht ein eine zu den mit von sich auf für dem auch es im wird",
        "es" to "el los las que en y del una por con para es se su al como más pero lo fue",
        "it" to "il che di della per una non sono con gli del le nel alla anche è come più ma dei",
        "pt" to "o os que de não uma para com do da dos em é se mais ao as pelo foi mas",
        "nl" to "de het een en van dat is niet op te zijn voor met die ook er maar aan wordt bij",
    )

    private val STOPWORD_LANGUAGES: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((language, words) in STOPWORDS) for (word in words.split(' ')) getOrPut(word) { mutableListOf() }.add(language)
    }
}
