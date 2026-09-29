# Backlog

The running plan. Each work cycle picks the top unblocked item, builds it
with tests, and moves it to Done with its PR. Milestones are from
[DESIGN.md §10](DESIGN.md#10-milestones).

## Next

### M4 Delivery and schedule
- [ ] Settings screen: edition size (minutes), per-source cap, ordering, schedule (DataStore)
- [ ] Scheduled builds (daily at a time, chosen days); "edition ready" notification with Send action
- [ ] SAF folder delivery (Kobo via Dropbox/Drive, KOReader via Syncthing), marks delivered automatically
- [ ] Failure notification (loud) vs success (quiet)

### M3 leftovers
- [ ] Auto-tune source ContentMode from ExtractedArticle feed/page word counts (`ArticleExtractor.suggestMode`)
- [ ] Edition detail screen: contents, bring back articles, share again
- [ ] Generated cover image (Canvas) so Kindle's library thumbnail shows date + headlines

### M5 Onboarding
- [ ] Device picker → delivery method; starter packs; size/schedule; first edition

### M6 Reading list
- [ ] Share target for URLs; reading list screen; markdown checklist import/export

### M7 Polish
- [ ] Source health view; remove-and-re-add shouldn't re-deliver (soft delete sources)
- [ ] OPML import/export UI

### M8 Advanced
- [ ] tt-rss source (mark read on server after delivery); SMTP delivery; notes export

### Tech debt
- [ ] Parser tests run on kxml2, the app ships Android's KXmlParser: add a Robolectric smoke test through the platform parser
- [ ] Charset sniffing for HTML pages without a header charset

## Done

- [x] M1 Skeleton: Gradle (AGP 9.1, Kotlin 2.3, Compose), `:core` + `:app`, CI, design doc (#1)
- [x] M2 Feeds: parser, finder, OPML, Room, FeedSync, Sources screen (#1)
- [x] M3 The edition: planner, extraction, EPUB writer, EditionBuilder, Today screen, share/open (#1)
- [x] Images: download with the article as Referer, 1200px JPEG via BitmapFactory, 20 per article, 15MB per edition
