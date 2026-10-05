<!-- Plain words, one idea per bullet. Delete any section that doesn't apply. -->

Fixes #

## What changed
- **What the reader sees or gets now.** Why.
-

## Screenshots
<!-- Only if a screen looks different. One pair per screen or state that changes; a new screen gets "after" only.
Render with `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest'` (PNGs in app/build/screenshots):
"before" from origin/main, "after" from this branch. A changed screen the test doesn't shoot yet gets a shot added.
Sample data only. After opening the PR, commit the PNGs to the `claude/screenshots` branch under pr-<N>/
as <screen>-before.png and <screen>-after.png, then fill in <N> below.
No Android SDK? Say so here instead. -->

| <screen>, before | <screen>, after |
|---|---|
| ![<screen>, before](https://github.com/madCode/newspaperss/blob/claude/screenshots/pr-<N>/<screen>-before.png?raw=true) | ![<screen>, after](https://github.com/madCode/newspaperss/blob/claude/screenshots/pr-<N>/<screen>-after.png?raw=true) |

## Diagrams
<!-- Only if the change alters structure or flow: modules, the edition pipeline, delivery, a state machine,
navigation, the database schema. Start from the matching diagram in docs/ARCHITECTURE.md and update it there too. -->

Before:
```mermaid
flowchart LR
```

After:
```mermaid
flowchart LR
```

## How it was tested
- `./gradlew build koverVerify` passes.
- `SomethingTest` checks that …
<!-- No behaviour to test (docs, comments, config)? Say so instead of inventing a test. -->

## What the review found
<!-- Behaviour changes only: a fresh-eyes subagent reviews the diff for data shared with other features,
sync and delivery, removal and re-adding, cancellation, e-ink and accessibility. -->
Fixed:
- **Finding:** what went wrong, and the fix.

Left as is, on purpose:
- **Finding:** why.

## Before merging
<!-- Tick what applies; delete the rest. -->
- [ ] README.md or docs/DESIGN.md updated, if they describe this behaviour.
- [ ] Room entity changed: database version bumped, Migration with a `MigrationTest` case, schema JSON in `app/schemas`.
- [ ] No personal data: no feed lists, hosts, emails or accounts from anyone's own setup.
