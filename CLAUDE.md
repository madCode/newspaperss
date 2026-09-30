# newspaperss

An Android app that makes a personal, finite newspaper from your RSS feeds
and saved links and delivers it to your e-reader as an EPUB. It brings the
ideas of [rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to
people who don't code. Read `docs/DESIGN.md` before changing behaviour;
`docs/BACKLOG.md` is the running plan and `docs/DEVLOG.md` the log of what
changed and why.

## Layout

- `:core`: pure Kotlin/JVM, no Android. Feed parsing, the edition planner,
  extraction, images and the EPUB writer. Test it with plain JUnit.
- `:app`: Android, Jetpack Compose, Room, WorkManager, DataStore. Manual
  DI through `AppContainer`. Test with Robolectric and Compose UI tests
  against a real Room database.

## Commands

    ./gradlew :core:test                 # fast JVM tests
    ./gradlew :app:testDebugUnitTest     # Robolectric tests
    ./gradlew build                      # everything CI runs
    ./gradlew koverHtmlReport            # coverage: build/reports/kover/html
    ./gradlew koverVerify                # fails below 90% line coverage (CI runs it)

JDK 21 and the Android SDK (compileSdk 37) are required.

Changing a Room entity means bumping the database version, adding a
Migration with a `MigrationTest` case, and committing the new schema JSON
in `app/schemas`. CI publishes main's debug APK to the `latest-debug`
release; its version name carries the CI run and commit.

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

## Documentation

Docs are for people: keep them readable, current and short.

- A change that alters behaviour updates README.md or docs/DESIGN.md in the
  same PR, if they describe it.
- Every few cycles, a documentation pass: check the docs against the code,
  fix what's stale, cut what's grown long or become history. DESIGN.md says
  how the app works now; plans go in BACKLOG.md, history in DEVLOG.md and
  the PRs.
- Plain words over jargon, short sections, one idea per bullet.

## Pull requests

Before opening a PR that changes behaviour, have a fresh-eyes subagent
review the diff. Point it at the risky parts (how the change interacts
with other features touching the same data, sync and delivery, removal
and re-adding, cancellation, e-ink and accessibility), and ask for
concrete findings only: file:line and a failure scenario, most severe
first, no edits. Verify each finding before acting on it, and say in the
PR what the review found and what was fixed or deliberately left. Docs-,
comment- and config-only changes can skip this; after fixing the
findings, a short second look at just the new diff is enough.
