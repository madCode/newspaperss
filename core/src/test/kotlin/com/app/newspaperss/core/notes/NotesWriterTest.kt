package com.app.newspaperss.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NotesWriterTest {
    private val top = """
        |# Tuesday Morning Edition
        |
        |Pick one piece that stayed with you. Some questions to start:
        |
        |- What's the main claim, and do I agree?
        |- How does it connect to other things I've read?
        |- What do I want to remember in six months?
    """.trimMargin()

    private fun edition(vararg articles: NotesArticle) =
        NotesEdition("Tuesday Morning Edition", LocalDate.of(2026, 9, 29), articles.toList())

    @Test
    fun anEditionGetsAHeaderAndOneSectionPerArticleInOrder() {
        val notes = NotesWriter.write(
            edition(
                NotesArticle("The quiet city", "Example News", "https://example.com/quiet", "Jane Doe", LocalDate.of(2026, 9, 28)),
                NotesArticle("Why rivers bend?", "Field Notes", "https://example.org/rivers", "A. Writer", LocalDate.of(2026, 9, 27)),
            ),
        )

        assertEquals(
            """
            |---
            |date: 2026-09-29
            |edition: "Tuesday Morning Edition"
            |sources:
            |  - "Example News"
            |  - "Field Notes"
            |tags:
            |  - newspaperss
            |---
            |
            |$top
            |
            |## The quiet city
            |
            |- Source: Example News
            |- Author: Jane Doe
            |- Published: 2026-09-28
            |- Link: https://example.com/quiet
            |- Read in: Tuesday Morning Edition, 2026-09-29
            |- Citation: Jane Doe. "The quiet city." *Example News*, 2026-09-28. https://example.com/quiet
            |
            |### Notes
            |
            |## Why rivers bend?
            |
            |- Source: Field Notes
            |- Author: A. Writer
            |- Published: 2026-09-27
            |- Link: https://example.org/rivers
            |- Read in: Tuesday Morning Edition, 2026-09-29
            |- Citation: A. Writer. "Why rivers bend?" *Field Notes*, 2026-09-27. https://example.org/rivers
            |
            |### Notes
            |
            """.trimMargin(),
            notes,
        )
    }

    @Test
    fun missingDetailsAreLeftOutRatherThanLeftBlank() {
        val notes = NotesWriter.write(edition(NotesArticle("A story", "Blog", url = null, author = " ", published = null)))

        assertEquals(
            """
            |---
            |date: 2026-09-29
            |edition: "Tuesday Morning Edition"
            |sources:
            |  - "Blog"
            |tags:
            |  - newspaperss
            |---
            |
            |$top
            |
            |## A story
            |
            |- Source: Blog
            |- Read in: Tuesday Morning Edition, 2026-09-29
            |- Citation: "A story." *Blog*.
            |
            |### Notes
            |
            """.trimMargin(),
            notes,
        )
    }

    @Test
    fun markdownInTitlesStaysLiteralAndOnOneLine() {
        val notes = NotesWriter.write(
            NotesEdition(
                "#1 [Weekend] *Edition*",
                LocalDate.of(2026, 9, 29),
                listOf(NotesArticle("C# tips:\n  [draft] *new*_ish_ `code` <b> | $5 ~ok~", "The \\ Times", "https://example.com/a", "Ann <ann@example.com>")),
            ),
        )

        val lines = notes.lines()
        assertTrue("# \\#1 \\[Weekend\\] \\*Edition\\*" in lines)
        assertTrue(
            "## C\\# tips: \\[draft\\] \\*new\\*\\_ish\\_ \\`code\\` \\<b\\> \\| \\$5 \\~ok\\~" in lines,
        )
        assertTrue("- Source: The \\\\ Times" in lines)
        assertTrue("- Author: Ann \\<ann@example.com\\>" in lines)
        assertTrue("- Read in: \\#1 \\[Weekend\\] \\*Edition\\*, 2026-09-29" in lines)
    }

    @Test
    fun anEntityInATitleIsntRenderedAsTheCharacter() {
        val notes = NotesWriter.write(edition(NotesArticle("Using &copy; in HTML", "Blog", "https://example.com/a")))
        assertTrue(notes.contains("## Using \\&copy; in HTML"))
    }

    @Test
    fun aBlankTitleStillMakesAHeading() {
        val notes = NotesWriter.write(edition(NotesArticle(" \n ", "Blog", "https://example.com/a")))

        assertTrue("## Untitled" in notes.lines())
    }

    @Test
    fun quotesAndBackslashesInTheFrontMatterStayInsideTheirStrings() {
        val notes = NotesWriter.write(
            NotesEdition("The \"Big\" \\ Edition", LocalDate.of(2026, 9, 29), listOf(NotesArticle("A", "Says \"hi\"\nthere", null))),
        )
        val front = notes.lines().takeWhile { it != "tags:" }
        assertTrue("edition: \"The \\\"Big\\\" \\\\ Edition\"" in front)
        assertTrue("  - \"Says \\\"hi\\\" there\"" in front)
    }
}
