package com.app.newspaperss.core.feed

import com.app.newspaperss.core.feed.ReadingListFile.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadLaterImportTest {
    // The shape of Pocket's ril_export.html.
    private val pocketHtml = """
        <!DOCTYPE html>
        <html>
        	<!--So long and thanks for all the fish-->
        	<head>
        		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
        		<title>Pocket Export</title>
        	</head>
        	<body>
        		<h1>Unread</h1>
        		<ul>
        			<li><a href="https://news.example.com/2024/05/slow-reading" time_added="1715000000" tags="essays,longread">Slow reading &amp; the attention economy</a></li>
        			<li><a href="https://blog.example.org/post?id=7&amp;ref=pocket" time_added="1714000000" tags="">https://blog.example.org/post?id=7&amp;ref=pocket</a></li>
        			<li><a href="mailto:someone@example.com" time_added="1713000000" tags="">Not a page</a></li>
        		</ul>

        		<h1>Read Archive</h1>
        		<ul>
        			<li><a href="https://science.example.net/tides" time_added="1600000000" tags="science">How
        			tides work</a></li>
        			<li><a href="https://news.example.com/2024/05/slow-reading" time_added="1500000000" tags="">Slow reading (again)</a></li>
        		</ul>
        	</body>
        </html>
    """.trimIndent()

    @Test
    fun pocketHtmlMarksTheReadArchiveDoneAndKeepsTitles() {
        val file = ReadLaterImport.parse(pocketHtml)
        assertEquals(Format.POCKET_HTML, file.format)
        assertEquals(
            listOf(
                ChecklistItem("https://news.example.com/2024/05/slow-reading", done = false, title = "Slow reading & the attention economy"),
                // Pocket puts the address in as the title when it never learned one.
                ChecklistItem("https://blog.example.org/post?id=7&ref=pocket", done = false, title = null),
                ChecklistItem("https://science.example.net/tides", done = true, title = "How tides work"),
            ),
            file.items,
        )
    }

    @Test
    fun pocketCsvReadsQuotedFieldsAndTheArchiveStatus() {
        val csv = "title,url,time_added,tags,status\r\n" +
            "\"Sleep, and why we skip it\",https://health.example.com/sleep,1712345678,health|science,unread\r\n" +
            "\"The \"\"quiet\"\" web\",https://web.example.org/quiet,1712345000,,archive\r\n" +
            "\"A title\nsplit over lines\",https://two.example.com/lines,1712340000,,unread\r\n" +
            ",https://untitled.example.com/,1712000000,,unread\r\n" +
            "\r\n" +
            "Not a link,ftp://files.example.com/x,1711000000,,unread\r\n"
        val file = ReadLaterImport.parse(csv)
        assertEquals(Format.POCKET_CSV, file.format)
        assertEquals(
            listOf(
                ChecklistItem("https://health.example.com/sleep", done = false, title = "Sleep, and why we skip it"),
                ChecklistItem("https://web.example.org/quiet", done = true, title = "The \"quiet\" web"),
                ChecklistItem("https://two.example.com/lines", done = false, title = "A title split over lines"),
                ChecklistItem("https://untitled.example.com/", done = false, title = null),
            ),
            file.items,
        )
    }

    @Test
    fun instapaperCsvTreatsTheArchiveFolderAsRead() {
        val csv = "URL,Title,Selection,Folder,Timestamp\n" +
            "https://essays.example.com/a,\"On walking, slowly\",,Unread,1712345678\n" +
            "https://essays.example.com/b,Done already,\"a highlighted line, with a comma\",Archive,1712345000\n" +
            "https://essays.example.com/c,Filed away,,Recipes,1712344000\n" +
            "https://essays.example.com/d,https://essays.example.com/d,,Starred,1712343000\n" +
            "https://essays.example.com/a,Duplicate,,Archive,1712342000\n"
        val file = ReadLaterImport.parse(csv)
        assertEquals(Format.INSTAPAPER_CSV, file.format)
        assertEquals(
            listOf(
                ChecklistItem("https://essays.example.com/a", done = false, title = "On walking, slowly"),
                ChecklistItem("https://essays.example.com/b", done = true, title = "Done already"),
                ChecklistItem("https://essays.example.com/c", done = false, title = "Filed away"),
                ChecklistItem("https://essays.example.com/d", done = false, title = null),
            ),
            file.items,
        )
    }

    @Test
    fun columnsAreFoundByNameAndAByteOrderMarkIsIgnored() {
        val csv = Char(0xFEFF) + "status,url,title\narchive,https://a.example/1,One\n"
        assertEquals(listOf(ChecklistItem("https://a.example/1", done = true, title = "One")), ReadLaterImport.pocketCsv(csv))
        // A spreadsheet re-save can reorder columns; "time_added" still marks it as Pocket's.
        assertEquals(Format.POCKET_CSV, ReadLaterImport.parse(Char(0xFEFF) + "url,time_added,title,status\n").format)
    }

    @Test
    fun anythingElseIsReadAsAMarkdownChecklist() {
        val md = "# Reading\n- [ ] [Tides](https://science.example.net/tides)\n- [x] https://a.example/done\n"
        val file = ReadLaterImport.parse(md)
        assertEquals(Format.MARKDOWN, file.format)
        assertEquals(MarkdownChecklist.parse(md), file.items)
        // A checklist line with a comma isn't mistaken for a CSV header.
        assertEquals(Format.MARKDOWN, ReadLaterImport.parse("- [ ] https://a.example/url,title\n").format)
    }

    @Test
    fun csvHandlesQuotesNewlinesAndAMissingFinalNewline() {
        assertEquals(
            listOf(listOf("a", "b, c", "say \"hi\""), listOf("line\r\nbreak", "", "end")),
            ReadLaterImport.csv("a,\"b, c\",\"say \"\"hi\"\"\"\r\n\"line\r\nbreak\",,end"),
        )
    }
}
