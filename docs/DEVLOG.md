# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **In flight:** comics and cartoons keep their image.
- **Next:** your device-testing list: edition preview and delete, an EPUB design pass (and the too-wide in-app article), the Sources page.
- **Waiting on you:** [#18](https://github.com/madCode/newspaperss/issues/18), a Dropbox app key for automatic Kobo delivery (optional). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Day 2 · Tue 29 Sep, afternoon

### Cycle 11: comics keep their image (14:12–)
- **From device testing:** the New Yorker's cartoon and a webcomic reached the edition without their images. Two causes:
  - xkcd-style feed items are just the image, and an item with no words was treated as empty;
  - on an image-only page the article extractor gave up and took the footer ("58 words" of legal links on the New Yorker's cartoon page).
- **Shipped:** an image-only feed item is kept, and wins over a page with little text and no image. When only the page has the image, its main image inside `<article>` becomes the post, captioned by the page's own article text or else the feed's description.
- **Review caught** (two rounds): the first version made any short, image-less page an image post, so a news brief could open with the site's share card or a headshot, a teaser's thumbnail could beat a short page, a paywall prompt could become the caption, and related-story cards could lend their thumbnails. It now needs strong signs: a feed item that's just the image, or page text that plainly isn't from the page's only article.
- **Checks:** the real New Yorker cartoon and xkcd now come through; a live edition passes epubcheck.

### Cycle 10: a one-source paper fills its time (14:02–14:12, [#27](https://github.com/madCode/newspaperss/pull/27))
- **From device testing:** a New Yorker-only edition held one comic. The per-site cap (1 by default) stopped it with the half hour unfilled. The cap is for fairness between sites, so once each has had its turn, what it held back fills the rest. A site's own number stays a hard limit, and its screen now says which it has.
- **Review caught:** the site screen still said "Up to 1 article", and a noisy site couldn't be held to 1.

### Cycle 9: which build is this? (13:50–14:01, [#26](https://github.com/madCode/newspaperss/pull/26))
- **From device testing:** every debug build said "0.1.0-debug", so feedback couldn't name one. CI builds now get the run number as their version code (each installs over the last) and `0.1.0-debug.<run>+<commit>` as their name, shown at the bottom of Settings.
- **Review caught:** release builds would have taken the run number too (F-Droid needs its own), and re-running an old CI run would have published a build phones couldn't install over a newer one.
- **Also:** your device-testing notes are in the backlog (a new "From device testing" list at the top, plus proposals for notes, multiple schedules and tt-rss categories as sections).

### Cycle 8: "ready" makes a sound (13:41–13:49, [#25](https://github.com/madCode/newspaperss/pull/25))
- **Shipped:** "Edition ready" has its own channel at default importance. It was silent, and many phones fold silent notifications away, but for share delivery it's the only prompt to send the paper. "Delivered" (folder delivery, nothing to do) stays quiet.

### Cycle 7: dated titles (13:31–13:41, [#24](https://github.com/madCode/newspaperss/pull/24))
- **Shipped:** "Tuesday Morning Edition, Sep 29". Without the date, every Tuesday's paper had the same name, so they collided in the Kindle and Kobo libraries and overwrote each other in a synced folder.
- **Review caught:** the title mixed the phone's language into "Morning Edition" (now English, like the rest of the book), and the Today card showed a second, sometimes different date (dropped).

### Cycle 6: persona audit, and sends that count (13:06–13:31, [#23](https://github.com/madCode/newspaperss/pull/23))
- **Audit:** walked the app as a Pocket refugee with a Kobo, a tt-rss + KOReader self-hoster, a casual Kindle owner, a Boox owner and a TalkBack user. Ten findings; the rest are in the backlog.
- **Shipped:** the worst one. Sending the daily paper from its notification was never recorded, so the next build marked it "Not sent" and its articles came back, every day. Now choosing an app in the share sheet marks the edition delivered, from the notification or the app, and opening it counts on a Boox. The "Did it reach your Kindle?" question, asked before Send to Kindle could have delivered anything, is gone.
- **Review caught:** a send and the morning build at the same moment could overwrite each other (a sent paper back to "Not sent", its articles repeated after tt-rss had marked them read); Open on the edition screen didn't count on a Boox; the opened book was missing from Recents. All fixed.
- **Friction:** FileProvider caches its paths in a static map, so one Robolectric test's files folder leaked into the next and broke an unrelated test. A test helper now clears it.

### Cycle 5: bring your reader into onboarding (12:53–13:06, [#22](https://github.com/madCode/newspaperss/pull/22))
- **From device testing:** onboarding had no way in for someone who already uses a reader. The sources step insisted on a starter feed; tt-rss and OPML were only in the Sources menu after setup.
- **Shipped:** "Already use a feed reader?" on the sources step, with Import an OPML file and Connect tt-rss. Either one is enough to go on.

### Cycle 4: timed editions start early (12:34–12:53, [#21](https://github.com/madCode/newspaperss/pull/21))
- **Shipped:**
  - A timed edition's timer fires 30 minutes before it's due. When Doze holds delayed work, the hold eats into that lead instead of making the paper late.
  - Editions are titled and dated for when they're due, not when they were started.
  - Two traps that would have built the paper twice: the early timer asking for "the next 6:30" gets today's again, and opening the app during the lead would re-arm the edition already building. The timer now carries its due time, and the scheduler remembers the last one it started.
- **Review caught:** a clock set ahead and then corrected would hold back editions; moving the time during the lead made a second paper; an edition due at 00:10 was titled for the day before, and then its title could be reused, which Send to Kindle drops. All fixed. Moving the time more than 30 minutes later during the lead can still make a "(2)" edition; left, as the PR explains.
- **Also:** the competitor research is in [docs/research/competitors.md](research/competitors.md). The headline: nobody else sizes the paper to a reading time; most rivals are paid, Kindle-only email digests. Its best ideas are in the backlog.

### Cycle 3: expedited builds, closed (12:28–12:33, [#20](https://github.com/madCode/newspaperss/pull/20))
- **Tried:** running scheduled builds as expedited work, so Android runs them promptly.
- **Review caught:** on Android 12+ expedited work has a quota, and a long build over it would be restarted silently. Keeping it running on older versions needs a foreground service, which Google Play makes you declare and justify.
- **Outcome:** closed and reverted. Cycle 4's lead time gets most of the benefit without either cost.

### Cycle 2: extraction fixes from the library review (12:15–12:27, [#19](https://github.com/madCode/newspaperss/pull/19))
- **Shipped:** the app gets the fixes the rss-to-e-reader review found in the same code:
  - A short "Further reading" list or a section titled "More on the method" is no longer removed.
  - A heading is never left without its list.
  - Screen-reader text that is an icon link's only label stays.
- **Checks:** a live edition from the starter feeds passes epubcheck with no junk left.

### Cycle 1: APK link, signing key, name (11:51–12:13, [#17](https://github.com/madCode/newspaperss/pull/17))
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
