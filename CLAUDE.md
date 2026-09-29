# newspaperss

An Android app that makes a personal, finite newspaper from your RSS feeds
and saved links and delivers it to your e-reader as an EPUB. It brings the
ideas of [rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to
people who don't code. Read `docs/DESIGN.md` before changing behaviour;
`docs/BACKLOG.md` is the running plan.

## Layout

- `:core`: pure Kotlin/JVM, no Android. Models, feed parsing, the edition
  planner, extraction and the EPUB writer. Test it with plain JUnit.
- `:app`: Android, Jetpack Compose, Room, WorkManager, DataStore. Manual
  DI through `AppContainer`. Test with Robolectric and Compose UI tests
  against a real Room database.

## Commands

    ./gradlew :core:test                 # fast JVM tests
    ./gradlew :app:testDebugUnitTest     # Robolectric tests
    ./gradlew build                      # everything CI runs
    ./gradlew koverHtmlReport            # coverage: build/reports/kover/html

JDK 21 and the Android SDK (compileSdk 37) are required.

## Privacy

This repository is public. Never commit personal data: no feed lists,
hosts, emails or accounts from anyone's own setup. Starter packs contain
only well-known public feeds.

## Comments

A comment is for what the code can't say itself: **why** it is written
this way (a constraint, a trade-off, what goes wrong with the obvious
alternative), **the non-obvious** (a gotcha, an invariant, a surprising
dependency), or **a summary of complex code**. Not restating the code, and
not history ("used to", "since the rewrite"): that belongs in the PR or the
commit message. Public API KDoc stays accurate when signatures change.

## Tests

A test should be able to catch a plausible regression. Test behaviour, not
structure. Don't feed code inputs it can never receive. When a change has
no behaviour to test, say so in the PR instead of inventing a test.
