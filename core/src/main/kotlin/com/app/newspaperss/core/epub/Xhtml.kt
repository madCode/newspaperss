package com.app.newspaperss.core.epub

import com.app.newspaperss.core.extract.LanguageDetector
import java.net.URI
import java.net.URISyntaxException

/** Escapes [text] for XML text or a double- or single-quoted attribute value. */
internal fun esc(text: String): String {
    val out = StringBuilder(text.length + 16)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when {
            cp == '&'.code -> out.append("&amp;")
            cp == '<'.code -> out.append("&lt;")
            cp == '>'.code -> out.append("&gt;")
            cp == '"'.code -> out.append("&quot;")
            cp == '\''.code -> out.append("&#39;")
            isXmlChar(cp) -> out.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return out.toString()
}

/** Removes characters XML 1.0 can't contain even escaped: most control characters and lone surrogates. */
internal fun stripInvalidXmlChars(text: String): String {
    if (text.all { isXmlChar(it.code) && !it.isSurrogate() }) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isXmlChar(cp)) out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

private fun isXmlChar(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
        cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

/**
 * [url] as an href an EPUB can carry, or null. Send to Kindle and EPUB validators reject hrefs
 * that aren't valid URIs (spaces, a second `#`), and a relative link would point at a file the
 * book doesn't have.
 */
internal fun externalHref(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.any { it == '"' || it == '<' || it == '>' || it == '\\' }) return null
    val hash = trimmed.indexOf('#')
    val fixed = (if (hash < 0) trimmed else trimmed.substring(0, hash + 1) + trimmed.substring(hash + 1).replace("#", "%23"))
        .replace(" ", "%20")
    val uri = try {
        URI(fixed)
    } catch (_: URISyntaxException) {
        return null
    }
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> if (uri.host.isNullOrEmpty()) null else fixed
        "mailto" -> fixed
        else -> null
    }
}

/**
 * ` xml:lang="…" lang="…"` for an element whose text is in [language], or "" where the page's
 * [pageLanguage] already covers it. Right-to-left languages also get `dir="rtl"`.
 */
internal fun languageAttributes(language: String?, pageLanguage: String): String {
    val tag = LanguageDetector.normalize(language) ?: return ""
    if (tag.equals(pageLanguage, ignoreCase = true)) return ""
    val dir = if (LanguageDetector.isRightToLeft(tag)) " dir=\"rtl\"" else ""
    return " xml:lang=\"${esc(tag)}\" lang=\"${esc(tag)}\"$dir"
}

/** A complete XHTML content document around [body], which must already be escaped markup. */
internal fun xhtmlPage(title: String, language: String, body: String, bodyClass: String? = null): String {
    val lang = esc(language)
    val cls = if (bodyClass == null) "" else " class=\"${esc(bodyClass)}\""
    return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
<head>
<meta charset="utf-8"/>
<title>${esc(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body$cls>
$body
</body>
</html>
"""
}
