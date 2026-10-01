# tt-rss as a backend: a design exploration

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
  per-feed model costs no extra requests.

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
    here with no special case. `publicationOf(sourceId, originId)` and `left_out_feeds` go away:
    a left-out feed is a paused one.
  - Sync still runs once per account and writes into the children.
- **B2. One source, plus a per-feed settings table.** Less to migrate, but every per-source
  feature (and every one added later) has to look in two places. Today's two code paths for
  "publication" and "left out" are that cost already, on a small scale.

B1 is the one worth building if B is built at all. The ripple points to get right:

- **Migration.** Make a child for each distinct `originId` and for each left-out feed (paused),
  move articles to it (`(sourceId, guid)` stays unique: tt-rss guids are `ttrss:<id>`), and
  carry over "last featured" so turns don't restart. A database version and a `MigrationTest`.
- **The account row stops owning articles.** Queries that count or list a source's articles,
  the Today counts, and the source page must not treat it as an empty feed.
- **Notes from tt-rss** (`serverNote`: couldn't mark read, sign in again) belong to the account,
  not the feed whose articles triggered them. `TtrssRepository.update` groups by `sourceId` today.
- **Removal.** Removing the account removes its children (a cascade on `parentId`).
  Signing in as another user clears them: feed ids belong to each tt-rss user, as today.
- **The mark-read queries** select `kind = 'TTRSS'`; they'd follow the parent.

### Lifecycle: tt-rss changes underneath

| On the server | Here |
|---|---|
| A feed is added | A new source appears at the next sync, in the paper, marked "New from tt-rss" once. Matches today: all unread is taken unless a category is chosen. |
| A feed has nothing unread | Nothing. `getFeeds` with `unread_only` doesn't list it, so absence doesn't mean gone. |
| A feed is unsubscribed | Only a full list tells (`getFeeds` with `unread_only=false`, or `getFeedTree`, once a day). Then its source goes, starred articles kept until delivered, as for a removed feed. |
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
