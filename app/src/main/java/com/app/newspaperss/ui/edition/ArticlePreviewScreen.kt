package com.app.newspaperss.ui.edition

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
private const val BOOK_ORIGIN = "https://edition.newspaperss.invalid/"

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
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (!url.startsWith(BOOK_ORIGIN)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        val path = "OEBPS/" + url.removePrefix(BOOK_ORIGIN).substringBefore('#').substringBefore('?')
                        val bytes = pages.entry(path) ?: return null
                        return WebResourceResponse(EpubPages.mimeOf(path), "utf-8", ByteArrayInputStream(bytes))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith(BOOK_ORIGIN)) return false
                        // "Original:" links go to the phone's browser, not into the preview.
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                loadDataWithBaseURL(BOOK_ORIGIN, xhtml, "application/xhtml+xml", "utf-8", null)
            }
        },
    )
}
