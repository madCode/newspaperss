package com.app.newspaperss.ui.edition

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * The context the preview's WebView is made with. Selected text's Share is the WebView's own: it
 * builds a share of just the text and starts it through this context, with no hook for the app.
 * So the share is caught here, on its way out, and [source]'s address added under the text, so a
 * quote says where it's from.
 */
internal class QuoteShareContext(base: Context, private val source: () -> ArticleLink?) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) = super.startActivity(withSource(intent, source()))

    override fun startActivity(intent: Intent, options: Bundle?) = super.startActivity(withSource(intent, source()), options)
}

/**
 * [intent] with [link]'s address on its own line under the text, if it shares text, directly or
 * through a chooser; anything else, such as a link opened in the browser, goes out unchanged.
 */
internal fun withSource(intent: Intent, link: ArticleLink?): Intent {
    if (link == null) return intent
    val chooser = intent.action == Intent.ACTION_CHOOSER
    val send = if (chooser) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) else intent
    if (send?.action != Intent.ACTION_SEND) return intent
    val text = send.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return intent
    if (link.url in text) return intent
    send.putExtra(Intent.EXTRA_TEXT, "${text.trimEnd()}\n\n${link.url}")
    // An email's subject, where the app shared to has one.
    if (link.title.isNotEmpty() && !send.hasExtra(Intent.EXTRA_SUBJECT)) send.putExtra(Intent.EXTRA_SUBJECT, link.title)
    if (chooser) intent.putExtra(Intent.EXTRA_INTENT, send)
    return intent
}
