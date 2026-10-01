# tt-rss as a backend: a design exploration

> **Superseded in part.** This was the first pass. The clickable proposals, the persona
> critiques and the final recommendation are in [ttrss-backend/index.html](ttrss-backend/index.html)
> (open it in a browser). Two things here changed after critique: per-feed settings belong in
> a table keyed like the planner's feed key, not in child sources (section 2), and only
> per-feed article text is worth building now (section 4).

A proposal, not a plan. It answers the open backlog item "Decide what tt-rss is as a source" and
sketches the two proposals that depend on it ("tt-rss categories as sections" and "Add sources to
tt-rss"). Written over four cycles:

1. Today, and the three shapes tt-rss could take.
2. Each tt-rss feed as a source of its own.
3. Adding what you subscribe to here back to tt-rss.
4. A recommendation, a staged path, and what's left for you to decide.

## 1. Today, and the question

### What tt-rss is in the app now

- **One source.** A tt-rss account is one row in Sources, one `SourceEntity` of kind `TTRSS`.
- **Its feeds are already there underneath.** Each article keeps the tt-rss feed it came from
  (`originId`, `originTitle`). The planner already takes turns and applies the per-source cap
  per *publication* (`sourceId/originId`), so a feed in tt-rss gets a fair turn like a feed added here.
- **Per-feed controls are few.** "Feeds in your paper" leaves a feed out. Everything else is
  one setting for the whole account:
  - **Article text.** Only feeds added here have "Article text: Automatic / Feed's text / Full
    page", and only they learn it from three days of evidence. tt-rss articles decide each time.
  - **Section.** All tt-rss articles go in one section of the contents.
  - **Cap.** Comes from the edition setting; a feed can't be "two from Current Affairs" (in the backlog).
  - **Category.** One category, or all unread.
- **The account-level things work well and should stay account-level:** sign-in, read sync, mark
  read after delivery, Start fresh.
- **Sync already works per feed:** `getFeeds`, then up to five unread from each feed. So a
  per-feed model costs no extra requests at sync.

### What you're asking

- **Doorway:** should each tt-rss feed show up as a source of its own, with its own controls?
- **Write-back:** should something you subscribe to in the app be subscribed in tt-rss too?
- **Clutter:** does either of these make the app, or tt-rss, worse to live in?

### Three shapes

| | **A. One source** (today) | **B. Doorway** | **C. tt-rss is the backend** |
|---|---|---|---|
| In Sources | one row | one row per tt-rss feed (grouped) | one row per feed, local or tt-rss |
| Per-feed controls | leave out only | all of a feed's controls | all |
| Where subscriptions live | split: tt-rss's on the server, the app's on the phone | split, but both visible here | in tt-rss, for anyone with an account |
| Adding a feed here | phone only | phone only | goes to tt-rss |

B and C aren't rivals: C needs B. Moving a feed into tt-rss without B would lose its cap,
article text and section, because a tt-rss feed has nowhere to keep them.

The cycles below take B, then C, then weigh them against A.

## 2. Each tt-rss feed as a source (the doorway)

### What the reader gets

- A tt-rss feed has the same page as a feed added here: cap, Article text (learned or chosen),
  section, pause, its waiting and delivered articles.
- tt-rss categories can become sections, feed by feed, without a separate feature.
- The account keeps its own page for what's truly account-wide: sign-in, read sync, mark read
  after delivery, Start fresh, which category.

### Two ways to store it

- **B1. A child source per feed.** A new kind, `TTRSS_FEED`, with a `parentId` pointing at the
  account's source and the tt-rss feed id. Articles belong to the feed's source.
  - The planner, caps, Article text, sections and pause already work on sources, so they work
    here with no special case. `publicationOf(sourceId, originId)` goes away.
  - Left out isn't paused: a star from a left-out feed still goes in the paper, but a paused
    source's articles are skipped entirely, stars too. So "left out" becomes a flag on the feed's
    source with today's meaning, not a reuse of Pause.
  - Sync still runs once per account and writes into the children.
- **B2. One source, plus a per-feed settings table.** Less to migrate, but every per-source
  feature (and every one added later) has to look in two places. Today's two code paths for
  "publication" and "left out" are that cost already, on a small scale.

B1 was the first choice here; critique against the code reversed it: the list below is why. B2, a per-feed table keyed by account and feed id as the planner already keys turns, leaves sync, read sync and mark-read alone. The ripple points B1 would have to get right:

- **Migration.** Make a child for each distinct `originId` and for each left-out feed (paused),
  move articles to it (`(sourceId, guid)` stays unique: tt-rss guids are `ttrss:<id>`), and
  carry over "last featured" so turns don't restart. A database version and a `MigrationTest`.
- **The account row stops owning articles.** Queries that count or list a source's articles,
  the Today counts, and the source page must not treat it as an empty feed.
- **Everything keyed on the account's id** has to look at its children instead:
  - read sync (`syncReadState`: unreported read and unread, waiting unread, confirmed read);
  - `setReportedRead` after marking read or unread;
  - `chooseCategory`'s `expireWaiting`, and leaving a feed out (`expireWaitingFromFeed`);
  - `clearLeftOut` when another user signs in.
- **The mark-read queries** select `kind = 'TTRSS'` and read `markReadOnServer` from the
  article's own source; both would come from the parent.
- **Notes from tt-rss** (`serverNote`: couldn't mark read, sign in again) belong to the account,
  not the feed whose articles triggered them. `TtrssRepository.update` groups by `sourceId` today.
- **Removal.** Removing the account removes its children (a cascade on `parentId`).
  Signing in as another user clears them: feed ids belong to each tt-rss user, as today.

### Lifecycle: tt-rss changes underneath

| On the server | Here |
|---|---|
| A feed is added | A new source appears at the next sync, in the paper, marked "New from tt-rss" once. Matches today: all unread is taken unless a category is chosen. |
| A feed has nothing unread | Nothing. `getFeeds` with `unread_only` doesn't list it, so absence doesn't mean gone. |
| A feed is unsubscribed | Only a full list tells (`getFeeds` with `unread_only=false`, or `getFeedTree`: one extra request a day). Removing a source deletes its articles, stars too, so it shouldn't simply go: it stays as "Gone from tt-rss", fetching nothing, until its starred articles are delivered, then goes. |
| A feed is renamed | The title follows, unless renamed here. |
| A feed moves category | Its section follows, unless set here. |

### What "Remove" means on a tt-rss feed

The risky word. Two very different wishes:

- **"Not in my paper"**: pause it here. It stays in tt-rss, and in the list, so it can come back.
- **"I'm done with this site"**: unsubscribe in tt-rss, which also takes it out of every other
  app reading that account.

The first is today's "leave out" and should be the default, called that. The second is
section 3's question.

### Clutter in Sources

The reader with tt-rss often has 50 to 200 feeds. One row each would bury the starter-pack
reader's five feeds, and Sources would become a feed reader's sidebar: the thing the app
isn't. Ways to keep it calm:

- **Grouped and folded (recommended).** One tt-rss row, as now, saying "63 feeds, 4 left out".
  It opens the account page, whose "Feeds in your paper" list is the doorway: tap a feed for
  its own page. A feed with its own settings shows a small mark so it's findable.
- **Grouped by section.** tt-rss feeds mix into Sources under their sections. Honest, but long,
  and the account's own controls lose their home.
- **Flat.** Every feed in one list. Ruled out at this scale.

The first keeps the Sources screen as simple as today for everyone, and gives the veteran the
per-feed controls one tap deeper. No unread counts anywhere, as the principles ask.

### Feeds in both places

Someone with tt-rss may also add a site here that's in tt-rss too. Today one delivery uses up
the other copy by link, so nothing goes out twice, but each copy takes turns. With per-feed
sources, `getFeeds` gives each feed's URL, so the app can spot the pair and offer "This is
also in your tt-rss: keep one". Not urgent; links already keep it from repeating.

### Cost and risk of B

- **Cost:** one migration, an account/feed split in the source page, a lifecycle check once a
  day. No extra requests at sync.
- **Risk:** the migration (articles change source), and every feature that assumed a source
  syncs its own articles. Both are testable against a real Room database.
- **Gain:** per-feed cap, Article text, sections, and a home for any later per-source feature,
  for one persona. Nothing changes for readers without tt-rss.

## 3. Adding what you subscribe to here back to tt-rss

### What the API allows

From the [tt-rss API reference](https://tt-rss.org/docs/API-Reference.html):

- `subscribeToFeed` (API level 5, 2013): a feed URL and a category id, 0 for Uncategorized.
  It answers with a status: already subscribed, subscribed, or why not (couldn't download,
  no feed there, several feeds found).
- `unsubscribeFeed` (level 5): by feed id.
- `getFeedTree` and `getFeeds`: the full list, with each feed's URL and category.
- **Nothing to create or rename a category, rename a feed, or move one between categories.**
  The app can only put a feed into a category that already exists.

### Why you'd want it

- **One list of subscriptions.** A site found here (a starter pack, a pasted address) shows up
  in your laptop's tt-rss and your other tt-rss apps too.
- **Read state in one place.** With read sync on, it's already true for tt-rss articles; a feed
  added only here never reaches the server.
- **The server does the fetching.** tt-rss checks feeds all day, so nothing is missed while the
  phone sleeps (a busy feed can roll items off between the phone's 12-hourly syncs). Less data
  than it sounds: the phone's HTTP cache already answers unchanged feeds with a 304.

### Why you might not

- **tt-rss changes the articles.** It strips ids and embeds: footnotes broke and Substack Notes
  lost their text after tt-rss (DEVLOG, cycle 68). A feed that's fine on the phone can get worse
  by moving to tt-rss. The app works around both, but each new platform may need its own fix.
- **The server isn't the phone.** A site may block the server's address (bot checks are harder
  on data-centre IPs) while it lets the phone through, or the reverse.
- **It deepens a fragile dependency.** tt-rss.org shut down in November 2025 and the project
  lives on as a fork (research/competitors.md). Every feature that needs tt-rss makes the app
  more dependent on it.
- **It's the reader's other reader.** Which is the clutter question.

### Clutter: when it is, when it isn't

**Not clutter** when tt-rss *is* your subscription list. A blog you'd have added on the laptop
anyway belongs there, and adding it from the phone saves a trip. With "Mark as read in tt-rss"
on, delivered articles are marked read there, so the paper's reading tidies tt-rss rather than piling it up.

**Clutter** when the subscription exists *for the paper*: a news starter pack, a daily site
you want three of in the morning but never want to browse. In tt-rss it becomes an unread count
that grows every hour, the endless timeline the app is built to get away from. And the paper
only uses up a few a day: the rest expire here but stay unread there (in the backlog: "mark
read when they expire here", opt-in).

So the line isn't "tt-rss or not", it's **"is this a feed I read, or a feed the paper reads?"**
Only the reader knows, per feed. A mode that sends everything to tt-rss gets it wrong for one
kind; never sending anything gets it wrong for the other.

### Shapes of write-back

- **W1. Ask when adding (recommended first step).** Only for someone with a tt-rss account,
  when adding one feed:

  ```
  Add Aeon
  ( ) Just for the paper            on this phone
  (•) In your tt-rss too            category: Essays ▾
  ```

  Remember the last choice. Not shown for the reading list or curated lists (they aren't feeds
  tt-rss can read) or for an OPML file (tt-rss imports OPML itself; point there).
- **W2. "Move to tt-rss"** on a feed's own page, for one already here. Needs B1 so its cap,
  Article text and section move with it. Steps: subscribe; at the next sync, match the new
  tt-rss feed by URL and give it the old source's settings; then remove the old source. Stars
  move to the same link where one is waiting; delivered links already stop repeats.
- **W3. "tt-rss is my subscription list"**, a setting that sends every add there. Not until
  W1 shows most adds go that way.

### Failure paths

- **The server can't fetch it** (couldn't download, no feed): say so and offer to keep it here.
- **Already subscribed:** say so; with B it's already in the list, so open its page.
- **Offline, or tt-rss down:** adding to tt-rss needs the server now. Offer "Add here now"
  rather than queueing a subscription the reader will forget.
- **No category to pick:** Uncategorized, with a note that categories are made in tt-rss.

### Unsubscribing from here

Leave it out at first. Unsubscribing removes the feed from every app on the account and, in
tt-rss, its old articles (starred ones are kept in Archived, worth checking on the fork). A
reader who's done with a site can unsubscribe in tt-rss; the lifecycle check in section 2 then
removes it here. If it's added later: on the feed's own page only, below "Leave out", behind a
dialog that names what goes.

## 4. Recommendation (final, after critique)

Your gut is right for Sources: tt-rss stays one row. One thing can't wait: tt-rss feeds don't
learn their article text, and nothing the reader can set fixes it today.

1. **Now: article text learned per tt-rss feed.** No screen. A small per-feed table (feed id,
   address, category, learned text) and a migration.
2. **After living with it: tt-rss categories as sections, then balance by section** (one article
   from each section, then More sections take the next turns), if the paper still isn't varied
   enough.
3. **Only if missed: feed pages** under the account (Leave out, text override, cap, section).
4. **Adding feeds to tt-rss:** OPML export and tt-rss's own subscribe already do it. A second
   "Add to tt-rss instead" button is designed, if that turns out to be a chore. Never by
   default, never on import: a feed that exists for the paper becomes an unread count there.

Spreading every tt-rss feed through Sources was tried and dropped. The details, mockups and
the findings behind each step are in [ttrss-backend/index.html](ttrss-backend/index.html).
