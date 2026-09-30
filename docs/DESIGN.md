# newspaperss: design

> Your own newspaper, on your e-reader. You pick the sources and the size;
> it arrives on schedule, it ends, and nobody's algorithm is involved.

newspaperss is an Android app that brings the ideas behind
[rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to people who
don't write code or run servers. You subscribe to feeds and save links, and
the app puts together a finite *edition* (for example "about 30 minutes,
ready by 6:30 every morning"), turns it into a clean EPUB and delivers it to
your Kindle, Kobo, Boox, PocketBook or KOReader device.

This document describes how the app works today. What's planned is in
[BACKLOG.md](BACKLOG.md); what changed and why is in [DEVLOG.md](DEVLOG.md).

## 1. Principles

1. **An edition that ends.** The unit of the app is the edition, not a feed.
   It has a reading-time budget, a clear last page ("That's all for
   today") and no unread counts anywhere.
2. **You are the editor.** Only sources you chose, ordered by rules you can
   see (take turns between sources, at most N per source). No
   recommendations, no ranking by engagement.
3. **Read somewhere calmer.** The app is where you *edit* your paper, not
   where you read it. You can preview an article, and a Boox can read the
   edition on the device, but there's no endless in-app timeline.
4. **Works for non-coders in two minutes.** From install to first edition:
   pick your device, pick some sources, make the edition. Advanced knobs
   (tt-rss, per-source article text) exist, out of the way.
5. **Nothing is lost on failure.** Articles are only used up once the
   edition is delivered. Failures are loud, successes are quiet.
6. **Private by default.** No account, no analytics, no server. Everything
   stays on the phone except the fetches it makes to your sources and the
   delivery you choose.

## 2. Who it's for

- **The Kindle reader who doomscrolls:** wants a few sites as a morning paper on the Kindle.
- **The ex-Pocket or Omnivore user:** wants links shared from anywhere to turn up in the next edition.
- **The RSS veteran:** already runs tt-rss or FreshRSS, wants those subscriptions as a well-made EPUB.
- **The Boox owner:** installs the app on the reader itself and reads the edition there.

## 3. Core concepts

| Concept | What it is |
|---|---|
| **Source** | Where articles come from: an RSS, Atom or JSON feed; the **reading list** (links you shared or saved); a tt-rss account; or a **curated list**, a page that picks a few links a day (Arts & Letters Daily). |
| **Section** | A heading in the edition's contents. Sections come from OPML folders, and the reading list is "Saved for later". |
| **Edition settings** | One recipe: size (minutes), per-source cap, order (take turns / in order / shuffle), and the time and days it should be ready. |
| **Edition** | One built issue: a dated title ("Tuesday Morning Edition, Sep 29"), its articles, the EPUB and its status: building, ready, delivered, failed or deleted. |
| **Article state** | `NEW`, then `IN_EDITION`, then `DELIVERED`; or `SKIPPED`, or `EXPIRED` (older than the source keeps articles). |

## 4. Making an edition

```
Sync feeds ─► Plan ─► Extract ─► Write the EPUB ─► Deliver ─► Mark delivered
```

The pipeline follows the Python library's shape, in the pure-JVM `:core`
module so it's all unit-tested without Android.

- **Plan.** Take turns between sources, at most one article each by default,
  until the reading-time budget is reached. Articles are fetched in plan
  order, so an edition goes over by at most one article. If a turn-taking
  pass leaves time unfilled, a second pass adds more from sources whose own
  cap allows it.
- **Extract.** Use the feed's own text when it's the full article;
  otherwise fetch the page, remove cookie banners and run Readability4J
  (Firefox's Reader View). schema.org JSON-LD is used instead when it has
  much more text, which usually means Readability only saw a paywall
  preview. If the page can't be fetched (or is over 5 MB, too big to parse
  on a phone), the feed's text goes in with a note saying so; an article that fails entirely still goes in, so a broken
  source gets noticed.
- **Comics and image posts.** A feed item that's just an image counts as
  content. When a page's text is clearly not the article, the page's main
  image is used, and a webcomic's own comic (all its panels) beats the
  feed's thumbnail.
- **Which text to use, per source.** Each article is evidence: a page with
  twice the feed's words means the feed is a teaser; a feed of 300+ words,
  or a site that blocks fetching, means the feed is enough. Three days
  pointing the same way set the source to that; the reader can override it
  on the source's page ("Article text: Automatic / Feed's text / Full page").
- **Language.** Each article is tagged with its language (`xml:lang`, and
  `dir="rtl"` for right-to-left scripts) so e-readers hyphenate and lay it
  out correctly. The text decides; the page's declared language breaks ties.
  Reading time counts Chinese and Japanese by character, since they have no
  spaces between words.
- **The EPUB.** Built to be accepted by Send to Kindle:
  - strict XHTML, EPUB 3 nav *and* NCX in the same order, the cover in the
    spine;
  - a generated cover image (masthead, date, first headlines) so library
    thumbnails show the edition;
  - "In this edition" contents with each article's source and reading time;
  - each article: source, headline, "By … · date · N min read", the body,
    "Read the original at site", and a "Next" link; then "That's all for
    today";
  - images as JPEG up to 1200px, about 15 MB in all including the cover, up
    to 20 per article. The budget goes to articles in reading order, and an
    image that can't fit isn't downloaded;
  - a unique title ("… Sep 29", then "… (2)"), because Send to Kindle
    silently drops a title it has seen before.
- **One build at a time.** Builds are unique WorkManager work, so a timed
  build and "Make an edition now" can't race.

## 5. Timing and background work

- **Timed editions start early.** The time you set is when the edition
  should be *ready*. A chain of one-off timers fires 30 minutes early,
  because Android's Doze can hold background work; the delay then eats lead
  time, not your morning. A clock or time-zone change re-arms the timer.
- **Feeds sync right before each build,** and in the background every 12
  hours when the battery isn't low.
- **No edition is never silent.** A timed run with nothing new sends a quiet
  "No new edition"; if every source failed, it's retried twice, then reported
  as a failure pointing to Sources. A build waiting for a connection says so.
- **Fetching is polite and cheap.** An HTTP cache revalidates every feed
  (If-None-Match / If-Modified-Since), so an unchanged feed costs a small
  "not modified" reply. Requests use a browser-like mobile user agent.

## 6. Delivery

| Device | How it gets there | Unattended? |
|---|---|---|
| Kindle | Share to the Kindle app, one tap from the "ready" notification | One tap |
| Kobo | Share to Dropbox, into the folder the Kobo syncs (`Apps/Rakuten Kobo`) | One tap |
| PocketBook | Share to an email app, to your `@pbsync.com` address | One tap |
| KOReader | Save to a folder that syncs to the device (Syncthing) | Yes |
| Boox | Open it in the device's reader, from the app or the notification | Yes |
| Anything else | The share sheet | One tap |

Folders use Android's folder picker. Google Drive and Dropbox don't offer
whole folders to other apps that way, so cloud delivery goes through a
share for now (a direct Dropbox connection is in the backlog).

**When is an edition delivered?** Saving it to your folder; choosing an app
in the share sheet (Android reports the choice back, from the notification
too); or opening it on a Boox. **I've sent it** covers any other route, and
**Send again** is there if a send didn't arrive. An edition still "ready"
when the next one is built was never sent: it's marked not sent and its
articles go into the new one.

## 7. What happens to articles you didn't read

- **Delivered means done.** An article appears in one edition. Delivered
  links are remembered for a year, so a source removed and added again, or
  the same story in two sources, isn't delivered twice.
- **Bring it back.** On a delivered edition, tick the articles you didn't
  get to and they go into the next one.
- **Everything else expires.** Unplanned articles older than the source's
  keep window (7 days for news feeds, never for the reading list) quietly
  go. Curated lists keep only their newest 12 unread links.
- **tt-rss:** articles are marked read on the server once delivered. A
  source can turn that off, or take one category instead of all unread.
- **Housekeeping.** Only the newest 14 editions keep their EPUB on the
  phone (ready ones always do). An article's feed text is dropped a month
  after it's delivered or expires; its row stays, so it's never re-offered.
- **Deleting an edition** removes its file and contents but keeps its title
  reserved, since Send to Kindle would drop a repeat. A ready edition's
  articles go back to the pool; a delivered one's stay used.

## 8. Onboarding (target: first edition in under two minutes)

1. **Welcome.**
2. **Where do you read?** Kindle, Kobo, Boox, PocketBook, KOReader, or
   "just the file". This picks the delivery method; KOReader asks for its
   folder.
3. **Pick your sources:** starter packs of well-known public feeds; paste
   any website (the app finds its feed); import an OPML file; connect a
   tt-rss account; or import saved links from Pocket or Instapaper, which
   alone are enough to start.
4. **How much, and when?** A 10–90 minute slider, "A new edition every
   day", and a "Ready by" time.
5. **The first edition** builds right away, with its progress on Today.

## 9. Screens

- **Today** (home): when the next edition is due, **Make an edition now**,
  and the latest edition with **Send**, **Open** and **I've sent it**
  (**Send again** once delivered). Earlier editions are listed below.
- **Edition:** its contents; tap an article to preview it as the e-reader
  will show it (read straight from the EPUB, with nothing fetched from the
  network). **Notes** exports a Markdown file for a notes app (per article:
  source, author, date, link, a citation and reflection prompts).
  **Delete**, and on delivered editions, **bring back**.
- **Sources:** each source with its health ("Full articles", "Summaries
  only", "Site blocks fetching", "Failing for N days"). A source's page
  shows its recent articles, its cap, pause and the article-text setting.
  Add a source, import or export OPML, or add a tt-rss account.
- **Reading list:** links you shared into the app, each with its title,
  site and reading time (looked up in the background). They go into the
  next edition under "Saved for later". Import and export as a Markdown
  checklist (compatible with the library); Pocket and Instapaper exports
  import too.
- **Settings:** the edition (size, per-source cap, order), the schedule
  (time and days), your e-reader, delivery (share or folder, optionally the notes file
  beside each edition), and the app's version.

The look: a newspaper feel (serif headlines, a masthead with the date), but
calm, with no badges, counts or endless animations, which smear on e-ink.

## 10. Architecture

```
:core  (Kotlin/JVM, no Android)          :app  (Android, Compose)
├─ feed/     parsing, feed discovery,    ├─ data/      Room database, repositories
│            OPML, starter packs         ├─ settings/  DataStore settings
├─ edition/  planner, titles, schedule   ├─ edition/   EditionBuilder, EditionRun, cover, notes
├─ extract/  page and article            ├─ work/      edition, sync and title workers, the scheduler
│            extraction, language        ├─ delivery/  share, folder, the sent callback
├─ images/   image rules and budget      ├─ notify/    the "ready" notification
├─ epub/     the EPUB writer             ├─ ui/        Compose screens and ViewModels
│                                        └─ AppContainer (manual dependency injection)
├─ lists/    curated-list scrapers
├─ notes/    the Markdown notes file
├─ ttrss/    the tt-rss API
└─ net/      HttpClient (OkHttp)
```

- **`:core` is pure Kotlin,** so the planner, parser, extractor and EPUB
  writer run as fast JVM tests.
- **One activity, Jetpack Compose, ViewModels with StateFlow.**
- **Room** holds sources, articles and editions, with exported schemas
  (`app/schemas`) and tested migrations. **DataStore** holds settings.
- **WorkManager** runs the sync, the build and the timers.
- **Manual dependency injection** (`AppContainer`): the app is small enough
  that Hilt isn't worth its machinery.
- **Permissive dependencies only** (Apache/MIT): Readability4J, jsoup,
  OkHttp, AndroidX. The open-source Android readers are GPL, so newspaperss
  learns from them but copies no code.
- **Tests:** JVM tests in `:core`; Robolectric and Compose UI tests in
  `:app` against a real Room database. CI runs `./gradlew build` and Kover.
  A daily scheduled job reads each curated list's live page, so a site redesign
  fails there before it reaches a phone.
- **License:** MIT.

## 11. Open questions for the owner

- **Name.** Shown as newspapeRSS; the repository stays newspaperss.
