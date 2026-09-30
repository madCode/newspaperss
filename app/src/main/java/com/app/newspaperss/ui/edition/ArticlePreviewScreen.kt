package com.app.newspaperss.ui.edition

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

// Pages are served under a made-up https origin so the book's relative links (style.css,
// images/…, the "Next" link) resolve against it; nothing is fetched from the network.
internal const val BOOK_ORIGIN = "https://edition.newspaperss.invalid/"

private sealed interface Preview {
    data object Loading : Preview
    data object Missing : Preview
    class Ready(val pages: EpubPages, val xhtml: String) : Preview
}

/**
 * One article as the e-reader will show it, read straight out of the EPUB.
 *
 * @param loadFile the edition's file, or null if it's gone. Called off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlePreviewScreen(loadFile: suspend () -> File?, position: Int, title: String, onBack: () -> Unit) {
    // Read in the background so the screen shows at once: reading a large edition during
    // composition holds up the frame, and the tap that opened it seems not to have registered.
    val preview by produceState<Preview>(Preview.Loading, position) {
        val pages = withContext(Dispatchers.IO) { loadFile()?.let(::EpubPages) }
        try {
            val xhtml = pages?.let { withContext(Dispatchers.IO) { it.article(position) } }
            value = if (pages != null && xhtml != null) Preview.Ready(pages, xhtml) else Preview.Missing
            awaitCancellation()
        } finally {
            pages?.close()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when (val p = preview) {
            Preview.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            Preview.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This edition's file is gone, so the article can't be shown.")
            }
            // Keyed: the WebView is built once, so a new article needs a new one.
            is Preview.Ready -> key(p) {
                val colors = MaterialTheme.colorScheme
                BookView(p.pages, p.xhtml, colors.background.toArgb(), colors.onBackground.toArgb(), Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

/**
 * What the preview's WebView gets for [url]: a file from the book, or nothing at all. Anything
 * outside the book, and anything missing from it, gets an empty body rather than being passed on,
 * so an article's HTML can never reach the network (no tracking pixels, fonts or stylesheets).
 */
internal fun bookResponse(url: String, pages: EpubPages, background: Int, text: Int): Pair<String, ByteArray> {
    if (!url.startsWith(BOOK_ORIGIN)) return "text/plain" to ByteArray(0)
    val path = "OEBPS/" + url.removePrefix(BOOK_ORIGIN).substringBefore('#').substringBefore('?')
    val bytes = pages.entry(path) ?: return "text/plain" to ByteArray(0)
    val mime = EpubPages.mimeOf(path)
    // A page reached by a link in the book ("Next") comes this way, not through loadData.
    if (mime == "application/xhtml+xml") return mime to forPreview(bytes.toString(Charsets.UTF_8), background, text).toByteArray()
    return mime to bytes
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookView(pages: EpubPages, xhtml: String, background: Int, text: Int, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Before the page loads too, so a dark screen doesn't flash white.
                setBackgroundColor(background)
                // The book has no scripts; keep the preview inert.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val (mime, bytes) = bookResponse(request.url.toString(), pages, background, text)
                        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
                    }

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
                loadDataWithBaseURL(BOOK_ORIGIN, forPreview(xhtml, background, text), "application/xhtml+xml", "utf-8", null)
            }
        },
    )
}

/**
 * The book leaves side margins and colours to the e-reader's own settings, so the preview supplies
 * them: a WebView has no margins, and would show the page black on white in the app's dark mode.
 * The book's links and rules take the text's colour, so they follow.
 */
internal fun forPreview(xhtml: String, background: Int, text: Int): String =
    xhtml.replaceFirst("</head>", "<style>body { margin: 0 5%; background: ${css(background)}; color: ${css(text)}; }</style></head>")

private fun css(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
