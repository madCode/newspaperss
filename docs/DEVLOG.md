# Devlog

A running log of the build cycles: what's in flight, and what each cycle shipped, what its review
caught, and what got in the way. Newest first. Times are Pacific.

**Latest debug build:** [newspapeRSS-debug.apk](https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk)
(updated on every merge to main; installs beside a release build as `com.app.newspaperss.debug`)

## Status

- **In flight:** a fixed download link for the debug APK, one signing key for debug builds, and the newspapeRSS name.
- **Next:** port yesterday's extraction review fixes to the app; expedite scheduled builds; stream images to disk.
- **Waiting on you:** a Dropbox app key, for automatic Kobo delivery (optional).

## Day 2 · Tue 29 Sep, afternoon

### Cycle 1: APK link, signing key, name (11:51–)
- **Shipped:**
  - Every merge to main publishes the debug APK to a rolling `latest-debug` release, with the fixed link above.
  - Debug builds use one committed signing key, so a newer build installs over an older one.
  - The app now shows itself as newspapeRSS.
- **Friction:** the debug APK was only uploaded for pull requests, so the first link I sent pointed at a run without one.

## Day 1 · Tue 29 Sep, overnight (summary)

Sixteen PRs took the app from an empty repo to a working, tested app:
- **Sources:** starter packs, any site's feed, OPML, Tiny Tiny RSS, curated lists and a reading list.
- **Editions:** an edition sized by reading time, with a cover and contents, delivered by share sheet, folder or Boox.

A fresh-eyes review before each merge found real bugs in 11 of the 15 reviewed changes. A live edition
run through epubcheck found two more that tests and reviews missed. The full report and a per-cycle
retro are in the session's report pages.
