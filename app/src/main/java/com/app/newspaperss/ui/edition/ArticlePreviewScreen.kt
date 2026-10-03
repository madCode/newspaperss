package com.app.newspaperss.ui.edition

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.app.newspaperss.settings.PreviewTextSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

// Pages are served under a made-up https origin so the book's relative links (style.css,
// images/…, the "Next" link) resolve against it; nothing is fetched from the network.
internal const val BOOK_ORIGIN = "https://edition.newspaperss.invalid/"

private sealed interface Preview {
    data object Loading : Preview
    data object Missing : Preview
    class Ready(val pages: EpubPages, val xhtml: String, val link: ArticleLink?) : Preview
}

/** An article's title and the web address of its original, as the book's page gives them. */
internal data class ArticleLink(val title: String, val url: String)

/**
 * The original's address from a page's "Read the original at …" link, or null when the page has
 * none: the book only makes that a link for a web address, so whatever it holds can be shared.
 */
internal fun articleLink(xhtml: String): ArticleLink? {
    val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
    val url = doc.selectFirst("p.source-link a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
    return ArticleLink(doc.selectFirst("h1.article-title")?.text().orEmpty(), url)
}

/** [articleLink] of the book page at [url], or null if [url] isn't a page of the book. */
internal fun pageLink(url: String, pages: EpubPages): ArticleLink? {
    if (!url.startsWith(BOOK_ORIGIN)) return null
    val path = "OEBPS/" + url.removePrefix(BOOK_ORIGIN).substringBefore('#').substringBefore('?')
    if (EpubPages.mimeOf(path) != "application/xhtml+xml") return null
    return pages.entry(path)?.toString(Charsets.UTF_8)?.let(::articleLink)
}

internal fun shareIntent(link: ArticleLink, fallbackTitle: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, link.title.ifEmpty { fallbackTitle })
        putExtra(Intent.EXTRA_TEXT, link.url)
    }
    return Intent.createChooser(send, null)
}

/**
 * One article as the e-reader will show it, read straight out of the EPUB.
 *
 * @param loadFile the edition's file, or null if it's gone. Called off the main thread.
 * @param textSize the preview's text size, chosen in Settings, on top of Android's font size; null
 *   until it's known, and the page waits for it rather than being laid out twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlePreviewScreen(
    loadFile: suspend () -> File?,
    position: Int,
    title: String,
    onBack: () -> Unit,
    textSize: PreviewTextSize? = PreviewTextSize.DEFAULT,
) {
    // Read in the background so the screen shows at once: reading a large edition during
    // composition holds up the frame, and the tap that opened it seems not to have registered.
    val preview by produceState<Preview>(Preview.Loading, position) {
        val pages = withContext(Dispatchers.IO) { loadFile()?.let(::EpubPages) }
        try {
            val xhtml = pages?.let { withContext(Dispatchers.IO) { it.article(position) } }
            val link = xhtml?.let { withContext(Dispatchers.IO) { articleLink(it) } }
            // Set on the main thread: under a test's unconfined dispatcher the code after
            // withContext(IO) can resume on the IO thread, and the new state then touches views.
            withContext(Dispatchers.Main.immediate) {
                value = if (pages != null && xhtml != null) Preview.Ready(pages, xhtml, link) else Preview.Missing
            }
            awaitCancellation()
        } finally {
            pages?.close()
        }
    }
    // The page on screen can change under the preview: a long article ends in a "Next" link.
    var link by remember(preview) { mutableStateOf((preview as? Preview.Ready)?.link) }
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf<Job?>(null) }
    val onPage = { url: String, pages: EpubPages ->
        // Only book pages: the first page is loaded as data and may report about:blank.
        if (url.startsWith(BOOK_ORIGIN)) {
            reading?.cancel()
            reading = scope.launch {
                val read = withContext(Dispatchers.IO) { pageLink(url, pages) }
                // Set on the main thread, where a newer page cancels this read: resumed on the IO
                // thread (as under a test's unconfined dispatcher), a read cancelled a moment too
                // late could write its page's link over the newer one.
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    link = read
                }
            }
        }
    }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    link?.let { l ->
                        IconButton(onClick = { runCatching { context.startActivity(shareIntent(l, title)) } }) {
                            Icon(Icons.Default.Share, contentDescription = "Share link")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val p = preview
        when {
            p == Preview.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This edition's file is gone, so the article can't be shown.")
            }
            // Words, not an animated bar, which smears on e-ink.
            p !is Preview.Ready || textSize == null -> Text("Opening…", Modifier.padding(padding).padding(24.dp))
            else -> {
                val colors = MaterialTheme.colorScheme
                // Setting textZoom replaces the WebView's own scaling by Android's font size, so apply
                // that here: someone who reads with large system text gets it at "Default" too.
                val textZoom = (textSize.percent * LocalDensity.current.fontScale).roundToInt()
                val justify = justifies(textSize, textZoom)
                // Keyed: the WebView is built once, so a new article, or a new alignment in its
                // stylesheet, needs a new one. A new zoom alone is applied in place.
                key(p, justify) {
                    BookView(p.pages, p.xhtml, colors.background.toArgb(), colors.onBackground.toArgb(), textZoom, justify, BOOK_ORIGIN + EpubPages.articleHref(position), { onPage(it, p.pages) }, Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
}

/**
 * Whether the preview justifies paragraphs, as the book does. At Larger and up, or the same size
 * reached through Android's font size, a narrow phone fits a few words a line and justifying
 * opens wide gaps between them, so paragraphs are left-aligned instead.
 */
