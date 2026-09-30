# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **In flight:** lighter extraction and the docs regrouping ([#40](https://github.com/madCode/newspaperss/pull/40)); Chinese and Japanese reading time.
- **Next:** the Le Monde script wall; the rest of the accessibility audit; saying why there's no edition.
- **Waiting on you:** [#18](https://github.com/madCode/newspaperss/issues/18), a Dropbox app key for automatic Kobo delivery (optional). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Day 2 · Tue 29 Sep, afternoon

### Cycle 26: Chinese and Japanese reading time (17:20–)
- **From the language work:** Chinese and Japanese have no spaces between words, so a whole NHK paragraph counted as one word: no reading time, and the full-text check would take a long Japanese feed for a teaser and fetch pages it didn't need.
- **Shipped:** one word count everywhere, which counts each Chinese or Japanese character as about 2/3 of a word (people read about 350 characters a minute, against 238 English words). Korean uses spaces and is counted by word.
- **Checks:** a live edition with BBC Chinese gives 3–7 minute articles instead of seconds, and passes epubcheck.

### Cycle 25: where things are tracked (17:15–17:20, in [#40](https://github.com/madCode/newspaperss/pull/40))
- **From your question:** onboarding items and the research were hard to find. The backlog was sorted by where each item came from, and the persona audits only existed as backlog bullets.
- **Shipped:** the backlog is grouped by part of the app (onboarding, sources, the book, delivery, reading list, accessibility, performance), each item tagged with its origin. The two persona audits are written up in [research/personas.md](research/personas.md), with each finding's status. The README has a short documentation index.

### Cycle 24: lighter extraction (17:00–17:15, [#40](https://github.com/madCode/newspaperss/pull/40))
- **From the resource audit:** the worst memory peak was a large page parsed and then copied whole for Readability.
- **Shipped:** scripts, styles and SVGs are dropped right after the page's JSON-LD is read, before the copy; on script-heavy sites they're most of the page. On a 2.6 MB test page that's 36 MB allocated before and 16 MB after, in half the time. Pages over 5 MB aren't parsed at all; the feed's text is used with a note.
- **Checks:** a live edition gives the same articles with the same word counts and passes epubcheck; xkcd, the New Yorker cartoon and Godslave all still get their image. The live-edition tool now prints each article's image count.
- **Review caught:** a page that fails for its own reasons (too large, no connection) was counted as "the feed's text is enough", so a source whose pages are 5–10 MB would have been switched to teasers for good. A failed page now counts as no evidence either way. Declarative shadow-DOM templates, which are shown on the page, are kept.
- **Left:** a saved link that can never be read (a PDF, a video, a huge page) waits silently, as it did before for anything over 10 MB. Putting it in the edition as a "couldn't fetch" page was tried and dropped: such pages cost no reading time, so a backlog of saved PDFs could fill an edition with them. The reading list should show these links as unreadable instead; that's in the backlog.

### Cycle 23: a documentation pass (16:50–17:00, in [#39](https://github.com/madCode/newspaperss/pull/39))
- **Why:** you asked for documentation passes in the cycles: readable, current, not onerous. CLAUDE.md now says so: behaviour changes update the docs in the same PR, and every few cycles a pass checks the docs against the code.
- **Shipped:** DESIGN.md rewritten to describe the app as it is. It had SMTP delivery, edition profiles, user-named sections and a reading-speed setting that don't exist, and nothing on today's changes: timed editions starting early, the feed cache and 12-hour sync, when an edition counts as delivered, housekeeping, comics, language tags, the preview. The finished milestone plan and history are gone (BACKLOG and this log have them), and it's shorter. README: the delivery rule, "ready by", the book's features, a link to the debug APK, and fresh screenshots. CLAUDE.md: database migrations and debug builds.
- **How:** an agent checked every statement in the three docs against the code and listed what was wrong or missing, with file references; I rewrote from that.

### Cycle 22: TalkBack and large fonts (16:30–16:50, [#39](https://github.com/madCode/newspaperss/pull/39))
- **Why:** the persona audit's TalkBack items, and you asked for an accessibility audit. These are the known gaps; the full audit is in the backlog.
- **Shipped:** Today announces what the build is doing ("Checking your sources", "Making your edition", and the result) through a live region. The running count isn't announced, or TalkBack would read every number. Onboarding's progress bar says "Step 2 of 3" instead of "66 percent". Earlier editions say what tapping does. Onboarding's Add and the reading list's Save moved below their text fields, where a 200% font can't squeeze out the space to type.
- **Review caught:** in Compose a live region announces when its text changes, not when it first appears, so the failure message and "Checking…" would have been silent. And if they had spoken, a failure WorkManager still remembered would have been read out every time Today opened. The status is now one always-present line whose text changes, and it's only live once a build has run while the screen is up. Onboarding's Add button stays put instead of being swapped for "Checking…" under TalkBack's focus. A second look found a new reader's very first "Checking…" still silent (the list rebuilt the panel when the first-edition prompt went away; items are keyed now), and success said nothing; it now says "Your edition is ready." Still to confirm with TalkBack on a device.

### Cycle 21: articles know their language (16:15–16:30, [#38](https://github.com/madCode/newspaperss/pull/38))
- **Why:** your note on languages. E-readers choose hyphenation, fonts and text direction from `xml:lang`, and every article was tagged English, so a French article was hyphenated with English rules and an Arabic one laid out left to right.
- **Shipped:** each article's headline and body are tagged with its language, and right-to-left languages get `dir="rtl"`. The text decides: the writing system for non-Latin scripts, and common words for English, French, German, Spanish, Italian, Portuguese and Dutch. The page's declared language breaks ties, and is kept when it agrees because it's more precise (pt-BR, Persian in Arabic script). Many sites declare the same language on every page, and a feed's text declares nothing. No database change.
- **Checks:** a live edition from Le Monde, Spiegel, El País, NHK, Al Jazeera Arabic, the Guardian and g1 tagged every article correctly and passes epubcheck. The Arabic page lays out right to left, with the English kicker and byline left to right.
- **Review caught:** ordinary Spanish and Italian news came out French: the word lists left out "la", so every "la" counted for French alone. The lists now include each language's most frequent words, and a shared word counts for much less. A page declaring Persian or Urdu site-wide could have turned an English caption right to left: Latin text now never takes a tag in another script. Galician read as Portuguese over the page's own "gl": a Latin language the lists don't know keeps the page's tag. Declared regions like "en-UK" (no such region) are dropped, a kanji-heavy Japanese page stays Japanese, and headlines in the contents and "Next" links are tagged too. A second look found Latin-script Serbian taken for Portuguese over the page's "sr-Latn", and Danish for English (the English list had "at" and "for"); both keep the page's tag now. A second live edition added Italian (ANSA) and Dutch (NOS); all eight languages are tagged right.
- **Found on the way:** NHK's Japanese articles count as "1 word" (reading time assumes spaces), and Le Monde serves a script wall the bot check doesn't catch. Both are in the backlog.

### Cycle 20: the first tap on an article (16:00–16:15, [#37](https://github.com/madCode/newspaperss/pull/37))
- **From device testing:** the first tap on an article seemed not to open it, and the article was a little wider than the phone.
- **Found:** the width came from this morning's EPUB, which printed the full original URL on its own line with no word breaking; the EPUB design round replaced both. A fresh live edition (11 articles, cover and contents) rendered at 360px wide has nothing wider than the screen. The tap wasn't being dropped: the preview read the EPUB during composition, and the first WebView of a session starts slowly, so the screen held still long enough to look ignored.
- **Shipped:** the preview appears at once with a progress bar, and the book is read in the background. One open zip serves the page and all its images, instead of reopening the zip for each one.
- **Review caught:** closing the book while the WebView was still reading an image could crash the app (ZipFile.close() ends the inflater mid-read and throws a NullPointerException), so reads now hold a lock that close waits for. The top bar had lost its fallback to the edition's name.

### Cycle 19: fewer downloads, fewer wakeups (15:44–16:00, [#36](https://github.com/madCode/newspaperss/pull/36))
- **Shipped:** a 10 MB HTTP cache, with every feed and page request revalidated (If-None-Match), so an unchanged feed answers 304 instead of downloading again (about 180 MB a month at 20 feeds). The background sync runs every 12 hours with the battery not low, instead of every 4: each edition syncs right before it's built.
- **Review caught:** OkHttp would serve a feed it judged "fresh" without asking at all (max-age, or a guess from Last-Modified), so a new post could miss an edition. Requests now ask for max-age=0, which forces the conditional request (no-cache would skip the cache altogether, as the test found). Re-applying the schedule with UPDATE on every start cost wakeups; it's a new name with KEEP.
- **Left:** tt-rss `sinceId`: a since-id cursor changes what "unread" returns, the trap rss-to-e-reader's #28 hit. In the backlog with that note.

### Cycle 18: resource audit, and housekeeping (15:30–15:44, [#35](https://github.com/madCode/newspaperss/pull/35))
- **Audit:** typical peak memory while building is about 80 MB, worst case about 220 MB (a 10 MB page parsed twice). Bitmaps live outside the Java heap on Android 8+, and the EPUB is written straight to disk. So **streaming images to disk isn't worth it** (at most 15 MB) and is dropped. The real costs were elsewhere.
- **Shipped:** only the newest 14 editions keep their EPUB (5–12 MB each, gigabytes a year otherwise); articles a month past delivery drop their feed text but keep their row (the database would have outgrown Auto Backup's 25 MB in months, silently stopping backup); the image budget counts as spent once nothing more fits (before, every later article downloaded and discarded its images).
- **Review caught:** an empty image budget no longer counted as spent (a test caught it too), and the month was counted from discovery, so a long-saved link sent today lost its text at once.
- **Next from the audit** (in the backlog): a feed cache (about 180 MB a month of repeat downloads), a 12-hour background sync, tt-rss `sinceId`, and trimming the page DOM before Readability's clone.

### Cycle 17: the reading list (15:17–15:30, [#34](https://github.com/madCode/newspaperss/pull/34))
- **From device testing:** saved links showed only their site, with no reading time, and tapping one did nothing.
- **Shipped:** every saved link's page is looked up once: its title if it has none, and its length for "site · N min read". Rows open in the browser. Until a title is found, a row shows one made from the address instead of the bare domain.
- **The first migration:** the length is a new column, so the database goes to version 2 with a migration and a test that validates it against the exported schema. Your installed debug build upgrades in place.
- **Review caught:** the site shown twice on an untitled row; one page the extractor chokes on would cancel the queued lookups behind it. Both fixed.

### Cycle 16: the source page (15:14–15:17, [#33](https://github.com/madCode/newspaperss/pull/33))
- **From device testing:** the recent-articles list made you squint, and "Recent articles" didn't stand out; "Article text" didn't say what it set.
- **Shipped:** "Recent articles · N" as a real heading; a status mark per article in its own column (● waiting, ✓ delivered, ○ not used: shapes, not colours, for e-ink); medium-weight titles; inset dividers; "Article text: Automatic / Feed's text / Full page".
- **Review caught:** "Site's text" read as the web page (now "Feed's text"); TalkBack read the heading's middle dot aloud; faint dividers would vanish on a Boox's greys.

### Cycle 15: webcomics (15:07–15:14, [#32](https://github.com/madCode/newspaperss/pull/32))
- **From device testing:** God Slave, Namesake and Cursed Princess Club (Webtoons), on top of the New Yorker cartoon and xkcd.
- **Shipped:** the feed finder follows an ordinary link to a feed (God Slave's `/comic/rss`), shortest path first and never a comments feed; a page's own comic (`img#cc-comic`, `#comic`), every panel, beats the feed's thumbnail, captioned by the feed's text.
- **Checks:** God Slave and Namesake now give their full comic, xkcd its 2x image, the New Yorker its cartoon; a live edition passes epubcheck.
- **Left:** Webtoons. The app is redirected to its mobile site, which doesn't link the feed, and an episode is a strip of dozens of images. Parked in the backlog: vertical colour strips suit e-ink poorly.
- **Review caught:** the caption strip removed a `<figcaption>` (often the joke); only the first panel survived; a tag or comments feed could beat the site's. All fixed.

### Cycle 14: an app icon (14:55–15:06, [#31](https://github.com/madCode/newspaperss/pull/31))
- **From device testing:** the launcher showed Android's default icon, and you asked for something design-forward like the Termux shortcut's.
- **Shipped:** an adaptive icon: a cream newspaper page with a navy masthead, a red photo and grey columns, on navy. Themed icons use the notification icon's newspaper as one shape.

### Cycle 13: EPUB design, round 1 (14:41–14:55, [#30](https://github.com/madCode/newspaperss/pull/30))
- **From device testing:** the EPUB wasn't as good-looking as rss-to-e-reader's; links were too dark in KOReader's night mode; the in-app article was a little wider than the phone.
- **Shipped:** each article opens with its source as a small-caps kicker, the title, "By … · date · N min read" and a hairline rule; body text is justified and hyphenated; links and rules take the text's colour, so night mode inverts them too; "Read the original at propublica.org" replaces the full URL, which had nowhere to break; the contents page reads "In this edition" with plain titles and small source lines.
- **Checks:** rendered at e-reader size in Chromium, light and night, before and after; a live edition passes epubcheck.
- **Review caught:** the new styles weren't scoped, so an article's own `class="kicker"` would take the edition's look; contents entries had lost the underline that marks a link on e-ink. Both fixed.

### Cycle 12: see what's inside, delete an edition (14:24–14:40, [#29](https://github.com/madCode/newspaperss/pull/29))
- **From device testing:** you couldn't find what went into an edition before sending it (tapping the title worked, but nothing said so), and couldn't delete one.
- **Shipped:** a "See what's inside" button on the Today card, and Delete on the edition screen. An unsent edition's articles go into the next one; a sent one's stay used.
- **Review caught:** deleting freed the title, so a remake the same morning got the exact same title and Send to Kindle would drop it silently. The row now stays as a hidden "deleted" marker holding the title.

### Cycle 11: comics keep their image (14:12–14:24, [#28](https://github.com/madCode/newspaperss/pull/28))
- **From device testing:** the New Yorker's cartoon and a webcomic reached the edition without their images. Two causes:
  - xkcd-style feed items are just the image, and an item with no words was treated as empty;
  - on an image-only page the article extractor gave up and took the footer ("58 words" of legal links on the New Yorker's cartoon page).
- **Shipped:** an image-only feed item is kept, and wins over a page with little text and no image. When only the page has the image, its main image inside `<article>` becomes the post, captioned by the page's own article text or else the feed's description.
- **Review caught** (two rounds): the first version made any short, image-less page an image post, so a news brief could open with the site's share card or a headshot, a teaser's thumbnail could beat a short page, a paywall prompt could become the caption, and related-story cards could lend their thumbnails. It now needs strong signs: a feed item that's just the image, or page text that plainly isn't from the page's only article.
- **Checks:** the real New Yorker cartoon and xkcd now come through; a live edition passes epubcheck.

### Cycle 10: a one-source paper fills its time (14:02–14:12, [#27](https://github.com/madCode/newspaperss/pull/27))
- **From device testing:** a New Yorker-only edition held one comic. The per-site cap (1 by default) stopped it with the half hour unfilled. The cap is for fairness between sites, so once each has had its turn, what it held back fills the rest. A site's own number stays a hard limit, and its screen now says which it has.
- **Review caught:** the site screen still said "Up to 1 article", and a noisy site couldn't be held to 1.

### Cycle 9: which build is this? (13:50–14:01, [#26](https://github.com/madCode/newspaperss/pull/26))
- **From device testing:** every debug build said "0.1.0-debug", so feedback couldn't name one. CI builds now get the run number as their version code (each installs over the last) and `0.1.0-debug.<run>+<commit>` as their name, shown at the bottom of Settings.
- **Review caught:** release builds would have taken the run number too (F-Droid needs its own), and re-running an old CI run would have published a build phones couldn't install over a newer one.
- **Also:** your device-testing notes are in the backlog (a new "From device testing" list at the top, plus proposals for notes, multiple schedules and tt-rss categories as sections).

### Cycle 8: "ready" makes a sound (13:41–13:49, [#25](https://github.com/madCode/newspaperss/pull/25))
- **Shipped:** "Edition ready" has its own channel at default importance. It was silent, and many phones fold silent notifications away, but for share delivery it's the only prompt to send the paper. "Delivered" (folder delivery, nothing to do) stays quiet.

### Cycle 7: dated titles (13:31–13:41, [#24](https://github.com/madCode/newspaperss/pull/24))
- **Shipped:** "Tuesday Morning Edition, Sep 29". Without the date, every Tuesday's paper had the same name, so they collided in the Kindle and Kobo libraries and overwrote each other in a synced folder.
- **Review caught:** the title mixed the phone's language into "Morning Edition" (now English, like the rest of the book), and the Today card showed a second, sometimes different date (dropped).

### Cycle 6: persona audit, and sends that count (13:06–13:31, [#23](https://github.com/madCode/newspaperss/pull/23))
- **Audit:** walked the app as a Pocket refugee with a Kobo, a tt-rss + KOReader self-hoster, a casual Kindle owner, a Boox owner and a TalkBack user. Ten findings; the rest are in the backlog.
- **Shipped:** the worst one. Sending the daily paper from its notification was never recorded, so the next build marked it "Not sent" and its articles came back, every day. Now choosing an app in the share sheet marks the edition delivered, from the notification or the app, and opening it counts on a Boox. The "Did it reach your Kindle?" question, asked before Send to Kindle could have delivered anything, is gone.
- **Review caught:** a send and the morning build at the same moment could overwrite each other (a sent paper back to "Not sent", its articles repeated after tt-rss had marked them read); Open on the edition screen didn't count on a Boox; the opened book was missing from Recents. All fixed.
- **Friction:** FileProvider caches its paths in a static map, so one Robolectric test's files folder leaked into the next and broke an unrelated test. A test helper now clears it.

### Cycle 5: bring your reader into onboarding (12:53–13:06, [#22](https://github.com/madCode/newspaperss/pull/22))
- **From device testing:** onboarding had no way in for someone who already uses a reader. The sources step insisted on a starter feed; tt-rss and OPML were only in the Sources menu after setup.
- **Shipped:** "Already use a feed reader?" on the sources step, with Import an OPML file and Connect tt-rss. Either one is enough to go on.

### Cycle 4: timed editions start early (12:34–12:53, [#21](https://github.com/madCode/newspaperss/pull/21))
- **Shipped:**
  - A timed edition's timer fires 30 minutes before it's due. When Doze holds delayed work, the hold eats into that lead instead of making the paper late.
  - Editions are titled and dated for when they're due, not when they were started.
  - Two traps that would have built the paper twice: the early timer asking for "the next 6:30" gets today's again, and opening the app during the lead would re-arm the edition already building. The timer now carries its due time, and the scheduler remembers the last one it started.
- **Review caught:** a clock set ahead and then corrected would hold back editions; moving the time during the lead made a second paper; an edition due at 00:10 was titled for the day before, and then its title could be reused, which Send to Kindle drops. All fixed. Moving the time more than 30 minutes later during the lead can still make a "(2)" edition; left, as the PR explains.
- **Also:** the competitor research is in [docs/research/competitors.md](research/competitors.md). The headline: nobody else sizes the paper to a reading time; most rivals are paid, Kindle-only email digests. Its best ideas are in the backlog.

### Cycle 3: expedited builds, closed (12:28–12:33, [#20](https://github.com/madCode/newspaperss/pull/20))
- **Tried:** running scheduled builds as expedited work, so Android runs them promptly.
- **Review caught:** on Android 12+ expedited work has a quota, and a long build over it would be restarted silently. Keeping it running on older versions needs a foreground service, which Google Play makes you declare and justify.
- **Outcome:** closed and reverted. Cycle 4's lead time gets most of the benefit without either cost.

### Cycle 2: extraction fixes from the library review (12:15–12:27, [#19](https://github.com/madCode/newspaperss/pull/19))
- **Shipped:** the app gets the fixes the rss-to-e-reader review found in the same code:
  - A short "Further reading" list or a section titled "More on the method" is no longer removed.
  - A heading is never left without its list.
  - Screen-reader text that is an icon link's only label stays.
- **Checks:** a live edition from the starter feeds passes epubcheck with no junk left.

### Cycle 1: APK link, signing key, name (11:51–12:13, [#17](https://github.com/madCode/newspaperss/pull/17))
- **Shipped:**
  - Every merge to main publishes the debug APK to a rolling `latest-debug` release, with the fixed link above.
  - Debug builds use one committed signing key, so a newer build installs over an older one.
  - The app now shows itself as newspapeRSS.
- **Review caught:**
  - The release-writing token sat on the build job, where every Gradle plugin and dependency could use it.
  - Two quick merges could race and leave the link broken.
  - The debug build's name and the README missed the rename.
- **Friction:**
  - The debug APK was only uploaded for pull requests, so the first link I sent pointed at a run without one.
  - A review agent left my checkout on a detached HEAD, so the fix commit sat unpushed for a while. Review agents now get a throwaway checkout of their own.

### Also: five rss-to-e-reader bug-fix PRs (#24–#28)
Ported from the app, each with a test that fails on main, a review and a second look:
- **#24:** reading-time budget.
- **#25:** tt-rss password replay on redirect.
- **#26:** invalid captions.
- **#27:** screen-reader text and related-links boxes.
- **#28:** optional cross-collector dedupe. It's off by default, because it would interact badly with a since-id cursor.

## Day 1 · Tue 29 Sep, overnight (summary)

Sixteen PRs took the app from an empty repo to a working, tested app:
- **Sources:** starter packs, any site's feed, OPML, Tiny Tiny RSS, curated lists and a reading list.
- **Editions:** an edition sized by reading time, with a cover and contents, delivered by share sheet, folder or Boox.

A fresh-eyes review before each merge found real bugs in 11 of the 15 reviewed changes. A live edition
run through epubcheck found two more that tests and reviews missed. The full report and a per-cycle
retro are in the session's report pages.
