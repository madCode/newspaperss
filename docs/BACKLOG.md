# Backlog

The running plan. Each work cycle picks the top unblocked item, builds it
with tests, and moves it to Done with its PR. Milestones are from
[DESIGN.md §10](DESIGN.md#10-milestones).

## Next

### M4 leftovers
- [ ] If lead time isn't enough on a real device, wake for timed editions with an exact alarm (Doze defers WorkManager; expedited work was rejected: its API 31+ quota can silently restart a long build)
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; Drive/Dropbox SAF providers don't expose folder trees
- [ ] Verify folder delivery + chooser-from-notification on a real device

### M3 leftovers
- [ ] Write encoded images to a cache dir and stream them into the zip (lower peak memory)

### M5 leftovers

### M6 leftovers

### M7 Polish

### M8 Advanced
- [ ] SMTP delivery (low priority: sharing to the Kindle app and Calibre cover most email needs)

### Tech debt

## Feature proposals

Ideas worth doing, not yet planned. Each gets a sketch before it moves to Next.

### Add sources to tt-rss, not just the phone
For someone with a tt-rss account, a site found in newspapeRSS (a starter pack, a curated list, a
pasted address) could be subscribed on the server instead, with the API's `subscribeToFeed`. It would
then show up in their other reader apps too, and read state stays in one place. Open questions:
- which tt-rss category it goes in;
- whether the phone-side source is then dropped, so articles don't arrive twice;
- what to do for an OPML import.

### Local news for your city or country
Help people find news sources near them: local papers, public broadcasters, city blogs.
- Options include a curated starter pack per country or region, and location-based Google News feeds.
- A "near me" search could use the device's locale, without needing a location permission.
- The hard part is keeping curated lists current and fair. Starter packs must stay public, well-known
  feeds only.

### Languages
What to do with non-English sources and readers. Today:
- the book's language is always `en`;
- the page text (contents, "min read") is English;
- reading time assumes English words per minute.

Questions:
- tag each article with its own language (`xml:lang`), so e-readers hyphenate and pick fonts correctly;
- reading time for languages that aren't space-separated (Chinese, Japanese), which is roughly
  characters per minute;
- right-to-left layout;
- whether the app UI and the book's own text should be translated;
- whether an edition should mix languages or keep them in sections.

A small first step: detect each article's language and tag it.

### Listen: the paper as an audiobook
An audiobook of your newspaper: listen to an edition on a walk, from the same finite paper.
- **Engine:** Android's own `TextToSpeech`. It's offline and free, and voices already on the phone keep the no-account promise. Cloud voices sound better but need an account and send the text away, so they'd only ever be an option.
- **Shape:** a "Listen" button on the edition screen that reads the articles in order. It runs as a media session with a notification (pause, skip article) and stops at the end of the edition, keeping the paper finite.
- **Text:** the article bodies as already cleaned for the EPUB. Headings are read as pauses; image captions and link lists are skipped.
- **Later:** export the edition as an audiobook file (M4B with a chapter per article) for podcast and audiobook apps. The EPUB could carry media overlays, but few e-readers play them.
- **Open questions:** remember the position between sessions? Count listened articles as read for "bring back"?

### From the competitor research ([docs/research/competitors.md](research/competitors.md))
- **Kobo through Google Drive.** Kobo syncs a "Rakuten Kobo" Drive folder natively. Drive's SAF provider
  has no folder trees, so this needs the Drive API (an OAuth client, like Dropbox's app key).
- **Close the loop from the device:** finished on the e-reader means archived; KOReader highlights feed the notes export.
- **More importers:** Matter, Readwise, Raindrop and Omnivore exports, for people leaving shut-down apps.
- **An OPDS catalog served from the phone,** for KOReader and jailbroken Kindles.

## Done

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
