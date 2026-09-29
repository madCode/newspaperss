# Backlog

The running plan. Each work cycle picks the top unblocked item, builds it
with tests, and moves it to Done with its PR. Milestones are from
[DESIGN.md §10](DESIGN.md#10-milestones).

## Next

### M4 leftovers
- [ ] Expedite scheduled builds (needs getForegroundInfo for API < 31)
- [ ] Dropbox connection (OAuth PKCE) so Kobo delivery is automatic; Drive/Dropbox SAF providers don't expose folder trees
- [ ] Verify folder delivery + chooser-from-notification on a real device

### M3 leftovers
- [ ] Write encoded images to a cache dir and stream them into the zip (lower peak memory)
- [ ] Re-check a source settled on the feed's text now and then: a 429/503 block can be temporary, and today only choosing Automatic again re-runs the check

### M5 leftovers

### M6 leftovers
- [ ] Curated list sources (a scraper per site, like rss-to-e-reader's Arts & Letters Daily queue) with keep-newest-N

### M7 Polish
- [ ] Source health view; remove-and-re-add shouldn't re-deliver (soft delete sources)

### M8 Advanced
- [ ] tt-rss: pick a category instead of all unread; a setting to leave articles unread on the server (DESIGN §5 calls it an option)
- [ ] SMTP delivery

### Tech debt
- [ ] Parser tests run on kxml2, the app ships Android's KXmlParser: add a Robolectric smoke test through the platform parser
- [ ] Charset sniffing for HTML pages without a header charset

## Done

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
