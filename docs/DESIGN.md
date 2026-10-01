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
| **Article state** | `NEW`, then `IN_EDITION`, then `DELIVERED`; or `SKIPPED` (you marked it as read), or `EXPIRED` (older than the source keeps articles). |
| **Star** | "Put this in my next edition." A flag beside the state, not a state: unstarring leaves the article as it was. |

## 4. Making an edition

```
Sync feeds ─► Plan ─► Extract ─► Write the EPUB ─► Deliver ─► Mark delivered
```

The pipeline follows the Python library's shape, in the pure-JVM `:core`
module so it's all unit-tested without Android.

- **Plan.** Starred articles go first, taking turns between the sources
  that have them (oldest star first within a source). Then everything
  else: take turns between sources, at most one article each by default,
  until the reading-time budget is reached. A star uses one of its
  source's slots, ahead of unstarred articles; a source's own cap still
  holds, and stars that don't fit wait for the next edition. When the same
  link waits in two sources, the starred copy goes in. Articles are fetched in plan
  order, so an edition goes over by at most one article. If a turn-taking
  pass leaves time unfilled, a second pass adds more from sources whose own
  cap allows it. Turns start with the source featured longest ago (a tt-rss
  feed counts as a source), so with more sources than fit, the next edition
  picks up the ones the last one left out.
- **Extract.** Use the feed's own text when it's the full article;
  otherwise fetch the page, remove cookie banners and run Readability4J
  (Firefox's Reader View). schema.org JSON-LD is used instead when it has
  much more text, which usually means Readability only saw a paywall
  preview. If the page can't be fetched (or is over 5 MB, too big to parse
  on a phone), the feed's text goes in with a note saying so; an article that fails entirely still goes in, so a broken
  source gets noticed.
- **Link posts.** Some feeds mostly pitch stories on other sites
  (Longreads' picks: a few paragraphs, then "Read the story" at
  `equator.org/…?src=longreads`). A feed or tt-rss item counts as a link
  post when its own text is under 500 words and it links to another site
  with a referral tag naming its own site (`src=`, `ref=`, `source=`,
  `via=`, `utm_source=`), and to only one such page: that's the story.
  (Some platforms tag every outbound link, so a short post linking to
  several isn't a pointer.) Nothing is set up per site.
  - The article is stored as the story's address, so the same story from
    two sources goes out once, and a delivered story isn't stored again.
    The feed's guid is kept, so the pitch isn't offered twice.
  - The edition fetches the story's page, with the usual paywall, bot
    check and size rules. It replaces the pitch only if it's clearly the
    story: at least half the words of the item's title are in the page's
    title or address (pointer sites title a pick with the story's
    headline), and it has twice the pitch's words.
  - The story goes in credited to both ("Equator via Longreads"), with
    the story's own author, and "Read the original" links the story.
  - A page that's too short (a paywall preview) or can't be fetched leaves
    the pitch, with a note, still credited to both. So does a pick of a
    comic or image post: the image rules above don't apply to link posts.
  - A page titled for something else means the post wasn't a pointer (a
    short commentary post with one tagged link): the post goes in as
    itself, credited to its source, with its own page as the original.
    Until it's picked it waits under the linked address, so two such posts
    about the same page, or the page itself from another source, can't go
    in the same edition; once it goes in, it's the post's own page that
    counts as delivered.
  - Only "Feed's text", chosen by the reader, always keeps the pitch.
  - Link posts aren't evidence for the source's article text: a Longreads
    source is judged by its own full-text posts.
- **Tracking.** `utm_*`, click ids and a referral tag naming the source's
  own site are removed from stored feed, tt-rss and curated-list links,
  so one story compares equal wherever it came from. The link as fetched
  is still checked against delivered links. The guid is left as the feed
  gave it. Saved links are kept as the reader saved them.
