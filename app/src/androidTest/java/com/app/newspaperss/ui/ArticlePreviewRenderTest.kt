package com.app.newspaperss.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.testutil.writeEpub
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The preview reads the edition's own EPUB in a WebView. Robolectric's WebView is a shadow that
 * never loads or draws, so every JVM test of this screen passes without a page having been
 * rendered -- whether the book the app writes is one a reader can display is only answered here.
 *
 * What it draws is counted in pixels rather than read as text. The preview turns JavaScript off
 * (feed pages are untrusted), so evaluateJavascript can't ask the document anything, and a
 * WebView's content never reaches the Compose semantics tree that onNodeWithText searches.
 */
@RunWith(AndroidJUnit4::class)
class ArticlePreviewRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val articles = listOf(
        EditionArticle(
            title = "A heron on the roof",
            sourceTitle = "The Example",
            url = "https://a.example/heron",
            bodyHtml = "<p>It stood there for an hour, unbothered.</p>".repeat(12),
            minutes = 2.0,
        ),
    )

    private fun View.firstWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).asSequence().mapNotNull { getChildAt(it).firstWebView() }.firstOrNull()
        else -> null
    }

    /** How many distinct colours the view draws: one means a blank page. */
    private fun View.coloursDrawn(): Int {
        if (width == 0 || height == 0) return 0
        var colours = emptySet<Int>()
        val done = CountDownLatch(1)
        compose.activity.runOnUiThread {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            colours = pixels.toSet()
            bitmap.recycle()
            done.countDown()
        }
        done.await(10, TimeUnit.SECONDS)
        return colours.size
    }

    @Test fun theBookTheAppWritesDrawsInARealWebView() {
        val epub = File(compose.activity.cacheDir, "render-test.epub").apply { writeEpub(articles) }
        try {
            compose.setContent {
                ArticlePreviewScreen(loadFile = { epub }, position = 0, title = "A heron on the roof", onBack = {})
            }
            compose.waitForIdle()
            val web = compose.activity.window.decorView.firstWebView()
            assertTrue("the preview has no WebView", web != null)

            // Loading and laying out the page is asynchronous and nothing here can observe the
            // WebViewClient, so wait for text to appear on the background.
            var colours = 0
            compose.waitUntil(timeoutMillis = 60_000) {
                colours = web!!.coloursDrawn()
                colours > 1
            }
            assertTrue("the page drew $colours colours: nothing was laid out", colours > 1)
        } finally {
            epub.delete()
        }
    }
}
