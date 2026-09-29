# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **In flight:** expedited edition builds.
- **Next:** a timer that fires in Doze; stream images to disk; the competitor research.
- **Waiting on you:** [#18](https://github.com/madCode/newspaperss/issues/18), a Dropbox app key for automatic Kobo delivery (optional). Five rss-to-e-reader PRs (#24–#28) are open for your batch review.

## Day 2 · Tue 29 Sep, afternoon

### Cycle 3: expedited builds (12:28–)
- **Shipped:** an edition build, scheduled or "Make one now", is expedited work. Android runs it straight away with a quiet "Making your edition…" notification, instead of waiting for the phone's next batch of background work.
- **Still open:** the timer that starts a scheduled build is ordinary delayed work, which Doze can hold until a maintenance window. That's the next cycle.

### Cycle 2: extraction fixes from the library review (12:15–12:27, [#19](https://github.com/madCode/newspaperss/pull/19))
- **Shipped:** the app gets the fixes the rss-to-e-reader review found in the same code:
  - A short "Further reading" list or a section titled "More on the method" is no longer removed.
  - A heading is never left without its list.
  - Screen-reader text that is an icon link's only label stays.
- **Checks:** a live edition from the starter feeds passes epubcheck with no junk left.
- **Review caught:**
  - No test put line breaks between a heading and its list, as feed HTML does.
  - A link labelled by two screen-reader spans still ended up empty. That fix went to the library PR too.

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