- **One story, one delivery.** When a link goes out, its waiting copies
  in other sources are used up, tt-rss ones included; those are marked
  read on the server along with the edition's own tt-rss articles.
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
  - a generated cover image (masthead, a large date, first headlines) so
    library thumbnails show which day's edition it is;
  - "In this edition" contents with each section's and article's reading
    time. Kindle opens the book here, like a paper's front page;
  - each article: section and source, headline, "By … · date · N min read",
    the body, an end mark, "Read the original at site", and a "Next" link
    with its source and time; then "That's all for today" with the day's
    totals and a question to think about, one of a short list of open
    questions that suit any paper ("What surprised you?"). It usually differs from
    one edition to the next, and the notes file shows the same one;
  - styles Kindle's conversion keeps: plain class selectors, relative
    sizes, no side margins, headings aligned explicitly;
  - images as JPEG up to 1200px, about 15 MB in all including the cover, up
    to 20 per article. The budget goes to articles in reading order, and an
    image that can't fit isn't downloaded;
  - a unique title ("… Sep 29", then "… (2)"), because Send to Kindle
    silently drops a title it has seen before. The file is named after it,
    since Send to Kindle's form titles the book after the file's name.
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
articles go back, keeping their stars, before the new one is planned.

**A send that didn't arrive can be undone.** A failed Send to Kindle still
counts as sent, because Android only reports the app you chose.
**Didn't arrive? Mark as not sent** on a sent edition (while its file is
still here) makes it ready to send again:
- its articles go back into it, with the stars they went in with;
- its links are no longer remembered as delivered;
- its tt-rss articles are marked unread on the server again.

Send it again, or leave it and the next edition takes its articles, like any
unsent one. Other copies of its links that delivery used up stay used. An
article brought back into a newer edition since stays in that one.

**The app you send to can read the book after its screen closes.** A share
only lets the receiving screen read the file, and Send to Kindle uploads
after its form closes. So the Kindle app, and whichever app you pick, is
allowed to read that edition's file until the phone restarts.

## 7. What happens to articles you didn't read

- **Delivered means done.** An article appears in one edition. Delivered
  links are remembered for a year, so a source removed and added again, or
  the same story in two sources, isn't delivered twice.
- **Stars.** Starring an article puts it in the next edition, whatever its
  state: waiting, delivered (this is how you bring one back), marked as
  read or expired. Stars never expire. Delivery clears them, on every copy
  of the link; an edition that's never sent gives its articles back with
  their stars, each in the state it had before (a starred delivered article
  goes back to delivered). A paused source holds its stars; removing a
  source removes them.
- **Read and unread.** A waiting article you've read elsewhere, or don't
  want, is marked `SKIPPED` and never goes in an edition; its star goes.
  Marking it unread puts it back to waiting. So does marking a delivered
  or expired one unread: it waits its turn with the others, unlike ★, which
  puts it in the next edition. It counts as found again, so it gets a week
  before it expires. A batch marked read from selection has one Undo.
- **While an edition is being made** you can star articles but not unstar
  them or mark them read: the build may already have put them in the book.
  A batch is held whole, never half done.
- **Everything else expires.** Unplanned articles older than the source's
  keep window (7 days for news feeds, never for the reading list) quietly
  go. Curated lists keep only their newest 12 unread links.
- **Titles for curated picks.** A list that gives only a teaser (Arts & Letters
  Daily) has each pick's title looked up from its page in the background, as
  saved links do, retried for two days; a bot-check page's title is never taken.
