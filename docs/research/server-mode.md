# Server or phone

The clickable mockups, critiques and log are in
[server-mode/index.html](server-mode/index.html) (open it in a browser). This is the short version.

## The question

There are two ways to use the app, and you'd never mix them:

1. **With an RSS server:** tt-rss today, and later FreshRSS or Miniflux through the Google Reader
   API. The server is the source of truth for subscriptions and read state.
2. **Without one:** the phone fetches feeds itself.

In both, the extras live on the phone: the reading list, curated lists (Arts & Letters Daily),
and later roundup feeds and newsletters. The one exception to "never mixed" is a site with no
usable feed.

The data model already fits: a Source is how articles arrive and a Publication is who wrote them.
What has to change is the screens. Sources today shows phone feeds, then tt-rss as one row with
its feeds inset beneath it, plus "Also on this phone" and "Also in your tt-rss" lines. That's the
mixed case, and it goes.

## Competitors

Readrops, Read You, Capy Reader, FeedMe, NetNewsWire, Reeder, Fluent Reader, and Feeder as a
local-only contrast. The page's table marks which claims are sourced and which come from memory.

- **One account at a time is the Android norm** (Read You, Readrops, Capy). You choose it up front
  or in Settings.
- **Nobody merges local and server feeds into one list.** NetNewsWire allows both at once, but as
  separate sections.
- **When the two overlap, the server wins,** and the app keeps its own settings for the feed
  (Fluent Reader).
- **Add goes to the current account.** Only NetNewsWire asks which account.
- **Moving feeds between accounts is OPML** nearly everywhere. Only NetNewsWire does it inside the
  app.
- **A local-only app has no account words at all** (Feeder).

tt-rss's API can subscribe a feed into an existing category and unsubscribe it. It can't create
categories or import OPML. The Google Reader API can create labels.

## Proposals

- **Onboarding.** O-A: the question is its own step, "Where do your sites come from?" (This phone,
  or My own RSS server). O-B: no question, just a server button on the sources step. O-B was
  dropped in round 2: the phone setup happened by default, and picking packs before signing in
  left you mixed.
- **Sources with a server.** P1: the server's categories as headings, all open. P2: one A–Z list
  with a category filter. P3: categories as rows, each opening a page of its feeds. All three put
  the account row first, then On this phone (the reading list, curated lists, phone feeds not yet
  moved), then the server's feeds.
- **Sources without a server:** today's list minus everything tt-rss, with no headings.
- **Adding a site with a server:** the phone finds the feed, then "Subscribe in your tt-rss" with a
  category. Fallbacks: save the page to the reading list, add it as a curated list, or (an open
  question) fetch it from the phone when tt-rss can't.
- **Switching.** One-tap move of phone feeds to the server, with settings carried by address and
  stars kept. Getting a server offers the move straight after sign-in. Leaving a server turns its
  feeds into phone feeds, and starred articles go to the reading list.
- **Settings:** a row "Where your feeds come from", after delivery. Its page holds the choice and
  the account's settings.

Each proposal had two rounds of critique: personas first, then the code and the edges. The
critics were the Kindle reader in their sixties, the Pocket refugee, a tt-rss owner with 58 feeds,
a FreshRSS user, someone with no server and 8 feeds, a TalkBack user, and e-ink (Boox, Kindle).

## Recommendation

- An explicit choice: O-A in onboarding, and Settings › Where your feeds come from.
- With a server, Sources is **P1**: the account row, On this phone, then the server's categories
  open, A to Z, with Uncategorized, Not in your paper and Left out last. Nothing folds, so e-ink
  doesn't redraw and TalkBack jumps by heading.
- Without a server, Sources is today's list minus tt-rss.
- Add subscribes on the server, with fallbacks. Phone feeds move in one tap.
- Not now: P2 (search does its job better, later), P3 (only for accounts with 150+ feeds).

### Decided for the owner

- **The choice is explicit:** asked in onboarding and changeable in Settings, never guessed.
- **An existing tt-rss user with phone feeds lands in the server setup,** with a one-tap "Move your
  N phone feeds to tt-rss". Nothing moves without that tap. The upgrade rule: if a tt-rss account
  exists, the server setup; otherwise the phone.
- **The reading list and curated lists stay on the phone in both setups.**

## Build order for tonight

Each step ships on its own.

**(a) The setup choice and the onboarding fork**
- **Screens:** a new onboarding step, "Where do your sites come from?". The server path is sign-in,
  "Found 58 feeds", then extras. Settings gets a page, "Where your feeds come from", which takes
  over the account's settings from the tt-rss source page. Leaving a server is today's Remove,
  worded honestly.
- **Data:** a DataStore setting `feedsFrom` (`PHONE`/`SERVER`), set on upgrade from whether a
  tt-rss source exists. `Step.FEEDS_FROM`.
- **Removed:** "Connect tt-rss" in onboarding; "Add tt-rss account" in the Sources ⋮ menu.
- **Risks:** `SERVER` with no account; "Step N of M" changing with the path; upgrade tests.

**(b) Sources for a server (P1)**
- **Screens:** the account row; On this phone; categories as headings; Not in your paper and Left
  out on the existing left-out screen. A server feed's page says "In your tt-rss · News".
- **Data:** nothing new (`publications.category`, `listed`, `leftOut`).
- **Removed:** the inset list (`InsetFeed`, `InsetRow`, `InsetDivider`), the fold button, the
  `feedsShown` setting and its `sources_feeds_shown` key, the duplicate lines (`alsoOnPhone`,
  `alsoInTtrss`), and "tt-rss last" sorting on Sources. Keep `sameFeed` for (d).
- **Risks:** rewriting DESIGN.md's Sources section; blank categories; TalkBack headings. Needs (a).

**(c) Adding a site to the server, with fallbacks**
- **Screens:** the Add dialog's server variant, a category picker, "Asking tt-rss…", and a
  snackbar with Undo. Fallbacks: the reading list and curated lists. With a server, the starter
  packs and OPML items are hidden.
- **Data:** `subscribeToFeed` and `unsubscribeFeed` in `:core`. A `lastCategoryId` setting. A feed
  list refresh after subscribing, which also gives Undo the feed's id.
- **Risks:** tt-rss fetches the feed before it answers, so this runs as work. The status codes
  need checking against a real server (they're from memory). Needs (a) and (b).

**(d) Moving phone feeds to the server**
- **Screens:** a banner under On this phone, the move sheet, stepped progress, and a partial
  result ("Move the other 3").
- **Data:** a Worker. Per feed: subscribe, unless `sameFeed` already matches a server feed; copy
  the publication row by address; pause and hide the phone source, and delete it once nothing
  starred or unsent is left. Pending moves are kept in DataStore.
- **Risks:** cascade deletes of starred articles; partial failure; duplicates for a day.

**Later:** (e) leaving a server brings its feeds along; the Google Reader API.

## Open questions for the owner

- A site tt-rss can't fetch: offer "Fetch it from this phone instead", or only the reading list
  and curated lists?
- Unsubscribe in tt-rss from a feed's page: allowed?
- A paused tt-rss account, or one with every feed left out, at upgrade: the server setup anyway?
- Starter packs with a server: hidden (proposed), or subscribed on the server?
- OPML with a server: point to tt-rss's own import (proposed), or subscribe each feed from here?
- FreshRSS or Miniflux first for the Google Reader API?
