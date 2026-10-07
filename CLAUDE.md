# newspaperss

An Android app that makes a personal, finite newspaper from your RSS feeds
and saved links and delivers it to your e-reader as an EPUB. It brings the
ideas of [rss-to-e-reader](https://github.com/madCode/rss-to-e-reader) to
people who don't code. Read `docs/DESIGN.md` before changing behaviour
and `docs/ARCHITECTURE.md` for how the code fits together;
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

The sections from Privacy to Pull requests are shared with the projects in
[claude-playground](https://github.com/madCode/claude-playground/blob/main/CLAUDE.md),
whose root CLAUDE.md has the same rules for any project. A change to one
belongs in the other too.

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

Delivery ends in another app's hands, so the part past the hand-off can
only be checked by a person: [docs/DELIVERY-CHECKS.md](docs/DELIVERY-CHECKS.md)
says how, and a change to delivery or the EPUB wants it run. Adding a test
that asserts the intent again raises coverage without covering anything.

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
review the diff. Point it at the risky parts:

- How the change interacts with other features touching the same data,
  including whatever else deletes or replaces that data.
- Sync and delivery, removal and re-adding, cancellation.
- Rotation, and the app being killed and restored.
- Feeds, pages and links as untrusted input: a hostile feed, a link that
  isn't to the web.
- Other locales: non-Latin digits, international domain names, pages in
  older encodings.
- E-ink, accessibility, and e-reader firmware that lacks standard
  Android screens.

Ask for concrete findings only: file:line and a failure scenario, most
severe first, no edits. Verify each finding before acting on it, and say
in the PR what the review found and what was fixed or deliberately left.
Docs-, comment- and config-only changes can skip this; after fixing the
findings, a short second look at just the new diff is enough.

A PR that changes how a screen looks shows it: before and after images in
the description, one pair per screen or state that changes (only "after"
for a new screen).

- Render both with `ScreenshotTest` (`./gradlew :app:testDebugUnitTest
  --tests '*ScreenshotTest'`, PNGs in `app/build/screenshots`): "before"
  from `origin/main`, "after" from the branch. A changed screen the test
  doesn't shoot yet gets a shot added, which also guards it.
- Sample data only, never anyone's own: the repo is public.
- Open the PR, then commit the PNGs to the `claude/screenshots` branch
  (never merged) under `pr-<N>/` as `<screen>-before.png` and
  `<screen>-after.png`, and edit them into the description with
  `![<screen>, before](https://github.com/madCode/newspaperss/blob/claude/screenshots/pr-<N>/<screen>-before.png?raw=true)`.
- If they can't be rendered (no Android SDK), say so in the PR.
