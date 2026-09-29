package com.app.newspaperss.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {
    private val english = "The city council voted on Tuesday to extend the bike lanes along the river, but several members " +
        "said that the plan was rushed and that residents had not been asked. It is the second time this year the council has " +
        "changed course on the project."
    private val french = "Le conseil municipal a voté mardi pour prolonger les pistes cyclables le long du fleuve, mais plusieurs " +
        "membres ont dit que le projet était précipité et que les habitants n'avaient pas été consultés. C'est la deuxième fois " +
        "cette année que le conseil change de cap sur ce dossier."
    private val german = "Der Stadtrat hat am Dienstag beschlossen, die Radwege entlang des Flusses zu verlängern, aber mehrere " +
        "Mitglieder sagten, dass der Plan überstürzt sei und die Anwohner nicht gefragt worden seien. Es ist das zweite Mal in " +
        "diesem Jahr, dass sich der Rat bei dem Projekt umentscheidet."
    private val spanish = "El ayuntamiento votó el martes para ampliar los carriles bici a lo largo del río, pero varios miembros " +
        "dijeron que el plan fue precipitado y que no se consultó a los vecinos. Es la segunda vez este año que el consejo cambia " +
        "de rumbo en el proyecto, y la oposición pide más tiempo para el debate."
    private val portuguese = "A câmara municipal votou na terça-feira para ampliar as ciclovias ao longo do rio, mas vários membros " +
        "disseram que o plano foi apressado e que os moradores não foram consultados. É a segunda vez este ano que o conselho " +
        "muda de rumo no projeto, e a oposição pede mais tempo para o debate."
    private val japanese = "市議会は火曜日、川沿いの自転車専用レーンを延長することを決めた。しかし、複数の議員は計画が急ぎすぎており、住民の意見を聞いていないと述べた。"
    private val chinese = "市议会周二投票决定延长河边的自行车道，但几名议员表示，这项计划过于仓促，而且没有征求居民的意见。这是今年议会第二次改变这个项目的方向。"
    private val korean = "시의회는 화요일 강변 자전거 도로를 연장하기로 결정했다. 그러나 여러 의원들은 계획이 너무 서둘러 진행되었고 주민들의 의견을 묻지 않았다고 말했다."
    private val arabic = "صوّت مجلس المدينة يوم الثلاثاء على تمديد مسارات الدراجات على طول النهر، لكن عدداً من الأعضاء قالوا إن الخطة متسرعة وإن السكان لم تتم استشارتهم."
    private val russian = "Городской совет во вторник проголосовал за продление велосипедных дорожек вдоль реки, но несколько депутатов заявили, что план был поспешным."

    @Test
    fun theTextsLanguageIsFoundWithNoDeclaration() {
        assertEquals("en", LanguageDetector.detect(english, null))
        assertEquals("fr", LanguageDetector.detect(french, null))
        assertEquals("de", LanguageDetector.detect(german, null))
        assertEquals("es", LanguageDetector.detect(spanish, null))
        assertEquals("pt", LanguageDetector.detect(portuguese, null))
        assertEquals("ja", LanguageDetector.detect(japanese, null))
        assertEquals("zh", LanguageDetector.detect(chinese, null))
        assertEquals("ko", LanguageDetector.detect(korean, null))
        assertEquals("ar", LanguageDetector.detect(arabic, null))
        assertEquals("ru", LanguageDetector.detect(russian, null))
    }

    @Test
    fun aSiteThatDeclaresEnglishOnEveryPageDoesntOverruleTheText() {
        assertEquals("fr", LanguageDetector.detect(french, "en"))
        assertEquals("en", LanguageDetector.detect(english, "de-DE"))
    }

    @Test
    fun aDeclarationThatAgreesIsKeptForItsPrecision() {
        assertEquals("pt-BR", LanguageDetector.detect(portuguese, "pt_br"))
        // Arabic script, Persian language: the text can't tell, the page can.
        assertEquals("fa", LanguageDetector.detect(arabic, "fa"))
        assertEquals("uk", LanguageDetector.detect(russian, "uk"))
        assertEquals("zh-TW", LanguageDetector.detect(chinese, "zh-TW"))
    }

    @Test
    fun whenTheTextCantTellTheDeclarationStands() {
        assertEquals("sv", LanguageDetector.detect("Kommunfullmäktige röstade på tisdagen för att förlänga cykelbanorna längs ån, " +
            "men flera ledamöter sa att planen var förhastad och att invånarna inte hade tillfrågats om saken alls.", "sv"))
        assertEquals("fr", LanguageDetector.detect("Un dessin.", "fr"))
        assertNull(LanguageDetector.detect("A cartoon.", null))
    }

    @Test
    fun junkDeclarationsAreIgnored() {
        assertNull(LanguageDetector.normalize("und"))
        assertNull(LanguageDetector.normalize("english please"))
        assertNull(LanguageDetector.normalize(""))
        assertEquals("en-US", LanguageDetector.normalize("en_us"))
        assertEquals("zh-Hant", LanguageDetector.normalize("zh-Hant"))
    }

    @Test
    fun rightToLeftLanguagesAreKnown() {
        assertTrue(LanguageDetector.isRightToLeft("ar"))
        assertTrue(LanguageDetector.isRightToLeft("he-IL"))
        assertFalse(LanguageDetector.isRightToLeft("en"))
    }
}
