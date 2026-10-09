package com.app.newspaperss.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Whether [url] is a web address. Links come from feeds, and a feed's `intent:`, `market:` or
 * `tel:` link would otherwise go to whichever app claims it.
 */
fun isWebAddress(url: String): Boolean = Uri.parse(url.trim()).scheme?.lowercase() in setOf("http", "https")

/**
 * Opens [url] in the browser if it's a web address ([isWebAddress]).
 *
 * @return whether a browser took it: some e-readers have none.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    if (!isWebAddress(url)) return false
    val uri = Uri.parse(url.trim())
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
