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
