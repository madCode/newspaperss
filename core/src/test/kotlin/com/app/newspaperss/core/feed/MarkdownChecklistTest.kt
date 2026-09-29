package com.app.newspaperss.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownChecklistTest {
    @Test
    fun readsTheLibrarysFormatAndSkipsTheRest() {
        // rss-to-e-reader's sample_markdown_list.md, plus a heading, a note, a link and a duplicate.
        val text = """
            # To read
            - [ ] a_url_that_does_not_exist
            - [ ] https://en.wikipedia.org/wiki/Geology_of_the_Lassen_volcanic_area
            - [x] https://www.npr.org/sections/goatsandsoda/2022/05/21/1100363030/meet
            Some note about why.
            * [ ] [Haiti's history](https://www.nytimes.com/2022/05/20/world/americas/haiti.html)
            - [ ] https://en.wikipedia.org/wiki/Geology_of_the_Lassen_volcanic_area
            - [x] https://broken.example/page (error 404 Not Found)
        """.trimIndent()
        assertEquals(
            listOf(
                ChecklistItem("https://en.wikipedia.org/wiki/Geology_of_the_Lassen_volcanic_area", done = false),
                ChecklistItem("https://www.npr.org/sections/goatsandsoda/2022/05/21/1100363030/meet", done = true),
                ChecklistItem("https://www.nytimes.com/2022/05/20/world/americas/haiti.html", done = false, title = "Haiti's history"),
                ChecklistItem("https://broken.example/page", done = true),
            ),
            MarkdownChecklist.parse(text),
        )
    }

    @Test
    fun writesWhatTheLibraryReads() {
        val items = listOf(ChecklistItem("https://a.example/1", false, "ignored title"), ChecklistItem("https://a.example/2", true))
        val text = MarkdownChecklist.write(items)
        assertEquals("- [ ] https://a.example/1\n- [x] https://a.example/2\n", text)
        assertEquals(items.map { it.url to it.done }, MarkdownChecklist.parse(text).map { it.url to it.done })
    }

    @Test
    fun findsTheLinkInSharedText() {
        assertEquals("https://a.example/story?id=1", MarkdownChecklist.firstUrl("Worth a read: https://a.example/story?id=1."))
        assertEquals("https://a.example/x", MarkdownChecklist.firstUrl("(see https://a.example/x)"))
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)", MarkdownChecklist.firstUrl("https://en.wikipedia.org/wiki/Mercury_(planet)"))
        assertEquals("https://en.wikipedia.org/wiki/The_Hitchhiker's_Guide", MarkdownChecklist.firstUrl("Read https://en.wikipedia.org/wiki/The_Hitchhiker's_Guide!"))
        assertNull(MarkdownChecklist.firstUrl("no link here"))
    }
}
