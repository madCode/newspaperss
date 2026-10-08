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
| **Publication** | Who wrote a source's articles, as against how they arrive: a feed added here is one, each feed inside a tt-rss account is one, and the reading list is one (your picks). Takes turns in the paper and holds the settings about the writing: article text, cap, left out, paid posts. |
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
- **Posts on a shared page.** An item linking to its spot on a page
  (`05.html#a1`, one of a day's posts on a blog), or whose address
  redirects up its own path to a page holding all the posts (a microblog's
  `/d/1291` to `/d`), is just its feed text, when the page has the item's
  text: the page holds the other posts too. Where a feed links every item
  to that one page and gives each its own address only as its id, the id
  is the item's address.
- **Paid posts.** A post for paying subscribers goes in as its free part,
  with a note saying so. It's known by what the platform puts on the page
  in place of the rest: Ghost's upgrade box, Substack's paywall, the sales
  pitch of MemberPress (a WordPress plugin) or of Passport (Stratechery's),
  or a sign-in form inside the article beside an offer to subscribe or buy
  (a magazine's own paywall, like New Left Review's). Those two pitches are
  removed before Readability runs, and when Readability then misses the
  text just above the pitch, that text is the article. Not
  schema.org's `isAccessibleForFree`: metered news sites set it to false
  and serve the whole story. A feed text ending in "Read more" back to the
  post (Substack's paid openings, excerpt feeds) counts as an excerpt
  however long, so the page is fetched; that link and the pitch over the
  paywall are removed. One with next to nothing free (under 50 words: a
  title and a picture) can be left out instead, per publication, with
  **Skip paid posts with nothing free** on its page: a feed's or a curated
  list's page, or each tt-rss feed's page. That switch shows once the
  publication has had one, is off until turned on, and says how many it
  has skipped. Only a first find is skipped: it counts as not picked
  ("Skipped: a paid post") and isn't fetched again. Starring it or marking it unread asks for it, so it goes
  in. An edition whose every new article was skipped is nothing new, not
  a failure.
- **Embeds.** A video can't play in a book: a video's poster frame stands
  in for it, and a YouTube or Vimeo player becomes a link to the video
  (with YouTube's thumbnail). tt-rss passes no players on, so a caption
  left without its video becomes a plain paragraph.
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
  feed's thumbnail. An image's title text (xkcd's hover joke) becomes a
  caption under it, unless it only repeats the image's description or file
  name, or the article already shows it.
- **Which text to use, per publication.** A publication is who wrote the
  articles: a feed added here, or one feed inside a tt-rss account, each
  learning on its own. Each article whose page was read is evidence:
  - a page with twice the feed's words, or with pictures the feed's copy
    lacks, means the feed is a teaser;
  - a feed of 300+ words whose page has no more, or a site that blocks
    fetching, means the feed is enough.
  Three days pointing the same way set the publication to that; the reader
  can override it on its page, a tt-rss feed's included ("Article text:
  Automatic / Feed's text / Full page").
  - A short item, or one ending in "Read more", always has its page read. A long one is taken from the feed,
    so it only counts once checked: each edition reads the pages of up to 5
    long items, one per publication, from publications still being worked
    out, or settled on the feed's text and not checked for 14 days (daily
    once a check finds a teaser, so it can switch in three days).
  - A check that finds a teaser uses the page in that edition already;
    otherwise the article is as it would have been without one. A page with
    pictures counts only if it has all of the feed's text.
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
  - "In this edition" contents with each article's reading time, in one
    list: no section headings, since a 30-minute paper is often 8 articles. Kindle opens the book here, like a paper's front page;
  - each article: source, headline, "By … · date · N min read",
    the body, an end mark, "Read the original at site", and, after a read of
    5 minutes or more, a "Next" link with the next one's source and time (a
    pause after a long piece; after a short one, turning the page is enough);
  - footnotes that work in the book, even from tt-rss, which strips the
    ids they point at (they're paired up again by number). A marker points
    at the whole footnote, so a Kindle's popup shows its text, and markers
    side by side are split with a comma ("3, 4"). Notes a page keeps in a
    `<footer>` or `<aside>` come along when the text links to them. Substack
    Notes quoted where the post embeds them (from Substack's own feed: tt-rss
    strips the Note's text), and @-mentions keep the name the page's script
    would have filled in;
  - "That's all for today", a chapter in the contents, with the day's
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
- **With the podcast, they start earlier still** (debug builds): by the
  time this phone takes to make your paper's podcast, × 1.25 for room to
  spare, in whole 5 minutes, up to 3 hours. Once real podcasts have timed
  the phone (each finished one moves the pace halfway to its own), the margin is × 1.1.
  The start shows under Ready by on Schedule ("Starts at 4:50 AM, for the
  podcast", and to leave the phone charging), in the Schedule summary, in
  Today's next-edition line and in the speed check's verdict.
- **Feeds sync right before each build,** and in the background every 12
  hours when the battery isn't low.
- **No edition is never silent.** A timed run with nothing new sends a quiet
  "No new edition"; if every source failed, it's retried twice, then reported
  as a failure pointing to Sources. A build waiting for a connection says so.
- **Fetching is polite and cheap.** An HTTP cache revalidates every feed
  (If-None-Match / If-Modified-Since), so an unchanged feed costs a small
  "not modified" reply. Requests use a browser-like mobile user agent.
- **A feed over 10 MB is read up to 10 MB.** Some static sites put every
  post they ever wrote in one feed, newest first; its start is plenty. The
  last item before the cut may be only part of one, so it's left out. The
  feed says it's too large instead when its start is its oldest posts (new
  ones would never come), when nothing whole fits, or when it's a JSON Feed,
  which can't be read in part. A page that big is refused. Reading stops at
  the limit without storing the feed in the HTTP cache, which it would
  overfill.

## 6. Delivery

| Device | How it gets there | Unattended? |
|---|---|---|
| Kindle | Email it to the Kindle's own address: Send opens your mail app with everything filled in, and you tap Send | One tap, then Send in the mail app |
| Kindle, without email | Share to the Kindle app, one tap from the "ready" notification. It goes to the cloud library, or straight to the Kindle with "Add to your library" turned off in its form (then it doesn't sync) | One tap |
| Kobo | Share to Dropbox, into the folder the Kobo syncs (`Apps/Rakuten Kobo`) | One tap |
| PocketBook | Share to an email app, to your `@pbsync.com` address | One tap |
| KOReader | Save to a folder that syncs to the device (Syncthing) | Yes |
| Boox | Open it in the device's reader, from the app or the notification | Yes |
| Anything else | The share sheet | One tap |

**Email to a Kindle.** Each Kindle has its own Send-to-Kindle address
(…@kindle.com), and a book emailed there is delivered to that device by
itself. The app doesn't send mail: Send opens the mail app chosen in
setup, straight to a compose screen with the Kindle's address, the
edition's title as the subject and the EPUB attached. The body lists the
edition's articles, so your Sent folder shows what each one held (Amazon
ignores it). "Ask each time", or
a chosen app that's been uninstalled, opens the share sheet with the same
email instead. Android can't choose the "From" account for another app,
so the address you send from has to be on Amazon's approved list
(Personal Document Settings), and setup says so. The notification's Send uses the
settings as they are when it's tapped. The app's mail apps are
the ones that both write mail and take an EPUB.

Folders use Android's folder picker. Google Drive and Dropbox don't offer
whole folders to other apps that way, so cloud delivery goes through a
share.

**When is an edition delivered?** Saving it to your folder; choosing an app
in the share sheet (Android reports the choice back, from the notification
too); opening the mail app to email it to a Kindle; or **Read** on a Boox. **Mark as sent** covers any other route, and
**Send again** (in a sent edition's ⋮ menu) is there if a send didn't arrive. An edition still "ready"
when the next one is built was never sent: it's marked not sent and its
articles go back, keeping their stars, before the new one is planned.

**Send to Kindle is slow.** A book can take a few minutes to reach a
Kindle, so for half an hour after a send the edition (on Today and its own
page) says so: "Sent to Kindle. It can take a few minutes to show up in
your library." after the Kindle app, or "Emailed to your Kindle. It can
take a few minutes to arrive; it shows up by itself." after an email. Both
add "Not there after 20 minutes? Use ⋮ to mark it as not sent." (TalkBack
reads ⋮ as "More options"). Any
other delivery, or marking it as not sent, takes the note away. It's only
remembered while the app is running.

**A send that didn't arrive can be undone.** A failed Send to Kindle still
counts as sent, because Android only reports the app you chose.
**Didn't arrive? Mark as not sent**, in a sent edition's ⋮ menu (while its
file is still here), asks first, then makes it ready to send again:
- its articles go back into it, with the stars they went in with;
- its links are no longer remembered as delivered;
- its tt-rss articles are marked unread on the server again.

Send it again, or leave it and the next edition takes its articles, like any
unsent one. Other copies of its links that delivery used up stay used. An
article brought back into a newer edition since stays in that one.

**The app you send to can read the book after its screen closes.** A share
only lets the receiving screen read the file, and Send to Kindle uploads
after its form closes. So the Kindle app, the mail app that emails it, and
whichever app you pick, are allowed to read that edition's file until the
phone restarts.

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
- **Two setups, never mixed.** Your sites come from this phone, or from
  your own RSS server (tt-rss). The reading list and curated lists stay on
  the phone in both. It's chosen in onboarding and changed in Settings.
- **tt-rss:** each sync takes up to five unread articles from every feed, so a
  feed that posts monthly isn't crowded out by busy ones. Articles are marked
  read on the server once delivered (and unread again if the edition is
  marked as not sent). One category can be taken instead of all unread,
  chosen in Settings › Where your feeds live; a new account starts
  with all. tt-rss's own stars
  aren't synced: there a star usually means "keep this", not "for tomorrow".
  - **Sync read status with tt-rss** (on by default) keeps read and unread
    the same in both, the newest change winning. tt-rss doesn't say when a
    flag changed, so each sync works it out from what changed since the
    last one:
    - Changes made here go out first and are read back from tt-rss; only
      what it confirms counts as done. A change of mind before the sync
      never reaches it.
    - Read in tt-rss while waiting here: read here too, so it stays out of
      the paper. Not if it's starred here (a star is deliberate; a read in
      tt-rss may just be opening it), nor if it's in an unsent edition.
    - Marked unread in tt-rss after it went out: waiting here again, once
      it's among that feed's newest five unread.
    - Changed on both sides between two syncs: the change made here wins.
    - Off, nothing flows either way: tt-rss and the app keep their own.
  - **The feed list.** Once a day a sync asks tt-rss for every feed the
    account takes articles from (in its category, if it has one), read ones
    too, with each feed's name, address and category. Until the first list,
    the feeds seen in the last month stand in. A failed list waits for the
    next sync; the sync itself has still succeeded. Changing the category
    lists the feeds again.
  - **Each feed is a publication** with its own page: leave it out or bring
    it back, its article text and cap, and its own recent
    articles. A feed left out isn't fetched, and its waiting articles go
    too, except starred ones. It stays in tt-rss and on the Left out list,
    so it can come back. **Feeds in your paper** on the account's page
    is the same choice as a checklist. Signing in as another user clears
    every feed's settings: feed ids belong to each tt-rss user. The same
    user signing in again keeps them.
  - **Start fresh** ("Back after a break?" in Settings › Where your feeds
    live), after a
    confirmation, marks everything that reached tt-rss more than two weeks
    ago read there (in the source's category, if it has one), starred ones
    included. Articles already waiting in the app aren't touched by it;
    with read sync on, any it marked read leave at the next sync. It
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
   folder. Kindle sets up email to the Kindle: its address (Next waits for
   one that looks like an email address, and an address not at kindle.com
   or kindle.cn gets a warning), the mail app to send with, and a reminder
   to approve the sending address on Amazon. **Use the Kindle app
   instead** shares to the Kindle app.
3. **Where do your feeds live now?** Three cards; tapping one answers and
   moves on, with no Next:
   - **I'll pick some sites** ("Newspapers, magazines, blogs, newsletters.
     Most people start here."): this phone's sources step.
   - **On my own RSS server** ("Tiny Tiny RSS. Your feeds stay there; the
     paper is made from them."): sign-in.
   - **In another reader app** ("Feedly, Inoreader and others: bring your
     list as a file."): this phone too, starting with the import step.

   Each card is a full-width bordered button with an arrow, one TalkBack
   button read as its title and description, with no selected state to
   show in colour. Below: "You can change this later in Settings." With
   sites already added on this phone, the server card asks first: "Remove
   the 3 sites you added?", **Remove and use my server** or **Keep them**.
   The answer is saved as the tap moves on. "Step N of M"
   follows the path: 4 steps for picking sites, 5 for a server or another
   reader app.
4. **From another reader app, bring your list:** "In Feedly or Inoreader,
   look for Export or OPML in settings. Save the file, then choose it
   here." **Choose the file** opens the system file picker; the step then
   says "Added 42 sites", what was there already, or a plain error with
   **Try again**. **Skip** (Next once sites are in) goes on to the sources
   step below, which the import counts towards. The result survives the app
   being closed in the picker.
5. **With this phone, pick your sources:** starter packs of well-known
   public feeds; paste any website (the app finds its feed); import an OPML
   file (its folders are ignored: the paper has no sections); or import
   saved links from Pocket or Instapaper, which alone are enough to start.
6. **With a server, sign in**, then see what's there: the address,
   username and password on the step itself, one message per cause when it
   fails (API off, wrong password, can't reach it), with what was typed
   kept, and **Use this phone instead**. Signed in, the same step says
   "Found 58 feeds in 7 categories"; the paper takes from all of them, and
   one category can be chosen later in Settings. Then **Also on this
   phone:** the reading list (with Pocket and Instapaper import) and curated
   lists. No starter packs. Going back to the question and choosing this
   phone signs out, so no half-made account is left; going back from this
   phone's sources and choosing the server removes the sites added there,
   and the question asks first. Back waits while signing in, and an import
   still reading stops before anything is removed.
7. **How much, and when?** A 10–90 minute slider, "A new edition every
   day", and a "Ready by" time.
8. **The first edition** builds right away, with its progress on Today.

Tap cards are for a single choice that moves you forward with nothing else
to set on the page; multiple choices are checkboxes; a choice with a usual
default on a page with more to set stays a radio group (Where do you read?,
whose Kindle and KOReader answers ask for more on the same page).

## 9. Screens

- **Today** (home): when the next edition is due, how many starred articles
  will go in the next one (only when some will), and the latest edition's
  card. Tapping anywhere on the card opens the edition.
  - On a debug build from CI, a line under the masthead says when a newer
    test build is out, with a link to download it. The app asks GitHub when
    it comes to the front, at most every three hours; a local or release
    build never asks.
  - The card previews what's inside, as the book's contents page opens: the
    first three headlines in order, each with its source (★ if you starred
    it) and reading time, then "and N more". A failed edition shows none:
    its articles went back to wait.
  - Every card has at most one button, the next step:
    - ready: **Send**, or **Read** on a Boox, where reading it the first
      time is the delivery; under it, "Sent it another way? **Mark as sent**";
    - sent: **Read** on a Boox. Everyone else reads it on their e-reader,
      so the card has no button and its last line is a link: "and 5 more ›".
  - The rest is in a ⋮ menu at the card's top right ("More options for" the
    edition's title, for TalkBack), shown only when it has something:
    **Open on this phone** (not for Kindle or Kobo, who get the book by
    sending it), **Send** on a Boox, **Send another way** (the share sheet,
    when Send emails a Kindle), and once sent **Send again**, **Didn't
    arrive? Mark as not sent** and **Open the Kindle app**.

  **Make an edition now** is the main button only before the first edition;
  after that it's a quiet **Make another edition**, since today's paper is
  done. A failed build or edition offers **Try again** after saying what
  went wrong. Earlier editions are listed below.
- **Edition:** the same button as Today's card, and its contents.
  Under them, **Listen · about 9 min** reads the edition aloud (see
  Listening, below). Its ⋮
  menu has the card's menu, above **Delete edition**. Tap an article to preview it as the e-reader
  will show it (read straight from the EPUB, with nothing fetched from the
  network), on top of Android's own font size. **Aa** in the top bar sets
  its text size (Small to Largest), the same size as Settings › Article
  text size, so changing either changes both. At Larger and up (or as large
  through Android's font size), paragraphs are left-aligned rather than
  justified, so a narrow screen doesn't open wide gaps between words; a
  change applies to the page on screen without losing the reader's place.
  Every article ends in a quiet "Next: *title* · *source* · *minutes* →" line like the
  book's, short ones too (the book has it only after long ones, and without the arrow),
  and the last in "That's all for today." The whole line is the link. It opens the next
  article in place; the title and **Share** follow the page on screen.
  **Share** in the top bar sends the article's original link to Android's share sheet;
  it's hidden when an article has no web link. Selected text shared from the
  page carries that link too, on its own line under the quote, with the
  article's title as the subject. Large pictures that stand
  alone fill the width (small ones keep their size), and the page can be
  pinched to zoom into a comic. **Notes** exports a Markdown file for a
  notes app: front matter
  (date, edition, sources, a tag) for Obsidian, the closing page's question and
  a few reflection prompts at the top, then per article its source, author, date, link, a citation and room for notes.
  **Delete edition** is in the ⋮ menu, and asks by name, saying what happens
  to the articles (an unsent edition's go back for the next one). An article that went in because it was starred says
  "You starred it". On delivered editions each article has a trailing **☆** to
  bring it back ("Didn't get to one? Tap ☆ to bring it back.").
- **Listening:** an edition read aloud by the phone's own text-to-speech
  voice, made for a walk with the phone in a pocket and the odd glance at
  it.
  - What's read: each article's source, title and author, then its text
    and the closing page, in book order. A picture is one line from its
    caption or alt text ("Image: Tait's table of knots"), or "An image."
    The date, reading time, link to the original and "Next" line aren't
    read; nor are footnote markers.
  - Between one article and the next, in either voice, a pause of about
    4 seconds: a moment's quiet, a soft breath of sound with a page
    turning, then the next article, so the listener can change contexts.
    Skipping with ⏭ has no pause. During it, ↶ goes back to the end of
    the article just heard and ↷ starts the next at once; pausing stops
    it, and Play starts the next article.
  - It reads a sentence at a time. ↶ goes back to the start of the
    sentence, or the one before if it has only just started; ↷ skips one;
    ⏮ ⏭ move by article (⏮ restarts the article after its first few
    sentences). Speed steps through 1×, 1.2×, 1.5× and 0.8×, and is kept:
    it's the same speed as Settings › Listening.
  - The playing screen is the article's text, at Settings › Article
    text size, the sentence being read tinted, following the voice.
    Scrolling away stops it following, and "Back to where it's reading"
    returns. Tapping a sentence reads from there. The bar under it is the
    edition, one piece per article.
  - It keeps playing with the screen off, with Android's media controls on
    the lock screen, headphones and in the car: there the skip buttons move
    by article and the seek buttons by sentence. A call or another app's
    audio pauses it; after a short interruption it carries on. Unplugging
    headphones pauses it.
  - It stops at "That's all for today", not running on into anything.
  - Where it stopped is kept for the ten most recent editions: the
    edition's button says **Resume · 3 min left**. Listening to a newer
    edition with an older one left part way asks "Finish Wednesday Morning
    Edition first?"
  - An article in a language the phone has no voice for is read in the
    phone's own, with a note offering Android's download of that voice.
  - Listening doesn't count as delivering the edition.
- **Sources:** one plain list, ruled between rows. Nothing on it folds or
  moves, so e-ink doesn't redraw it, and TalkBack can jump by heading.
  Tapping a row opens its page; a row has nothing else to tap.
  - **Without a server:** the reading list, then each source in your order
    with its health ("Full articles", "Summaries only", "Site blocks
    fetching", "Failing for N days"). No headings, and nothing about servers.
  - **With a server**, one list, not split into tt-rss and this phone:
    nearly everything on it is tt-rss's, and the account's settings are in
    Settings. Top to bottom:
    - **Only when something needs doing**, a banner above the list (so one
      that appears later isn't scrolled out of sight; at most a third of
      the screen, scrolling within it, with TalkBack hearing it as a
      heading and when it appears): **Sign in to your
      tt-rss** with no working account; otherwise what's wrong in words
      ("⚠ Couldn't reach tt-rss. Since 6:10 AM. Showing what it last
      listed.", or that it's paused) with **Settings**, which opens
      Settings › Where your feeds live. Nothing when all is well.
    - **The reading list.**
    - **Still on this phone**, a heading, only while there are phone feeds:
      a banner, "8 feeds are fetched by this phone, not your tt-rss", with
      **Move them to tt-rss** (only with a working account), then those
      feeds. Above the server's feeds, since it asks for something; it goes
      once they're moved.
    - **Moving them:** a sheet lists every phone feed, ticked. One tt-rss
      already has at the same address says "Already in your tt-rss: just
      removed here". One category for the new ones (the paper's, else the
      last used; a different one is warned about), and "Their settings here
      come along". **Move N** starts it in the background; the banner then
      shows stepped lines, "✓ Aeon · subscribed", then "Moving 5 of 8 ·
      Quanta Magazine", changing once per feed. At the end a snackbar says
      "Moved 8 feeds to your tt-rss". If some couldn't move, the banner says
      "Moved 5 of 8", why each other one didn't, and **Move the other 3**,
      which opens the sheet with just those ticked. If tt-rss stops
      answering, the rest stop with it rather than each waiting in turn.
    - **What a move does:** each feed is subscribed in tt-rss (or found
      there), then its settings are carried onto its tt-rss feed: article
      text, the cap, Skip paid posts, left out, and what the text check
      learned. A feed paused on the phone is left out in tt-rss. The phone
      feed stops fetching and leaves Sources at once, but it's only deleted
      when nothing starred, waiting or in an unsent edition is left in it:
      until then editions still take from it, so stars are kept, and for
      two weeks after an edition with its articles is delivered, so Mark as
      not sent still brings them back. A link already delivered, or marked
      read on the phone, isn't delivered again when tt-rss brings it.
      Signing in as another tt-rss user makes feeds still kept phone feeds
      again, to move into that account if wanted.
    - **The server's feeds:** each category a heading with its count and
      an arrow ("News, 9 feeds, Expanded" to TalkBack), pinned at the top
      while its feeds scroll by; its feeds A to Z under it. Tapping the
      heading folds the category to just that line, and folded categories
      stay folded, kept by name in the app's settings. Feeds with
      no category go under **Uncategorized**, last. A line under a feed only
      when it says something: "tt-rss can't fetch it" for a feed tt-rss's own
      fetcher fails on, "Not fetched by tt-rss yet" for a feed
      just added, or its own settings ("Feed's text", "Full page", "At most
      2", "Skips paid posts"). Each opens its own page, which says "In your
      tt-rss · category News", and for a feed tt-rss can't fetch, that the
      feed may have moved or closed, with tt-rss's own words ("HTTP Code:
      404").
    - **Feeds tt-rss can't fetch** are also named in a banner above the list
      ("⚠ tt-rss can't fetch 2 of your feeds: Aeon and Wired. Check their
      addresses in tt-rss."), as their rows can be under folded categories.
      tt-rss fetches its feeds itself, so without this a dead feed just goes
      quiet. The error comes from tt-rss's feed list (once a day) and is
      updated at each sync for feeds with new articles, so a fixed feed
      stops showing as soon as it has posts. Right after Articles from changes,
      until the next check lists the new category, it says "Your feeds in
      Science show here after the next check" instead.
    - **Curated lists**, a heading, when there are any. They and the
      reading list stay on the phone in both setups, and are never moved.
    - **Not in your paper**, a heading, when either of these is there:
      **Outside News** ("43 feeds") when the paper takes from one category,
      whose page lists the other categories with their feeds and points to
      Articles from in Settings; and **Left out** ("2 feeds"), whose page
      lists the left-out feeds, each opening its page to bring it back.
    - Every group (the reading list, Still on this phone, each category,
      Curated lists, Not in your paper) has the same heading, semibold
      serif in black like Settings' own (rust is for what you tap), and
      ends with a rule across the page. Shorter rules, starting at the
      text, divide the rows within one.
  - A source's page shows its recent articles, pause, its cap and, for a
    feed, the article-text setting; **Remove source** is in its ⋮ menu. A
    tt-rss account has no page of its own: its settings, and leaving it,
    are in Settings, and each of its feeds has a page.
  On a source's or feed's page, each article row is a status mark (`●` waiting, `✓` delivered, `○` read or not
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
  Read, delivered and too-old rows have a dimmed title, in a lighter weight
  as well as a muted colour so it shows on e-ink, so what's still to come
  stands out. A starred one isn't dimmed: it's going out again.
  The details line ends with where the article stands: "Waiting, N days
  left" (it expires 7 days after it was found; curated lists and saved
  links just say "Waiting"), "In your next edition" (starred; "Starred,
  source paused" while paused), "In Thursday's edition", "Sent Wednesday"
  (when its link last went out), "Read", "Not picked" (it waited a
  week, or the list moved on), or "Skipped: a paid post".
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
  - **Without a server**, **Add a source** finds a site's feed and adds it
    to the phone, offers the curated lists, or saves a page with no feed to
    the reading list. The ⋮ menu imports or exports OPML.
  - **The finder** looks for a feed in turn: the address itself, the feeds
    the page advertises, feed links on the page, the usual feed addresses,
    and, from a site's front page, the feeds advertised by the section most
    of its links go into (a HubSpot magazine's `/news`), if that section
    stays on the site and isn't a year, a language, a shop or a podcast.
  - **With a server**, **Add a site** puts the site into tt-rss, so other
    reader apps get it too:
    - The phone finds the feed first, with the same finder (and "Which
      part of this site?").
    - Already in tt-rss (matched by address): "Already in your tt-rss",
      "Quanta Magazine is in Science.", and nothing else.
    - Otherwise **Subscribe in your tt-rss**, with a **Category** picker:
      the category used last, else the one the paper takes articles from,
      else Uncategorized. "To add a category, make it in tt-rss first":
      tt-rss's API can't make one.
    - **Asking tt-rss to subscribe…**, in plain text with no spinner. It
      can take half a minute, as tt-rss downloads the feed first. Closing
      the dialog doesn't stop it.
    - Then a snackbar, "Added to your tt-rss, in Science.", with **Undo**,
      which unsubscribes it. The feed's row appears at once, waiting for
      tt-rss's first fetch.
    - tt-rss refusing (it couldn't download or read the feed, an old
      server, a read-only account) says why in plain words, and offers
      **Save this page to your reading list** when the address typed was an
      article.
    - A site with no feed: "No feed on this site", with **Save this page to
      your reading list**, or **Add Arts & Letters Daily** when the site is
      one of the curated lists, which stay on the phone.
    - If the dialog was closed, the answer comes as a snackbar when Sources
      is open.
    - The ⋮ menu has **Where your feeds live** instead of OPML: tt-rss
      imports and exports OPML itself. No starter packs are offered.
  Getting or leaving a server is in Settings.
- **Reading list:** links you shared into the app, each with its title,
  site and reading time (looked up in the background). They go first in
  the next edition. Import and export as a Markdown
  checklist (compatible with the library); Pocket and Instapaper exports
  import too. **✕** removes a link at once, with **Undo** in a snackbar, since
  removing is routine and a confirm would only be tapped through.
- **Settings** is a summary: one row per page, each with a line saying
  how it's set now ("About 30 minutes · 1 per source · take turns",
  "Ready by 6:30 AM, weekdays", "Kindle · emailed to …", "Off"), then the
  app's version. Tapping a row opens its page, with a back arrow. Anything
  that needs fixing (no days picked, notifications off, a folder it can't
  reach, a Kindle address missing) also shows on the row in red, so it
  isn't a tap away. The pages:
  - **Your edition:** size, how many from each source, order. At large
    text sizes, the − and + for the per-source count go below its words.
  - **Schedule:** on or off, the time and the days. With notifications off, a warning for every delivery:
    a shared or emailed edition's Send is in its notification, and a
    folder save that fails is only reported there.
  - **Article text size** (its row names the size): how big articles are
    in the app's preview, on top of Android's font size. A sample line
    drawn at that size, then Small, Default, Large, Larger and Largest
    (85% to 175%) as radio rows; the preview's **Aa** sets the same size.
    The rest of the app follows Android's font size, and the EPUB the
    e-reader's own settings.
  - **Listening** (its row says "This phone's voice · 1×", or "No voice on
    this phone", which isn't shown as a problem: many e-readers have none):
    the phone's text-to-speech engine by name with **Hear it**, a sample line
    at the chosen speed (it pauses an edition that's playing); the speed as
    radio rows, 0.8× to 1.5×, the same one the player's speed button sets;
    and **Change the phone's voice**, which opens Android's text-to-speech
    settings. A voice, language or engine changed there is heard from the next start or jump.
    A speed asked for by a car or watch becomes the nearest of these.
    Without an engine the page says so and offers Speech Services by Google.
    Debug builds also offer **Make a podcast in a natural voice**, Kokoro
    (see the backlog). Picking it downloads
    384 MB, asking first when not on Wi-Fi ("Wait for Wi-Fi" starts it on
    the next Wi-Fi by itself); then a 20-second check times this phone and
    says what your paper would take to make: up to about an hour, "This
    phone can do it"; up to 2½ hours, "slow, but it can"; beyond, "Too
    slow", offering only Remove and the paper size that would fit. A 32-bit
    phone sees the option greyed, with why. In use, it lists Heart,
    Michael, Emma and George, each with a clip shipped in the app,
    **Remove Kokoro**, and **Podcast log**: what the making did, run by run,
    newest first, with Share to send it to whoever's fixing it. While it's in use, each scheduled edition's podcast
    is made after the book, only while the phone charges: unplugged, it
    stops and keeps what's made. It's made in the foreground, with a
    quiet "Making the podcast" notification (if notifications are on):
    Android lets long work run only so. From Android 12 that's allowed
    overnight only if the app's battery use is Unrestricted, and from
    Android 15 for 6 hours a day; otherwise it's made a few minutes at a
    time, slowly.
    English articles only; the rest are left
    to the phone's voice. A podcast is deleted with its edition's book, and
    all of them with Kokoro. Listen plays an article from its podcast once
    it's made, and in the phone's voice until then: from the next play or
    jump once it's made, never mid-sentence. The playing screen shows the podcast voice's name by
    the source, and a quiet line when an article of an edition with a
    podcast plays in the phone's voice (not made yet, or left out).
    Under Listen on the edition page, a bar of its articles fills as each
    is made (an article being made fills as each ~2-minute piece is kept),
    with how many minutes are made, and whether it's being made now or
    waiting for the phone to charge; an edition without a podcast
    (made by hand, say) offers **Make the podcast**, made while charging.
  - **E-reader & delivery:** the e-reader in a dropdown at the top, then
    "How it gets there". They share a page because the e-reader decides
    which delivery options show: **Email it to your Kindle** (shown for a Kindle, or once
    chosen) with the Kindle's address and the mail app to send with
    (without a working address it says Send opens the share sheet until
    one is added); **Send it myself** (tap Send and choose an app), with
    a tip on sending to your e-reader under it while it's chosen; or
    **Save to a folder**. A folder the app can no longer reach (its access
    was revoked, or its app uninstalled) says "Can't reach <name>. Tap to
    choose it again." instead of saving automatically, and tapping picks
    it again; so does the notes folder, which also offers **Turn off**.
  - **Where your feeds live** (its row says "Sites you pick" or "Your
    tt-rss · host"; in red, "Not signed in" or "Can't sign in to tt-rss"):
    the two setups as radio rows, in onboarding's words: **I pick my own
    sites** and **On my own RSS server**. Onboarding's third answer, "In
    another reader app", is the first plus an import, so under it a line
    says where the import is (Sources' menu). Picking the other setup
    changes nothing until it's confirmed. **On my own RSS server** opens the sign-in, in place
    of the page (Back returns to it). Signed in from the phone setup with
    phone feeds, the page offers the move at once: "Signed in. 58 feeds in
    7 categories. Move your 8 phone feeds to tt-rss?", **Move 8** (the same
    sheet as Sources) or **Not now** (the banner on Sources stays). **I pick my own sites** asks first ("Pick your own sites instead?"), saying
    what happens: NewspapeRSS signs out and deletes all tt-rss data here
    (articles, stars and feed settings); past editions stay; nothing
    changes in tt-rss; the feeds don't come along, so it points to
    tt-rss's OPML export, imported in Sources.
    Feeds moved to tt-rss and still kept for their stars become phone feeds
    again, and a move under way stops.
    With a server, the page also has the account: its address, who's
    signed in or why the login fails, when it was last checked, **Sign in
    again** (the address and username filled in; the same user keeps their
    category), **Articles from**, **Sync read status with tt-rss** and
    **Start fresh**, and **Resume** if the account was paused.
  - **Reading notes:** "Save notes for each edition" asks for a folder (an Obsidian
    vault, say). Each edition's notes file is saved there once the edition is
    delivered, by share, folder or Read on a Boox, in the background so a slow cloud folder
    doesn't hold up delivery. A folder that refuses the file gets a notification;
    **Notes** on the edition still shares them. It can be the delivery folder too;
    changing one folder never drops the app's access to the other.

The look: a newspaper feel (serif headlines, a masthead with the date), but
calm, with no badges, counts or endless animations, which smear on e-ink.

## 10. Architecture

How the code is built, with diagrams, is in [ARCHITECTURE.md](ARCHITECTURE.md).
