# Delivery: what only a person can check

Every delivery route ends in another app's hands. `EditionIntents` builds an
intent and calls `startActivity`; what happens next is Dropbox's, Gmail's or
the Kindle app's business, and no test on a runner can see it. An emulator
doesn't help: it has none of those apps, no Amazon account and no Kobo on the
other end of a sync.

So the intents are covered by tests (`EditionReadGrantTest`,
`KindleEmailTest`: the action, the MIME type, the `FileProvider` URI and the
read grant), and everything past the hand-off is checked here, by hand.

Run this before a release. A route nobody has exercised since the EPUB
writer last changed is a route nobody knows about.

## Each route

Build an edition first, then for each row: does the file arrive, and does it
open with its cover and contents intact?

| Route | Do this | It worked if |
|---|---|---|
| **Kindle, by email** | Send → your mail app opens, addressed to `…@kindle.com` with the EPUB attached | It arrives on the Kindle by itself within ~20 minutes, and the app's note ("can take a few minutes") goes away |
| **Kindle app** | Share → Kindle, from the ready notification | It reaches the cloud library, or the device directly with "Add to your library" off |
| **Kobo** | Share → Dropbox, into `Apps/Rakuten Kobo` | The file is in that folder, under its own name, and appears on the Kobo after a sync |
| **PocketBook** | Share → a mail app, to your `@pbsync.com` address | It arrives on the device |
| **KOReader** | Save to the Syncthing folder | It syncs and opens |
| **Boox** | Read, from the app or the notification | The device's reader opens it |
| **Folder** | Save to a folder with Android's picker | The file is there, and the edition is marked delivered |
| **Anything else** | The share sheet | Android reports the choice back and the edition is marked sent |

## Then check the state it left behind

The app can't see whether a send arrived, so these are the parts that go
wrong quietly:

- **Marked delivered.** Saving, choosing an app in the share sheet, opening
  the mail app, or Read on a Boox each count as delivery. Check the edition
  says so.
- **Mark as sent / Send again** (a sent edition's ⋮ menu) for a route that
  isn't covered above, or a send that didn't arrive.
- **An edition still "ready" when the next is built** is marked not sent and
  its articles go back, keeping their stars. Build two editions without
  sending the first and check that happened.
- **From a cold start.** Kill the app, then send from the notification: the
  `FileProvider` grant has to still work.

## A sending address Amazon accepts

Android can't choose the "From" account for another app, so the address you
send from must be on Amazon's approved list (Personal Document Settings). A
send from the wrong account fails silently at Amazon's end, with nothing the
app can show you. Check this first if a Kindle email never arrives.
