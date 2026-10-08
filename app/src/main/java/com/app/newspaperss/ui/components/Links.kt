package com.app.newspaperss.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [url] in the browser if it's a web address. Links come from feeds, and a feed's `intent:`,
 * `market:` or `tel:` link would otherwise go to whichever app claims it.
 *
 * @return whether a browser took it: some e-readers have none.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url.trim())
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
