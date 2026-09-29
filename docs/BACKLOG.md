# Backlog

The running plan. Each work cycle picks the top unblocked item, builds it
with tests, and moves it to Done with its PR. Milestones are from
[DESIGN.md §10](DESIGN.md#10-milestones).

## Next

### M2 Feeds
- [ ] `:core` feed parser: RSS 2.0, Atom, RDF, JSON Feed → `FeedItem`s (title, url, content, author, date, guid)
- [ ] `:core` feed autodiscovery: `<link rel=alternate>` + common paths
- [ ] `:core` OPML import/export
- [ ] `:app` Room schema: sources, articles, editions, edition_articles; `AppContainer`
- [ ] `:app` Sources screen: add by URL (autodiscovery), list, delete
- [ ] `:app` SyncWorker: fetch all sources, insert new articles, dedup by URL

### M3 The edition
- [ ] `:core` EditionPlanner: take turns, per-source cap, reading-time budget with lazy fetch
- [ ] `:core` extraction: Readability4J, overlay removal, JSON-LD fallback, feed fallback with note, e-reader cleaning
- [ ] `:core` EpubWriter: mimetype/container/OPF/nav/NCX, cover page, contents with minutes, strict XHTML
- [ ] `:core` images: download, downscale to 1200px JPEG, size budget
- [ ] `:app` EditionWorker + "Make one now" + share the EPUB (FileProvider)
- [ ] `:app` Today screen with edition history

### M4 Delivery and schedule
- [ ] SAF folder delivery; open-in-reader; "Sent it" confirmation
- [ ] Scheduled builds per profile; "edition ready" notification

### M5 Onboarding
- [ ] Device picker → delivery method; starter packs; size/schedule; first edition

### M6 Reading list
- [ ] Share target for URLs; reading list screen; markdown checklist import/export

### M7 Polish
- [ ] Edition preview; source health; bring back unread; generated cover art; expiry

### M8 Advanced
- [ ] tt-rss source; SMTP delivery; notes export

## Done

- [x] M1 Skeleton: Gradle (AGP 9.1, Kotlin 2.3, Compose), `:core` + `:app`, CI, design doc
