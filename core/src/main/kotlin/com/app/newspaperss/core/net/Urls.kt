package com.app.newspaperss.core.net

import java.net.URI
import java.net.URISyntaxException

/**
 * The site part of [url] for showing to the reader: "example.com" for
 * "https://www.example.com:8080/feed?x=1". A URL java.net.URI can't parse or that has no host
 * (no scheme, spaces) falls back to the text between "://" and the first '/', and a URL with
 * nothing there to [url] itself, so a name is never blank.
 */
fun hostOf(url: String): String {
    val trimmed = url.trim()
    val host = try {
        URI(trimmed).host
    } catch (e: URISyntaxException) {
        null
    } ?: trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return host.removePrefix("www.").ifBlank { url }
}
