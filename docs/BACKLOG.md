# Backlog

The running plan. Each work cycle picks what matters most for readers right now (device feedback
first, then research and judgment), builds it with tests, and moves it to Done. Feature proposals are
ideas, not commitments.

## Next

Grouped by part of the app. The tag says where each item came from: *device* (your testing),
*personas* ([docs/research/personas.md](research/personas.md)), *resources* (the resource audit),
*live* (building real editions), *a11y* (the accessibility audit).

### Onboarding and setup
- [ ] A short Kindle how-to: same Amazon account, pick the device in Send to Kindle, Library › Docs *(personas)*
- [ ] Explain per device what counts as "delivered" *(personas)*

### Sources and fetching
- [ ] Paywalled and summary-only sites: warn when a site is added; keep stubs from eating the budget; drop metered sites from starter packs *(personas)*
- [ ] "No feed found" in onboarding: offer the reading list there too (done in Sources) *(personas)*
- [ ] Decide what tapping an article in a source's list does: open the original, render it, or offer "add to the next edition" *(device)*
- [ ] Page cleanup's furniture patterns ("Recommended stories", "Subscribe to", "Read more:") are English only, so "Lire aussi", "Mehr zum Thema" and "Lee también" slip into French, German and Spanish articles. Key them by the article's language *(live)*
- [ ] Webtoons: episodes are one long strip of dozens of lazy images (`data-url`), beyond the 20-image cap, and its mobile site hides the feed. Support strips properly *(device)*
- [ ] Webcomic title text (xkcd's hover text) is dropped; show it as a caption *(device)*

### The book
- [ ] EPUB design, round 2: the cover image, section pages, and a look on real devices (Kindle, Kobo, KOReader) *(device)*

### Delivery and schedule
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; waiting on an app key ([#18](https://github.com/madCode/newspaperss/issues/18)) *(personas)*
- [ ] Boox: offer folder delivery into the Books folder, so editions stay in the library *(personas)*
- [ ] Folder delivery: tt-rss marks articles read as soon as the file is saved, before Syncthing has synced; old editions pile up in the folder *(personas)*
- [ ] Verify folder delivery and the chooser from the notification on a real device
- [ ] If lead time isn't enough on a real device, wake timed editions with an exact alarm (Doze defers WorkManager; expedited work can silently restart a long build)

### Reading list
- [ ] Saved links that can never be read (a PDF, a video, a page over 5 MB, a 410) wait silently forever. Show them in the reading list as unreadable, with the reason and a way to open or remove them. Not as "couldn't fetch" pages in the edition: they cost no reading time, so a backlog of them could fill one
- [ ] Links saved before database version 2 with a title never get a reading time (no backfill)

### Accessibility *(a11y, you asked for it)*
A full pass over the app and the book, not just spot fixes:
- [ ] App: TalkBack walk-through of every screen (labels, headings, focus order), font scale at 200%, display size, touch targets ≥ 48dp, contrast in light and dark, e-ink readability, nothing carried by colour or animation alone. Confirm the new live regions with TalkBack on a device
- [ ] Book: EPUB Accessibility 1.1 metadata (`schema:accessMode`, `accessibilityFeature`, `accessibilitySummary`), image alt text carried through, reading order checked with a screen reader
- [ ] Tooling: Compose accessibility checks in the Robolectric tests, Accessibility Scanner on a device, Ace by DAISY on a live edition

### Performance *(resources)*
- [ ] tt-rss: pass sinceId, so each sync doesn't re-download the same 200 unread items. Careful: a since-id cursor changes what "unread" returns; rss-to-e-reader's #28 hit a similar trap
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
- [x] Notes export: a Markdown notes file per edition (details, citation, reflection prompts) from Edition detail, and optionally saved beside each edition with folder delivery
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
