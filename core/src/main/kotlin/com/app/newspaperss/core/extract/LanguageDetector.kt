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
        return when (val guess = guess(text)) {
            Guess.Unknown -> page
            // Latin text in a language not detected: a page's non-Latin tag is a site-wide
            // default, not this article's, and would turn an English caption right to left.
            Guess.UnknownLatin -> page?.takeUnless { primary(it) in NON_LATIN }
            is Guess.Known -> when {
                page == null -> guess.tag
                // Kept when it agrees: it can be more precise ("pt-BR", "fa" for Arabic script).
                primary(page) in compatibleWith(guess.tag) -> page
                // A Latin-script language not detected (Galician, Afrikaans, Catalan) reads as its
                // nearest neighbour here, so the page knows better; unless the text is plainly English.
                guess.tag in LATIN && guess.tag != "en" && primary(page) !in LATIN && primary(page) !in NON_LATIN -> page
                else -> guess.tag
            }
        }
    }

    /**
     * A tag that's safe to write from a declaration: the language, then a script and region if
     * well formed, with anything after dropped. "en_us" becomes "en-US"; "en-UK" (no such region)
     * becomes "en"; junk and "und" become null.
     */
    fun normalize(tag: String?): String? {
        val parts = tag?.trim()?.replace('_', '-')?.split('-') ?: return null
        val primary = parts.first().lowercase()
        if (!PRIMARY.matches(primary) || primary == "und") return null
        val out = mutableListOf(primary)
        var rest = parts.drop(1)
        rest.firstOrNull()?.takeIf { SCRIPT.matches(it) }?.let { script ->
            out += script.lowercase().replaceFirstChar(Char::uppercaseChar)
            rest = rest.drop(1)
        }
        rest.firstOrNull()?.uppercase()?.takeIf { it in COUNTRIES }?.let { out += it }
        return out.joinToString("-")
    }

    /** Right-to-left languages, for `dir="rtl"`. */
    fun isRightToLeft(tag: String): Boolean = primary(tag) in RTL

    private sealed interface Guess {
        data class Known(val tag: String) : Guess
        /** Mostly Latin letters, in a language the word lists don't know (or too little text). */
        data object UnknownLatin : Guess
        data object Unknown : Guess
    }

    private fun guess(text: String): Guess {
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
        val latin = scripts[UnicodeScript.LATIN] ?: 0
        if (latin * 2 >= letters && letters > 0) return latinLanguage(sample)?.let(Guess::Known) ?: Guess.UnknownLatin
        if (letters < MIN_LETTERS) return Guess.Unknown
        val kana = (scripts[UnicodeScript.HIRAGANA] ?: 0) + (scripts[UnicodeScript.KATAKANA] ?: 0)
        val han = scripts[UnicodeScript.HAN] ?: 0
        val hangul = scripts[UnicodeScript.HANGUL] ?: 0
        if (kana + han + hangul > 0 && (kana + han + hangul) * 2 >= letters) {
            return Guess.Known(
                when {
                    hangul >= kana + han -> "ko"
                    kana * 10 >= kana + han -> "ja"
                    else -> "zh"
                },
            )
        }
        val (top, count) = scripts.maxByOrNull { it.value } ?: return Guess.Unknown
        return SCRIPT_LANGUAGE[top]?.takeIf { count * 2 >= letters }?.let(Guess::Known) ?: Guess.Unknown
    }

    private fun latinLanguage(text: String): String? {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < MIN_WORDS) return null
        val scores = HashMap<String, Double>()
        for (word in words) {
            val languages = STOPWORD_LANGUAGES[word] ?: continue
            // A word several languages share ("la", "de", "que") counts for much less in each: split
            // evenly, a Spanish article's many "la"s were enough to make it French.
            val weight = 1.0 / (languages.size * languages.size)
            for (language in languages) scores[language] = (scores[language] ?: 0.0) + weight
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        return best.key.takeIf { best.value >= MIN_SHARE * words.size && best.value >= MARGIN * runnerUp }
    }

    private fun primary(tag: String) = tag.substringBefore('-').lowercase()

    private fun compatibleWith(guess: String): Set<String> =
        // Japanese written mostly in kanji reads as Chinese; a page saying Japanese is right.
        if (guess == "zh") FAMILIES.first { "zh" in it } + "ja"
        else FAMILIES.firstOrNull { guess in it } ?: setOf(guess)

    private val PRIMARY = Regex("[a-z]{2,3}")
    private val SCRIPT = Regex("[A-Za-z]{4}")
    private val COUNTRIES = java.util.Locale.getISOCountries().toSet()
    private val WORD = Regex("\\p{L}+")
    private const val SAMPLE_CHARS = 6_000
    private const val MIN_LETTERS = 40
    private const val MIN_WORDS = 20
    // Weighted distinctive function words are a fifth or more of running text in these languages;
    // well under that and the text is probably something else.
    private const val MIN_SHARE = 0.05
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

    // Each language's most frequent words, shared ones included: leaving out "la" or "de" because
    // several languages use them hands those words to whichever list does have them.
    private val STOPWORDS = mapOf(
        "en" to "the of and to a in is that it was for on are with as be by this have not but at from they his her",
        "fr" to "le la les de des et est un une que qui dans pour pas sur au du ce il elle avec sont mais en ne se plus",
        "es" to "el la los las de del y que en un una por con para es se su al como más pero lo fue no le ha",
        "it" to "il la le di della del e che un una per non sono con gli nel alla anche è come più ma dei si ha lo",
        "pt" to "o a os as de do da dos das e que em um uma para com não se mais ao pelo foi mas no na é",
        "nl" to "de het een en van dat is niet op te zijn voor met die ook er maar aan wordt bij in",
        "de" to "der die und das ist nicht ein eine zu den mit von sich auf für dem auch es im wird in",
    )
    private val LATIN = STOPWORDS.keys

    // Languages written in another script, whose tag on Latin text is a mistake.
    private val NON_LATIN = FAMILIES.flatten().toSet() + SCRIPT_LANGUAGE.values +
        setOf("ja", "ko", "am", "km", "lo", "my", "si", "te", "kn", "ml", "gu", "pa", "or", "dz", "bo")

    private val STOPWORD_LANGUAGES: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((language, words) in STOPWORDS) for (word in words.split(' ')) getOrPut(word) { mutableListOf() }.add(language)
    }
}
