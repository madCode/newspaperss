# Backlog

The running plan. Each work cycle picks the top unblocked item, builds it
with tests, and moves it to Done with its PR. Milestones are from
[DESIGN.md §10](DESIGN.md#10-milestones).

## Next

### M4 leftovers
- [ ] A timer that fires in Doze: WorkManager's delayed timer can wait for the phone's next maintenance window overnight, so a 6:30 edition may start late. An inexact `AlarmManager.setAndAllowWhileIdle` alarm fires in Doze without the exact-alarm permission
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

### Listen: the paper as an audiobook
An audiobook of your newspaper: listen to an edition on a walk, from the same finite paper.
- **Engine:** Android's own `TextToSpeech`. It's offline and free, and voices already on the phone keep the no-account promise. Cloud voices sound better but need an account and send the text away, so they'd only ever be an option.
- **Shape:** a "Listen" button on the edition screen that reads the articles in order. It runs as a media session with a notification (pause, skip article) and stops at the end of the edition, keeping the paper finite.
- **Text:** the article bodies as already cleaned for the EPUB. Headings are read as pauses; image captions and link lists are skipped.
- **Later:** export the edition as an audiobook file (M4B with a chapter per article) for podcast and audiobook apps. The EPUB could carry media overlays, but few e-readers play them.
- **Open questions:** remember the position between sessions? Count listened articles as read for "bring back"?

### Research: what else is out there
A deep-research pass on competing and neighbouring apps: RSS-to-e-reader tools (e.g. services that send
feeds to a Kindle, Calibre's news recipes, KOReader's news downloader), read-later apps and newsletter
digests. For each: what it does, what it costs, how popular it is, and what newspapeRSS does differently
or should borrow. The result goes in `docs/research/`.

## Done

- [x] Edition builds are expedited work with a "Making your edition…" notification, so they start as soon as they're asked for
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
