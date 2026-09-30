package com.app.newspaperss.core.epub

// Only properties and selectors e-readers support. Kindle's conversion drops rules with child or
// sibling combinators (`>`, `+`, `~`), pseudo-elements and max-width, so the edition's parts are
// matched by class alone; ArticleBody strips the articles' own classes so none collide.
// Font sizes are relative and no fonts are embedded so the reader's own font and size settings
// keep working, and the body has no side margins so the reader's margin setting is the only one.
// Links take the text's colour: a reader's dark mode inverts the text, but a fixed link colour
// stays dark on black. Borders take it too, so rules show in both modes.
// Headings are aligned explicitly: Kindle justifies whatever isn't, which opens wide gaps in a
// short line. Plain `left`, not `start`: Kindle doesn't list `start`, and might keep it as the
// last declaration and justify. Right-to-left articles get their own rules by `dir` instead.
internal const val EPUB_CSS = """body { margin: 0; font-family: serif; line-height: 1.5; overflow-wrap: break-word; word-wrap: break-word; }
a { color: inherit; }
h1, h2, h3, h4, h5, h6 { line-height: 1.2; text-align: left; hyphens: manual; -webkit-hyphens: manual; page-break-after: avoid; }
p { margin: 0 0 0.7em 0; }
.article-body p { text-align: justify; hyphens: auto; -webkit-hyphens: auto; -epub-hyphens: auto; }
.article-body h2 { font-size: 1.2em; margin: 1.3em 0 0.5em 0; }
.article-body h3, .article-body h4, .article-body h5, .article-body h6 { font-size: 1.05em; margin: 1.1em 0 0.4em 0; }
p.kicker { font-size: 0.75em; text-transform: uppercase; letter-spacing: 0.12em; margin: 0.8em 0 0.5em 0; }
h1.article-title { font-size: 1.6em; margin: 0 0 0.5em 0; }
p.byline { font-size: 0.85em; font-style: italic; margin: 0 0 0.8em 0; }
hr.rule { border: 0; border-top: 1px solid; margin: 0 0 1.2em 0; }
p.note { font-size: 0.85em; border: 1px solid; padding: 0.4em; }
p.end-mark { text-align: center; font-size: 1em; margin: 1.2em 0 0 0; }
p.source-link { font-size: 0.85em; margin-top: 1.2em; text-align: left; }
p.article-nav { font-size: 0.85em; margin-top: 0.6em; padding-top: 0.6em; border-top: 1px solid; text-align: left; }
img { max-width: 100%; height: auto; }
figure { margin: 1em 0; text-align: center; page-break-inside: avoid; }
figcaption { font-size: 0.8em; font-style: italic; text-align: left; margin-top: 0.3em; }
figcaption small { font-size: 1em; }
blockquote { margin: 1em 0 1em 3%; padding-left: 4%; border-left: 2px solid; }
pre { white-space: pre-wrap; font-size: 0.85em; }
table { border-collapse: collapse; margin: 1em 0; max-width: 100%; }
th, td { border: 1px solid; padding: 0.2em 0.4em; vertical-align: top; }
.cover { text-align: center; }
.cover-image { text-align: center; margin: 0; }
.cover .masthead { font-size: 2.2em; font-weight: bold; margin: 2em 0 0.3em 0; }
.cover .edition-title { font-size: 1.4em; margin: 0 0 1.5em 0; text-align: center; }
.totals { font-style: italic; }
p.dateline { font-size: 0.75em; text-transform: uppercase; letter-spacing: 0.12em; margin: 0 0 0.3em 0; }
h1.contents-title { font-size: 1.6em; margin: 0 0 0.3em 0; }
h2.section-title { font-size: 0.8em; text-transform: uppercase; letter-spacing: 0.12em; border-bottom: 1px solid; padding-bottom: 0.2em; margin: 1.4em 0 0.6em 0; }
h2.section-title span.meta { font-size: 1em; text-transform: none; letter-spacing: 0; font-weight: normal; }
ol.contents { padding-left: 1.6em; margin: 0; }
ol.contents li { margin-bottom: 0.7em; text-align: left; }
.meta { font-size: 0.8em; letter-spacing: 0.03em; }
h1.end-title { font-size: 1.4em; text-align: center; margin: 3em 0 0.6em 0; }
p.end-summary { text-align: center; font-size: 0.85em; margin: 0 0 2.5em 0; }
div.reflection { border-top: 1px solid; border-bottom: 1px solid; padding: 0.8em 0; margin: 0 8%; }
div.reflection p { text-align: left; margin: 0 0 0.4em 0; }
p.reflection-label { font-size: 0.75em; text-transform: uppercase; letter-spacing: 0.12em; }
p.end-imprint { text-align: center; font-size: 0.8em; margin-top: 3em; }
h1[dir="rtl"], ol.contents li[dir="rtl"], div[dir="rtl"] figcaption { text-align: right; }
div[dir="rtl"] blockquote { margin: 1em 3% 1em 0; padding-left: 0; padding-right: 4%; border-left: 0; border-right: 2px solid; }
"""