- **tt-rss:** each sync takes up to five unread articles from every feed, so a
  feed that posts monthly isn't crowded out by busy ones. Articles are marked
  read on the server once delivered (and unread again if the edition is
  marked as not sent). What you mark read or unread here reaches the server
  at the next sync, so a change of mind before then never does. A source can turn that off, or
  take one category instead of all unread; adding the account asks which,
  before the first sync. tt-rss's own stars aren't synced: there a star
  usually means "keep this", not "for tomorrow".
  - **Feeds in your paper** (on the source's page) lists the account's feeds
    seen in the last month, with a checkbox each. A feed left out isn't
    fetched, and its waiting articles go too, except starred ones. It stays
    in tt-rss, and stays listed so it can come back. Signing in as another
    user clears the choices: feed ids belong to each tt-rss user.
  - **Start fresh** ("Back after a break?" on the source's page), after a
    confirmation, marks everything that reached tt-rss more than two weeks
    ago read there (in the source's category, if it has one), starred ones
    included. Articles already waiting in the app expire as usual. It
    needs tt-rss API level 15 (2020) or later: older servers ignore the two
    weeks and would mark everything read, so the app refuses and says so.
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

- **Today** (home): when the next edition is due, how many starred articles
  will go in the next one (only when some will), and the latest edition with
  **Send**, **Open** and **I've sent it** (**Send again** and **Didn't
  arrive? Mark as not sent** once delivered).
  **Make an edition now** is the main button only before the first edition;
  after that it's a quiet **Make another edition**, since today's paper is
  done. A failed build or edition offers **Try again** after saying what
  went wrong. Earlier editions are listed below. Kindle and Kobo readers
  aren't offered **Open**, here or on an edition's page: it opens the book
  on the phone, and they get it by sending it.
- **Edition:** the same buttons as Today's card, including **Mark as not sent**,
  and its contents; tap an article to preview it as the e-reader
  will show it (read straight from the EPUB, with nothing fetched from the
  network). **Notes** exports a Markdown file for a notes app: front matter
  (date, edition, sources, a tag) for Obsidian, the closing page's question and
  a few reflection prompts at the top, then per article its source, author, date, link, a citation and room for notes.
  **Delete edition** is in the ⋮ menu, and asks by name, saying what happens
  to the articles (an unsent edition's go back for the next one). An article that went in because it was starred says
  "You starred it". On delivered editions each article has a trailing **☆** to
  bring it back ("Didn't get to one? Tap ☆ to bring it back.").
- **Sources:** each source with its health ("Full articles", "Summaries
  only", "Site blocks fetching", "Failing for N days"). A source's page
  shows its recent articles, its cap, pause and the article-text setting;
  **Remove source** is in its ⋮ menu, as on the list.
  Each row is a status mark (`●` waiting, `✓` delivered, `○` read or not
  used), the title, a details line and a trailing **☆**; tapping the row
  opens the original. Two toggles, one per question:
  - The status mark marks the article read (`●` → `○`) or unread (`○` or
    `✓` → `●`). A second tap undoes it, so there's no Undo. It has no
    outline; the line above the list says it can be tapped. Its touch
    area is 48dp, though the mark is narrower so titles line up.
  - The star (a 48dp target) puts the article in the next edition and
    turns into **★** on an outlined disc.
  A row already in an unsent edition can't change: its mark doesn't
  respond, and it has no star but keeps the slot so titles line up.
  - **Select** (or pressing and holding a row) enters selection mode:
    checkboxes take the status marks' place, the top bar says "N
    selected", and a bar at the bottom has **☆ Next edition** and
    **Mark N as read** (counting only waiting articles). One Undo covers
    the whole batch. Back or ✕ leaves without changing anything.
  - With TalkBack, the mark is its own button: "Mark <title> as read" or
    "as unread".
  - Nothing moves: a marked-read row stays in place with `○`, and
    entering or leaving selection doesn't shift the list, so e-ink
    doesn't redraw it.
  Add a source, import or export OPML, or add a tt-rss account.
- **Reading list:** links you shared into the app, each with its title,
  site and reading time (looked up in the background). They go into the
  next edition under "Saved for later". Import and export as a Markdown
  checklist (compatible with the library); Pocket and Instapaper exports
  import too. **✕** removes a link at once, with **Undo** in a snackbar, since
  removing is routine and a confirm would only be tapped through.
- **Settings:** the edition (size, per-source cap, order), the schedule
  (time and days), your e-reader, delivery (share or folder), reading notes, and the
  app's version.
  - **Reading notes:** "Save notes for each edition" asks for a folder (an Obsidian
    vault, say). Each edition's notes file is saved there once the edition is
    delivered, by share, folder or Open, in the background so a slow cloud folder
    doesn't hold up delivery. A folder that refuses the file gets a notification;
    **Notes** on the edition still shares them. It can be the delivery folder too;
    changing one folder never drops the app's access to the other.

The look: a newspaper feel (serif headlines, a masthead with the date), but
calm, with no badges, counts or endless animations, which smear on e-ink.

## 10. Architecture

```
:core  (Kotlin/JVM, no Android)          :app  (Android, Compose)
├─ feed/     parsing, feed discovery,    ├─ data/      Room database, repositories
│            OPML, starter packs         ├─ settings/  DataStore settings
├─ edition/  planner, titles, schedule   ├─ edition/   EditionBuilder, EditionRun, cover, notes
├─ extract/  page and article            ├─ work/      background workers, the scheduler
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
- **WorkManager** runs the sync, the build, the timers, and what follows
  delivery (marking tt-rss read, saving notes).
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
