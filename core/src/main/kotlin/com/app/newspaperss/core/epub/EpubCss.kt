package com.app.newspaperss.core.epub

// Only properties e-readers support. Font sizes are relative and no fonts are embedded so the
// reader's own font and size settings keep working.
internal const val EPUB_CSS = """body { margin: 0 2%; font-family: serif; line-height: 1.45; }
h1, h2, h3, h4, h5, h6 { line-height: 1.2; page-break-after: avoid; }
h1.article-title { margin: 0 0 0.4em 0; }
p { margin: 0 0 0.8em 0; }
p.byline { font-size: 0.85em; font-style: italic; margin-bottom: 1.4em; }
p.note { font-size: 0.85em; border: 1px solid #888; padding: 0.4em; }
p.source-link, p.article-nav { font-size: 0.85em; margin-top: 1.5em; }
img { max-width: 100%; height: auto; }
figure { margin: 1em 0; text-align: center; page-break-inside: avoid; }
figcaption { font-size: 0.8em; font-style: italic; }
blockquote { margin: 1em 1.5em; font-style: italic; }
pre { white-space: pre-wrap; font-size: 0.85em; }
table { border-collapse: collapse; margin: 1em 0; }
th, td { border: 1px solid #888; padding: 0.2em 0.4em; vertical-align: top; }
.cover { text-align: center; }
.cover-image { text-align: center; margin: 0; }
.masthead { font-size: 2.2em; font-weight: bold; margin: 2em 0 0.3em 0; }
.edition-title { font-size: 1.4em; margin: 0 0 1.5em 0; }
.totals { font-style: italic; }
ol.contents li { margin-bottom: 0.5em; }
.meta { font-size: 0.8em; }
.end { text-align: center; margin-top: 3em; font-size: 1.3em; }
"""
