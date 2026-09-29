package com.app.newspaperss.ui.edition

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File

// Pages are served under a made-up https origin so the book's relative links (style.css,
// images/…, the "Next" link) resolve against it; nothing is fetched from the network.
internal const val BOOK_ORIGIN = "https://edition.newspaperss.invalid/"

/** One article as the e-reader will show it, read straight out of the EPUB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlePreviewScreen(file: File?, position: Int, title: String, onBack: () -> Unit) {
    val pages = remember(file) { file?.takeIf { it.exists() }?.let(::EpubPages) }
    val xhtml = remember(pages, position) { pages?.article(position) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (pages == null || xhtml == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This edition's file is gone, so the article can't be shown.")
            }
        } else {
            BookView(pages, xhtml, Modifier.fillMaxSize().padding(padding))
        }
    }
}

/**
 * What the preview's WebView gets for [url]: a file from the book, or nothing at all. Anything
 * outside the book, and anything missing from it, gets an empty body rather than being passed on,
 * so an article's HTML can never reach the network (no tracking pixels, fonts or stylesheets).
 */
internal fun bookResponse(url: String, pages: EpubPages): Pair<String, ByteArray> {
    if (!url.startsWith(BOOK_ORIGIN)) return "text/plain" to ByteArray(0)
    val path = "OEBPS/" + url.removePrefix(BOOK_ORIGIN).substringBefore('#').substringBefore('?')
    val bytes = pages.entry(path) ?: return "text/plain" to ByteArray(0)
    return EpubPages.mimeOf(path) to bytes
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookView(pages: EpubPages, xhtml: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // The book has no scripts; keep the preview inert.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val (mime, bytes) = bookResponse(request.url.toString(), pages)
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
                loadDataWithBaseURL(BOOK_ORIGIN, xhtml, "application/xhtml+xml", "utf-8", null)
            }
        },
    )
}