internal fun justifies(textSize: PreviewTextSize, textZoom: Int): Boolean =
    textSize < PreviewTextSize.LARGER && textZoom < PreviewTextSize.LARGER.percent

/**
 * What the preview's WebView gets for [url]: a file from the book, or nothing at all. Anything
 * outside the book, and anything missing from it, gets an empty body rather than being passed on,
 * so an article's HTML can never reach the network (no tracking pixels, fonts or stylesheets).
 */
internal fun bookResponse(url: String, pages: EpubPages, background: Int, text: Int, justify: Boolean): Pair<String, ByteArray> {
    if (!url.startsWith(BOOK_ORIGIN)) return "text/plain" to ByteArray(0)
    val path = "OEBPS/" + url.removePrefix(BOOK_ORIGIN).substringBefore('#').substringBefore('?')
    val bytes = pages.entry(path) ?: return "text/plain" to ByteArray(0)
    val mime = EpubPages.mimeOf(path)
    // A page reached by a link in the book ("Next") comes this way, not through loadData.
    if (mime == "application/xhtml+xml") return mime to forPreview(bytes.toString(Charsets.UTF_8), background, text, imageSizes(pages), justify).toByteArray()
    return mime to bytes
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookView(pages: EpubPages, xhtml: String, background: Int, text: Int, textZoom: Int, justify: Boolean, pageUrl: String, onPage: (url: String) -> Unit, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        // Applied in place, so a new size keeps the reader's place in the article.
        update = { it.settings.textZoom = textZoom },
        factory = { context ->
            WebView(context).apply {
                // Before the page loads too, so a dark screen doesn't flash white.
                setBackgroundColor(background)
                // The book has no scripts; keep the preview inert.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Pinch to zoom, for a comic's small print; no on-screen buttons over the page.
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val (mime, bytes) = bookResponse(request.url.toString(), pages, background, text, justify)
                        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onPage(url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.toString().startsWith(BOOK_ORIGIN)) return false
                        // "Original:" links go to the phone's browser, not into the preview; only
                        // web links someone actually tapped.
                        if (request.hasGesture() && url.scheme in setOf("http", "https", "mailto")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        return true
                    }
                }
                // The page's own address in the book, not the bare origin: its footnote links then
                // resolve to this page, and the Share link follows the page on screen.
                loadDataWithBaseURL(pageUrl, forPreview(xhtml, background, text, imageSizes(pages), justify), "application/xhtml+xml", "utf-8", null)
            }
        },
    )
}

/**
 * The book leaves side margins and colours to the e-reader's own settings, so the preview supplies
 * them: a WebView has no margins, and would show the page black on white in the app's dark mode.
 * The book's links and rules take the text's colour, so they follow.
 *
 * A large picture that stands alone (alone in its figure, or an article that is just the image)
 * fills the width: a tall strip shrunk to the book's image size would otherwise sit narrow in the
 * middle. Small ones (a headshot, a logo, a row of icons) keep their size, as in the book.
 *
 * @param imageSize an image's width and height in pixels by its `src`, or null if unknown.
 * @param justify false to left-align the article's paragraphs (see [justifies]).
 */
internal fun forPreview(xhtml: String, background: Int, text: Int, imageSize: (src: String) -> Pair<Int, Int>? = { null }, justify: Boolean = true): String {
    // `start`, not `left`, so a right-to-left article lines up on its right; the book itself says
    // `left` only because Kindle doesn't know `start`.
    val align = if (justify) "" else " .article-body p { text-align: start; }"
    val style = "<style>body { margin: 0 5%; background: ${css(background)}; color: ${css(text)}; } img.$FILL { width: 100%; }$align</style></head>"
    return markLargeImages(xhtml, imageSize).replaceFirst("</head>", style)
}

private const val FILL = "preview-fill"
// Wide enough to be the picture rather than an icon, or a strip shrunk by the book's 1200px limit.
private const val FILL_MIN_WIDTH = 240
private const val FILL_MIN_HEIGHT = 800

private fun markLargeImages(xhtml: String, imageSize: (String) -> Pair<Int, Int>?): String {
    if (!xhtml.contains("<img")) return xhtml
    val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
    val large = doc.select("img").filter { img ->
        val parent = img.parent() ?: return@filter false
        val alone = (parent.tagName() == "figure" && parent.select("img").size == 1) ||
            (parent.tagName() == "div" && parent.hasClass("article-body"))
        val size = if (alone) imageSize(img.attr("src")) else null
        size != null && (size.first >= FILL_MIN_WIDTH || size.second >= FILL_MIN_HEIGHT)
    }
    if (large.isEmpty()) return xhtml
    large.forEach { it.addClass(FILL) }
    doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
    return doc.outerHtml()
}

/** Reads each image's size from its header in the book, without decoding the picture. */
internal fun imageSizes(pages: EpubPages): (String) -> Pair<Int, Int>? = { src ->
    pages.entry("OEBPS/$src")?.let { bytes ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        (options.outWidth to options.outHeight).takeIf { options.outWidth > 0 && options.outHeight > 0 }
    }
}

private fun css(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
