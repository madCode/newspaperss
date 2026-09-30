package com.app.newspaperss.core.net

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

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

/**
 * The domain a site is registered under: "theguardian.co.uk" for "https://www.theguardian.co.uk/x",
 * or null for anything but an http(s) URL with a host. A short list of second-level labels
 * ("co.uk", "com.au") stands in for the public suffix list, which is too big to ship for this.
 */
fun registrableDomainOf(url: String): String? {
    val labels = hostLabels(url) ?: return null
    return labels.drop(registrableIndex(labels)).joinToString(".")
}

/**
 * The name a site goes by in referral tags: "longreads" for longreads.com. For a host below its
 * registrable domain, its own leftmost label ("foo" for foo.substack.com): a tag naming the
 * platform (`utm_source=substack`) says nothing about which of its sites put it there.
 */
fun siteNameOf(url: String): String? {
    val labels = hostLabels(url) ?: return null
    val index = registrableIndex(labels)
    return if (index > 0) labels.first() else labels[index]
}

/** Whether [url] has a referral parameter (`src=longreads`, `utm_source=longreads.com`) naming one of [siteNames]. */
fun creditsSite(url: String, siteNames: Set<String>): Boolean =
    queryOf(url).any { (key, value) -> key.lowercase() in CREDIT_KEYS && namesSite(value, siteNames) }

/**
 * [url] without tracking parameters: `utm_*`, click ids, and a referral parameter naming one of
 * [siteNames], so a story linked from two sites compares equal. Done on the text, leaving other
 * parameters, their order and encoding as they were: a site may need them exactly so.
 */
fun withoutTracking(url: String, siteNames: Set<String> = emptySet()): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
    val kept = url.substring(queryStart + 1, fragmentStart).split('&').filter { pair ->
        val key = decode(pair.substringBefore('=')).lowercase()
        val tracking = key.startsWith("utm_") || key in CLICK_IDS ||
            (key in REFERRAL_KEYS && namesSite(decode(pair.substringAfter('=', "")), siteNames))
        pair.isNotEmpty() && !tracking
    }
    val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    return url.substring(0, queryStart) + query + url.substring(fragmentStart)
}

private fun hostLabels(url: String): List<String>? {
    val uri = try {
        URI(url.trim())
    } catch (e: URISyntaxException) {
        return null
    }
    if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
    val labels = uri.host?.lowercase()?.trimEnd('.')?.split('.')?.filter { it.isNotEmpty() } ?: return null
    if (labels.size < 2) return null
    return if (labels.first() in LEADING_LABELS && labels.size > 2) labels.drop(1) else labels
}

private fun registrableIndex(labels: List<String>): Int {
    val underCountry = labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL
    return labels.size - if (underCountry) 3 else 2
}

private fun queryOf(url: String): List<Pair<String, String>> =
    url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }
        .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }

/**
 * A whole word of [value] is one of the names, or, for a name too long to turn up by chance,
 * [value] contains it ("longreads" in "www.longreads.com", "the-fence" in "thefence").
 */
private fun namesSite(value: String, siteNames: Set<String>): Boolean {
    val lower = value.lowercase()
    val words = lower.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }
    val squashed = lower.replace(NOT_ALPHANUMERIC, "")
    return siteNames.any { name ->
        val squashedName = name.lowercase().replace(NOT_ALPHANUMERIC, "")
        squashedName.isNotEmpty() && (squashedName in words || (squashedName.length >= 5 && squashedName in squashed))
    }
}

// The Charset overload is Java 10 and Android 13, later than the app supports.
private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

private val WEB_SCHEMES = setOf("http", "https")
private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")
private val LEADING_LABELS = setOf("www", "m")
private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go", "gob", "nic", "ltd", "plc", "sch", "nhs")
private val CLICK_IDS = setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid")
private val REFERRAL_KEYS = setOf("src", "ref", "source", "via", "ref_src")
private val CREDIT_KEYS = REFERRAL_KEYS + "utm_source"
