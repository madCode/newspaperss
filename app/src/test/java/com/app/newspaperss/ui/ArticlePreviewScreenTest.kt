package com.app.newspaperss.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.app.newspaperss.settings.PreviewTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EpubPages
import com.app.newspaperss.ui.edition.forPreview
import com.app.newspaperss.ui.edition.imageSizes
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.ui.edition.bookResponse
import com.app.newspaperss.ui.edition.BOOK_ORIGIN
import org.junit.Assert.assertTrue
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ArticlePreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theScreenShowsBeforeTheEditionIsReadAndSaysSoIfItsFileIsGone() {
        val file = CompletableDeferred<File?>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file.await() }, position = 0, title = "A story", onBack = {}) }

        // Still reading: the tap has visibly done something, and it isn't reported as missing yet.
        compose.onNodeWithText("A story").assertIsDisplayed()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()

        file.complete(null)
        idleUntil { compose.onAllNodesWithText("file is gone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("file is gone", substring = true).assertIsDisplayed()
    }

    @Test
    fun anEditionOnDiskIsShownNotReportedMissing() {
        val file = tmp.newFile("e.epub")
        val article = EditionArticle(title = "A story", sourceTitle = "S", url = "https://a.example/", bodyHtml = "<p>x</p>", minutes = 1.0)
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, listOf(article)))), it)
        }
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }

        idleUntil { compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()
    }

    @Test
    fun everyPageThePreviewShowsGetsTheMarginsAndColoursTheEReaderWouldAdd() {
        val file = tmp.newFile("m.epub")
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 1.0)
        }
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, articles))), it)
        }
        val style = "body { margin: 0 5%; background: #1C1B1F; color: #E6E1E5; }"
        val dark = 0xFF1C1B1F.toInt() to 0xFFE6E1E5.toInt()
        EpubPages(file).use { pages ->
            val opened = forPreview(pages.article(0)!!, dark.first, dark.second)
            // After the book's stylesheet, so it wins over the book's own body rule.
            assertTrue(opened.indexOf("stylesheet") in 0 until opened.indexOf(style))
            // The second article is reached by the first one's "Next" link, served page by page.
            val next = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(1), pages, dark.first, dark.second).second.toString(Charsets.UTF_8)
            assertTrue(next.contains(style))
        }
    }

    private fun png(width: Int, height: Int): ByteArray =
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun onlyLargePicturesStandingAloneFillTheWidth() {
        val file = tmp.newFile("i.epub")
        val images = mapOf("strip" to png(300, 1000), "wide" to png(600, 200), "headshot" to png(120, 120), "a" to png(600, 400), "b" to png(600, 400))
            .map { (name, bytes) -> EpubImage("images/$name.png", "image/png", bytes) }
        val body = "<figure><img src=\"images/strip.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/headshot.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/a.png\" alt=\"\"/><img src=\"images/b.png\" alt=\"\"/></figure>" +
            "<p>Inline <img src=\"images/wide.png\" alt=\"\"/> in a line.</p>"
        val articles = listOf(
            EditionArticle(title = "One", sourceTitle = "S", url = "https://a.example/1", bodyHtml = body, minutes = 1.0, images = images),
            EditionArticle(title = "Two", sourceTitle = "S", url = "https://a.example/2", bodyHtml = "<img src=\"images/solo.png\" alt=\"\"/>", minutes = 1.0, images = listOf(EpubImage("images/solo.png", "image/png", png(600, 200)))),
        )
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, articles))), it)
        }
        EpubPages(file).use { pages ->
            fun filled(xhtml: String): List<String> {
                // Still XHTML a strict parser takes: the WebView loads it as application/xhtml+xml.
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xhtml.byteInputStream())
                val imgs = doc.getElementsByTagName("img")
                return (0 until imgs.length).map { imgs.item(it) as org.w3c.dom.Element }
                    .filter { it.getAttribute("class") == "preview-fill" }.map { it.getAttribute("src") }
            }
            val first = forPreview(pages.article(0)!!, 0, 0, imageSizes(pages))
            assertEquals("a headshot, a row of pictures and an inline one keep their size", listOf("images/strip.png"), filled(first))
            // A page reached by "Next": an article that is just the picture.
            val next = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(1), pages, 0, 0).second.toString(Charsets.UTF_8)
            assertEquals(listOf("images/solo.png"), filled(next))
            assertTrue(next.contains("img.preview-fill { width: 100%; }"))
        }
    }

    private fun oneArticleEdition(): File {
        val file = tmp.newFile("z.epub")
        val article = EditionArticle(title = "A story", sourceTitle = "S", url = "https://a.example/", bodyHtml = "<p>x</p>", minutes = 1.0)
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, listOf(article)))), it)
        }
        return file
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? = view as? WebView ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        var found: WebView? = null
        idleUntil { found = find(compose.activity.window.decorView); found != null }
        return found!!
    }

    @Test
    fun thePickedTextSizeIsAppliedAndKeptInPlace() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        val picked = mutableListOf<PreviewTextSize>()
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size, onTextSize = { picked += it; size = it })
        }
        val view = webView()
        assertEquals(100, view.settings.textZoom)

        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNodeWithText("Larger").performClick()
        compose.waitForIdle()

        assertEquals(listOf(PreviewTextSize.LARGER), picked)
        assertEquals(145, view.settings.textZoom)
        assertSame("the same page, so the reader keeps their place", view, webView())
        // The menu marks the current size.
        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNode(hasText("Larger") and hasContentDescription("Current size")).assertExists()
        compose.onNode(hasText("Default") and hasContentDescription("Current size")).assertDoesNotExist()
    }

    @Test
    fun thePreviewCanBePinchedToZoomWithoutButtonsOverThePage() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        val settings = webView().settings
        assertTrue(settings.builtInZoomControls)
        assertFalse(settings.displayZoomControls)
    }
}
