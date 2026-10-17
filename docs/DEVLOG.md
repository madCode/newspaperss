# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/Seafloor-Studio/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **Lately:** six overnight cycles on 16 Oct (#234–#239): screenshots on a fixed clock, bot checks, other-language furniture, players, oversized feed items, and an architecture and test health audit.
- **Watching:** `PodcastSettingsTest` timed out once in six full runs under load (backlog). The older settings and source-page flakes' likely causes were fixed on 16 Oct (#245, #246); neither reproduced locally in 10 runs before, so they're watched rather than counted. #245 itself made main's tests fail now and then for about an hour, until #267.
- **Waiting on you:** reading times for saved links (a call on the morning page; titled imports have none either); the `extraction` label for Send for a look. [#18](https://github.com/Seafloor-Studio/newspaperss/issues/18), a Dropbox app key (only matters for Kobo). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Sat 17 Oct

### Podcast episodes: the corpus and a prototype detector
- **Research plan phases R1 and R2** ([podcast-episodes.md](research/podcast-episodes.md)): 421 labelled posts from 41 public feeds, scrambled, and a detector in core's tests that the corpus test holds to the plan's bar. No article misclassed; 9 in 10 notes and transcripts caught; every pair found.
- **Checked beyond the corpus:** 15 public feeds it hadn't seen, and one reader's own list, locally (its results stay out of the repository). Neither showed an article called anything else.
- **Not in the app yet:** the feed parser and tt-rss sync still drop attachments, which detection needs first.

## Fri 16 Oct

### Today, area D (tt-rss, extraction, the book), closing
- **Every item on the list done but one:** #242 (docs: the heart to Not doing), #243 (Substack posts whole via tt-rss), #252 (mark-read waits a couple of minutes after a delivery), #254 (the book's accessibility metadata, headings that never skip a level), #259 (mark read when they expire), #265 (leaving tt-rss brings your feeds along), #274 (a quiet tt-rss feed says so), and #281 (starred in tt-rss goes in the paper, madCode's call this morning), all with CI green. #268 fixed a test that failed now and then.
- **Item 7, Webtoons, parked:** built and measured on `claude/today-d-c7`; the backlog item says what must change first (a strip taken whole or not at all, no silent cut, smaller panels).
- **Nothing waiting on you from this area.** Database versions: 12 (#274), 13 (#281); session C takes 14.

### Today, D8: starred in tt-rss goes in the paper
- **madCode's call on the morning page:** one-way and opt-in, as recommended.
- **Shipped:** a switch under tt-rss in Settings › Where your feeds live, off by default. On, what you star in tt-rss goes in your next edition, once each; the star stays in tt-rss. Stars already there when it's turned on are kept stars and stay out. Seen stars are kept apart from articles (`ttrss_stars`), so a star kept for months isn't sent again; unstarred and starred again in tt-rss, it is. Database version 13.
- **The review found:** tt-rss lists stars by article date, so a capped list missed new stars on old articles and could let unnoted kept stars through (the list is now read whole, up to 10,000, or nothing is taken); a server could add articles it wasn't asked for; a star on an article in an unsent edition was noted and lost, and could hold up the rest.

### Today, D4: a quiet tt-rss feed says so
- **From the backlog (personas):** a tt-rss feed that stopped posting looked like any other on Sources.
- **Shipped:** "Nothing new in N weeks" (months past two) under a feed's row once it has gone three weeks without posting. Each sync takes one look at every article tt-rss got since the last, read or not, without their text (up to 1,000, oldest first; the rest wait for the next sync), since a feed read on the laptop leaves nothing unread here. A feed with nothing seen counts from when the app started watching it. Database version 12.
- **The review changed the design:** the first version asked each feed for its newest article once a day; that missed purged articles, starved big accounts and slowed the edition's sync. A second look found that a look cut short dropped older articles for good; it now goes on from where it stopped.
- **Item 7, Webtoons, built and measured, not shipped:** branch `claude/today-d-c7`. See the backlog item for what the review found.

### Today, C8 (#283): saved links that can never be read
- **From the backlog:** a PDF, a video or a gone page saved to the reading list waited silently for ever, and every edition downloaded it (up to 10 MB) only to leave it for the next.
- **Shipped:** the lookup when a link is saved, or the first edition to try it, records why it can't be read (not a web page, a 410, too large). Editions leave it out, it isn't counted as waiting, and the reading list says "Can't go in your paper: … Tap to open it, or remove it." Saving it again looks again. Database version 14.
- **Review:** two rounds. Files over the 10 MB limit (most videos) were still missed, there was no way back from a mark, and unreadable links still counted as waiting; then a guard that let a starred link wait for ever again. All fixed with tests. Merging main found builds planning from a new query (#272) that needed the same filter.
- **Left:** a 404 isn't counted (often a passing problem); "Tap to open it" does nothing on a reader with no browser.

### Today, area C (screens), closing
- **Six PRs merged, seven of the nine items done** (#251, #258, #264, #266, #270, #276), all with CI green, plus one item checked and found not to be a bug (Stop making and Delete podcast). #271 was a test fix #268 had already made, so it was closed.
- **Item 6, saved links that can never be read, is built and reviewed twice** (branch `claude/screens-c8`). It adds database version 14, after session D's 12 and 13, so it opens once those merge.
- **Your call:** item 7, reading times for saved links. See the backlog's *Your call* line: it isn't only links from before version 2; every titled link from a Pocket or Instapaper import has none either, and filling them in costs one page fetch each.
- **For you:** the `extraction` label for Send for a look doesn't exist yet. The `handle-issues` skill (shared from claude-playground) doesn't mention those issues; as extraction bug reports they're fixed the usual way.

### Today, C6 (#276): typed text kept when Android ends the app
- **From the audit:** an address typed into Add a site, or into Settings' tt-rss sign-in, was lost if Android ended the app in the background (say, while looking the site up in a browser). Onboarding already kept its own.
- **Shipped:** both come back where they were: the Add dialog open with the address, the sign-in with its address and username, never the password.
- **Review:** nothing serious; it found no test that a closed sign-in isn't restored, now added. A restored step comes back as the address alone, to search again.

### Today, C5 (#270): a Kindle how-to, and what counts as sent
- **From the backlog (personas):** a short Kindle how-to, and per device what counts as "delivered".
- **Shipped:** the Kindle tip now starts with the Kindle app on the same Amazon account and ends with where the edition shows up (Library › Docs). Under each device's tip, in setup and in Settings under Send it myself, a quieter line says when the app takes an edition as sent (choosing the Kindle app or Dropbox, sending it to the mail app, Read on a Boox, saving to the folder), that its articles won't come again, and to use "Didn't arrive? Mark as not sent" if it never arrives.
- **Review:** checked each line against the delivery code. It caught KOReader's line naming a folder under Send it myself, where none is in use, and two loose phrasings; fixed, with a test.

### Today, C4 (#266): no dead end in setup
- **From the backlog (personas):** a site with no feed, pasted during setup, ended at "No feed found"; Sources already offered the reading list instead.
- **Shipped:** setup offers **Save this page to your reading list instead** under the error. Saved, the field clears and says so, and the link is enough to go on.
- **Review:** caught a double tap saving twice (and saying "already in your reading list"), and a late answer clearing what the reader had typed since; the tap now hides the button and only its own answer counts, with a test. Left: TalkBack doesn't announce a search's "No feed found" (it's the field's error text), as before.

### Today, C3 (#264): turning the phone closed dialogs
- **From the audit:** Listen's "Finish X first?" and its contents, and the category list of Add a site and of Move feeds, closed (or stepped back) when the phone turned.
- **Shipped:** they stay. The two questions are saved with the screen; the category list's open state now lives with the dialog's own state in the view model.
- **Review:** caught what saving brings back after Android ends the app in the background: a category list reopening the next time the dialog opened (and swallowing a Back), the contents over an empty player, and "Finish X first?" for an edition finished or pruned since. Each fixed, with a test. It also caught that copying test files across from an older branch had undone #245's teardown; restored.

### Today, C2 (#258): an Undo that timed out unseen
- **From the audit:** Sources' and the reading list's snackbars counted down while the app was in the background.
- **Found:** real for a snackbar already on screen as the app leaves: its time ran out unseen, Undo with it. One arriving in the background already waited (the screens read it only while on screen). The same held on a source's page, Settings and an edition's page.
- **Shipped:** those five screens show a snackbar only while the app is on screen, and show one cut short again in full on its return.
- **Review:** caught that waiting would leave a removed reading-list link in the database while the app was away, where an overnight edition would take it and a killed app would bring it back. Leaving the app now settles that removal, as leaving the screen already did.
- **Also checked:** "Stop making and Delete podcast without `NonCancellable`" isn't a bug: both are blocking writes launched straight onto a background thread, so leaving can't cut them short. Ticked with the reason.

### Today, C1 (#251): Send this article for a look
- **From you (morning page):** build the approved proposal.
- **Shipped:** an article's preview has a ⋮ in its top bar (only for an article with a web link) with **Send for a look…**. It says first that what's sent becomes a public GitHub issue with the article's address and the app's version, then opens GitHub's new-issue form in the browser filled in: "Extraction: *title*", the address, the version, and a line to say what looks wrong. The question stays up across rotation; with no browser it says so.
- **Review:** found the form could still be too long once encoded (a non-Latin or already percent-encoded address), and a long title could be cut through an emoji; both fixed, with a test. The ⋮ costs the title some room at 2× font ("The quie…" becomes "The q…").
- **For you:** the `extraction` label doesn't exist yet; GitHub only applies a label from the form for people who can label issues, so the title's "Extraction:" is what marks the rest. The `handle-issues` skill (shared from claude-playground) doesn't mention these issues yet; as a bug report they're fixed the usual way.
- **Left:** no browser is a dead end; whether the GitHub app takes the link and loses the fields wants a look on a device. Both in the backlog.

### Today, B13: a smaller APK
- **From you:** one APK per ABI, chosen on the morning page.
- **Shipped:** four APKs instead of one. Release builds:
  - arm64-v8a, for phones and recent e-readers: 50.6 MB, down from 86.8 MB.
  - x86_64: 54.8 MB.
  - armeabi-v7a, for older 32-bit e-readers: 18.6 MB, without the podcast voice, as before.
  - x86: 18.6 MB.
- **Version codes:** each APK's is `versionCode * 10` plus 1 to 4, with 64-bit last, so a device is offered the highest it runs. The release workflow attaches all four, the latest-debug page has each, and F-Droid's draft recipe builds each.
- **Review:** the app's "newer build" link always fetched the arm64 file, which a 32-bit e-reader can't install; it now picks the device's own, and a test checks each name against what CI uploads. Play's bundle takes the first APK's code, `versionCode * 10 + 1`, which still rises, and RELEASING says so.
- **Left:** whether F-Droid wants a changelog per ABI code, to check at the first submission.

### Today, B: test health and speed (B1–B12)
Area B of four parallel sessions: the audit's test findings, then two speed items. All merged with CI green; #269 merged after you answered.
- **B1, each test's own stores ([#245](https://github.com/Seafloor-Studio/newspaperss/pull/245)):** the app's settings, tt-rss and feed-move stores were made once per test run, so a setting one test wrote was read by the next (shown with two ordered tests). Each test's app now has its own, stopped at teardown. **It broke main for about an hour:** stopping them ran what a test had left queued, a screen's query of its closed database, and the error failed the next test (3 of 7 main builds). The first review had named the risk. Fixed in **B9 ([#267](https://github.com/Seafloor-Studio/newspaperss/pull/267))**: the stores stop off the main thread first. Then 5 of 6 full local runs were clean; the sixth timed out elsewhere under load.
- **B2–B6 ([#244](https://github.com/Seafloor-Studio/newspaperss/pull/244), [#246](https://github.com/Seafloor-Studio/newspaperss/pull/246)–[#248](https://github.com/Seafloor-Studio/newspaperss/pull/248), [#250](https://github.com/Seafloor-Studio/newspaperss/pull/250)):**
  - The app's FakeHttp can serve a cut-short feed, and FeedSync's handling of one is tested.
  - Source-page view models stop at a test's end.
  - `NotesWorkerTest` checks the edition it's given is saved; before, it couldn't fail.
  - `PodcastMakerTest`'s fake counts loaded engines atomically.
  - One shared 30-second wait replaces three 5-second loops. Falsifying it showed two "stopped part way" tests passed without being stopped part way; they now check.
  - `SourceDetailScreenTest` runs on a fixed clock.
- **B7, screenshots ([#260](https://github.com/Seafloor-Studio/newspaperss/pull/260)):** the three that differed between renders caught the last tap's ripple. Advancing Compose's clock and the main looper's didn't settle it, so screenshots are drawn without ripples. A third render found a fourth difference: Listen sometimes doesn't scroll down to the sentence (backlog).
- **B8, EPUB writing ([#263](https://github.com/Seafloor-Studio/newspaperss/pull/263)):** pictures go into the book stored rather than compressed again, through a buffer. A 4 MB edition was written in 42 ms instead of 146. Wants a delivery check on a device.
- **B10, coverage ([#269](https://github.com/Seafloor-Studio/newspaperss/pull/269), merged on your answer):** the screenshots run as their own task, which Kover doesn't measure. Coverage goes from 94.73% to 94.32%, still over the 93% floor.
- **B12, a race other sessions hit ([#273](https://github.com/Seafloor-Studio/newspaperss/pull/273)):** the source page's Undo test tapped **Mark 1 as read** before the list showed the article waiting again; it now waits for the button.
- **B11, memory ([#272](https://github.com/Seafloor-Studio/newspaperss/pull/272)):** a build reads each article's feed text as it reaches it. 300 waiting articles of 50,000 characters held 15 MB before, 82 KB after. The review found one rare difference, left on purpose: a source removed mid-build sends its remaining articles from their pages.

### Day cycles, area A, closing
- **Six audit findings fixed, seven backlog items done** (#249, #255, #256, #257, #261, #262), all merged with CI green: the build and delivery list madCode set for area A, in order. Each was reproduced with a failing test first.
- **Waiting on you:** nothing new. Two smaller gaps from the reviews are in the backlog: delivered links are shared between tt-rss accounts, and a phone restored from a backup can't tell whose tt-rss articles it holds.
- **Got in the way:** with four sessions merging, every PR's BACKLOG, DEVLOG and cycle log entries conflicted after each merge, costing a CI run each time. The last four PRs moved theirs here, into one docs PR. Several app tests also failed now and then on CI; session B fixed the causes (#267, #273) and one here (#255).
- **Next run in this area starts with:** the backlog's audit list is done for build and delivery; what's left there is screens, tests and the two tt-rss gaps above.

### Day cycles, A6: leaving the server as a feed moves
- **From the backlog (audit):** leaving the server just as a feed move paused a phone feed could leave that feed paused, and hidden, with no server to come from.
- **Found:** real. A test that leaves at that exact moment left the feed paused.
- **Shipped (#262):** the move pauses a feed and notes it in one step that leaving waits for, so leaving always finds it and makes it a phone feed again.
- **Review:** also found the step could be cut in two if the background work was stopped at that moment. It can't now. Left: a disk error writing the move's notes at that step, and Tidy racing Leave for a feed that was paused before it moved; both were already so and both are rare.

### Day cycles, A4: a dead build's book
- **From the backlog (audit):** a build cut off by the process dying could leave its book (several MB) in the app's storage for good, as nothing pointed to it.
- **Shipped (#257):** the next build, which already marks such an edition as interrupted, now deletes its book too.
- **Review:** nothing blocking. It suggested reading the edition again before failing it, in case a stopped build had only just finished; done. Left: titles are unique over two days, not forever, so the same date years later could share a file name; that was already so.

### Day cycles, A3: a timed edition behind one made by hand
- **From the backlog (audit):** with **Make one now** tapped offline, the morning's timed edition was dropped when its time came, and the paper then came from the manual run: no Ready notification, no podcast, no retries.
- **Found:** real. Both share one background job name, and the second to ask was ignored.
- **Shipped (#256):** a timed edition now takes the place of a manual one that hasn't started. A build already under way is left to finish, and tapping Make while a timed one waits changes nothing.
- **Review:** nothing blocking. Left: a connection returning in the milliseconds between the check and the switch would stop the manual build (its articles go back, and the timed one makes the paper). The "one already being made" case has no test, since the test WorkManager can't hold a build running.

### Day cycles, A2: a late send of a released edition counts
- **From the backlog (audit, and the UX pass):** an edition released because a newer one was made could still be sent from a share sheet left open, its Ready notification or Today, and that send wasn't recorded, so its articles went out again in the next edition. Its Ready notification also stayed up after it was released.
- **Found:** both real. Only the edition page's **Mark as sent** handled a released edition.
- **Shipped (#255):** the share sheet, the notification's Send and Read, email to a Kindle and Today now count such a send as Mark as sent does, while its book is still there. A build releasing an edition takes its Ready notification down. Also a flaky test fixed: Mark as sent's test waited for text a ready edition already shows.
- **Review:** caught that a send arriving while a build runs still doesn't count (as for Mark as sent, the build may be taking those articles), yet left a "Sent to Kindle" note. The note now only follows a recorded send. Left: the newer edition holding the same articles still offers Send; nothing is lost, as before with Mark as sent.

### Today, D6: leaving tt-rss keeps your feeds
- **From the backlog (you asked):** **I pick my own sites** deleted the account's feeds with it; the dialog pointed to the OPML export.
- **Shipped:** the feeds in the paper become phone feeds before signing out, with their article text, cap and paid-post settings; left-out ones come paused. Not one outside the paper's category, one already on the phone, or one without an address yet. A message says how many came and why any didn't. Starred articles still go with the account.
- **Review:** the paper's feeds were read from a list that goes stale when the category changes (or doesn't exist yet), so leaving now lists them first, waiting up to 20 seconds, and says when it had to use the last list. Feeds come paused and are unpaused only after signing out, so the app killed between can't fetch a feed twice. A phone feed the reader paused stays paused.

### Today, D2: articles that expire here can be read in tt-rss too
- **From the backlog (personas):** articles that waited their week out here stayed unread in tt-rss.
- **Shipped:** **Mark read when they expire here**, a switch under Sync read status (off by default, shown only with read sync on). Waiting out the week then counts as marked read, which the next sync passes to tt-rss. Leaving a feed out or changing the category still leaves articles unread there. Database version 11.
- **Review:** the first version emptied tt-rss's backlog through the paper: each sync takes a feed's newest five unread, so marking five read brought in the next five, months old, each with a fresh week. Now a newly arrived article published over a week ago is marked read at once; a second look caught that the first version of that also undid a reader's own mark-unread. A paused account's articles expire as before rather than going out as read on resume.
- **Left:** such articles show as "Read" on a source's page, not "Not picked". Another user signing in on the same server inherits the switch (as with the rest of the account's settings; the audit's item covers it).

### Today, D5: the book says what it offers a screen reader
- **From the backlog (a11y):** EPUB Accessibility 1.1 metadata and alt text carried through.
- **Found:** alt text already reached the book. Ace by DAISY on a live edition found what the metadata item didn't name: article headings skipping levels (a site's h3 straight under the title), 7 of them in 14 articles, and the contents list without its ARIA role.
- **Shipped:** the metadata (what the book needs and offers; pictures count as described only when every one has alt text), headings re-leveled under each article's title, `role="doc-toc"`. A live edition passes EPUBCheck 5.2.1 (0 messages) and Ace 1.3.2 (0 violations).
- **Review:** "no hazards" now only when every picture is a still JPEG; a custom `<img-zoom>` element and a no-break-space alt no longer count; right-to-left subheads are right-aligned.
- **Left:** a screen reader on a device, and Ace on an edition with pictures (backlog).

### Today, D3: a quick "Mark as not sent" stays off tt-rss
- **From the backlog (personas):** sending to the wrong app and marking the edition as not sent flipped its articles read, then unread, on the server.
- **Shipped:** the marking waits two minutes (WorkManager's initial delay). A Mark as not sent in that time replaces it, so tt-rss isn't told either way.
- **Review:** a sync inside the two minutes still marks delivered links read, and Doze can stretch the wait, so DESIGN says "a couple of minutes or more" and "usually". A rare path (an article tt-rss has unread but the app thinks it confirmed read, synced in the window) can bring it back for one more edition; noted in the PR, not fixed.

### Day cycles, A5: another tt-rss user on the same server
- **From the backlog (audit):** signing in as another user on the same tt-rss server kept the first user's waiting and starred articles, filed under feed ids that belong to the first user and may name the second's feeds. A sync running during the switch could add more.
- **Found:** both real, each with a test (the second switches users in the middle of a sync).
- **Shipped:** another user on the same server now starts with a fresh tt-rss source, as another server already did, so the first user's articles and stars go with the old one. A sync still running for the first user can't add to a source that's gone. The same user signing in again keeps everything, as before.
- **Review:** an edition being made during the switch would have failed; #249, merged first, keeps it. Two older gaps went to the backlog: delivered links are shared across accounts, and a phone restored from a backup has no login to compare with.

### Day cycles, A1: a source removed during a build
- **From the backlog (audit), area A of four parallel sessions:** removing a source, a saved link or the tt-rss account while an edition was being made threw the finished edition away.
- **Found:** real. A test that removes a feed and a saved link mid-build got "Something went wrong" and no edition: the articles read at the start were gone by the time they were linked into the edition, eight minutes later at most.
- **Shipped:** the edition is kept. The removed articles are in the book, and the edition lists them by title, as it already did for a source removed after the edition was made.
- **Review:** nothing blocking. It noted that a removed article's link isn't remembered as delivered, so a feed removed and added straight back could send the same story again. That was already so for a source removed while an edition waited to be sent; left as it is.

### Today, D1: Substack Notes through tt-rss
- **From the backlog (device):** tt-rss strips the text of a Note embedded in a Substack post, so the sentence introducing it hung.
- **Found:** Substack's page shows only a placeholder there too, but carries the whole post, as its feed has it, in a data script (`preloads-json`).
- **Shipped:** a Substack post from tt-rss with an empty block where a Note was is read from its page's data instead: one page fetch, and the Note comes back as a quote. Checked on a live post.
- **Review:** paid previews lost their "paid" mark, and a subscriber's full text could be cut to the page's free part when the paywall sat late. Both fixed: an excerpt is never restored, and the page's post must be at least as long. The first trigger also fetched almost every Substack post (a picture's stripped zoom button leaves an empty block too); now only where a Note goes.
- **Left:** a Note after a heading or list, or a Substack post read from its page, still loses it (backlog).

### The 16 Oct run, closing
- **Six cycles, five backlog items done** (screenshot clock, bot check, furniture, players, and the oversized items found on the way), one stale item ticked (#190), Dependabot's #229 and #230 merged. All merged with CI green.
- **Waiting on you:** CLAUDE.md's drift from claude-playground's shared rules; a delivery check on a device for #237 (players in the book); the environment's setup script, so a new session has the Android SDK and Maven Central's mirror (the lines are in the 16 Oct session).
- **Next run starts with:** the audit's top findings, first the build that a delete throws away, then the edition sent but not marked sent.

### Overnight cycles, 6: architecture and test health audit
- **Due:** the recurring pass, last done in #163–#166. Three fresh-eyes reviewers, one area each: how features fit together, test health, and lifecycle with the docs.
- **Fixed now:** tapping any notification while the app was open finished it and started it afresh, losing the screen the reader was on (the intent lacked `SINGLE_TOP`). Docs: the coverage floor is 93%, not 90%; the Boox button is **Read**; DESIGN no longer describes the gone "Feeds in your paper" checklist; `:epubsink` and the device tests are in CLAUDE.md and ARCHITECTURE.
- **Found, in the backlog (top of Next):** the most serious is that removing a source or saved link while an edition is being made throws the finished edition away; also an edition released by a newer one can be sent without being marked sent, so its articles go out again. Thirteen findings in all, each checked against the code, several needing a cycle of their own.
- **Your call:** CLAUDE.md's shared sections have drifted from claude-playground's.

### Overnight cycles, 5: one big item stopped every edition
- **From the backlog (resources), then a finding:** "load build candidates without their feed text" turned up something worse while checking it. Android reads a database row through a 2 MB window, and nothing capped a feed item's text. One item over it (a post with a picture inlined as text, in a feed the app reads up to 10 MB) made the edition fail with "Something went wrong" every time until the article expired, or forever if starred, and could crash a source's page.
- **Shipped:** feed text is kept to 500,000 characters when stored; a bigger item loses its inlined pictures, or else its text, and its page is fetched instead. Rows already stored are shrunk once by a database migration (version 10), read in pieces that fit the window, so a post keeps its words.
- **Review:** the first version healed on each build and sync instead: too late for a source's page, it threw away words the cap keeps and emptied starred articles, and it scanned every stored text each time. Moved into the migration. A second look caught that a 10 MB row read into memory could run a small e-reader out at every launch; such rows are now dropped unread.
- **Left:** loading candidates without their text stays in the backlog, now about memory rather than a crash; a feed's title and link are still uncapped, a new backlog item.

### Overnight cycles, 4: a line where a player was
- **From the backlog (live):** audio players, and video players other than YouTube and Vimeo, vanished, leaving a lead-in ("Hear it for yourself:") over nothing.
- **Found:** real: `<audio>`, a video with no poster frame and other sites' players were simply removed.
- **Shipped:** each leaves a line where it was: "Listen to the audio" or "Watch the video" linking to the file, "Listen on Spotify: …" for a known site's player, or "There's audio here: listen on the original page." when there's nothing to link to. A video with a poster frame keeps its picture and now also links to its file. Looping muted videos (today's GIFs) and audio with no controls stay out, as before.
- **Review:** the reviewer ran the code against 30-odd cases and found plenty: the repo's own Longreads sample still lost its Pocket Casts player; a player alone stopped reading as an empty item, which would have changed when a page is fetched; a player inside a heading or link made invalid XHTML; several in a paragraph came out reversed; a link the cleaner refuses later lost its link. All fixed, each with a test.
- **Left:** players loaded by script (`data-src`, or only in `<noscript>`) still go, as YouTube's always have. Listen reads the lines aloud ("Listen to the audio"), which a listener can't act on. Worth running the delivery check on a real device, since articles in the book change.

### Overnight cycles, 3: "Lire aussi" and friends
- **From the backlog (live):** page cleanup's furniture patterns were English only, so "Lire aussi", "Mehr zum Thema" and "Lee también" stayed in French, German and Spanish articles.
- **Found:** real: a "Lire aussi" box with its links came through the cleaner untouched.
- **Shipped:** the same furniture in those three languages goes: headings over a short box of links ("Sur le même sujet", "Das könnte Sie auch interessieren", "Noticias relacionadas"…), and cross-link lines ("Lire aussi | …", "Lesen Sie auch: …"), however long the headline. Matched in every article rather than by its language: a whole heading can't be mistaken across languages, and pages often don't say theirs.
- **Review:** caught that Le Monde's long cross-links would have left a stray "| headline", that a list item or heading opening with "Lire aussi" would have gone, and several common spellings and headings missed ("A LIRE AUSSI", "Más información"). All fixed, each with a test.
- **Left:** a lone "Les plus lus" or "Meistgelesen" heading goes even over a paragraph, the same small risk "Most read" already carries in English.

### Overnight cycles, 2: a bot check isn't "not a feed"
- **From the backlog (sweep):** a host's bot check answering a feed with a 202 and a captcha page.
- **Found:** real, through the app's own code: a source said "We can't get new articles from this site any more. It may have moved; try adding it again", and adding the site said "No feed found". Adding it again would meet the same check.
- **Shipped:** both now say the site turned NewspapeRSS away with a bot check, and to try again later, as a 403 does. SiteGround's captcha joins the bot checks the app knows, which also helps article pages.
- **Review:** found the first version too eager: a small site behind Cloudflare carries its challenge script on every page and would have been refused while adding, and a real JSON Feed with sorted keys could have been called a bot check. Both now ask only once the feed couldn't be found or read; tests cover each.

### Overnight cycles, 1: screenshots on a fixed clock
- **From you:** six cycles or three backlog items tonight, flaky tests and the screenshot clock first; merge Dependabot's two PRs (#229, #230, merged).
- **Found:** the flaky-DataStore item was already done (#190) and never ticked. The screenshot one was real: two renders two minutes apart differed in 10 of 85 pictures ("Last checked today at 6:53 AM"), and from New Year every article row would have gained ", 2026".
- **Shipped:** the screens read the time from one clock; `ScreenshotTest` fixes it at a Tuesday morning and hands it to the repositories it builds. Two renders now match, but for three pictures caught mid-animation (an open ⋮ menu's ripple, a row's selection fading in), now in the backlog. The README's screenshots were redrawn.
- **Review:** found the year in short dates still read the real clock, and the edition's date and "Trying again at" the machine's zone; fixed, with a test across New Year.
- **Getting started:** this container had no Android SDK and Maven Central answered 429; the cycle log says how it was set up.

## Fri 9 Oct

### Podcasts: the end of every piece was cut
- **From you:** some sentences in podcasts clip, always in the same place: "simply said: 'Get a haircut'" in the Glen Campbell piece, and somewhere in Elysian's.
- **Found, one log at a time:** the encoder kept every frame (#214), Kokoro made no quiet gap and playback didn't skip (#219). Your EPUB showed the clipped sentence was the last line of its two-minute piece, and listening to another piece's last line ("…more than a whole day's work.", cut at "more") confirmed it. Each piece was played by its own MediaPlayer, which said it had finished while the last 0.15–0.9 s was still to be heard; releasing it for the next piece cut that off.
- **Shipped (#220):** an article's pieces now go to one Media3 player as a list, which plays them back to back through one audio output and says it has ended only once the sound is out. Each piece skips the encoder's 85 ms lead-in, so the joins have no pause. On the way: waiting by the clock and keeping a finished player a second, which fixed most of it on your phone but left a little.
- **With help:** your local Claude session measured it on an emulator with audio on: MediaPlayer always holds 0.3–0.5 s unplayed; Media3's playlist keeps one audio output across the join, with no underruns; it ends only once that output has drained; the encoder's lead-in is exactly two frames, and clipping it keeps the join gapless. The emulator didn't reproduce the phone's early "finished"; you were on Bluetooth, which holds more audio unplayed.
- **Also (#219):** the podcast log follows playback, flags faint noise where words should be, and logs a flagged line's exact text.
- **Then:** the playing code simplified now that one player plays the list: it tells the speaker which piece each position is in, the encoder's frame count and the per-piece log lines are gone, and the audio has its own file.
- **Left:** a smaller APK (per-ABI), in the backlog.

## Thu 8 Oct

### Extraction sweep: a magazine, paywalls, notes
- **From you:** how New Left Review's "Changing the Guard" comes out against how it should, whether there's a list of articles to catch regressions, then: use your reading list to grow it and fix what else breaks.
- **Found:** NLR's paywall went unnoticed and "footnote" (screen-reader text) showed beside each marker; its free articles lost all their notes (kept in a `<footer>`, which Readability and the cleaner drop); small capitals came out lower case ("Tory mps"); a saved link's title carried the author and issue. Across the reading list, run with the new `extractionSweep` tool: Stratechery's and Jane Friedman's paid posts came out as their sales pitch, unmarked; Substack's share and gift buttons and its embedded-post cards' "Read more · 12 likes" got through.
- **Shipped:** all of the above fixed. The corpus gains NLR, Stratechery, Jane Friedman and Hamilton Nolan, with checks that the notes come along. The reading list itself stays out of the repository.
- **Then, from you:** comb the web for a more varied set. `tools/sweep-feeds.opml` now lists 40 public feeds picked for variety; running it found a microblog whose posts all link to (or redirect to) the one page holding them all, Substack's site footer in a paid teaser, and newsletter pitches at the end of magazine articles. Fixed. Le Monde, El País, NHK and Tumblr turned the sandbox away, so they're unchecked.
- **Review:** the fresh-eyes review found the free-part rule could throw away a MemberPress page's free text, a short post opening with a pitch could vanish, the notes check was quadratic on a hostile page, a note could become a paragraph inside a paragraph, "invisible" hid Tailwind's visible text, a log-in-to-comment form counted as a paywall, and the title rule dropped real subtitles. Its second look caught a quote's credit lost with its `<footer>`, a pitch rule hitting authors' own sentences, and a site's name found inside a subtitle ("time" on time.com). All fixed, each with a test.
- **Then, from you:** handle the feed refused as too large (on tt-rss, whose limit is 16 MB, it likely never failed; the sweep used the app's own fetching). A feed over the 10 MB limit (a static site's whole archive) is now read up to the limit, its whole items kept and the last, maybe cut, left out; adding the feed works the same way. Pages keep their limit. Its review caught that reading part of a feed then closing it stored the whole feed in the HTTP cache and pushed every other feed out, that a JSON Feed that big could be added but never read, and that a cut feed listing oldest posts first would silently never get new ones; all fixed.
- **Then, from you:** the app couldn't find Current Affairs' feed. The site moved to HubSpot, which advertises the feed only on its blog's page (`/news`), not the front page. The finder now also looks at the section most of a front page's links go into. Reading tt-rss's own finder turned up one more way a page names its feed, `<link rel="feed">`, which the app now reads too; otherwise the app's finder already does more (feed links on the page, the usual addresses, the main section).
- **Then, from you:** build the tt-rss feed errors. tt-rss reports why it can't fetch a feed; the app now keeps that with each feed (database version 9) and shows it: "tt-rss can't fetch it" on the feed's row, what to do and tt-rss's own words on its page, and a banner on Sources naming the feeds, as their rows can be under folded categories.
- **And:** nothing in the app said when tt-rss itself fails to fetch a feed. tt-rss reports it; showing it is a proposal in the backlog.
- **Left:** a bot check answering a feed with a 202, in the backlog; "send this article for a look", a proposal waiting for you.

## Tue 6 Oct

### Listen
- **From you:** a "podcast mode"; an audiobook of the paper rather than a podcast; listening on the go with the phone in a pocket, taking it out for a picture or a sentence that didn't land.
- **Shipped (#162):** Listen on the edition reads it aloud in the phone's voice, a sentence at a time: the screen is the article's text following the voice, ↶ goes back a sentence, the lock screen and headphones skip by article. Where you stopped is kept, and yesterday's paper is offered before today's.
- **Next, in the backlog:** Settings › Listening, then Kokoro, a better voice to download, its audio made with the edition.

### Architecture audit, four cycles
- **From you:** an audit for simplicity and best practices, in four cycles.
- **Cycle 1 (#163), data and background work:** builds stop fetching after 8 minutes rather than hitting WorkManager's limit and starting over; folder delivery can't be cancelled between copying and marking delivered; the time zone is read per build; indexes for two watched queries.
- **Cycle 2 (#164), `:core`:** windows-1252 pages labelled Latin-1, cancellable fetches, ASCII chapter names on any locale, international domains kept, entity-expansion feeds refused, linear regexes.
- **Cycle 3 (#165), the screens:** two crashes (notification settings, folder grants), feed links limited to the web, the preview keeping its place, lifecycle-aware screens, headings for TalkBack.
- **Cycle 4 (#166), Listen, build and docs:** Listen lets go of a deleted book, CI checkouts hold no token, docs brought up to date.
- **Left for you:** timed and manual builds can swallow each other (cycle 1); turning on R8 for release; pinning CI actions by SHA; a debug key anyone can sign with.
- **Afterwards:** the PR review now also covers rotation and a killed app, untrusted feeds and links, other locales, and what else deletes the same data, where most of the audit's bugs came from. Two whole-codebase passes are in the backlog to recur: security and supply chain, and architecture and test health.

## Day 6 · Sat 3 Oct

### Cycle 91: ready to release
- **From you:** do the code parts of the release review; keep the app ID `com.app.newspaperss`.
- **Shipped:** a **Release** workflow: a `v*` tag that matches `versionName` builds the signed APK and the AAB for Google Play and attaches them to a draft GitHub release. The store listing in `fastlane/metadata/android/en-US/` (title, descriptions, the first changelog, six phone screenshots cropped to Play's 2:1 limit, and a 512×512 icon `ScreenshotTest` draws from the launcher icon's own layers). A privacy policy, `docs/PRIVACY.md`. `docs/RELEASING.md` for the signing secrets, each release, F-Droid and Play. A draft of F-Droid's metadata that only follows `v` tags, as `latest-debug` moves on every merge. README's setup steps describe the current onboarding.
- **Left for you:** the signing key and its secrets, a 1024×500 feature graphic for Play, the Play forms and its 14-day closed test, and the F-Droid merge request.

### Cycle 90: categories that fold
- **From you:** a line after a category's last feed; categories you can fold; and whether categories should be at the page's own heading level.
- **Shipped:** yes, one level: with no "Your tt-rss" heading, categories and the page's own groups are siblings. Every group heading is now the same semibold serif in black, as Settings' are (rust was only on Sources, and rust is what you tap). Each group ends with a rule across the page; rows within it keep the shorter rule. A category's heading shows its count and an arrow, and tapping it folds the category to that one line; folded categories stay folded, kept by name in the settings ("" for Uncategorized). TalkBack hears "News, 9 feeds, Expanded".

### Cycle 89: a calmer Sources
- **From you:** Sources felt overwhelming, and the category headings looked random next to the rest of the page.
- **Shipped:** two critique passes. A rule ran under every row and above and below every category, about 25 on a page of 18 feeds; now rules only divide rows within a group, start at the text, and the space above each heading separates groups. Categories were small sans text boxed in by rules, reading like rows; they're now the page's serif a size down, in black ("Essays · 4"), and pinned at the top while a long category scrolls by. The page's own groups (Still on this phone, Curated lists, Not in your paper) keep the rust heading. The page ends with a **Not in your paper** heading over **Outside News** and **Left out**, each with its count. "Waiting for tt-rss's first fetch" is now "Not fetched by tt-rss yet".
- **Critique caught, and left:** a fresh-eyes critic also suggested dropping the rules between feeds (kept: you asked for them with 50+ feeds), a "Your tt-rss" section heading (left: it brings back the split you removed), and one-line rows for phone feeds and curated lists, without the address and "Checking…" (open).

### Cycle 88: Settings in onboarding's words
- **From you:** match the Settings wording to onboarding.
- **Shipped:** the Settings page is now **Where your feeds live**, with onboarding's words for its two setups: **I pick my own sites** ("Newspapers, magazines, blogs, newsletters. This phone fetches them.") and **On my own RSS server** ("Tiny Tiny RSS. Your feeds stay there; the paper is made from them.", or "Tiny Tiny RSS · host" when signed in). It stays radio rows, as it shows what's chosen now. Onboarding's third answer, another reader app, is the phone setup plus an import, so with sites you pick a line says the import is in Sources' menu. The row on Settings says "Sites you pick" instead of "This phone"; leaving the server asks "Pick your own sites instead?" with **Stop using tt-rss**. The Sources menu item and the tt-rss error messages use the new page name.

### Cycle 87: onboarding in the reader's words; one list on Sources
- **From you:** the clean-room wording for the onboarding question, as cards that move on when tapped, with a third answer for a list in another reader app; and Sources with a server as one list, since nearly all of it is tt-rss and its settings are in Settings.
- **Shipped:** onboarding asks "Where do your feeds live now?" with three full-width cards: **I'll pick some sites**, **On my own RSS server** (Tiny Tiny RSS only, as it's the one that works) and **In another reader app**. A tap answers and moves on; each card is one TalkBack button read as its title and description, with an arrow and no state shown only in colour. Under them, "You can change this later in Settings". With sites already added on this phone, the server card asks before removing them: "Remove the 3 sites you added?" Another reader app is the phone setup starting with a step of its own: how to get the file from Feedly or Inoreader, **Choose the file**, then "Added 42 sites", what was there already, "No sites in that file", or "Couldn't read that file" with **Try again**; **Skip** goes on to picking sites. Its path counts 5 steps; going back and picking the server removes what it added, after asking. The result survives the app being killed in the file picker. With a server, Sources drops the account row and the "On this phone" and "From your tt-rss" headings: a banner only when the account needs something (sign in, can't reach it since a time, paused, a problem marking read) with **Settings**; then the reading list; **Still on this phone** with the Move banner and its feeds while there are any; the server's categories; **Curated lists**; Not in your paper and Left out. Sources' own OPML import now says when a file has no sites in it rather than "No new sites".
- **Review caught:** with no Next, one tap (or a late e-ink tap, or TalkBack reaching the server card before the warning under the cards) removed every site already added; the server card now asks first. Going back while a file was still being read and then choosing the server let the import add its sites afterwards, mixing the setups; the fork now stops the import first. An earlier file's "Added 12 sites" stayed on the import step after those sites were removed; and sites added on the sources step showed as the file's before any file was chosen; the step shows only this visit's file now. The account banner could fill the screen at large text or in landscape; it takes at most a third and scrolls. It wasn't a TalkBack heading nor announced when it appeared; it's both now. Back during the fork's sign-out was overridden when it finished; Back waits now (from before this cycle). The second look caught the first fix waiting for a file still downloading before moving on, with Back refused meanwhile; the server choice now stops the import without waiting, and a lock keeps its adds and the removal apart. It also caught the phone choice cutting an import short, the confirmation flashing "0 sites" after the app was killed, and Back looking tappable while it did nothing. Found while testing: the banner as the list's first item appeared out of sight when it came up with the list open, as the list keeps its first row in place; it's above the list now.
- **Decided for you:**
  - The onboarding audit, by the rule (a single choice that moves you forward with nothing else to set becomes cards; multi-select stays checkboxes; a choice with a usual default on a page with more to set stays radios):

    | Step | Choice | Decision | Why |
    |---|---|---|---|
    | Welcome | none | unchanged | Nothing to choose. |
    | Where do you read? | device, single | stays radios | Kindle opens its email setup and KOReader its folder on the same page, and the others show a tip to read before going on. |
    | ↳ Kindle's mail app | single, in a menu | stays a menu | "Ask each time" is the usual answer, on a page with the address to type. |
    | Where do your feeds live now? | single | **cards** | Moves you on, with nothing else on the page. |
    | Bring your list | none (a file) | unchanged | One button. |
    | What do you like to read? | starter packs and sites, multiple | stays chips and checkboxes | Several at once. |
    | Sign in to your tt-rss | none (a form) | unchanged | Fields to fill. |
    | Also on this phone | curated lists, multiple | stays checkboxes | Several at once, or none. |
    | How big, how often? | slider, switch, time | unchanged | No single choice. |
  - The import step reads the file itself, with the same SourceRepository import as Sources, so its result is part of onboarding's saved state.
  - Skip on the import step goes to the starter packs rather than straight to the end: someone whose file didn't work still needs sites.
  - Coming back to the question shows no answer as chosen: each card is a way forward, not a setting.
  - The account's "54 feeds in your paper" and "Articles from News" lines went with the row; Not in your paper ("43 feeds outside News") still says which category the paper takes.
  - Settings › Where your feeds come from keeps its own wording (This phone / My own RSS server): it's a setting with a current value, so radios fit there.

### Cycle 86: moving your phone feeds into tt-rss
- **From you:** build step (d) of the server-or-phone design: phone feeds move into tt-rss in one tap, with their settings, and stars kept.
- **Shipped:** with a server, Sources puts the phone's own feeds last under On this phone, beneath a banner, "8 feeds are fetched by this phone, not your tt-rss", with **Move them to tt-rss** (only with a working account; curated lists and the reading list never count). The sheet lists them all ticked, says "Already in your tt-rss: just removed here" for one tt-rss has at the same address, takes one category for the new ones, and says their settings come along. The move runs in the background, one feed at a time, as stepped lines: "✓ Aeon · subscribed", "Moving 3 of 4 · Quanta Magazine". At the end, "Moved 8 feeds to your tt-rss"; if some couldn't move, "Moved 5 of 8", why each didn't, and **Move the other 3**. Signing in from the phone setup in Settings offers the same at once: "Move your 8 phone feeds to tt-rss?", Move 8 or Not now. Each moved feed's article text, cap, Skip paid posts, left out and what the text check learned go onto its tt-rss feed; the phone feed stops fetching and leaves Sources, and is deleted only when nothing starred, waiting or in an unsent edition is left in it.
- **Review caught:** signing in as another tt-rss user after a move left the moved feeds hidden and paused, then deleted, in neither account; they're phone feeds again now. Stories marked read on the phone came back unread from tt-rss; their links are remembered at the move. A moved feed was deleted as soon as its stars were delivered, so Mark as not sent on that edition lost them; it stays two weeks after delivery. Leaving the server just as a move finished could leave a phone feed paused; the move checks it's still on before each feed. A move whose work kept failing said "Moving" for good and refused a new one; after three tries what's left goes back to the banner. A feed an old tt-rss has under another address said "move it again" forever; it says to check tt-rss now. Left: Today's count of starred articles doesn't count those in moved feeds, though they go in; a feed paused on the phone sends its old stars once moved, which a paused feed wouldn't; leaving the server at the very moment a feed is moved can still leave that one paused.
- **Decided for you:**
  - Stars are kept by keeping the phone feed, paused and hidden, rather than moving its articles: editions still take what it holds, and it's deleted, in one statement that checks again, once nothing is left. Moving starred articles onto the tt-rss account would mix them into tt-rss's read sync.
  - Its waiting articles still go in the paper meanwhile, so nothing goes missing while tt-rss makes its first fetch. For up to a week, a site can then fill two places in one edition (one from the phone, one from tt-rss) and the same post can wait twice, out of sight; the same link never goes in twice, and one delivered isn't delivered again.
  - A feed tt-rss already has at much the same address isn't subscribed again (it would be a second feed in tt-rss); for anything else tt-rss's own "already subscribed" counts too.
  - The phone's settings win over the tt-rss feed's own when it was already there: it's the one that was in the paper. A feed paused on the phone is left out in tt-rss.
  - The category starts on the one the paper takes articles from, so moved feeds stay in it; another one is warned about.
  - When tt-rss stops answering, or another login signs in, the rest of the batch stops at once instead of each waiting its turn.
  - Leaving the server makes feeds still kept for their stars phone feeds again; without that they'd stay hidden with nothing to come from.
  - No progress screen of its own: the banner on Sources (and the page in Settings) shows it, and the reader can leave.
  - A move that loses its connection waits for it, saying "Moving", rather than failing; it carries on once connected.

### Cycle 85: adding a site to your tt-rss
- **From you:** build step (c) of the server-or-phone design: with a server, Add puts the site into tt-rss, with fallbacks when it can't.
- **Shipped:** with a server, **Add a site** finds the feed on the phone as before, then asks **Subscribe in your tt-rss** with a category picker (the one used last, else the paper's, else Uncategorized; "To add a category, make it in tt-rss first"). "Asking tt-rss to subscribe…" can be closed: the request carries on in the app, and the answer comes as a snackbar, "Added to your tt-rss, in Science.", with **Undo**, which unsubscribes it and lets go of anything it brought meanwhile. The feed list is read again straight away, so the new row shows at once, "Waiting for tt-rss's first fetch", on Sources and its page. A feed already there says "Already in your tt-rss" and its category, matched by address or by tt-rss's own answer. tt-rss refusing says why in words (it couldn't download or read the feed, an old server, a read-only account) and offers the reading list for an article; a site with no feed offers the reading list, or Arts & Letters Daily when that's the site. With a server, the Sources ⋮ menu has Where your feeds come from instead of OPML. On the phone, nothing changes. tt-rss's subscribe status codes are checked against its source.
- **Review caught:** signing in as another user while tt-rss was still answering listed the first user's feeds under the new account, and Undo then reported a removal that never happened; the list waits for the same login now, and Undo refuses another login's feed. Undo during a sync could still let that sync's articles from the feed into the paper; the feed is left out as well now. On an older tt-rss that doesn't say the new feed's id, Undo could pick a feed already there under a near-same address; it takes only a new feed now. An answer arriving while another Add was open hid its snackbar, and Undo, under the dialog; it waits now. Back from the category list closed the whole dialog. Re-adding a site while its Undo ran stayed on "Asking…". A database error after tt-rss had added the feed said it hadn't, and one during Undo could crash the app. A feed unsubscribed in tt-rss long ago counted as "already there" right after a category change. Left: "Waiting for tt-rss's first fetch" stays until the next daily list when the feed has no unread articles; a feed whose address redirects can still be added twice if tt-rss has it under the old address; an Undo snackbar not yet shown is lost if Android clears Sources (the feed stays in tt-rss, where it can be removed).
- **Decided for you:**
  - The subscribe runs in the app, not as background work: its answer goes back to the dialog or a snackbar with Undo. If the app is closed mid-way, tt-rss still adds it, and the next daily list shows it.
  - No "fetch it from this phone instead" when tt-rss can't: that's your open question.
  - The reading-list fallback is offered only for an article's address, not a site's front page, as on the phone setup.
  - The curated lists stay offered in the Add dialog with a server, as on the phone: they're on the phone in both setups.
  - The starter packs weren't offered anywhere after onboarding, and onboarding already hid them with a server, so nothing else changed there.
  - New categories come from tt-rss: the picker lists empty ones too, so one just made there shows.

### Cycle 84: Sources for a server
- **From you:** build step (b) of the server-or-phone design: Sources for the server setup, P1 v3 of the mockups.
- **Shipped:** with a server, Sources starts with **Your tt-rss · host** ("54 feeds in your paper", or what's wrong in words with a ⚠, such as "Couldn't reach tt-rss. Since 6:10 AM. Showing what it last listed."), which opens Settings › Where your feeds come from. Then **On this phone** (the reading list, curated lists, any phone feeds), then **From your tt-rss**: each category a TalkBack heading ("News, 9 feeds") with its feeds A to Z, Uncategorized last, each feed opening its page ("In your tt-rss · category News"). A line under a feed only for its own settings. **Not in your paper** counts the feeds outside a chosen category and opens a page listing them by category; **Left out · N** opens the left-out feeds. Without a server, Sources is today's list with nothing of tt-rss. The inset feeds, the fold button and its saved setting, the "Also on this phone" and "Also in your tt-rss" lines and the tt-rss-last sort on Sources are gone. The daily feed list now also lists the account's other feeds when a category is chosen (a new `outsideCategory` flag, folded into this branch's unreleased database step). A paused tt-rss account gets **Resume** in Settings.
- **Review caught:** The list would open scrolled past the account row and the sign-in banner, which loaded after the rows (caught in the screenshots; the list now loads as one). After a category change, the old category's feeds showed as in the paper until the next check; they're hidden until then, with the left-out ones still reachable. Not in your paper said the paper took from everything right after a change. A left-out feed outside the category didn't say it was left out. tt-rss's Uncategorized in another language sorted as a category of its own. A comment claimed the paper's tt-rss order matched the screen's. Left: the paper still orders tt-rss feeds its own way, not by category; a feed list that keeps failing leaves "Checking…" until it succeeds, as before.
- **Decided for you:**
  - The account row opens Settings, as the mockups show; the tt-rss account's own source page (its articles, Feeds in your paper, Pause) can no longer be reached, so its Feeds in your paper checklist is gone. Each feed's page and Left out cover leaving feeds out.
  - Pause isn't offered for the account any more; an account paused before gets Resume in Settings so it can't be stuck.
  - Not in your paper lists the other categories with their feeds' names, not tappable feeds: a feed there comes back by changing Articles from.
  - A left-out feed outside the chosen category counts under Not in your paper, not Left out.
  - Left out and Not in your paper show only when they have something in them.
  - Add a source is unchanged: with a server it still adds to the phone until step (c).
  - Sources waits for the whole list (phone rows, the tt-rss part, the sign-in state) before showing it, so nothing lands above what's on screen.

### Cycle 83: server or phone, chosen up front
- **From you:** build step (a) of the server-or-phone design: the setup is an explicit choice, in onboarding and in Settings.
- **Shipped:** onboarding asks "Where do your sites come from?" after "Where do you read?". This phone leads to the sources step, without Connect tt-rss; My own RSS server leads to sign-in on the step itself, then "Found 58 feeds in 7 categories", then the reading list and curated lists. "Step N of M" follows the path (4 or 5). Going back and choosing this phone signs out. Settings has a new page, Where your feeds come from, after E-reader & delivery: the choice, and with tt-rss the account's settings (Articles from, read sync, Start fresh, Sign in again), moved off the tt-rss source's page, which links there. Leaving tt-rss says what happens first, including that its feeds don't come along yet. With a server but no working account, Sources and Settings say "Sign in to your tt-rss". "Add tt-rss account" is gone from the Sources menu. No database change: the choice is a setting.
- **Review caught:** going back to the phone while a sign-in was still running, or leaving Settings mid sign-in, could leave a tt-rss account in the phone setup with no way to remove it; Back waits now, and saving the account and the choice can't be cut in half. Sites added on the phone path stayed when the reader went back and picked the server; they're removed, and the step says so first. Choosing this phone with no account left a stray saved login. Leaving the server could be cut short by pressing Back. Left: switching from phone to server in Settings keeps the phone feeds, as designed until step (d) moves them; a DataStore write failing at the fork isn't caught, as elsewhere in Settings.
- **Decided for you:**
  - The choice is explicit: asked in onboarding, changed in Settings, and never guessed again once set.
  - Someone who added tt-rss before the choice existed lands in the server setup; anyone else on this phone.
  - The reading list and curated lists stay on the phone in both setups.
  - No "fetch it from the phone instead" exception yet.
  - With a server, onboarding doesn't offer the starter packs, and sites added on the phone path before switching to the server are removed.
  - Onboarding doesn't ask for a category: the paper takes from all of tt-rss, and Settings narrows it.

### Cycle 82: skipping paid posts, per feed
- **From you:** paid posts are a habit of the writer, not of how the articles arrive, so the switch belongs to the publication.
- **Shipped:** "Skip paid posts with nothing free" moves from the source to the publication: a feed's page and a curated list's page keep it, each tt-rss feed has its own on its page, and the tt-rss account's page has none. Its count of skipped posts is per publication too. A tt-rss account that had it on carries it to every feed already seen in its articles and to its left-out feeds; a feed first seen later starts with it off. Database version 7.

### Cycle 81: up to date with main; plain rows on Sources
- **From you:** bring the branch up to date with main; take the ⋮ menus off Sources, since everything in them is on the source's page.
- **Shipped:** main's paid posts, Kindle email and Settings pages are in. Main's database version 6 shipped first, so this branch's database changes are one step from it, to version 7. A row on Sources opens its page and has nothing else to tap, except tt-rss's ▾.

### Cycle 80: no sections; rules between the feeds
- **From you:** sections are complexity a 30-minute paper of 8 articles doesn't need; take them out, and bring them back if they're missed. 50+ feeds without rules between them would be overwhelming.
- **Shipped:** the paper is one list in your sources' order, with no headings, the reading list's "Saved for later" included; there's no Section setting, OPML folders are ignored on import and not written on export. The feeds under tt-rss have an inset rule between each. Database version 7 simply doesn't have sections.

### Cycle 79: your tt-rss feeds on Sources, each with a page
- **From you:** build the rest of "sources carry, publications write", through to the inset feeds on Sources.
- **Shipped:** the article text you choose and the cap now belong to each publication, so a tt-rss feed can have its own ("at most 2 from Morning Wire"). Once a day tt-rss is asked for its whole feed list, read feeds included, with addresses and categories. On Sources, tt-rss is last, with a ▾ button that shows its feeds just beneath it, each opening a page like a feed's own, and a Left out list. The same site on the phone and in tt-rss says so on both rows. Database version 7.
- **Left for later:** "Nothing new in 3 weeks" for quiet feeds (tt-rss's feed list says when it last fetched a feed, not when it last posted).

### Cycle 78: left out moves onto the publication
- **From you:** go with "sources carry, publications write": settings about the writing belong to the publication, settings about the connection to the source.
- **Shipped:** the first step. Leaving a tt-rss feed out is now a flag on its publication, beside what the text check learned, instead of a table of its own. Nothing changes on screen. Database version 7.
- **Review caught:** signing in again after the phone lost the password's key (same user, same server) wiped every left-out feed, and would now wipe what each feed had learned too. The saved address and username are now compared, which stay readable without the password.

## Day 5 · Fri 2 Oct

### Cycle 77: article text, checked and per publication
- **From you:** Automatic should look at the page now and then before deciding a feed is full text; tt-rss feeds should each learn on their own; count pictures, not just words.
- **Found:** a long item (300+ words) counted as proof the feed was full without its page ever being read, so a feed of long teasers settled on "Full articles". tt-rss feeds learned nothing at all.
- **Shipped:** what the check learns lives per publication (a feed here, or one feed in tt-rss), moved from the source row. Each edition reads the pages of up to 5 long items, one per publication, while it's still working out or every 14 days once settled on the feed; a teaser found that way gets its page in that edition. A page with pictures the feed's copy lacks counts for the page.

## Day 4 · Thu 1 Oct

### Cycle 76: what tt-rss is as a source, explored
- **From you:** each tt-rss feed as its own source? add what you subscribe to here back to tt-rss? does either clutter?
- **Did:** competitors, today's screens and clickable proposals, each critiqued by the personas over several rounds; in [docs/research/ttrss-backend/](research/ttrss-backend/index.html). Recommended: tt-rss stays one row with its feeds inset under it, folded; each feed learns its article text (cycle 77); sections and balance later; adding to tt-rss only by hand for now.
### Cycle 75: a tidier Settings page
- **From you:** a pass over Settings, to clean it up.
- **Shipped:**
  - Your e-reader comes before Delivery, which depends on it.
  - The share tip sits under "Send it myself", and only shows when that's chosen.
  - Every delivery warns when notifications are off.
  - A folder the app can no longer reach says so, and tapping it picks it again.
  - The two folder buttons say which folder they change.
  - "Source" instead of "site" throughout.
  - At large text, the per-source buttons go under the words.
- **Review caught:** tapping an unreachable notes folder's row turned notes off, despite its line saying "choose it again". Fixed, with a separate Turn off.

### Cycle 74: editions emailed straight to your Kindle ([#123](https://github.com/Seafloor-Studio/newspaperss/issues/123))
- **From you:** sharing to the Kindle app put editions in the library but not on the Kindle; the Kindle app's send screen turned out to offer library or device, not both, and it still errors on a cold first tap.
- **Shipped:** "Email it to your Kindle" delivery. Set your Kindle's own address and a mail app once; Send opens that app with everything filled in and counts as sent. Onboarding offers it for Kindle readers; Settings › Delivery has it first. "Ask each time" or a missing app falls back to the share sheet.
- **Review caught:** the notification's Send used an address from when it was posted, overlapping share sheets overwrote each other, hints and error messages that were wrong in the fallback, a blank address falling back silently, and an address field that went red as you typed. All fixed.

### Cycle 73: skipping paid posts with nothing free
- **From you:** detect premium posts with no content at all, and let a source opt in to skipping them.
- **Shipped:** a paid post with under 50 words free (a title and a picture) is remembered when an edition tries it. Its source's page then offers "Skip paid posts with nothing free", off until you turn it on. Skipped posts read "Skipped: a paid post", aren't fetched again, and are counted on the page. A starred one still goes in.

### Cycle 72: the platform pass, round 1
- **From you:** test against the big blog and newsletter platforms, from their own feeds and through tt-rss.
- **Built:** a corpus of 40 real posts from Substack, Ghost, WordPress, Medium, Blogger, Buttondown and Micro.blog, with their words scrambled. Each one is run through tt-rss's own sanitizer as well. A test runs the extractor over every post both ways.
- **Found and fixed:**
  - Pictures through tt-rss came out at a third of the size.
  - Paid posts weren't told apart: Substack's "Read more" and paywall pitch reached the book, and a Ghost paid post was a picture and a sentence. They now say they're the free part.
  - Substack's subscribe box got through tt-rss.
  - Videos and YouTube players vanished, leaving their captions.
  - Medium's tracking pixel got in through tt-rss.
  - Newsletter sign-up boxes and comment-form labels got in.
- **Not yet:** Tumblr turned the collector away (429); galleries, tweets and beehiiv are for round 2.

### Cycle 71: five small asks
- **From you:** the backlog's quick ones, after the README catch-up.
- **Shipped:** "It can take a few minutes to show up" after a send with the Kindle app; a text size for the article preview; comics in the preview fill the width and pinch to zoom; xkcd's hover text as a caption in the book; sync errors on a source's page in plain words.
- **Review caught:** captions put in the middle of a line of text, every figure stretched in the preview, advice to re-add a built-in list, and the Kindle note coming back after Mark as not sent. All fixed.

### Cycle 70: each article says where it stands
- **From you:** option B's plain lines from the read/unread mockups ([#111](https://github.com/Seafloor-Studio/newspaperss/issues/111)), with days left on waiting ones.
- **Shipped:** a source's rows end with "Waiting, 3 days left", "In your next edition", "In Thursday's edition", "Sent Wednesday", "Read" or "Not picked". The reading list uses the same words, without days left.

## Day 3 · Wed 30 Sep

### Cycle 69: read rows step back (21:50–22:05)
- **From you:** after the read/unread mockups ([#111](https://github.com/Seafloor-Studio/newspaperss/issues/111)), dim the rows already read.
- **Shipped:** on a source's page, read, delivered and too-old titles are dimmed, lighter in weight as well as colour so it shows on e-ink. Starred ones stay full strength.

### Cycle 68: what reading an edition on the Kindle turned up (20:10–21:00)
- **From you:** a quote missing after "…from *South Park*:", footnotes that don't work, "Next" feeling unneeded between short articles, and the closing page hard to reach.
- **Found:** the quote was a Substack Note, an embed the page fills in with script, dropped as an empty "comment" box. Footnotes broke in tt-rss, which strips ids and points "#footnote-4" at the site's address.
- **Shipped:** Notes become quotes with their author (from Substack's feed; tt-rss strips their text, now in the backlog), footnotes are paired up again by number so they work, "Next" only after reads of 5 minutes or more, and "That's all for today" is a chapter in the contents.
### Cycle 67: read and unread in step with tt-rss (19:00–19:45)
- **From you:** the newest change should win, checked by reading back from tt-rss; a star here should win over a read in tt-rss (your tt-rss marks read on opening); one switch, "Sync read status with tt-rss".
- **Shipped:** each sync sends the app's changes, reads them back, then brings in what changed in tt-rss: read there while waiting here leaves the paper, unread there after it went out comes back. Stars and unsent editions aren't touched. Off, nothing flows either way.
- **Cost:** a light headline list per feed only where the regular fetch can't tell, without article text.

### Cycle 66: read and unread, one tap each (18:15–18:45, mockups then option B)
- **From you:** three states in a row (★, ●, ○) and a wish to mark one unread, including a sent one, without putting it first in line. You compared mockups: one button that cycles (A), two toggles (B), or words in a menu; you picked B to try, without outlines.
- **Shipped:** on a source's page the status mark is a toggle. ● → ○ marks read; ○ or ✓ → ● marks unread, which puts it back with the waiting ones (★ is still "next edition"). A second tap undoes it, so there's no Undo. tt-rss hears of both at the next sync. An article marked unread counts as found again, so it isn't expired at the next sync.
- **Also:** the line above the list says what the mark and ☆ do; TalkBack gets the mark as its own button.

### Cycle 65: a send that didn't arrive can be undone (17:15–18:10)
- **From you:** a Send to Kindle that failed still marked the edition sent, so its articles were used up and marked read in tt-rss, with no way back.
- **Shipped:** **Didn't arrive? Mark as not sent** on a sent edition, on Today and its page, after a dialog that names it. It's ready to send again: its articles go back into it with the stars they went in with, its links stop counting as delivered, and its tt-rss articles are marked unread on the server. Send it again, or the next edition takes them.
- **tt-rss:** the mark-read work now looks at the edition when it runs and marks read or unread to match, so a late retry can't undo a newer change.
### Cycle 64: no doubled gaps around screens (17:35–17:50)
- **From you:** a screenshot of an edition page: why so much space?
- **Cause:** the tabs pad every screen for the status bar and the gesture bar, and each screen's own top bar and frame padded for them again: an empty status bar's height above the title, and a gesture bar's height above the tabs.
- **Shipped:** the screens see those bars as already handled. A test gives the phone a status bar and checks the title sits just under it once (it sat a second bar lower before the fix).

### UX pass (13:00–15:00, afternoon)
UX design only, no new features. Twelve patterns listed, four research sweeps (onboarding, delivery and hand-off, lists and destructive actions, settings with accessibility and e-ink), then one PR per fix, each reviewed. The options considered and what was decided for every pattern are in the session's UX report page. Pattern by pattern:
- **Today** ([#81](https://github.com/Seafloor-Studio/newspaperss/pull/81)): once an edition exists, "Make another edition" is a quiet link, not the filled button; a failure reads before its Try again, and there's one retry per failure; "N starred articles are waiting for your next edition". Review caught two retry buttons in the most common failure, a retry beside Send, and a first fix that would have told TalkBack "Your edition is ready." for a failure.
- **Edition page** ([#83](https://github.com/Seafloor-Studio/newspaperss/pull/83)): Delete edition moved into a ⋮ menu, away from Notes; the dialog names the edition and answers Delete edition / Keep; "You starred it" instead of a bare "Starred" beside an empty ☆.
- **Settings** ([#84](https://github.com/Seafloor-Studio/newspaperss/pull/84)): rows at least 48dp, headings, the size slider says minutes in 5-minute stops, − and + say what they change.
- **Sources** ([#85](https://github.com/Seafloor-Studio/newspaperss/pull/85), [#89](https://github.com/Seafloor-Studio/newspaperss/pull/89)): Remove source in the ⋮ menu on a source's page too, and the dialog answers Remove source / Keep. A note explaining "Mark 2 as read" with 3 chosen was tried and dropped after review.
- **Notifications** ([#86](https://github.com/Seafloor-Studio/newspaperss/pull/86)): a sent edition's Ready notification comes down.
- **Adding a source** ([#87](https://github.com/Seafloor-Studio/newspaperss/pull/87)): errors say what the answer means and what to try, and the dialog scrolls.
- **Reading list** ([#88](https://github.com/Seafloor-Studio/newspaperss/pull/88)): ✕ offers Undo, and says which link. Reviews sank a delete-and-restore design; the link is now hidden while Undo is offered and deleted after.
- **Article preview** ([#90](https://github.com/Seafloor-Studio/newspaperss/pull/90)): "Opening…" instead of an animated bar, and long titles end in an ellipsis.
- **Onboarding:** no change. An "Add all" button for starter packs was tried and dropped: at large text it broke pack names mid-word and lost TalkBack's checked state.
- **Round two** ([#91](https://github.com/Seafloor-Studio/newspaperss/pull/91)–[#94](https://github.com/Seafloor-Studio/newspaperss/pull/94)): a fresh visual audit of every screen after round one found twelve more. Fixed: outlined buttons you can see on e-ink (they were a 1.2:1 line on Today's card), a filled Add a source, "sent" for editions and "Delivered" for articles, one waiting phrase, "Ready by" in Settings, a calm message for an edition that wasn't marked sent, "nothing new since your last edition", Make another edition always below the latest, and the per-site buttons on their own line at large text. Left, with reasons in the report: the disabled − and Add, the "Mark 2 as read" count, the Settings gutter, heading styles.
- **Also:** two flaky tests fixed at the root (a settings store outliving its folder, #82; screen tests closing their database under a live screen, #89), and the pass now uses a branch per PR, so PRs no longer queue behind each other.

### Cycle 63: Send to Kindle can read the book (14:25–14:45, [#82](https://github.com/Seafloor-Studio/newspaperss/pull/82))
- **From you:** the first Send to the Kindle app errors (not when Kindle is already open); the second gets through the form and bounces back, but nothing arrives, in the library or in Content and Devices, and no email.
- **Likely cause:** a share lets only the receiving screen read the file. Send to Kindle reads it for the form, then uploads after the form closes, when it can't any more, and fails without a word.
- **Shipped:** the Kindle app may read the edition before the share sheet opens, and the app picked may read it until the phone restarts. **Confirmed by you:** the edition now reaches the Kindle.

### Cycle 61: older editions under their title (09:40–10:05, [#79](https://github.com/Seafloor-Studio/newspaperss/pull/79), reverted in [#80](https://github.com/Seafloor-Studio/newspaperss/pull/80))
- Shared editions made before cycle 59 under their title too. You'd rather make the day's edition again than carry code for a handful of old files, so it's reverted.

### Cycle 60: the author in Send to Kindle (09:10–09:30, [#78](https://github.com/Seafloor-Studio/newspaperss/pull/78))
- **From you:** the author should be newspapeRSS. The book already says so, but the Kindle app's form fills in the Amazon account's name, and nothing an app sends changes it (Amazon reads the book's author only for emailed documents).
- **Shipped:** the Kindle tip, in onboarding and Settings, says to change Author to newspapeRSS in the form, so editions sit together in the library.

### Cycle 59: Send to Kindle gets the edition's title (08:30–08:45, [#77](https://github.com/Seafloor-Studio/newspaperss/pull/77))
- **From you:** Send to Kindle's form said "edition-3" as the title.
- **Cause:** the Kindle app titles a document after the shared file's name, and editions were stored as `edition-<id>.epub`.
- **Shipped:** new editions are stored under their title ("Wednesday Morning Edition, Sep 30.epub"), so that's what the Kindle library shows. Editions made before the update keep their old name.

### Cycle 58: Arts & Letters Daily picks get their titles (07:40–, this PR)
- **From you:** the source's page listed its picks as nplusonemag.com, wsj.com, english.elpais.com.
- **Cause:** the list gives a teaser and a link, no headline, so picks were stored untitled; the page's title was only read when an edition fetched it.
- **Shipped:** after each sync, a curated list's untitled picks go to the same background lookup saved links use, which reads each page's title.
- **Review caught:** a bot-check page's title ("Client Challenge") would have stuck as the headline, and the edition prefers a stored title; picks that never get a title (a paywall, a PDF) would have been fetched every sync (now for two days); a failure to schedule the lookup could have marked a good sync as failed.

## Night 2 · Tue 29 Sep, 20:30 PT – Wed 30 Sep, 05:45 PT

### Cycle 57: a slow tt-rss server says so (02:37–02:49, [#73](https://github.com/Seafloor-Studio/newspaperss/pull/73))
- **From the persona audit:** a home server on a slow line timed out and the app said "Couldn't reach tt-rss", sending you to check a connection that works.
- **Shipped:** a slow answer now says tt-rss took too long: on the source (with "it'll be tried again at the next sync", which is true there), when signing in, choosing a category and marking read. Start fresh says the server may still be working through it.
- **Review caught:** the retry promise shown where nothing retries (start fresh, categories, the mark-read note); a connect timeout, which means an unreachable server (a VPN off, a wrong address), taken for slowness.

### Cycle 56: leave a tt-rss feed out (02:15–02:37, [#72](https://github.com/Seafloor-Studio/newspaperss/pull/72))
- **From the persona audit:** a noisy feed (a live blog, press releases) kept taking a slot, and the only fix was on the server.
- **Shipped:** "Feeds in your paper" on the tt-rss source's page: a checkbox per feed. A left-out feed isn't fetched and its waiting articles go, except starred ones. Database version 5.
- **Review caught:** a choice carried over to another tt-rss user on the same server; left-out articles still shown as waiting; feeds from outside the chosen category listed for ever, under their alphabetically last name instead of their latest; a promise that a star would still bring one in, when nothing new is fetched to star.

### Cycle 55: no Open for Kindle and Kobo (02:05–02:15, [#71](https://github.com/Seafloor-Studio/newspaperss/pull/71))
- **From the persona audit:** Today and the edition page offered Open to a Kindle owner, which opens the book on the phone, the one thing the app tells her not to do.
- **Shipped:** Kindle and Kobo readers see Send (and Send again) without Open. Boox readers still get Open first; everyone else keeps both.

### Cycle 54: tt-rss, start fresh (01:43–02:04, [#70](https://github.com/Seafloor-Studio/newspaperss/pull/70))
- **From the persona audit:** a reader back after a break sees thousands unread in tt-rss, and the paper only ever uses up a few a day.
- **Shipped:** "Back after a break?" on the tt-rss source's page: after asking, it marks everything that reached tt-rss more than two weeks ago read there (in the chosen category, if there is one). Servers older than API level 15 (2020) would ignore the two weeks and mark everything read, so the app refuses and says so.
- **Review caught:** clearing the app's waiting articles by publication date didn't match tt-rss's "received" date, so a backdated article could vanish here while staying unread there for good (now left to expire as usual); a login saved for another server could have caught up the wrong account; the notice claimed all of tt-rss when only a category was caught up; "Starred articles stay starred" read as if they were spared.

### Cycle 53: a question at the end of the paper (01:27–01:42, [#69](https://github.com/Seafloor-Studio/newspaperss/pull/69))
- **From the persona audit:** the book's closing page could ask a question, but the app never gave it one.
- **Shipped:** each edition ends with one of twelve open questions ("What surprised you?", "Which piece was hardest to put down?"), and its notes show the same one above the prompts, so what you turned over on the Kindle is waiting in Obsidian.
- **Review caught:** questions that assumed opinion pieces ("Whose point of view was missing?") don't fit a one-article or all-comics paper, so the list was rewritten; "you" questions mixed into the notes' "I" prompts; a blank question printed as "Untitled"; tests that couldn't catch a duplicated question; a claim that consecutive papers never repeat, which empty builds make untrue.

### Cycle 52: reading notes, into your vault (01:10–01:27, [#68](https://github.com/Seafloor-Studio/newspaperss/pull/68))
- **From the persona audit:** notes were only saved beside editions with folder delivery, so sharing to the Kindle app meant exporting them by hand every day.
- **Shipped:** Settings › Reading notes takes a folder (your vault); each edition's notes are saved there once it's delivered, however it was sent, in the background.
- **Review caught:** a folder copy finishing after you'd tapped Sent delivered the edition twice and saved "notes (1).md"; the Notes button and the background save rewrote the same file, so a half-written copy could reach the vault; a worker test that never reached the code it named.

### Cycle 51: article rows, a star and Select (23:41–01:02, [#66](https://github.com/Seafloor-Studio/newspaperss/pull/66))
- **From you:** the labelled buttons under every row felt heavy on such a small row.
- **Design audit first:** how Gmail, Feedly, Pocket, NetNewsWire, Readwise Reader and e-reader lists do it, and four options checked against five readers (you over breakfast, a Boox, TalkBack, 200% font, a first-timer). A trailing star plus a Select mode won.
- **Shipped:** one star at the right of each row; "Mark as read" for several at once in Select (long-press or the Select button), with one Undo; a TalkBack action per row; the same star on delivered editions. A 30-row list is about five screens instead of seven, and nothing moves on e-ink when selection starts or ends.
- **Review caught:** tests covering a path the app no longer used; the list shifting when selection ended at the bottom; untested take-out and notice paths; the helper line changing height; the count not announced; the snackbar covering the bar's buttons.
- **CI:** a settings test the PR doesn't touch failed once on a DataStore file rename, then passed on re-run. It stays on the watch list: if it fails again, each test's DataStore gets its own scope, closed at teardown.

### Cycle 50: Longreads, the story not the teaser (23:47–00:50, [#65](https://github.com/Seafloor-Studio/newspaperss/pull/65))
- **From you:** does Longreads work? Its feed does, but most posts are 200-word picks linking to the story elsewhere.
- **Shipped:** a general rule, not a Longreads one: a short post with exactly one link to another site tagged with the post's own name (`?src=longreads`) points at a story, so the edition fetches the story, bylines its own author and credits "Equator via Longreads". Tracking tags are stripped from stored links, so a story two lists picked goes out once.
- **Review caught:** the Longreads editor bylined as the author; links delivered before the update coming back; short blog commentary swallowed by the page it links to (now the titles must match); a tt-rss copy of a delivered story delivered again; a rejected post using up, and marking read in tt-rss, the page it linked to.

### Cycle 49: a flaky preview test (00:05–00:12, [#64](https://github.com/Seafloor-Studio/newspaperss/pull/64))
- **Shipped:** the preview sets its loaded state on the main thread; under the test dispatcher it could resume on an IO thread.

### Cycle 48: backlog notes (23:55–00:00, [#63](https://github.com/Seafloor-Studio/newspaperss/pull/63))
- **From you:** a Kill the Newsletter inbox the app makes itself; link posts.

### Cycle 47: a coverage floor (23:25–23:52, [#62](https://github.com/Seafloor-Studio/newspaperss/pull/62))
- **From you:** did coverage get a floor? Now it has: CI fails below 90% of lines (92.5% today).

### Cycle 46: published dates on a source's page (23:28–23:44, [#61](https://github.com/Seafloor-Studio/newspaperss/pull/61))
- **From the persona audit:** a tt-rss backlog arrives at once, so every row showed the day it was fetched.
- **Shipped:** rows show and are ordered by when they were published, with the year when it isn't this one.
- **Review caught:** rows ordered by fetch time looked jumbled once dates showed; feed dates in the future or in 2099 would have shown as they were.

### Cycle 45: the preview in dark mode (23:05–23:20, [#59](https://github.com/Seafloor-Studio/newspaperss/pull/59))
- **From device testing:** with the app in dark mode, the article preview stayed black on white.
- **Shipped:** the preview gives each page the app theme's colours (the book leaves colours to the e-reader), and starts dark so it doesn't flash white.
- **Review caught:** a page reached by "Next" came unstyled, black on near-black; the style now goes on every book page the preview serves, after the book's stylesheet.

### Cycle 44: every source gets its turn (22:45–23:12, [#58](https://github.com/Seafloor-Studio/newspaperss/pull/58))
- **From the persona audit:** the take-turns start slid by one feed per edition, so with 60 tt-rss feeds and room for 8, seven of yesterday's eight came back, and a quiet feed's article expired before its turn.
- **Shipped:** turns start with the source featured longest ago; delivered editions break ties; starred articles and editions never sent don't count as a turn.
- **Review caught:** without the tie-breaker, when most sources fit, the same few went in every edition (20 appearances against 10); a star used up its source's turn; no test covered tt-rss feeds or unsent editions.

### Cycle 43: stars and Mark as read (21:42–22:57, [#57](https://github.com/Seafloor-Studio/newspaperss/pull/57))
- **Decided with you:** tap opens the original; `☆ Next edition` puts an article in the next paper; "Mark as read" keeps it out, with Undo. Three design passes first (light, dark, e-ink, 200% font).
- **Shipped:** stars replace bring back as one flag and go first across *all* sources (bring back only jumped its own source's queue, a persona-audit bug); delivery clears stars on every copy of a link; an unsent edition gives articles back their exact earlier state; Mark as read reaches tt-rss from the database at the next sync; database version 3.
- **Review caught:** Mark as read during a build was silently undone (now held while an edition is being made); marked articles outside tt-rss's five-per-feed window never reached the server; a re-saved link could be dropped on release; a link could go out twice through a starred copy; a stale Undo could come back; the Today count double-counted. A second look caught a build that could stay stuck "being made" and a hold that never expired on an open screen.

### Cycle 42: tt-rss asks which articles (22:37–22:47, [#56](https://github.com/Seafloor-Studio/newspaperss/pull/56))
- **From the persona audit:** a returning reader's first paper came from all unread, mostly news; the category choice was only on the source's page.
- **Shipped:** after signing in, "Which articles?" (all unread, or one category); the account is saved only on Add.
- **Review caught:** the first version saved the account at sign-in, so backing out or a sync during the question could leave it half-set or lose articles for good. Redesigned to save nothing until Add.

### Cycle 41: the EPUB, Kindle first (21:41–22:28, [#55](https://github.com/Seafloor-Studio/newspaperss/pull/55))
- **Found by the design passes:** Kindle drops `body >` rules, which held nearly all the book's styling; headlines came out justified with wide gaps.
- **Shipped:** styles by class; left-aligned headings; a cover whose date you can read in the library; the book opens at the contents; contents entries point at articles (an epubcheck warning); a closing page; no "0 min" for comics.
- **Review caught:** the in-app preview lost its margins; a comics-only section said "0 min"; `text-align: start` might justify on Kindle; right-to-left captions and quotes fought the new rules.

### Cycle 40: notes for Obsidian (22:05–22:20, [#54](https://github.com/Seafloor-Studio/newspaperss/pull/54))
- **From the persona audit:** five prompts under every article read as homework, and Obsidian's properties got nothing.
- **Shipped:** front matter (date, edition, sources, a tag) and three prompts once at the top.
- **Review caught:** a stray control character in a title broke the front matter in strict YAML parsers.

### Cycle 39: tt-rss, a few from every feed (21:50–22:05, [#53](https://github.com/Seafloor-Studio/newspaperss/pull/53))
- **From the persona audit:** one call for the newest 200 unread let busy news feeds crowd out monthly essays.
- **Shipped:** up to five unread from each feed, newest first.
- **Review caught:** subcategories came back as items and were fetched as unrelated feeds; one failing feed failed the whole sync; "newest five" were really the highest-scored.

### Cycle 38: a daily live check of the curated lists (21:41–21:48, [#51](https://github.com/Seafloor-Studio/newspaperss/pull/51))
- **Why:** the unit tests read pages saved on the day each parser was written, so they couldn't see Arts & Letters Daily change. Coverage wouldn't have helped either: the parser was covered.
- **Shipped:** a scheduled CI job reads each curated list's real page every morning and fails, emailing the owner, when a parser no longer finds its three links. Ordinary builds skip it.

### Cycle 37: Arts & Letters Daily's nested paragraph (21:20–21:41, [#50](https://github.com/Seafloor-Studio/newspaperss/pull/50))
- **From device testing:** the source said "This page has changed its layout… took none".
- **Cause:** the live page nested a `<p>` in a teaser; the HTML parser splits that, leaving the "more »" link outside any paragraph.
- **Shipped:** the newest entry is read from the column header up to its "more »" link, still stopping at a divider or a second paragraph so an older pick is never taken.
- **Review caught:** the first version's test never ran the new path (a plain space where the saved page has a non-breaking one); without a divider, a first entry that lost its link fell through to yesterday's pick; a teaser link starting with "more" could be taken for the more link; ad text could leak into the teaser. All fixed, with tests.

### Cycle 36: MIT license, and the article row decided (21:08–21:18, [#49](https://github.com/Seafloor-Studio/newspaperss/pull/49))
- **Shipped:** the MIT license. In the backlog, the design decided with you: tapping an article opens it; `☆` puts it in the next paper; "Mark as read" keeps it out; stars count toward each source's cap and the paper stays capped.

### Cycle 35: backlog decisions (20:48–20:54, [#48](https://github.com/Seafloor-Studio/newspaperss/pull/48))
- **Shipped:** generated summaries decided against (the paper gives whole articles); the iOS app and local news parked; translation by people, not a machine, with what that needs first.

## Day 2 · Tue 29 Sep, 11:47–19:30 PT

### Cycle 34: "all already read" (18:27–18:36, [#46](https://github.com/Seafloor-Studio/newspaperss/pull/46))
- **From #45's review:** an export of only archived links said "Added 500 links from Pocket." while onboarding still wouldn't go on, with no hint why.
- **Shipped:** it says "Added 500 links from Pocket, all already read."

### Cycle 33: saved links in onboarding (18:14–18:27, [#45](https://github.com/Seafloor-Studio/newspaperss/pull/45))
- **From the persona audit:** someone leaving Pocket couldn't finish onboarding with saved links alone: Next wanted a feed, and the Pocket import was three screens deep in Sources.
- **Shipped:** the sources step has "Leaving Pocket or Instapaper? Import your saved links", and links waiting in the reading list are enough to go on.
- **Review caught:** after process death, finishing could read the saved-link count before it had loaded and do nothing; it now reads it from the source. The import's message hid "That's enough to start"; both show now. The "unread only" count moved into the reading list, tested with a Pocket export that mixes archived and unread links.

### Cycle 32: CI timeout, report pages (18:06–18:14, [#44](https://github.com/Seafloor-Studio/newspaperss/pull/44))
- **Shipped:** a 30-minute timeout on CI (a hung test would otherwise hold a PR for six hours), and the Day 2 report and retro pages.

### Cycle 31: no feed? Save the page instead (17:53–18:06, [#43](https://github.com/Seafloor-Studio/newspaperss/pull/43))
- **From the persona audit:** pasting a site with no feed ended at "No feed found at …", a dead end.
- **Shipped:** when an article's page loads but its site has no feed, Add a source offers "Save this page to your reading list instead". An address that can't be reached isn't offered.
- **Review caught:** a site's front page was offered too, the most common case, and would make an edition of navigation; so would a paywall's sign-in page a redirect landed on. Only an article-like page on the same site is offered now.

### Cycle 30: change your e-reader later (17:42–17:53, in [#42](https://github.com/Seafloor-Studio/newspaperss/pull/42))
- **From the persona audit:** the e-reader picked in onboarding couldn't be changed; a Kobo owner who got a Boox had to reinstall.
- **Shipped:** Settings has "Your e-reader", with the same list and tip as onboarding. It decides whether editions offer Send or Open.

### Cycle 29: a deleted edition's notification (17:35–17:45, in [#42](https://github.com/Seafloor-Studio/newspaperss/pull/42))
- **From the backlog (tech debt):** deleting an edition left its "ready" notification up, and its Send would share a file that no longer exists.
- **Shipped:** the notification carries its edition's id, and deleting that edition takes it down. Only if it's still about that edition: the slot is shared, and a newer edition's news stays.

### Cycle 28: say why there's no edition (17:24–17:53, [#42](https://github.com/Seafloor-Studio/newspaperss/pull/42))
- **From the persona audit (every persona):** a timed run that found nothing new sent no notification, so the paper just didn't come; when every source failed, Today said "Nothing new to read yet"; offline, a queued build said "Checking your sources…" indefinitely.
- **Shipped:** a timed run with nothing new sends a quiet "No new edition: nothing new to read since your last one." When nothing is new because every source failed, it's a failure that says so ("None of your 9 sources could be read. Sources shows what went wrong with each."), loud on a timed run and shown on Today. A build queued without a connection shows "Waiting for an internet connection…".
- **Review caught:** "check your connection" blamed the wrong thing (a timed run only starts once connected, so it's usually a tt-rss sign-in, a moved feed or a changed list page); the message now points to Sources. A timed run that reads no source is retried twice, five minutes apart, before the reader hears of it. "No new edition" no longer arrives minutes after a paper made by hand, and says "add a few sources" when there's never been one. Today's connection check now wants a working connection, as WorkManager does (a captive portal isn't online), is debounced so a flaky signal doesn't flip the status line, and can't crash on the Android 11 builds whose network callback throws. A second look caught Today saying "Checking your sources…" through the retry wait, with nothing running; it now says "Couldn't read your sources. Trying again at 6:20."

### Cycle 27: Le Monde's bot check (17:18–17:24, in [#41](https://github.com/Seafloor-Studio/newspaperss/pull/41))
- **From a live edition:** Le Monde articles came out as "A required part of this site couldn't load": the app fetched Fastly's bot challenge ("Client Challenge", a 3 KB page served with a 200) and took it for the article.
- **Shipped:** the challenge is recognised, so the feed's text is used with a note, and the source learns the site blocks fetching.
- **Checks:** a live Le Monde edition has the feed's text and the note, and no challenge text.
- **Review caught:** from a server, Le Monde can also answer 402 "Accès restreint", which wasn't counted as the site refusing. It is now, like 401, 403 and 429.

### Cycle 26: Chinese and Japanese reading time (17:10–17:24, [#41](https://github.com/Seafloor-Studio/newspaperss/pull/41))
- **From the language work:** Chinese and Japanese have no spaces between words, so a whole NHK paragraph counted as one word: no reading time, and the full-text check would take a long Japanese feed for a teaser and fetch pages it didn't need.
- **Shipped:** one word count everywhere, which counts each Chinese or Japanese character as about 2/3 of a word (people read about 350 characters a minute, against 238 English words). Korean uses spaces and is counted by word.
- **Checks:** a live edition with BBC Chinese gives 3–7 minute articles instead of seconds, and passes epubcheck.

### Cycle 25: where things are tracked (17:05–17:10, in [#40](https://github.com/Seafloor-Studio/newspaperss/pull/40))
- **From your question:** onboarding items and the research were hard to find. The backlog was sorted by where each item came from, and the persona audits only existed as backlog bullets.
- **Shipped:** the backlog is grouped by part of the app (onboarding, sources, the book, delivery, reading list, accessibility, performance), each item tagged with its origin. The two persona audits are written up in [research/personas.md](research/personas.md), with each finding's status. The README has a short documentation index.

### Cycle 24: lighter extraction (16:54–17:10, [#40](https://github.com/Seafloor-Studio/newspaperss/pull/40))
- **From the resource audit:** the worst memory peak was a large page parsed and then copied whole for Readability.
- **Shipped:** scripts, styles and SVGs are dropped right after the page's JSON-LD is read, before the copy; on script-heavy sites they're most of the page. On a 2.6 MB test page that's 36 MB allocated before and 16 MB after, in half the time. Pages over 5 MB aren't parsed at all; the feed's text is used with a note.
- **Checks:** a live edition gives the same articles with the same word counts and passes epubcheck; xkcd, the New Yorker cartoon and Godslave all still get their image. The live-edition tool now prints each article's image count.
- **Review caught:** a page that fails for its own reasons (too large, no connection) was counted as "the feed's text is enough", so a source whose pages are 5–10 MB would have been switched to teasers for good. A failed page now counts as no evidence either way. Declarative shadow-DOM templates, which are shown on the page, are kept.
- **Left:** a saved link that can never be read (a PDF, a video, a huge page) waits silently, as it did before for anything over 10 MB. Putting it in the edition as a "couldn't fetch" page was tried and dropped: such pages cost no reading time, so a backlog of saved PDFs could fill an edition with them. The reading list should show these links as unreadable instead; that's in the backlog.

### Cycle 23: a documentation pass (16:45–16:54, in [#39](https://github.com/Seafloor-Studio/newspaperss/pull/39))
- **Why:** you asked for documentation passes in the cycles: readable, current, not onerous. CLAUDE.md now says so: behaviour changes update the docs in the same PR, and every few cycles a pass checks the docs against the code.
- **Shipped:** DESIGN.md rewritten to describe the app as it is. It had SMTP delivery, edition profiles, user-named sections and a reading-speed setting that don't exist, and nothing on today's changes: timed editions starting early, the feed cache and 12-hour sync, when an edition counts as delivered, housekeeping, comics, language tags, the preview. The finished milestone plan and history are gone (BACKLOG and this log have them), and it's shorter. README: the delivery rule, "ready by", the book's features, a link to the debug APK, and fresh screenshots. CLAUDE.md: database migrations and debug builds.
- **How:** an agent checked every statement in the three docs against the code and listed what was wrong or missing, with file references; I rewrote from that.

### Cycle 22: TalkBack and large fonts (16:32–16:54, [#39](https://github.com/Seafloor-Studio/newspaperss/pull/39))
- **Why:** the persona audit's TalkBack items, and you asked for an accessibility audit. These are the known gaps; the full audit is in the backlog.
- **Shipped:** Today announces what the build is doing ("Checking your sources", "Making your edition", and the result) through a live region. The running count isn't announced, or TalkBack would read every number. Onboarding's progress bar says "Step 2 of 3" instead of "66 percent". Earlier editions say what tapping does. Onboarding's Add and the reading list's Save moved below their text fields, where a 200% font can't squeeze out the space to type.
- **Review caught:** in Compose a live region announces when its text changes, not when it first appears, so the failure message and "Checking…" would have been silent. And if they had spoken, a failure WorkManager still remembered would have been read out every time Today opened. The status is now one always-present line whose text changes, and it's only live once a build has run while the screen is up. Onboarding's Add button stays put instead of being swapped for "Checking…" under TalkBack's focus. A second look found a new reader's very first "Checking…" still silent (the list rebuilt the panel when the first-edition prompt went away; items are keyed now), and success said nothing; it now says "Your edition is ready." Still to confirm with TalkBack on a device.

### Cycle 21: articles know their language (16:13–16:32, [#38](https://github.com/Seafloor-Studio/newspaperss/pull/38))
- **Why:** your note on languages. E-readers choose hyphenation, fonts and text direction from `xml:lang`, and every article was tagged English, so a French article was hyphenated with English rules and an Arabic one laid out left to right.
- **Shipped:** each article's headline and body are tagged with its language, and right-to-left languages get `dir="rtl"`. The text decides: the writing system for non-Latin scripts, and common words for English, French, German, Spanish, Italian, Portuguese and Dutch. The page's declared language breaks ties, and is kept when it agrees because it's more precise (pt-BR, Persian in Arabic script). Many sites declare the same language on every page, and a feed's text declares nothing. No database change.
- **Checks:** a live edition from Le Monde, Spiegel, El País, NHK, Al Jazeera Arabic, the Guardian and g1 tagged every article correctly and passes epubcheck. The Arabic page lays out right to left, with the English kicker and byline left to right.
- **Review caught:** ordinary Spanish and Italian news came out French: the word lists left out "la", so every "la" counted for French alone. The lists now include each language's most frequent words, and a shared word counts for much less. A page declaring Persian or Urdu site-wide could have turned an English caption right to left: Latin text now never takes a tag in another script. Galician read as Portuguese over the page's own "gl": a Latin language the lists don't know keeps the page's tag. Declared regions like "en-UK" (no such region) are dropped, a kanji-heavy Japanese page stays Japanese, and headlines in the contents and "Next" links are tagged too. A second look found Latin-script Serbian taken for Portuguese over the page's "sr-Latn", and Danish for English (the English list had "at" and "for"); both keep the page's tag now. A second live edition added Italian (ANSA) and Dutch (NOS); all eight languages are tagged right.
- **Found on the way:** NHK's Japanese articles count as "1 word" (reading time assumes spaces), and Le Monde serves a script wall the bot check doesn't catch. Both are in the backlog.

### Cycle 20: the first tap on an article (16:00–16:13, [#37](https://github.com/Seafloor-Studio/newspaperss/pull/37))
- **From device testing:** the first tap on an article seemed not to open it, and the article was a little wider than the phone.
- **Found:** the width came from this morning's EPUB, which printed the full original URL on its own line with no word breaking; the EPUB design round replaced both. A fresh live edition (11 articles, cover and contents) rendered at 360px wide has nothing wider than the screen. The tap wasn't being dropped: the preview read the EPUB during composition, and the first WebView of a session starts slowly, so the screen held still long enough to look ignored.
- **Shipped:** the preview appears at once with a progress bar, and the book is read in the background. One open zip serves the page and all its images, instead of reopening the zip for each one.
- **Review caught:** closing the book while the WebView was still reading an image could crash the app (ZipFile.close() ends the inflater mid-read and throws a NullPointerException), so reads now hold a lock that close waits for. The top bar had lost its fallback to the edition's name.

### Cycle 19: fewer downloads, fewer wakeups (15:44–16:00, [#36](https://github.com/Seafloor-Studio/newspaperss/pull/36))
- **Shipped:** a 10 MB HTTP cache, with every feed and page request revalidated (If-None-Match), so an unchanged feed answers 304 instead of downloading again (about 180 MB a month at 20 feeds). The background sync runs every 12 hours with the battery not low, instead of every 4: each edition syncs right before it's built.
- **Review caught:** OkHttp would serve a feed it judged "fresh" without asking at all (max-age, or a guess from Last-Modified), so a new post could miss an edition. Requests now ask for max-age=0, which forces the conditional request (no-cache would skip the cache altogether, as the test found). Re-applying the schedule with UPDATE on every start cost wakeups; it's a new name with KEEP.
- **Left:** tt-rss `sinceId`: a since-id cursor changes what "unread" returns, the trap rss-to-e-reader's #28 hit. In the backlog with that note.

### Cycle 18: resource audit, and housekeeping (15:30–15:44, [#35](https://github.com/Seafloor-Studio/newspaperss/pull/35))
- **Audit:** typical peak memory while building is about 80 MB, worst case about 220 MB (a 10 MB page parsed twice). Bitmaps live outside the Java heap on Android 8+, and the EPUB is written straight to disk. So **streaming images to disk isn't worth it** (at most 15 MB) and is dropped. The real costs were elsewhere.
- **Shipped:** only the newest 14 editions keep their EPUB (5–12 MB each, gigabytes a year otherwise); articles a month past delivery drop their feed text but keep their row (the database would have outgrown Auto Backup's 25 MB in months, silently stopping backup); the image budget counts as spent once nothing more fits (before, every later article downloaded and discarded its images).
- **Review caught:** an empty image budget no longer counted as spent (a test caught it too), and the month was counted from discovery, so a long-saved link sent today lost its text at once.
- **Next from the audit** (in the backlog): a feed cache (about 180 MB a month of repeat downloads), a 12-hour background sync, tt-rss `sinceId`, and trimming the page DOM before Readability's clone.

### Cycle 17: the reading list (15:17–15:30, [#34](https://github.com/Seafloor-Studio/newspaperss/pull/34))
- **From device testing:** saved links showed only their site, with no reading time, and tapping one did nothing.
- **Shipped:** every saved link's page is looked up once: its title if it has none, and its length for "site · N min read". Rows open in the browser. Until a title is found, a row shows one made from the address instead of the bare domain.
- **The first migration:** the length is a new column, so the database goes to version 2 with a migration and a test that validates it against the exported schema. Your installed debug build upgrades in place.
- **Review caught:** the site shown twice on an untitled row; one page the extractor chokes on would cancel the queued lookups behind it. Both fixed.

### Cycle 16: the source page (15:14–15:17, [#33](https://github.com/Seafloor-Studio/newspaperss/pull/33))
- **From device testing:** the recent-articles list made you squint, and "Recent articles" didn't stand out; "Article text" didn't say what it set.
- **Shipped:** "Recent articles · N" as a real heading; a status mark per article in its own column (● waiting, ✓ delivered, ○ not used: shapes, not colours, for e-ink); medium-weight titles; inset dividers; "Article text: Automatic / Feed's text / Full page".
- **Review caught:** "Site's text" read as the web page (now "Feed's text"); TalkBack read the heading's middle dot aloud; faint dividers would vanish on a Boox's greys.

### Cycle 15: webcomics (15:07–15:14, [#32](https://github.com/Seafloor-Studio/newspaperss/pull/32))
- **From device testing:** God Slave, Namesake and Cursed Princess Club (Webtoons), on top of the New Yorker cartoon and xkcd.
- **Shipped:** the feed finder follows an ordinary link to a feed (God Slave's `/comic/rss`), shortest path first and never a comments feed; a page's own comic (`img#cc-comic`, `#comic`), every panel, beats the feed's thumbnail, captioned by the feed's text.
- **Checks:** God Slave and Namesake now give their full comic, xkcd its 2x image, the New Yorker its cartoon; a live edition passes epubcheck.
- **Left:** Webtoons. The app is redirected to its mobile site, which doesn't link the feed, and an episode is a strip of dozens of images. Parked in the backlog: vertical colour strips suit e-ink poorly.
- **Review caught:** the caption strip removed a `<figcaption>` (often the joke); only the first panel survived; a tag or comments feed could beat the site's. All fixed.

### Cycle 14: an app icon (14:55–15:06, [#31](https://github.com/Seafloor-Studio/newspaperss/pull/31))
- **From device testing:** the launcher showed Android's default icon, and you asked for something design-forward like the Termux shortcut's.
- **Shipped:** an adaptive icon: a cream newspaper page with a navy masthead, a red photo and grey columns, on navy. Themed icons use the notification icon's newspaper as one shape.

### Cycle 13: EPUB design, round 1 (14:41–14:55, [#30](https://github.com/Seafloor-Studio/newspaperss/pull/30))
- **From device testing:** the EPUB wasn't as good-looking as rss-to-e-reader's; links were too dark in KOReader's night mode; the in-app article was a little wider than the phone.
- **Shipped:** each article opens with its source as a small-caps kicker, the title, "By … · date · N min read" and a hairline rule; body text is justified and hyphenated; links and rules take the text's colour, so night mode inverts them too; "Read the original at propublica.org" replaces the full URL, which had nowhere to break; the contents page reads "In this edition" with plain titles and small source lines.
- **Checks:** rendered at e-reader size in Chromium, light and night, before and after; a live edition passes epubcheck.
- **Review caught:** the new styles weren't scoped, so an article's own `class="kicker"` would take the edition's look; contents entries had lost the underline that marks a link on e-ink. Both fixed.

### Cycle 12: see what's inside, delete an edition (14:24–14:40, [#29](https://github.com/Seafloor-Studio/newspaperss/pull/29))
- **From device testing:** you couldn't find what went into an edition before sending it (tapping the title worked, but nothing said so), and couldn't delete one.
- **Shipped:** a "See what's inside" button on the Today card, and Delete on the edition screen. An unsent edition's articles go into the next one; a sent one's stay used.
- **Review caught:** deleting freed the title, so a remake the same morning got the exact same title and Send to Kindle would drop it silently. The row now stays as a hidden "deleted" marker holding the title.

### Cycle 11: comics keep their image (14:12–14:24, [#28](https://github.com/Seafloor-Studio/newspaperss/pull/28))
- **From device testing:** the New Yorker's cartoon and a webcomic reached the edition without their images. Two causes:
  - xkcd-style feed items are just the image, and an item with no words was treated as empty;
  - on an image-only page the article extractor gave up and took the footer ("58 words" of legal links on the New Yorker's cartoon page).
- **Shipped:** an image-only feed item is kept, and wins over a page with little text and no image. When only the page has the image, its main image inside `<article>` becomes the post, captioned by the page's own article text or else the feed's description.
- **Review caught** (two rounds): the first version made any short, image-less page an image post, so a news brief could open with the site's share card or a headshot, a teaser's thumbnail could beat a short page, a paywall prompt could become the caption, and related-story cards could lend their thumbnails. It now needs strong signs: a feed item that's just the image, or page text that plainly isn't from the page's only article.
- **Checks:** the real New Yorker cartoon and xkcd now come through; a live edition passes epubcheck.

### Cycle 10: a one-source paper fills its time (14:02–14:12, [#27](https://github.com/Seafloor-Studio/newspaperss/pull/27))
- **From device testing:** a New Yorker-only edition held one comic. The per-site cap (1 by default) stopped it with the half hour unfilled. The cap is for fairness between sites, so once each has had its turn, what it held back fills the rest. A site's own number stays a hard limit, and its screen now says which it has.
- **Review caught:** the site screen still said "Up to 1 article", and a noisy site couldn't be held to 1.

### Cycle 9: which build is this? (13:50–14:01, [#26](https://github.com/Seafloor-Studio/newspaperss/pull/26))
- **From device testing:** every debug build said "0.1.0-debug", so feedback couldn't name one. CI builds now get the run number as their version code (each installs over the last) and `0.1.0-debug.<run>+<commit>` as their name, shown at the bottom of Settings.
- **Review caught:** release builds would have taken the run number too (F-Droid needs its own), and re-running an old CI run would have published a build phones couldn't install over a newer one.
- **Also:** your device-testing notes are in the backlog (a new "From device testing" list at the top, plus proposals for notes, multiple schedules and tt-rss categories as sections).

### Cycle 8: "ready" makes a sound (13:41–13:49, [#25](https://github.com/Seafloor-Studio/newspaperss/pull/25))
- **Shipped:** "Edition ready" has its own channel at default importance. It was silent, and many phones fold silent notifications away, but for share delivery it's the only prompt to send the paper. "Delivered" (folder delivery, nothing to do) stays quiet.

### Cycle 7: dated titles (13:31–13:41, [#24](https://github.com/Seafloor-Studio/newspaperss/pull/24))
- **Shipped:** "Tuesday Morning Edition, Sep 29". Without the date, every Tuesday's paper had the same name, so they collided in the Kindle and Kobo libraries and overwrote each other in a synced folder.
- **Review caught:** the title mixed the phone's language into "Morning Edition" (now English, like the rest of the book), and the Today card showed a second, sometimes different date (dropped).

### Cycle 6: persona audit, and sends that count (13:06–13:31, [#23](https://github.com/Seafloor-Studio/newspaperss/pull/23))
- **Audit:** walked the app as a Pocket refugee with a Kobo, a tt-rss + KOReader self-hoster, a casual Kindle owner, a Boox owner and a TalkBack user. Ten findings; the rest are in the backlog.
- **Shipped:** the worst one. Sending the daily paper from its notification was never recorded, so the next build marked it "Not sent" and its articles came back, every day. Now choosing an app in the share sheet marks the edition delivered, from the notification or the app, and opening it counts on a Boox. The "Did it reach your Kindle?" question, asked before Send to Kindle could have delivered anything, is gone.
- **Review caught:** a send and the morning build at the same moment could overwrite each other (a sent paper back to "Not sent", its articles repeated after tt-rss had marked them read); Open on the edition screen didn't count on a Boox; the opened book was missing from Recents. All fixed.
- **Friction:** FileProvider caches its paths in a static map, so one Robolectric test's files folder leaked into the next and broke an unrelated test. A test helper now clears it.

### Cycle 5: bring your reader into onboarding (12:53–13:06, [#22](https://github.com/Seafloor-Studio/newspaperss/pull/22))
- **From device testing:** onboarding had no way in for someone who already uses a reader. The sources step insisted on a starter feed; tt-rss and OPML were only in the Sources menu after setup.
- **Shipped:** "Already use a feed reader?" on the sources step, with Import an OPML file and Connect tt-rss. Either one is enough to go on.

### Cycle 4: timed editions start early (12:34–12:53, [#21](https://github.com/Seafloor-Studio/newspaperss/pull/21))
- **Shipped:**
  - A timed edition's timer fires 30 minutes before it's due. When Doze holds delayed work, the hold eats into that lead instead of making the paper late.
  - Editions are titled and dated for when they're due, not when they were started.
  - Two traps that would have built the paper twice: the early timer asking for "the next 6:30" gets today's again, and opening the app during the lead would re-arm the edition already building. The timer now carries its due time, and the scheduler remembers the last one it started.
- **Review caught:** a clock set ahead and then corrected would hold back editions; moving the time during the lead made a second paper; an edition due at 00:10 was titled for the day before, and then its title could be reused, which Send to Kindle drops. All fixed. Moving the time more than 30 minutes later during the lead can still make a "(2)" edition; left, as the PR explains.
- **Also:** the competitor research is in [docs/research/competitors.md](research/competitors.md). The headline: nobody else sizes the paper to a reading time; most rivals are paid, Kindle-only email digests. Its best ideas are in the backlog.

### Cycle 3: expedited builds, closed (12:28–12:33, [#20](https://github.com/Seafloor-Studio/newspaperss/pull/20))
- **Tried:** running scheduled builds as expedited work, so Android runs them promptly.
- **Review caught:** on Android 12+ expedited work has a quota, and a long build over it would be restarted silently. Keeping it running on older versions needs a foreground service, which Google Play makes you declare and justify.
- **Outcome:** closed and reverted. Cycle 4's lead time gets most of the benefit without either cost.

### Cycle 2: extraction fixes from the library review (12:15–12:27, [#19](https://github.com/Seafloor-Studio/newspaperss/pull/19))
- **Shipped:** the app gets the fixes the rss-to-e-reader review found in the same code:
  - A short "Further reading" list or a section titled "More on the method" is no longer removed.
  - A heading is never left without its list.
  - Screen-reader text that is an icon link's only label stays.
- **Checks:** a live edition from the starter feeds passes epubcheck with no junk left.

### Cycle 1: APK link, signing key, name (11:51–12:13, [#17](https://github.com/Seafloor-Studio/newspaperss/pull/17))
- **Shipped:**
  - Every merge to main publishes the debug APK to a rolling `latest-debug` release, with the fixed link above.
  - Debug builds use one committed signing key, so a newer build installs over an older one.
  - The app now shows itself as newspapeRSS.
- **Review caught:**
  - The release-writing token sat on the build job, where every Gradle plugin and dependency could use it.
  - Two quick merges could race and leave the link broken.
  - The debug build's name and the README missed the rename.
- **Friction:**
  - The debug APK was only uploaded for pull requests, so the first link I sent pointed at a run without one.
  - A review agent left my checkout on a detached HEAD, so the fix commit sat unpushed for a while. Review agents now get a throwaway checkout of their own.

### Also: five rss-to-e-reader bug-fix PRs (#24–#28)
Ported from the app, each with a test that fails on main, a review and a second look:
- **#24:** reading-time budget.
- **#25:** tt-rss password replay on redirect.
- **#26:** invalid captions.
- **#27:** screen-reader text and related-links boxes.
- **#28:** optional cross-collector dedupe. It's off by default, because it would interact badly with a since-id cursor.

## Day 1 · Tue 29 Sep, overnight (summary)

Sixteen PRs took the app from an empty repo to a working, tested app:
- **Sources:** starter packs, any site's feed, OPML, Tiny Tiny RSS, curated lists and a reading list.
- **Editions:** an edition sized by reading time, with a cover and contents, delivered by share sheet, folder or Boox.

A fresh-eyes review before each merge found real bugs in 11 of the 15 reviewed changes. A live edition
run through epubcheck found two more that tests and reviews missed. The full report and a per-cycle
retro are in the session's report pages.
