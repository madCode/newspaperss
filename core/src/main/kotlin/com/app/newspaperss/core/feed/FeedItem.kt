package com.app.newspaperss.core.feed

import java.time.Instant

data class Feed(
    val title: String?,
    val siteUrl: String?,
    val items: List<FeedItem>,
)

/**
 * One entry from a feed.
 *
 * @param guid the feed's own id for the entry, or its url when it has none.
 * @param contentHtml the fullest body the feed offers (content:encoded, Atom
 *   content, JSON Feed content_html), or its summary if that's all there is.
 */
data class FeedItem(
    val guid: String,
    val url: String,
    val title: String,
    val contentHtml: String?,
    val author: String?,
    val published: Instant?,
)

open class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A feed over the size limit that can't be read in part; its message is fit to show the reader. */
class FeedTooLargeException(message: String) : FeedParseException(message)
