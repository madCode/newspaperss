package com.app.newspaperss.core.epub

// Only properties e-readers support. Font sizes are relative and no fonts are embedded so the
// reader's own font and size settings keep working.
// Links take the text's colour: a reader's dark mode inverts the text, but a fixed link colour
// stays dark on black. Borders take it too, so rules show in both modes. The edition's own
// parts are matched as children of body: an article's HTML keeps its classes ("kicker" is common).
internal const val EPUB_CSS = """body { margin: 0 3%; font-family: serif; line-height: 1.5; overflow-wrap: break-word; }
a { color: inherit; }
h1, h2, h3, h4, h5, h6 { line-height: 1.2; page-break-after: avoid; }
p { margin: 0 0 0.7em 0; }
.article-body p { text-align: justify; hyphens: auto; -webkit-hyphens: auto; -epub-hyphens: auto; }
body > p.kicker { font-size: 0.75em; text-transform: uppercase; letter-spacing: 0.12em; margin: 0.8em 0 0.5em 0; }
body > h1.article-title { font-size: 1.6em; line-height: 1.15; margin: 0 0 0.5em 0; }
body > p.byline { font-size: 0.85em; font-style: italic; margin: 0 0 0.8em 0; }
body > hr.rule { border: 0; border-top: 1px solid; margin: 0 0 1.2em 0; }
body > p.note { font-size: 0.85em; border: 1px solid; padding: 0.4em; }
body > p.source-link { font-size: 0.85em; margin-top: 1.5em; }
body > p.article-nav { font-size: 0.85em; margin-top: 0.6em; padding-top: 0.6em; border-top: 1px solid; }
img { max-width: 100%; height: auto; }
figure { margin: 1em 0; text-align: center; page-break-inside: avoid; }
figcaption { font-size: 0.8em; font-style: italic; }
blockquote { margin: 1em 1.5em; font-style: italic; }
pre { white-space: pre-wrap; font-size: 0.85em; }
table { border-collapse: collapse; margin: 1em 0; max-width: 100%; }
th, td { border: 1px solid; padding: 0.2em 0.4em; vertical-align: top; }
.cover { text-align: center; }
.cover-image { text-align: center; margin: 0; }
.cover .masthead { font-size: 2.2em; font-weight: bold; margin: 2em 0 0.3em 0; }
.cover .edition-title { font-size: 1.4em; margin: 0 0 1.5em 0; }
.totals { font-style: italic; }
body > h2 { font-size: 0.8em; text-transform: uppercase; letter-spacing: 0.12em; border-bottom: 1px solid; padding-bottom: 0.2em; margin: 1.4em 0 0.6em 0; }
body > ol.contents { padding-left: 1.6em; }
body > ol.contents li { margin-bottom: 0.7em; }
.meta { font-size: 0.8em; letter-spacing: 0.03em; }
.end { text-align: center; margin-top: 3em; font-size: 1.3em; }
"""
