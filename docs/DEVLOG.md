# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **Last night:** Night 2 focused on one reader: a tt-rss user reading a 30-minute paper on a Kindle over breakfast, who wants thoughtful, varied writing with some fun, and time to reflect. It shipped stars and Mark as read, a Kindle-first EPUB, tt-rss per-feed sync, a category choice, fair turns across many feeds, Start fresh and leaving feeds out, Obsidian-friendly notes saved to your vault, a question at the end of each paper, and a dark preview.
- **Watching:** two tests that failed CI now and then: a settings test on a DataStore rename (#66, #81) and a source-page test on a closed database (#89). Both now stop what they opened only after the screen is torn down; watching whether that was it.
- **Waiting on you:** [#18](https://github.com/madCode/newspaperss/issues/18), a Dropbox app key (only matters for Kobo). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Day 3 · Wed 30 Sep

### Cycle 65: a send that didn't arrive can be undone (17:15–18:10)
- **From you:** a Send to Kindle that failed still marked the edition sent, so its articles were used up and marked read in tt-rss, with no way back.
- **Shipped:** **Didn't arrive? Mark as not sent** on a sent edition, on Today and its page, after a dialog that names it. It's ready to send again: its articles go back into it with the stars they went in with, its links stop counting as delivered, and its tt-rss articles are marked unread on the server. Send it again, or the next edition takes them.
- **tt-rss:** the mark-read work now looks at the edition when it runs and marks read or unread to match, so a late retry can't undo a newer change.
### Cycle 64: no doubled gaps around screens (17:35–17:50)
- **From you:** a screenshot of an edition page: why so much space?
- **Cause:** the tabs pad every screen for the status bar and the gesture bar, and each screen's own top bar and frame padded for them again: an empty status bar's height above the title, and a gesture bar's height above the tabs.
- **Shipped:** the screens see those bars as already handled. A test gives the phone a status bar and checks the title sits just under it once (it sat a second bar lower before the fix).

### UX pass (13:00–15:00, afternoon)
UX design only, no new features. Twelve patterns listed, four research sweeps (onboarding, delivery and hand-off, lists and destructive actions, settings with accessibility and e-ink), then one PR per fix, each reviewed. The options considered and what was decided for every pattern are in the session's UX report page. Pattern by pattern:
- **Today** ([#81](https://github.com/madCode/newspaperss/pull/81)): once an edition exists, "Make another edition" is a quiet link, not the filled button; a failure reads before its Try again, and there's one retry per failure; "N starred articles are waiting for your next edition". Review caught two retry buttons in the most common failure, a retry beside Send, and a first fix that would have told TalkBack "Your edition is ready." for a failure.
- **Edition page** ([#83](https://github.com/madCode/newspaperss/pull/83)): Delete edition moved into a ⋮ menu, away from Notes; the dialog names the edition and answers Delete edition / Keep; "You starred it" instead of a bare "Starred" beside an empty ☆.
- **Settings** ([#84](https://github.com/madCode/newspaperss/pull/84)): rows at least 48dp, headings, the size slider says minutes in 5-minute stops, − and + say what they change.
- **Sources** ([#85](https://github.com/madCode/newspaperss/pull/85), [#89](https://github.com/madCode/newspaperss/pull/89)): Remove source in the ⋮ menu on a source's page too, and the dialog answers Remove source / Keep. A note explaining "Mark 2 as read" with 3 chosen was tried and dropped after review.
- **Notifications** ([#86](https://github.com/madCode/newspaperss/pull/86)): a sent edition's Ready notification comes down.
- **Adding a source** ([#87](https://github.com/madCode/newspaperss/pull/87)): errors say what the answer means and what to try, and the dialog scrolls.
- **Reading list** ([#88](https://github.com/madCode/newspaperss/pull/88)): ✕ offers Undo, and says which link. Reviews sank a delete-and-restore design; the link is now hidden while Undo is offered and deleted after.
- **Article preview** ([#90](https://github.com/madCode/newspaperss/pull/90)): "Opening…" instead of an animated bar, and long titles end in an ellipsis.
- **Onboarding:** no change. An "Add all" button for starter packs was tried and dropped: at large text it broke pack names mid-word and lost TalkBack's checked state.
- **Round two** ([#91](https://github.com/madCode/newspaperss/pull/91)–[#94](https://github.com/madCode/newspaperss/pull/94)): a fresh visual audit of every screen after round one found twelve more. Fixed: outlined buttons you can see on e-ink (they were a 1.2:1 line on Today's card), a filled Add a source, "sent" for editions and "Delivered" for articles, one waiting phrase, "Ready by" in Settings, a calm message for an edition that wasn't marked sent, "nothing new since your last edition", Make another edition always below the latest, and the per-site buttons on their own line at large text. Left, with reasons in the report: the disabled − and Add, the "Mark 2 as read" count, the Settings gutter, heading styles.
- **Also:** two flaky tests fixed at the root (a settings store outliving its folder, #82; screen tests closing their database under a live screen, #89), and the pass now uses a branch per PR, so PRs no longer queue behind each other.

### Cycle 63: Send to Kindle can read the book (14:25–14:45, [#82](https://github.com/madCode/newspaperss/pull/82))
- **From you:** the first Send to the Kindle app errors (not when Kindle is already open); the second gets through the form and bounces back, but nothing arrives, in the library or in Content and Devices, and no email.
- **Likely cause:** a share lets only the receiving screen read the file. Send to Kindle reads it for the form, then uploads after the form closes, when it can't any more, and fails without a word.
- **Shipped:** the Kindle app may read the edition before the share sheet opens, and the app picked may read it until the phone restarts. **Confirmed by you:** the edition now reaches the Kindle.

### Cycle 61: older editions under their title (09:40–10:05, [#79](https://github.com/madCode/newspaperss/pull/79), reverted in [#80](https://github.com/madCode/newspaperss/pull/80))
- Shared editions made before cycle 59 under their title too. You'd rather make the day's edition again than carry code for a handful of old files, so it's reverted.

### Cycle 60: the author in Send to Kindle (09:10–09:30, [#78](https://github.com/madCode/newspaperss/pull/78))
- **From you:** the author should be newspapeRSS. The book already says so, but the Kindle app's form fills in the Amazon account's name, and nothing an app sends changes it (Amazon reads the book's author only for emailed documents).
- **Shipped:** the Kindle tip, in onboarding and Settings, says to change Author to newspapeRSS in the form, so editions sit together in the library.

### Cycle 59: Send to Kindle gets the edition's title (08:30–08:45, [#77](https://github.com/madCode/newspaperss/pull/77))
- **From you:** Send to Kindle's form said "edition-3" as the title.
- **Cause:** the Kindle app titles a document after the shared file's name, and editions were stored as `edition-<id>.epub`.
- **Shipped:** new editions are stored under their title ("Wednesday Morning Edition, Sep 30.epub"), so that's what the Kindle library shows. Editions made before the update keep their old name.

### Cycle 58: Arts & Letters Daily picks get their titles (07:40–, this PR)
- **From you:** the source's page listed its picks as nplusonemag.com, wsj.com, english.elpais.com.
- **Cause:** the list gives a teaser and a link, no headline, so picks were stored untitled; the page's title was only read when an edition fetched it.
- **Shipped:** after each sync, a curated list's untitled picks go to the same background lookup saved links use, which reads each page's title.
- **Review caught:** a bot-check page's title ("Client Challenge") would have stuck as the headline, and the edition prefers a stored title; picks that never get a title (a paywall, a PDF) would have been fetched every sync (now for two days); a failure to schedule the lookup could have marked a good sync as failed.

## Night 2 · Tue 29 Sep, 20:30 PT – Wed 30 Sep, 05:45 PT

### Cycle 57: a slow tt-rss server says so (02:37–02:49, [#73](https://github.com/madCode/newspaperss/pull/73))
- **From the persona audit:** a home server on a slow line timed out and the app said "Couldn't reach tt-rss", sending you to check a connection that works.
- **Shipped:** a slow answer now says tt-rss took too long: on the source (with "it'll be tried again at the next sync", which is true there), when signing in, choosing a category and marking read. Start fresh says the server may still be working through it.
- **Review caught:** the retry promise shown where nothing retries (start fresh, categories, the mark-read note); a connect timeout, which means an unreachable server (a VPN off, a wrong address), taken for slowness.

### Cycle 56: leave a tt-rss feed out (02:15–02:37, [#72](https://github.com/madCode/newspaperss/pull/72))
- **From the persona audit:** a noisy feed (a live blog, press releases) kept taking a slot, and the only fix was on the server.
- **Shipped:** "Feeds in your paper" on the tt-rss source's page: a checkbox per feed. A left-out feed isn't fetched and its waiting articles go, except starred ones. Database version 5.
- **Review caught:** a choice carried over to another tt-rss user on the same server; left-out articles still shown as waiting; feeds from outside the chosen category listed for ever, under their alphabetically last name instead of their latest; a promise that a star would still bring one in, when nothing new is fetched to star.

### Cycle 55: no Open for Kindle and Kobo (02:05–02:15, [#71](https://github.com/madCode/newspaperss/pull/71))
- **From the persona audit:** Today and the edition page offered Open to a Kindle owner, which opens the book on the phone, the one thing the app tells her not to do.
- **Shipped:** Kindle and Kobo readers see Send (and Send again) without Open. Boox readers still get Open first; everyone else keeps both.

### Cycle 54: tt-rss, start fresh (01:43–02:04, [#70](https://github.com/madCode/newspaperss/pull/70))
- **From the persona audit:** a reader back after a break sees thousands unread in tt-rss, and the paper only ever uses up a few a day.
- **Shipped:** "Back after a break?" on the tt-rss source's page: after asking, it marks everything that reached tt-rss more than two weeks ago read there (in the chosen category, if there is one). Servers older than API level 15 (2020) would ignore the two weeks and mark everything read, so the app refuses and says so.
- **Review caught:** clearing the app's waiting articles by publication date didn't match tt-rss's "received" date, so a backdated article could vanish here while staying unread there for good (now left to expire as usual); a login saved for another server could have caught up the wrong account; the notice claimed all of tt-rss when only a category was caught up; "Starred articles stay starred" read as if they were spared.

### Cycle 53: a question at the end of the paper (01:27–01:42, [#69](https://github.com/madCode/newspaperss/pull/69))
- **From the persona audit:** the book's closing page could ask a question, but the app never gave it one.
- **Shipped:** each edition ends with one of twelve open questions ("What surprised you?", "Which piece was hardest to put down?"), and its notes show the same one above the prompts, so what you turned over on the Kindle is waiting in Obsidian.
- **Review caught:** questions that assumed opinion pieces ("Whose point of view was missing?") don't fit a one-article or all-comics paper, so the list was rewritten; "you" questions mixed into the notes' "I" prompts; a blank question printed as "Untitled"; tests that couldn't catch a duplicated question; a claim that consecutive papers never repeat, which empty builds make untrue.

### Cycle 52: reading notes, into your vault (01:10–01:27, [#68](https://github.com/madCode/newspaperss/pull/68))
- **From the persona audit:** notes were only saved beside editions with folder delivery, so sharing to the Kindle app meant exporting them by hand every day.
- **Shipped:** Settings › Reading notes takes a folder (your vault); each edition's notes are saved there once it's delivered, however it was sent, in the background.
- **Review caught:** a folder copy finishing after you'd tapped Sent delivered the edition twice and saved "notes (1).md"; the Notes button and the background save rewrote the same file, so a half-written copy could reach the vault; a worker test that never reached the code it named.

### Cycle 51: article rows, a star and Select (23:41–01:02, [#66](https://github.com/madCode/newspaperss/pull/66))
- **From you:** the labelled buttons under every row felt heavy on such a small row.
- **Design audit first:** how Gmail, Feedly, Pocket, NetNewsWire, Readwise Reader and e-reader lists do it, and four options checked against five readers (you over breakfast, a Boox, TalkBack, 200% font, a first-timer). A trailing star plus a Select mode won.
- **Shipped:** one star at the right of each row; "Mark as read" for several at once in Select (long-press or the Select button), with one Undo; a TalkBack action per row; the same star on delivered editions. A 30-row list is about five screens instead of seven, and nothing moves on e-ink when selection starts or ends.
- **Review caught:** tests covering a path the app no longer used; the list shifting when selection ended at the bottom; untested take-out and notice paths; the helper line changing height; the count not announced; the snackbar covering the bar's buttons.
- **CI:** a settings test the PR doesn't touch failed once on a DataStore file rename, then passed on re-run. It stays on the watch list: if it fails again, each test's DataStore gets its own scope, closed at teardown.

### Cycle 50: Longreads, the story not the teaser (23:47–00:50, [#65](https://github.com/madCode/newspaperss/pull/65))
- **From you:** does Longreads work? Its feed does, but most posts are 200-word picks linking to the story elsewhere.
- **Shipped:** a general rule, not a Longreads one: a short post with exactly one link to another site tagged with the post's own name (`?src=longreads`) points at a story, so the edition fetches the story, bylines its own author and credits "Equator via Longreads". Tracking tags are stripped from stored links, so a story two lists picked goes out once.
- **Review caught:** the Longreads editor bylined as the author; links delivered before the update coming back; short blog commentary swallowed by the page it links to (now the titles must match); a tt-rss copy of a delivered story delivered again; a rejected post using up, and marking read in tt-rss, the page it linked to.

### Cycle 49: a flaky preview test (00:05–00:12, [#64](https://github.com/madCode/newspaperss/pull/64))
- **Shipped:** the preview sets its loaded state on the main thread; under the test dispatcher it could resume on an IO thread.

### Cycle 48: backlog notes (23:55–00:00, [#63](https://github.com/madCode/newspaperss/pull/63))
- **From you:** a Kill the Newsletter inbox the app makes itself; link posts.

### Cycle 47: a coverage floor (23:25–23:52, [#62](https://github.com/madCode/newspaperss/pull/62))
- **From you:** did coverage get a floor? Now it has: CI fails below 90% of lines (92.5% today).

### Cycle 46: published dates on a source's page (23:28–23:44, [#61](https://github.com/madCode/newspaperss/pull/61))
- **From the persona audit:** a tt-rss backlog arrives at once, so every row showed the day it was fetched.
- **Shipped:** rows show and are ordered by when they were published, with the year when it isn't this one.
- **Review caught:** rows ordered by fetch time looked jumbled once dates showed; feed dates in the future or in 2099 would have shown as they were.

### Cycle 45: the preview in dark mode (23:05–23:20, [#59](https://github.com/madCode/newspaperss/pull/59))
- **From device testing:** with the app in dark mode, the article preview stayed black on white.
- **Shipped:** the preview gives each page the app theme's colours (the book leaves colours to the e-reader), and starts dark so it doesn't flash white.
- **Review caught:** a page reached by "Next" came unstyled, black on near-black; the style now goes on every book page the preview serves, after the book's stylesheet.

### Cycle 44: every source gets its turn (22:45–23:12, [#58](https://github.com/madCode/newspaperss/pull/58))
- **From the persona audit:** the take-turns start slid by one feed per edition, so with 60 tt-rss feeds and room for 8, seven of yesterday's eight came back, and a quiet feed's article expired before its turn.
- **Shipped:** turns start with the source featured longest ago; delivered editions break ties; starred articles and editions never sent don't count as a turn.
- **Review caught:** without the tie-breaker, when most sources fit, the same few went in every edition (20 appearances against 10); a star used up its source's turn; no test covered tt-rss feeds or unsent editions.

### Cycle 43: stars and Mark as read (21:42–22:57, [#57](https://github.com/madCode/newspaperss/pull/57))
- **Decided with you:** tap opens the original; `☆ Next edition` puts an article in the next paper; "Mark as read" keeps it out, with Undo. Three design passes first (light, dark, e-ink, 200% font).
- **Shipped:** stars replace bring back as one flag and go first across *all* sources (bring back only jumped its own source's queue, a persona-audit bug); delivery clears stars on every copy of a link; an unsent edition gives articles back their exact earlier state; Mark as read reaches tt-rss from the database at the next sync; database version 3.
- **Review caught:** Mark as read during a build was silently undone (now held while an edition is being made); marked articles outside tt-rss's five-per-feed window never reached the server; a re-saved link could be dropped on release; a link could go out twice through a starred copy; a stale Undo could come back; the Today count double-counted. A second look caught a build that could stay stuck "being made" and a hold that never expired on an open screen.

### Cycle 42: tt-rss asks which articles (22:37–22:47, [#56](https://github.com/madCode/newspaperss/pull/56))
- **From the persona audit:** a returning reader's first paper came from all unread, mostly news; the category choice was only on the source's page.
- **Shipped:** after signing in, "Which articles?" (all unread, or one category); the account is saved only on Add.
- **Review caught:** the first version saved the account at sign-in, so backing out or a sync during the question could leave it half-set or lose articles for good. Redesigned to save nothing until Add.

### Cycle 41: the EPUB, Kindle first (21:41–22:28, [#55](https://github.com/madCode/newspaperss/pull/55))
- **Found by the design passes:** Kindle drops `body >` rules, which held nearly all the book's styling; headlines came out justified with wide gaps.
- **Shipped:** styles by class; left-aligned headings; a cover whose date you can read in the library; the book opens at the contents; contents entries point at articles (an epubcheck warning); a closing page; no "0 min" for comics.
- **Review caught:** the in-app preview lost its margins; a comics-only section said "0 min"; `text-align: start` might justify on Kindle; right-to-left captions and quotes fought the new rules.

### Cycle 40: notes for Obsidian (22:05–22:20, [#54](https://github.com/madCode/newspaperss/pull/54))
- **From the persona audit:** five prompts under every article read as homework, and Obsidian's properties got nothing.
- **Shipped:** front matter (date, edition, sources, a tag) and three prompts once at the top.
- **Review caught:** a stray control character in a title broke the front matter in strict YAML parsers.

### Cycle 39: tt-rss, a few from every feed (21:50–22:05, [#53](https://github.com/madCode/newspaperss/pull/53))
- **From the persona audit:** one call for the newest 200 unread let busy news feeds crowd out monthly essays.
- **Shipped:** up to five unread from each feed, newest first.
- **Review caught:** subcategories came back as items and were fetched as unrelated feeds; one failing feed failed the whole sync; "newest five" were really the highest-scored.

### Cycle 38: a daily live check of the curated lists (21:41–21:48, [#51](https://github.com/madCode/newspaperss/pull/51))
- **Why:** the unit tests read pages saved on the day each parser was written, so they couldn't see Arts & Letters Daily change. Coverage wouldn't have helped either: the parser was covered.
- **Shipped:** a scheduled CI job reads each curated list's real page every morning and fails, emailing the owner, when a parser no longer finds its three links. Ordinary builds skip it.

### Cycle 37: Arts & Letters Daily's nested paragraph (21:20–21:41, [#50](https://github.com/madCode/newspaperss/pull/50))
- **From device testing:** the source said "This page has changed its layout… took none".
- **Cause:** the live page nested a `<p>` in a teaser; the HTML parser splits that, leaving the "more »" link outside any paragraph.
- **Shipped:** the newest entry is read from the column header up to its "more »" link, still stopping at a divider or a second paragraph so an older pick is never taken.
- **Review caught:** the first version's test never ran the new path (a plain space where the saved page has a non-breaking one); without a divider, a first entry that lost its link fell through to yesterday's pick; a teaser link starting with "more" could be taken for the more link; ad text could leak into the teaser. All fixed, with tests.

### Cycle 36: MIT license, and the article row decided (21:08–21:18, [#49](https://github.com/madCode/newspaperss/pull/49))
- **Shipped:** the MIT license. In the backlog, the design decided with you: tapping an article opens it; `☆` puts it in the next paper; "Mark as read" keeps it out; stars count toward each source's cap and the paper stays capped.

### Cycle 35: backlog decisions (20:48–20:54, [#48](https://github.com/madCode/newspaperss/pull/48))
- **Shipped:** generated summaries decided against (the paper gives whole articles); the iOS app and local news parked; translation by people, not a machine, with what that needs first.

## Day 2 · Tue 29 Sep, 11:47–19:30 PT

### Cycle 34: "all already read" (18:27–18:36, [#46](https://github.com/madCode/newspaperss/pull/46))
- **From #45's review:** an export of only archived links said "Added 500 links from Pocket." while onboarding still wouldn't go on, with no hint why.
- **Shipped:** it says "Added 500 links from Pocket, all already read."

### Cycle 33: saved links in onboarding (18:14–18:27, [#45](https://github.com/madCode/newspaperss/pull/45))
- **From the persona audit:** someone leaving Pocket couldn't finish onboarding with saved links alone: Next wanted a feed, and the Pocket import was three screens deep in Sources.
- **Shipped:** the sources step has "Leaving Pocket or Instapaper? Import your saved links", and links waiting in the reading list are enough to go on.
- **Review caught:** after process death, finishing could read the saved-link count before it had loaded and do nothing; it now reads it from the source. The import's message hid "That's enough to start"; both show now. The "unread only" count moved into the reading list, tested with a Pocket export that mixes archived and unread links.

### Cycle 32: CI timeout, report pages (18:06–18:14, [#44](https://github.com/madCode/newspaperss/pull/44))
- **Shipped:** a 30-minute timeout on CI (a hung test would otherwise hold a PR for six hours), and the Day 2 report and retro pages.

### Cycle 31: no feed? Save the page instead (17:53–18:06, [#43](https://github.com/madCode/newspaperss/pull/43))
- **From the persona audit:** pasting a site with no feed ended at "No feed found at …", a dead end.
- **Shipped:** when an article's page loads but its site has no feed, Add a source offers "Save this page to your reading list instead". An address that can't be reached isn't offered.
- **Review caught:** a site's front page was offered too, the most common case, and would make an edition of navigation; so would a paywall's sign-in page a redirect landed on. Only an article-like page on the same site is offered now.

### Cycle 30: change your e-reader later (17:42–17:53, in [#42](https://github.com/madCode/newspaperss/pull/42))
- **From the persona audit:** the e-reader picked in onboarding couldn't be changed; a Kobo owner who got a Boox had to reinstall.
- **Shipped:** Settings has "Your e-reader", with the same list and tip as onboarding. It decides whether editions offer Send or Open.

### Cycle 29: a deleted edition's notification (17:35–17:45, in [#42](https://github.com/madCode/newspaperss/pull/42))
- **From the backlog (tech debt):** deleting an edition left its "ready" notification up, and its Send would share a file that no longer exists.
- **Shipped:** the notification carries its edition's id, and deleting that edition takes it down. Only if it's still about that edition: the slot is shared, and a newer edition's news stays.

### Cycle 28: say why there's no edition (17:24–17:53, [#42](https://github.com/madCode/newspaperss/pull/42))
- **From the persona audit (every persona):** a timed run that found nothing new sent no notification, so the paper just didn't come; when every source failed, Today said "Nothing new to read yet"; offline, a queued build said "Checking your sources…" indefinitely.
- **Shipped:** a timed run with nothing new sends a quiet "No new edition: nothing new to read since your last one." When nothing is new because every source failed, it's a failure that says so ("None of your 9 sources could be read. Sources shows what went wrong with each."), loud on a timed run and shown on Today. A build queued without a connection shows "Waiting for an internet connection…".
- **Review caught:** "check your connection" blamed the wrong thing (a timed run only starts once connected, so it's usually a tt-rss sign-in, a moved feed or a changed list page); the message now points to Sources. A timed run that reads no source is retried twice, five minutes apart, before the reader hears of it. "No new edition" no longer arrives minutes after a paper made by hand, and says "add a few sources" when there's never been one. Today's connection check now wants a working connection, as WorkManager does (a captive portal isn't online), is debounced so a flaky signal doesn't flip the status line, and can't crash on the Android 11 builds whose network callback throws. A second look caught Today saying "Checking your sources…" through the retry wait, with nothing running; it now says "Couldn't read your sources. Trying again at 6:20."

### Cycle 27: Le Monde's bot check (17:18–17:24, in [#41](https://github.com/madCode/newspaperss/pull/41))
- **From a live edition:** Le Monde articles came out as "A required part of this site couldn't load": the app fetched Fastly's bot challenge ("Client Challenge", a 3 KB page served with a 200) and took it for the article.
- **Shipped:** the challenge is recognised, so the feed's text is used with a note, and the source learns the site blocks fetching.
- **Checks:** a live Le Monde edition has the feed's text and the note, and no challenge text.
- **Review caught:** from a server, Le Monde can also answer 402 "Accès restreint", which wasn't counted as the site refusing. It is now, like 401, 403 and 429.

### Cycle 26: Chinese and Japanese reading time (17:10–17:24, [#41](https://github.com/madCode/newspaperss/pull/41))
- **From the language work:** Chinese and Japanese have no spaces between words, so a whole NHK paragraph counted as one word: no reading time, and the full-text check would take a long Japanese feed for a teaser and fetch pages it didn't need.
- **Shipped:** one word count everywhere, which counts each Chinese or Japanese character as about 2/3 of a word (people read about 350 characters a minute, against 238 English words). Korean uses spaces and is counted by word.
- **Checks:** a live edition with BBC Chinese gives 3–7 minute articles instead of seconds, and passes epubcheck.

### Cycle 25: where things are tracked (17:05–17:10, in [#40](https://github.com/madCode/newspaperss/pull/40))
- **From your question:** onboarding items and the research were hard to find. The backlog was sorted by where each item came from, and the persona audits only existed as backlog bullets.
- **Shipped:** the backlog is grouped by part of the app (onboarding, sources, the book, delivery, reading list, accessibility, performance), each item tagged with its origin. The two persona audits are written up in [research/personas.md](research/personas.md), with each finding's status. The README has a short documentation index.

### Cycle 24: lighter extraction (16:54–17:10, [#40](https://github.com/madCode/newspaperss/pull/40))
- **From the resource audit:** the worst memory peak was a large page parsed and then copied whole for Readability.
- **Shipped:** scripts, styles and SVGs are dropped right after the page's JSON-LD is read, before the copy; on script-heavy sites they're most of the page. On a 2.6 MB test page that's 36 MB allocated before and 16 MB after, in half the time. Pages over 5 MB aren't parsed at all; the feed's text is used with a note.
- **Checks:** a live edition gives the same articles with the same word counts and passes epubcheck; xkcd, the New Yorker cartoon and Godslave all still get their image. The live-edition tool now prints each article's image count.
- **Review caught:** a page that fails for its own reasons (too large, no connection) was counted as "the feed's text is enough", so a source whose pages are 5–10 MB would have been switched to teasers for good. A failed page now counts as no evidence either way. Declarative shadow-DOM templates, which are shown on the page, are kept.
- **Left:** a saved link that can never be read (a PDF, a video, a huge page) waits silently, as it did before for anything over 10 MB. Putting it in the edition as a "couldn't fetch" page was tried and dropped: such pages cost no reading time, so a backlog of saved PDFs could fill an edition with them. The reading list should show these links as unreadable instead; that's in the backlog.

### Cycle 23: a documentation pass (16:45–16:54, in [#39](https://github.com/madCode/newspaperss/pull/39))
- **Why:** you asked for documentation passes in the cycles: readable, current, not onerous. CLAUDE.md now says so: behaviour changes update the docs in the same PR, and every few cycles a pass checks the docs against the code.
- **Shipped:** DESIGN.md rewritten to describe the app as it is. It had SMTP delivery, edition profiles, user-named sections and a reading-speed setting that don't exist, and nothing on today's changes: timed editions starting early, the feed cache and 12-hour sync, when an edition counts as delivered, housekeeping, comics, language tags, the preview. The finished milestone plan and history are gone (BACKLOG and this log have them), and it's shorter. README: the delivery rule, "ready by", the book's features, a link to the debug APK, and fresh screenshots. CLAUDE.md: database migrations and debug builds.
- **How:** an agent checked every statement in the three docs against the code and listed what was wrong or missing, with file references; I rewrote from that.

### Cycle 22: TalkBack and large fonts (16:32–16:54, [#39](https://github.com/madCode/newspaperss/pull/39))
- **Why:** the persona audit's TalkBack items, and you asked for an accessibility audit. These are the known gaps; the full audit is in the backlog.
- **Shipped:** Today announces what the build is doing ("Checking your sources", "Making your edition", and the result) through a live region. The running count isn't announced, or TalkBack would read every number. Onboarding's progress bar says "Step 2 of 3" instead of "66 percent". Earlier editions say what tapping does. Onboarding's Add and the reading list's Save moved below their text fields, where a 200% font can't squeeze out the space to type.
- **Review caught:** in Compose a live region announces when its text changes, not when it first appears, so the failure message and "Checking…" would have been silent. And if they had spoken, a failure WorkManager still remembered would have been read out every time Today opened. The status is now one always-present line whose text changes, and it's only live once a build has run while the screen is up. Onboarding's Add button stays put instead of being swapped for "Checking…" under TalkBack's focus. A second look found a new reader's very first "Checking…" still silent (the list rebuilt the panel when the first-edition prompt went away; items are keyed now), and success said nothing; it now says "Your edition is ready." Still to confirm with TalkBack on a device.

### Cycle 21: articles know their language (16:13–16:32, [#38](https://github.com/madCode/newspaperss/pull/38))
- **Why:** your note on languages. E-readers choose hyphenation, fonts and text direction from `xml:lang`, and every article was tagged English, so a French article was hyphenated with English rules and an Arabic one laid out left to right.
- **Shipped:** each article's headline and body are tagged with its language, and right-to-left languages get `dir="rtl"`. The text decides: the writing system for non-Latin scripts, and common words for English, French, German, Spanish, Italian, Portuguese and Dutch. The page's declared language breaks ties, and is kept when it agrees because it's more precise (pt-BR, Persian in Arabic script). Many sites declare the same language on every page, and a feed's text declares nothing. No database change.
- **Checks:** a live edition from Le Monde, Spiegel, El País, NHK, Al Jazeera Arabic, the Guardian and g1 tagged every article correctly and passes epubcheck. The Arabic page lays out right to left, with the English kicker and byline left to right.
- **Review caught:** ordinary Spanish and Italian news came out French: the word lists left out "la", so every "la" counted for French alone. The lists now include each language's most frequent words, and a shared word counts for much less. A page declaring Persian or Urdu site-wide could have turned an English caption right to left: Latin text now never takes a tag in another script. Galician read as Portuguese over the page's own "gl": a Latin language the lists don't know keeps the page's tag. Declared regions like "en-UK" (no such region) are dropped, a kanji-heavy Japanese page stays Japanese, and headlines in the contents and "Next" links are tagged too. A second look found Latin-script Serbian taken for Portuguese over the page's "sr-Latn", and Danish for English (the English list had "at" and "for"); both keep the page's tag now. A second live edition added Italian (ANSA) and Dutch (NOS); all eight languages are tagged right.
- **Found on the way:** NHK's Japanese articles count as "1 word" (reading time assumes spaces), and Le Monde serves a script wall the bot check doesn't catch. Both are in the backlog.

### Cycle 20: the first tap on an article (16:00–16:13, [#37](https://github.com/madCode/newspaperss/pull/37))
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
