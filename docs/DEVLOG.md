# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **In flight:** onboarding offers tt-rss and OPML import (from your device testing).
- **Next:** a resource-usage audit (it decides whether streaming images to disk is worth doing); a design pass.
- **Waiting on you:** [#18](https://github.com/madCode/newspaperss/issues/18), a Dropbox app key for automatic Kobo delivery (optional). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Day 2 · Tue 29 Sep, afternoon

### Cycle 5: bring your reader into onboarding (12:53–)
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
