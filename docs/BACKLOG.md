# Backlog

The running plan. Each work cycle picks what matters most for readers right now (device feedback
first, then research and judgment), builds it with tests, and moves it to Done. Feature proposals are
ideas, not commitments.

## Next

Grouped by part of the app. The tag says where each item came from: *device* (your testing),
*personas* ([docs/research/personas.md](research/personas.md)), *resources* (the resource audit),
*live* (building real editions), *a11y* (the accessibility audit), *ux* (the Day 3 UX pass).

### Onboarding and setup
- [ ] A short Kindle how-to: same Amazon account, pick the device in Send to Kindle, Library › Docs *(personas)*
- [ ] Explain per device what counts as "delivered" *(personas)*

### Sources and fetching
- [ ] **Platform pass** *(you asked)*: a test corpus of real posts from the big blog and newsletter platforms (Substack, Ghost, WordPress, Medium, Blogger, Tumblr, Buttondown, beehiiv, Micro.blog), from their own feeds and through tt-rss, each checked in the book for footnotes, embeds (Notes, tweets and Bluesky posts, YouTube, galleries), pull quotes and captions, paywall teasers and link posts. Fix what breaks, and keep the corpus as regression tests. The first finds: Substack Notes and footnotes after tt-rss (#109)
- [ ] Paywalled and summary-only sites: warn when a site is added; keep stubs from eating the budget; drop metered sites from starter packs *(personas)*
- [ ] **Empty premium posts** *(you asked)*: some feeds list paid-only articles with a title and no content at all, and the page behind them is a paywall. Detect them (no body in the feed and nothing usable fetched, or a premium marker like Substack's paid audience tag), and let a source opt in to skipping them so they never take a slot. Off by default; the source page says how many it skipped
- [ ] "No feed found" in onboarding: offer the reading list there too (done in Sources) *(personas)*
- [ ] Page cleanup's furniture patterns ("Recommended stories", "Subscribe to", "Read more:") are English only, so "Lire aussi", "Mehr zum Thema" and "Lee también" slip into French, German and Spanish articles. Key them by the article's language *(live)*
- [ ] Webtoons: episodes are one long strip of dozens of lazy images (`data-url`), beyond the 20-image cap, and its mobile site hides the feed. Support strips properly *(device)*
- [ ] Webcomic title text (xkcd's hover text) is dropped; show it as a caption *(device)*

### The book
- [ ] EPUB design, round 2: the cover image, section pages, and a look on real devices (Kindle, Kobo, KOReader) *(device)*
- [ ] A text size setting for the article preview *(your brother asked; medium priority)*
- [ ] Comics in the article preview: let a strip fill the page width, and allow pinch to zoom *(you asked)*
- [ ] Substack Notes embedded in posts that come through tt-rss: tt-rss strips the Note's text, so the sentence introducing it hangs. Fetching the post's page would bring it back (the full post is in the page's data), at one page fetch per Substack article *(device)*

### Delivery and schedule
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; waiting on an app key ([#18](https://github.com/madCode/newspaperss/issues/18)) *(personas)*
- [ ] Boox: offer folder delivery into the Books folder, so editions stay in the library *(personas)*
- [ ] Folder delivery: tt-rss marks articles read as soon as the file is saved, before Syncthing has synced; old editions pile up in the folder *(personas)*
- [ ] After a send to the Kindle app, say it can take a few minutes to show up in the library, so a slow arrival doesn't look like a failure *(device)*
- [ ] Verify folder delivery and the chooser from the notification on a real device
- [ ] If lead time isn't enough on a real device, wake timed editions with an exact alarm (Doze defers WorkManager; expedited work can silently restart a long build)

### tt-rss, for a returning reader *(personas)*
- [ ] Decide what tt-rss is as a source *(you asked; sitting with it for a week first)*. Is it one source by itself, as now, or a doorway to the feeds underneath it, each with its own controls? Your gut says the current simple approach is best. The per-feed cap below and "tt-rss categories as sections" in the proposals depend on the answer
- [ ] Per-feed cap on the tt-rss source page ("two from Current Affairs"); leaving a feed out is done
- [ ] Several categories, and tt-rss's Starred and Published as choices
- [ ] A heart for "loved this / keep it", synced to tt-rss *(you asked)*. The ☆ stays "put it in my next edition": two different wishes, and tt-rss's own star already means "keep" (which is why its stars aren't synced as ☆ today). Things to settle first:
  - Where: on a source's rows, on delivered editions, and maybe from the book (a link on each article's end that opens the app).
  - What it sets in tt-rss: its star (`marked`, field 0 of `updateArticle`), or Published (field 1) if she'd rather keep her stars for something else.
  - Which way it syncs: app → tt-rss only, or also tt-rss → app, so a heart given on the laptop shows here. Both ways needs a rule for conflicts (the last change wins, like Mark as read reaching the server at sync).
  - Not only tt-rss: for feeds and saved links, a hearted article could go to a "Loved" list here, exported with the notes (and later to Obsidian).
  - Offline: queue the change and send it at the next sync, as Mark as read does, so Undo never has to reach the server.
- [ ] Articles that expire in the app stay unread in tt-rss: an opt-in "mark read when they expire here"
- [ ] Delay tt-rss mark-read a little after sharing, so a quick "Mark as not sent" doesn't flip articles read and then unread again on the server
- [ ] Read/unread, after trying the toggle *(you asked)*. The status mark on a source's page now marks read and unread, reaching tt-rss at the next sync. If the two toggles don't feel right on the phone, the one-button cycle (option A in the mockups) is the alternative
- [ ] Read sync: an article marked unread in tt-rss further back than its feed's newest five unread only comes back here once it's among them. Ask tt-rss about delivered articles directly if that turns out to matter

### From the UX pass *(ux)*
- [ ] Sync errors on a source's page still read "The site answered with error 403"; give them the plain words the add dialog now uses ("turned newspapeRSS away… try again later")
- [ ] An edition released because another was made by hand keeps its Ready notification (with Send) until the next timed one replaces it
- [ ] Boox: consider turning off ripples on e-ink (they cause partial refreshes); Boox's own refresh modes may make it moot

### Reading list
- [ ] Saved links that can never be read (a PDF, a video, a page over 5 MB, a 410) wait silently forever. Show them in the reading list as unreadable, with the reason and a way to open or remove them. Not as "couldn't fetch" pages in the edition: they cost no reading time, so a backlog of them could fill one
- [ ] Links saved before database version 2 with a title never get a reading time (no backfill)

### Accessibility *(a11y, you asked for it)*
A full pass over the app and the book, not just spot fixes:
- [ ] App: TalkBack walk-through of every screen (labels, headings, focus order), font scale at 200%, display size, touch targets ≥ 48dp, contrast in light and dark, e-ink readability, nothing carried by colour or animation alone. Confirm the new live regions with TalkBack on a device
- [ ] Book: EPUB Accessibility 1.1 metadata (`schema:accessMode`, `accessibilityFeature`, `accessibilitySummary`), image alt text carried through, reading order checked with a screen reader
- [ ] Tooling: Compose accessibility checks in the Robolectric tests, Accessibility Scanner on a device, Ace by DAISY on a live edition

### Performance *(resources)*
- [ ] A floor device: Android 8, 2 GB RAM, slow CPU and storage (a 2018 budget phone or an older Boox). Measure on an emulator with that profile how long a 30-minute edition takes, peak memory, whether timed editions still arrive under Doze, and whether long lists and the preview stay smooth; set budgets from the numbers *(you asked)*
- [ ] Load build candidates without feedHtml; fetch it per article
- [ ] EPUB zip: buffered output, JPEGs stored uncompressed

### Tech debt
- [ ] `SettingsScreenTest` can fail under full-suite load (DataStore "Unable to rename …tmp", a write still running when the temp folder is deleted). Give test DataStores a scope that finishes before cleanup

### Later
- [ ] SMTP delivery (low priority: sharing to the Kindle app and Calibre cover most email needs)

## Feature proposals

### What you found thought-provoking
Let the reader mark articles that stayed with them (in the app, or by finishing or highlighting them on
the e-reader), and let the planner lean towards similar ones from their own feeds. It stays on the phone,
is visible and adjustable, and mustn't narrow the paper into an echo chamber: "nobody's algorithm" is the
pitch, so it has to be the reader's own.

### Sharing an article
Share an article with someone. The link is easy; the full extracted text raises copyright questions and
shouldn't become a way around paywalls. A likely middle: the link plus a short excerpt.

- [ ] **First step: Share the original's link** *(you asked)*. Android's share sheet with the title and the original URL (tracking tags already stripped; a link post's story, not the list's pick), nothing else.
  - Where: the article preview's top bar, and on rows (in Select mode's bar, or a TalkBack action) so rows don't get a second icon on e-ink.
  - From the book: each article already ends with "Read the original"; a Kindle can share that link itself, so nothing needed there.
  - Later: the excerpt, if the link alone feels bare.

### Notes: what should they be?
The edition notes export (a markdown checklist of each edition's articles) went in without much thought
about who uses it. Research first: what people do with a read list; whether to import highlights from
Kindle (My Clippings), Kobo or KOReader; where notes should live (the app, or a markdown file elsewhere).

### More than one schedule, and one-off editions
Several timed editions (a weekday morning paper and Sunday long reads), and a one-off custom edition
(pick sources, size) without changing the defaults.

### tt-rss categories as sections
newspapeRSS sits on top of a reader rather than replacing it. tt-rss is one source today, optionally
one category; its categories could become the paper's sections. This is the direction rather than
making the app a full client, and it pairs with "Add sources to tt-rss": sources found here get
subscribed on the server, and the server's categories come back as the paper's sections.

Ideas worth doing, not yet planned. Each gets a sketch before it moves to Next.

### Add sources to tt-rss, not just the phone
For someone with a tt-rss account, a site found in newspapeRSS (a starter pack, a curated list, a
pasted address) could be subscribed on the server instead, with the API's `subscribeToFeed`. It would
then show up in their other reader apps too, and read state stays in one place. Open questions:
- which tt-rss category it goes in;
- whether the phone-side source is then dropped, so articles don't arrive twice;
- what to do for an OPML import.

### Languages
What to do with non-English sources and readers. Today:
- the book's language is always `en`;
- the page text (contents, "min read") is English;
- reading time assumes English words per minute.

Done: each article is tagged with its own language (`xml:lang`, and `dir="rtl"` for Arabic,
Hebrew, Persian), so e-readers hyphenate and lay it out correctly; Chinese and Japanese reading
time is counted by character.

Questions:
- the book's `dc:language` when a whole edition is in one language other than English (Kindle
  picks its dictionary from it);
- whether an edition should mix languages or keep them in sections.

Translation, decided: people translate, not a machine. Nobody on the project can check a machine
translation, so it goes to volunteers who read the language. What that needs first:
- the app's text moved out of the code into `strings.xml` (today almost all of it is written
  inline in the screens), and the book's own words ("Contents", "min read") into a table per language;
- a CONTRIBUTING section on how to translate: which file to copy, how to test it, how to send it;
- optionally a hosted tool such as Weblate (free for open-source projects; the repo is MIT);
- then a call for translators in the README.

### Newsletters *(you asked)*
Many of the writers worth reading publish as newsletters. Two routes, from easy to heavy:
- **Their own feeds, first.** Substack (`/feed`), Ghost (`/rss/`), Buttondown (`/rss`) and beehiiv
  (when the writer turns RSS on) already work as sources. What to handle, recognised by the
  platform's own markers (a feed's `<generator>`, a page's `generator` meta) rather than a list of
  domains, since most use custom domains:
  - paid posts arrive as a teaser ending in "Subscribe to read" or similar: treat as summary-only
    and say so on the source, rather than fetching a paywall;
  - redirect-wrapped and tracking links (`substack.com/redirect/…`, `utm_*`): unwrap for the
    "Read the original" link and strip before fetching;
  - "Share", "Subscribe", "Leave a comment" and like buttons: page-cleanup furniture;
  - podcast episodes in the same feed (an enclosure, little text): skip or note, don't pad the paper.
- **Newsletters that only arrive by email.** Options: a Kill the Newsletter-style address
  (email in, Atom feed out; hosted or self-hosted), which needs nothing new in the app; or reading
  one folder or label over IMAP with an app password (Fastmail, iCloud, most hosts; Gmail needs
  OAuth, much heavier). Email HTML is table layouts, "View in browser" headers, unsubscribe
  footers and tracking pixels, so it needs its own cleanup pass.
- Start with the first route (it's mostly cleanup rules) and a help line pointing email-only
  newsletters at a Kill the Newsletter address; IMAP only if that proves too fiddly.
- **The app could make the Kill the Newsletter inbox itself** *(you asked)*: "Add a newsletter" asks
  for a name, creates the inbox (a form post to the service, or a self-hosted instance), shows the
  email address to paste into the newsletter's signup, and adds the inbox's feed as a source. The
  confirmation email arrives in that feed first, so the app can surface its "Confirm" link instead of
  putting it in a paper. Risks: it depends on a third party's form and goodwill (be a polite client,
  let people point it at their own instance), and the inbox address is effectively a password.

### Listen: the paper as an audiobook
An audiobook of your newspaper: listen to an edition on a walk, from the same finite paper.
- **Engine:** Android's own `TextToSpeech`. It's offline and free, and voices already on the phone keep the no-account promise. Cloud voices sound better but need an account and send the text away, so they'd only ever be an option.
- **Shape:** a "Listen" button on the edition screen that reads the articles in order. It runs as a media session with a notification (pause, skip article) and stops at the end of the edition, keeping the paper finite.
- **Text:** the article bodies as already cleaned for the EPUB. Headings are read as pauses; image captions and link lists are skipped.
- **Later:** export the edition as an audiobook file (M4B with a chapter per article) for podcast and audiobook apps. The EPUB could carry media overlays, but few e-readers play them.
- **Open questions:** remember the position between sessions? Count listened articles as read for "bring back"?

### Cloud backup
Android's Auto Backup already copies the database and settings (sources, reading list, edition
history) to the reader's Google account, within its 25 MB quota. Past EPUBs, the schedule timer and
the tt-rss password are left out on purpose: the password is sealed by a key that never leaves the
phone. What's missing:
- it's invisible: nothing in the app says it's on, when it last ran, or what comes back;
- a restore has never been tried on a real phone (the timer re-arms, but does a restored tt-rss
  source ask for its password clearly?);
- no manual copy: one "Export everything" file (sources as OPML, the reading list as the
  library's Markdown checklist, settings), for people without Google services or moving to another
  reader, and a matching import.

### From the competitor research ([docs/research/competitors.md](research/competitors.md))
- **Kobo through Google Drive.** Kobo syncs a "Rakuten Kobo" Drive folder natively. Drive's SAF provider
  has no folder trees, so this needs the Drive API (an OAuth client, like Dropbox's app key).
- **Close the loop from the device:** finished on the e-reader means archived; KOReader highlights feed the notes export.
- **More importers:** Matter, Readwise, Raindrop and Omnivore exports, for people leaving shut-down apps.
- **An OPDS catalog served from the phone,** for KOReader and jailbroken Kindles.

## Parked and decided against

- **Generated summaries:** not doing. The paper gives whole articles; AI-shortened digests are what
  the competitors do, not what this app is for.

### Local news for your city or country (parked)
Parked: hard to do well, and curated lists go stale. Adding a local paper by its address already works.
Help people find news sources near them: local papers, public broadcasters, city blogs.
- Options include a curated starter pack per country or region, and location-based Google News feeds.
- A "near me" search could use the device's locale, without needing a location permission.
- The hard part is keeping curated lists current and fair. Starter packs must stay public, well-known
  feeds only.

### An iOS app (parked)
Parked: Android comes first. Kept here for the notes.
Possible, but a second app rather than a port:
- `:core` is plain Kotlin, but leans on JVM libraries (jsoup, Readability4J, OkHttp). Kotlin
  Multiplatform would need replacements (Ksoup, Ktor, a Readability port), then Compose
  Multiplatform or SwiftUI for the screens.
- iOS decides when background work runs (`BGAppRefreshTask`), so "ready by 6:30" can't be
  promised the way Android's timers allow; a notification to build on opening may be the honest version.
- Delivery works: the share sheet reaches Send to Kindle, Dropbox (Kobo) and Files; Boox and
  KOReader users are mostly on Android anyway.
- It needs a Mac to build and an Apple developer account ($99 a year) to ship.
A first step, if wanted: move `:core` to Kotlin Multiplatform, which also keeps the logic shared.

## Done

- [x] A tt-rss timeout says the server took too long, not that it couldn't be reached *(personas)*
- [x] tt-rss: leave a feed out of the paper from the source's page; it isn't fetched, and its waiting articles go except starred ones *(personas)*
- [x] No Open for Kindle and Kobo readers, who send the book rather than open it on the phone *(personas)*
- [x] tt-rss "Start fresh" for a reader back after a break: marks everything older than two weeks read on the server, after asking, and refuses on servers too old to limit it *(personas)*
- [x] A question to think about on each edition's closing page, from a short list that suits any paper, and shown in its notes *(personas)*
- [x] Reading notes saved to a folder of your choice (an Obsidian vault) for every delivered edition, whether it was shared, saved to a folder or opened *(personas)*
- [x] Link posts (Longreads' picks): a short item whose link to another site carries a referral tag naming its own site is stored as that story, fetched and credited "Equator via Longreads", with its pitch as the fallback; tracking tags come off stored links, so a story two sources picked goes out once *(you asked)*
- [x] Sources take turns starting with the one featured longest ago, not shifting by one each edition, so with many feeds every one gets its turn *(personas)*
- [x] Stars: `☆ Next edition` on a source's articles and on delivered editions puts an article first in the next edition (it replaces bring back); the planner takes stars first, taking turns across sources, within each source's slots and the budget; Today says how many are waiting
- [x] Article rows in a source's list: tap opens the original; "Mark as read" with Undo, marked read in tt-rss at the next sync
- [x] tt-rss sync takes up to five unread articles from each feed instead of the newest 200 overall, so busy feeds can't crowd out quiet ones *(personas)*
- [x] A daily live check of each curated list's page in CI (`live-check.yml`), after Arts & Letters Daily's markup changed under the app
- [x] Saved links in onboarding: import a Pocket or Instapaper export there, and start with saved links alone
- [x] "No feed found" in Sources offers to save the page to the reading list instead of a dead end
- [x] Change the e-reader in Settings after onboarding (it decides Send or Open, and the tips)
- [x] Deleting an edition takes down its "ready" or "delivered" notification, whose Send would have shared a missing file
- [x] Say why there's no edition: a timed run with nothing new sends a quiet "No new edition"; when every source failed it's retried, then a failure that says so, not "nothing new"; a build queued offline shows "Waiting for an internet connection"
- [x] Fastly's bot challenge (Le Monde's "Client Challenge") is recognised: the feed's text is used with a note, and the source learns the site blocks fetching
- [x] Reading time for Chinese and Japanese counts characters (about 350 a minute), so a paragraph isn't one word; the full-text check no longer takes a long Japanese feed for a teaser
- [x] Extraction drops scripts, styles and SVGs before Readability copies the page (a 2.6 MB script-heavy page: 36 MB allocated before, 16 MB after, twice as fast); pages over 5 MB aren't parsed and use the feed's text
- [x] Documentation pass: DESIGN.md describes the app as it is (no SMTP, profiles or reading-speed setting; today's changes in), README and CLAUDE.md updated, fresh screenshots; documentation passes are now part of the cycles
- [x] TalkBack: the build's stage is announced (not every count), onboarding's progress says "Step 2 of 3", earlier editions say "See what's inside"; Add and Save sit below their fields so large fonts leave room to type
- [x] Each article is tagged with its language (`xml:lang`, `dir="rtl"`), detected from its text (writing system, common words) with the page's declared language as a tiebreaker
- [x] The article preview shows at once and reads the book in the background, with one open zip per screen (the "first tap doesn't open it" report). The side-scroll came from the old EPUB's long URL line, already gone
- [x] An HTTP cache: feeds are revalidated (If-None-Match), and unchanged ones answer 304 instead of the whole feed; the background sync runs every 12 hours with the battery not low
- [x] Housekeeping from the resource audit: only the newest 14 editions keep their EPUB; old articles drop their feed text (a month after delivery); the image budget counts as spent when nothing more fits. Streaming images to disk dropped: at most 15 MB, not the real peak
- [x] Reading list: every saved link's page is measured for a reading time (database version 2, the first migration), rows open in the browser, and an untitled row shows a title made from its address
- [x] Source page: a real "Recent articles" heading, a status mark per article (● ✓ ○), stronger titles, lighter inset dividers; "Article text: Automatic" says what it sets
- [x] Webcomics: feeds linked only from the page are found (God Slave's `/comic/rss`), and a page's own comic (`#cc-comic`, `#comic`) beats the feed's thumbnail
- [x] An app icon: a cream newspaper page on navy, after the Termux shortcut's icon; themed icons get the newspaper glyph
- [x] EPUB design, round 1: source kicker, byline and rule, justified hyphenated text, links and rules in the text's colour (KOReader night mode), "Read the original at site.com" (no long URL widening the page), a cleaner contents page
- [x] "See what's inside" on Today, and deleting an edition (its title stays taken, so Send to Kindle doesn't drop a remake)
- [x] Comics and cartoons keep their image: an image-only feed item isn't dropped as empty, and a cartoon page gives its main image instead of its footer
- [x] The per-site cap gives way when there's room: with one source (the New Yorker) an edition held one article
- [x] "Edition ready" makes a sound (its own default-importance channel); "delivered" stays quiet
- [x] Dated edition titles ("Tuesday Morning Edition, Sep 29"), so next week's Tuesday doesn't collide in libraries and folders
- [x] Sending from the "ready" notification counts: choosing an app in the share sheet marks the edition delivered (it was released the next day and its articles repeated)
- [x] Onboarding: import an OPML file or connect tt-rss on the sources step, instead of only after setup
- [x] Research: competing and neighbouring apps, in [docs/research/competitors.md](research/competitors.md)
- [x] Timed editions start 30 minutes before they're due, so Doze's hold on delayed work becomes lead time instead of a late paper
- [x] Extraction: screen-reader-only text ("list 1 of 4") and "Recommended stories" link lists no longer reach the edition (found in a live edition's Al Jazeera article)
- [x] tt-rss: take articles from one category, and a setting to leave delivered articles unread on the server, both on the source's screen
- [x] Per-source article cap on the source screen, replacing the edition's "up to N from each site" for that site (not for tt-rss, which is capped per publication)
- [x] Source detail: tap a source for its health (status, failing for N days, last checked, where its text comes from), its recent articles and what happened to each, pause, article text and remove with a confirmation
- [x] Delivered links are remembered for a year, so removing and re-adding a source, or the same story in two sources, doesn't deliver it twice
- [x] Real-world check: a live edition from the starter feeds passes epubcheck (stray figcaptions fixed; `./gradlew :core:liveEdition`); a source settled on the feed's text keeps probing short items, so a lifted block is noticed
- [x] Tech debt: feed parser smoke-tested on Android's own XmlPullParser; HTML meta-charset and byte-order-mark sniffing
- [x] Curated list sources: a scraper per site (Arts & Letters Daily first), each list its own source, keeping its newest 12 unread links; added from the Add a source dialog
- [x] Notes export: a Markdown notes file per edition (details, citation, reflection prompts) from Edition detail
- [x] App shell: splash until settings load, onboarding survives process death, timer re-armed on time-zone change, launch test
- [x] Auto-tune each source's ContentMode from its articles (three in a row), a manual choice that's never overridden, and per-source full-text health on the Sources screen
- [x] Import Pocket (HTML, CSV) and Instapaper (CSV) exports into the reading list; saved links without a title get the page's title in the background
- [x] tt-rss source (one account, all unread, marked read after delivery); in-app article preview (#9)
- [x] M1 Skeleton: Gradle (AGP 9.1, Kotlin 2.3, Compose), `:core` + `:app`, CI, design doc (#1)
- [x] M2 Feeds: parser, finder, OPML, Room, FeedSync, Sources screen (#1)
- [x] OPML import/export in Sources; source freshness ("last new article 2 days ago") instead of unread counts (#8)
- [x] Architecture pass: no stuck editions, timer can't go stale, IO off main; e-ink progress, first-edition moment, Try again (#7)
- [x] UX pass from persona audit: delivery confirmation, notification permission, Boox Open, next-edition line, check chips (#6)
- [x] Edition detail with bring back; generated cover image (#5)
- [x] M6 Reading list: share target, reading list screen, markdown checklist import/export compatible with rss-to-e-reader (#4)
- [x] M5 Onboarding: device, starter packs, paste a site, size and schedule, first edition (#3)
- [x] Images in editions: 1200px JPEG, per-edition allowance so over-budget images aren't downloaded (#3)
- [x] M4 Settings, scheduled editions (timer chain that never skips an overdue edition), folder delivery, notifications (#2)
- [x] M3 The edition: planner, extraction, EPUB writer, EditionBuilder, Today screen, share/open (#1)
