# newspaperss

Your own newspaper, on your e-reader.

newspaperss turns the sites and links you choose into a finite edition
(say, *about 30 minutes, every morning at 6:30*) and delivers it as a clean
EPUB to your Kindle, Kobo, Boox, PocketBook or KOReader device. No
algorithm, no account, no endless feed: it arrives, it ends, you're done.

It's the app version of
[rss-to-e-reader](https://github.com/madCode/rss-to-e-reader), for people
who'd rather not set up Python, a server and a scheduler.

<p>
  <img src="docs/screenshots/01-onboarding-welcome.png" width="200" alt="Welcome screen">
  <img src="docs/screenshots/03-onboarding-sources.png" width="200" alt="Picking starter packs">
  <img src="docs/screenshots/05-today.png" width="200" alt="Today, with an edition ready to send">
  <img src="docs/screenshots/cover.png" width="170" alt="A generated edition cover">
</p>

## What it does

- **Pick what you read.** Starter packs of well-known sites, paste any
  website (the app finds its feed), import OPML from another reader, or
  connect a Tiny Tiny RSS account.
- **Save things to read later.** Share a link from any app to "Read in
  newspaperss"; import your Pocket or Instapaper export. Add curated lists
  like Arts & Letters Daily, which picks a few links a day.
- **A paper that ends.** Each edition fills a reading-time budget, taking
  turns between sites with at most one article from each (you can allow
  more), so no site drowns out the others. The full article is fetched and cleaned for e-ink when a
  site only sends summaries; the app works out which sites need that.
- **A proper book.** Cover with the day's headlines, contents with reading
  times, images sized for e-ink, "Next" links, and an end page.
- **Delivered your way.** A notification with a Send button (Kindle app,
  Dropbox for a Kobo, email), a synced folder (KOReader), or Open on a
  Boox. Articles are only used up once the edition is delivered: saved to
  your folder, or confirmed by you after sending.
- **Didn't finish?** Bring articles back into tomorrow's edition.
- **Take notes.** Export a Markdown notes file per edition, with a citation
  and reflection prompts for each article, for Obsidian, Logseq or any notes app.
- **Calm by design.** No unread counts, no infinite timeline, no
  animations to smear on an e-ink screen.

<p>
  <img src="docs/screenshots/05b-edition-detail.png" width="200" alt="Edition contents with bring back">
  <img src="docs/screenshots/06-sources.png" width="200" alt="Sources">
  <img src="docs/screenshots/07-settings.png" width="200" alt="Settings">
</p>

**Status:** early development, not yet released. See
[docs/DESIGN.md](docs/DESIGN.md) for the design and
[docs/BACKLOG.md](docs/BACKLOG.md) for the plan.

## Building

JDK 21 and the Android SDK (API 37):

    ./gradlew build          # unit, Robolectric and Compose tests, lint
    ./gradlew installDebug

`:core` is plain Kotlin (feed parsing, the edition planner, extraction and
the EPUB writer) with fast JVM tests; `:app` is the Android app (Compose,
Room, WorkManager). Screenshots come from `ScreenshotTest`, which renders
each screen under Robolectric into `app/build/screenshots`.
