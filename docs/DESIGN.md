# newspaperss: design

> Your own newspaper, on your e-reader. You pick the sources and the size;
> it arrives on schedule, it ends, and nobody's algorithm is involved.

newspaperss is an Android app that brings the ideas behind
[rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to people who
don't write code or run servers. You subscribe to feeds and save links, and
the app puts together a finite *edition* (for example "about 30 minutes,
every morning at 6:30"), turns it into a clean EPUB and delivers it to your
Kindle, Kobo, Boox, PocketBook or KOReader device.

## 1. Principles

1. **An edition that ends.** The unit of the app is the edition, not a feed.
   It has a reading-time budget, a clear last page ("That's all for
   today") and no unread counts anywhere. You shouldn't feel you owe the
   app anything.
2. **You are the editor.** Only sources you chose, ordered by rules you can
   see (take turns between sources, at most N per source). No
   recommendations, no ranking by engagement.
3. **Read somewhere calmer.** The app is where you *edit* your paper, not
   where you read it. An in-app preview exists so you can check an edition,
   and for Android e-readers like Boox that run the app themselves, but
   there's no endless in-app timeline.
4. **Works for non-coders in two minutes.** From install to first edition:
   pick your device, pick some sources, press "Make my first edition".
   Advanced knobs (tt-rss, per-source full-text, SMTP) exist, out of the way.
5. **Nothing is lost on failure.** Articles are only marked as consumed
   after delivery succeeds (the library's "tick off after send" rule).
   Failures are loud, successes are quiet.
6. **Private by default.** No account, no analytics, no server. Everything
   stays on the phone except the fetches it makes to your sources and the
   delivery you choose.

## 2. Who it's for

- **The Kindle reader who doomscrolls.** Owns a Kindle, reads news on
  their phone and wishes they didn't. Wants: pick a few sites, get a
  morning paper on the Kindle, done.
- **The ex-Pocket or Omnivore user.** Saved links vanished when those
  services shut down. Wants: share a link from anywhere and have it turn
  up in their next edition.
- **The RSS veteran.** Already runs tt-rss or FreshRSS. Wants: their
  existing subscriptions turned into a well-made EPUB on a schedule.
- **The Boox or other Android e-reader owner.** Installs the app on the
  reader itself and reads the edition there.

## 3. Core concepts

| Concept | What it is |
|---|---|
| **Source** | Somewhere articles come from: an RSS/Atom/JSON feed; a **reading list** (links you shared or saved); later, a tt-rss/FreshRSS/Miniflux account or a scraper-fed curated list. |
| **Section** | A user-named group of sources ("World", "Long reads", "Friends' blogs"). Sections become the edition's contents pages. |
| **Edition profile** | A recipe: schedule, reading-time budget *or* article count, per-source cap, ordering (take turns / in order / shuffle), which sections to use, delivery method. Starts with one ("Morning paper"); power users add more ("Sunday long reads"). |
| **Edition** | One built issue: title ("Tuesday Morning Edition"), articles, the EPUB file and its delivery status. The history of editions is the app's main screen. |
| **Delivery method** | How the EPUB reaches the device (§6). |
| **Article state** | `NEW`, then `IN_EDITION` (planned), then `DELIVERED`. Or `SKIPPED` (the user dismissed it) or `EXPIRED` (older than the source's keep window). |

## 4. The edition pipeline

The same shape as the Python library, rewritten in Kotlin in the pure-JVM
`:core` module so all of it can be unit-tested without Android:

```
Collectors ──► Candidates ──► EditionPlanner ──► Fetcher/Extractor ──► EpubWriter ──► Delivery ──► Commit
 (feeds,        (Article       (take turns,       (readability,          (cover, TOC,     (share,     (mark
  reading        metadata,      per-source cap,    JSON-LD fallback,      nav + NCX,       folder,     DELIVERED,
  lists,         dedup by       reading-time       e-reader cleaning,     images ≤1200px,  email)      record the
  tt-rss)        URL)           budget)            feed fallback)         size budget)                 edition)
```

Ported from the library, with the lessons its code and comments record:

- **Reading-time budget with lazy fetching.** Fetch in plan order and stop
  once the running total reaches the budget, so it goes over by at most one
  article. Minutes are *fractional*: the library's integer division counts
  short pieces as 0 minutes.
- **Take turns (round-robin) between sources, with a per-source cap.** The
  default cap is 1 per source for a daily paper, so a busy feed can't take
  over the edition.
- **Extraction.** Use the feed's own content when it's full text, otherwise
  fetch the page and run Readability4J. Before extraction, remove
  cookie/consent overlays. Fall back to schema.org JSON-LD `articleBody`,
  then to the feed text with a visible note ("Couldn't fetch the full
  article; showing the feed's version"). An article that failed still goes
  in the edition, so you notice a source that always fails.
- **Automatic full-text detection per source.** The library's source check:
  if the page has at least 2× the feed's words, the feed is a teaser, so
  fetch pages; if extraction keeps under 0.7× the feed, trust the feed. It
  runs automatically on the first few articles of a new source instead of
  asking the user.
- **EPUB that Send to Kindle accepts.**
  - strict XHTML (serialized through jsoup in XML mode);
  - EPUB 3 nav *and* NCX, in the same order;
  - the cover in the spine as a linear item;
  - images re-encoded to JPEG at up to 1200px, with no SVG, WebP or AVIF;
  - a total size budget of about 15MB so email delivery works too;
  - a unique title per day ("… (2)"), because Send to Kindle silently
    drops a document whose title it has already seen.
- **Commit after delivery.** Articles become `DELIVERED` and list items are
  ticked off only when delivery reports success. For share-sheet delivery,
  where the app can't know whether it worked, "success" is the user
  confirming "Sent it" (§6).
- **One run at a time.** Builds are unique WorkManager work (`KEEP`), so a
  scheduled build and a manual "Make one now" can't race.

## 5. What happens to articles you didn't read

The library's personal setup re-offers *all unread* articles until you mark
them read on the tt-rss server. That's a separate manual step, and a
non-coder won't know to do it. newspaperss defaults to:

- **Delivered means done.** An article appears in one edition.
- **Bring it back.** In an edition's detail screen, you can tap articles
  you didn't get to and they go back into the pool, at the front of their
  source's queue.
- **Everything else expires.** Unplanned articles older than the source's
  keep window (default 7 days for news feeds, never for reading lists)
  quietly disappear. No backlog guilt.

tt-rss sources keep the library's behaviour as an option: mark read on the
server when an edition is delivered.

## 6. Delivery

| Device | Default method | Unattended? |
|---|---|---|
| Kindle | **Share to the Kindle app** (the Send to Kindle share target), one tap from the "Your edition is ready" notification | No, one tap |
| Kindle (advanced) | Email to your @kindle.com address via SMTP app password | Yes |
| Kobo | **Save to a folder** through the Android file picker (SAF). Pick a Dropbox or Google Drive folder that the Kobo syncs | Yes |
| PocketBook | Email to `@pbsync.com`: share with an email intent, or SMTP | One tap / yes |
| KOReader | Save to a folder (Syncthing or similar) | Yes |
| Boox / Android e-readers | Open in the device's reader app (`ACTION_VIEW`) or read in the app | Yes |
| Anything else | Share sheet | No |

"Save to a folder" through SAF covers every cloud-folder route without the
app handling any OAuth: Google Drive, Dropbox and Syncthing-Fork all
provide document providers.

Share-sheet delivery can't tell whether the send worked, so the edition
stays "Ready" until the user taps **Sent it** (or opens the edition again
later and is asked). That keeps the commit-after-delivery rule honest.

## 7. Onboarding (target: first edition in under two minutes)

1. **Welcome.** One sentence of pitch, then "Get started".
2. **Where do you read?** Pictures: Kindle / Kobo / Boox / PocketBook /
   KOReader / "Just give me the file". This picks the delivery method and
   shows device-specific setup (for example "Pick your Kobo's Dropbox
   folder").
3. **Pick your sources.**
   - **Starter packs** by topic: a few well-known public feeds each (news,
     science, tech, essays, culture).
   - **Paste any website.** Feed autodiscovery from `<link rel="alternate">`,
     then common paths (`/feed`, `/rss`, `/atom.xml`, `/index.xml`).
   - **Import OPML**, from other readers or a tt-rss export.
4. **How big, how often?**
   - Size slider: "15 min · 30 min · 1 hour" (reading at about 238 wpm).
   - Time and days: "Every morning at 6:30".
5. **Make my first edition.** It builds right away, with visible progress
   ("Fetching 7 of 12 …"), then offers delivery.

## 8. Screens

- **Today** (home): the next edition ("Tomorrow 6:30 · ~30 min · 9 sources"),
  a big **Make one now** button, and the latest edition's card (cover,
  article list, delivery status, *Send* / *Sent it* / *Open*). Below it,
  the history of past editions.
- **Edition detail:** the cover and contents. Tap an article to preview it
  as the e-reader will show it. Actions: *bring back unread*, *send again*,
  *share EPUB*.
- **Sources:** sections, each with its sources. Per source: its last
  articles, health ("full text ✓", "failing for 3 days ✗"), cap override,
  pause. An add button (paste URL / search starter packs / OPML).
- **Reading list:** links you shared into the app (the app is a share
  target for URLs), each with a title and domain. They show up in the next
  edition with their own slot, like the library's markdown-checklist
  collector. Export and import as a markdown checklist, which keeps
  compatibility with the library.
- **Settings:** edition profiles (schedule, size, ordering, cap), delivery,
  reading speed, advanced (tt-rss account, SMTP, notes export), about.

The visual language: a newspaper feel inside the app (serif headlines, a
masthead with the date), but calm, with no badges or counts.

## 9. Architecture

```
:core   (Kotlin/JVM, no Android)            :app   (Android, Compose)
├─ model/        Article, Source, …          ├─ data/       Room DB, DAOs, repositories, DataStore
├─ feed/         RSS/Atom/JSON Feed parser,  ├─ work/       EditionWorker, SyncWorker (WorkManager)
│                feed autodiscovery, OPML    ├─ delivery/   Share, SAF folder, open-in-reader, SMTP
├─ edition/      EditionPlanner (turns, cap, ├─ ui/         Compose screens + ViewModels
│                budget), titles             │   ├─ today/ edition/ sources/ reading list/ settings/ onboarding/
├─ extract/      Readability4J + cleaning    └─ AppContainer  (manual DI, no Hilt)
├─ epub/         EpubWriter (hand-rolled zip)
└─ net/          HttpFetcher interface
```

- **`:core` is pure Kotlin** so the planner, parser, extractor and EPUB
  writer are fast JVM unit tests. XML parsing uses the XmlPullParser API:
  the Android platform provides it, and kxml2 provides it in JVM tests.
- **Single activity, Jetpack Compose, Navigation Compose, ViewModels with
  StateFlow.** Compose avoids the fragment-recreation class of bugs
  dailylog ran into.
- **Room** for sources, articles, editions and the reading list, with
  exported schemas and explicit migrations from the first release.
  **DataStore** for settings.
- **WorkManager**: a periodic feed sync, plus a unique scheduled edition
  build that re-arms itself for the profile's next time slot. The build
  is idempotent: rerunning it after a crash doesn't duplicate an edition.
- **OkHttp** for fetching, with a normal desktop user agent and timeouts.
  The library's TLS-impersonation fallback (curl_cffi) has no easy Android
  equivalent. The feed-text fallback with a note covers blocked pages.
- **Manual dependency injection** (`AppContainer`): the app is small, and
  Hilt would add kapt/KSP and annotation machinery for little gain.
- **Dependencies stay permissively licensed** (Apache/MIT): Readability4J,
  jsoup, OkHttp and AndroidX. The open-source Android readers (Read You,
  Feeder, Capy) are GPL, so newspaperss learns from them but copies no code.
- **Tests:** JVM unit tests in `:core`; Robolectric + Compose UI tests in
  `:app` against a real Room database; MockWebServer for network code.
  CI runs `./gradlew build` and Kover. The EPUB writer's output is checked
  structurally in tests (mimetype first and stored, nav/NCX agree, XHTML
  well-formed).

## 10. Milestones

1. **Skeleton:** Gradle, CI, Compose shell, Room, `:core`.
2. **Feeds:** parse RSS/Atom/JSON Feed, add a source by URL with
   autodiscovery, OPML import, sync worker.
3. **The edition:** planner, extraction, EPUB writer, "Make one now", share
   the EPUB. **This is the first usable app.**
4. **Delivery and schedule:** SAF folder, open-in-reader, scheduled builds,
   the "ready" notification, *Sent it*.
5. **Onboarding:** device picker, starter packs, first-edition flow.
6. **Reading list:** share target, markdown import/export.
7. **Polish:** edition preview, source health, bring-back-unread, cover art.
8. **Advanced:** tt-rss source, SMTP, notes export (Markdown notes with
   citations and reflection prompts, from the personal setup).

## 11. Open questions for the owner

- **Name.** "newspaperss" works well written down but is hard to say
  aloud or spell. Alternatives: *Morning Paper*, *Broadsheet*, *Slowpaper*,
  *Inkling*, *The Daily Edition*. It stays newspaperss until decided.
- **License.** rss-to-e-reader has no LICENSE file. For F-Droid, the app
  needs one (Apache-2.0 or GPL-3.0 are the usual choices).
- **Starter packs.** Which feeds to curate by default (public, well-known,
  full-text where possible).
