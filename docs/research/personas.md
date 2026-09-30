# Persona audits

Two walk-throughs of the app as the people it's for, done by reading the code and screenshots
as each of them would meet it. Each finding says what happened and where it went. Open items
are in [BACKLOG.md](../BACKLOG.md), tagged *personas*.

## Who

- **A Kindle reader in their sixties** who reads news on their phone and wishes they didn't.
- **A Pocket refugee with a Kobo,** whose saved links vanished when Pocket shut down.
- **A tt-rss and KOReader self-hoster** who wants their existing subscriptions as a good EPUB.
- **A Boox owner** who runs the app on the e-reader itself.
- **A TalkBack user** (Day 2).

## Day 2 (29 Sep): ten findings

| # | Finding | Who | Status |
|---|---|---|---|
| 1 | Sending the paper from its notification was never recorded, so the next build marked it "Not sent" and its articles came back every day. The "Did it reach your Kindle?" dialog asked before anything could have arrived. | Kindle, Kobo, Boox | Fixed in [#23](https://github.com/madCode/newspaperss/pull/23): choosing an app in the share sheet counts as delivered |
| 2 | Titles repeated every week, and Send to Kindle silently drops a title it has seen. | Kindle, Kobo, KOReader | Fixed in [#24](https://github.com/madCode/newspaperss/pull/24): dated titles |
| 3 | The "ready" notification was silent, the only prompt to send. | Everyone who shares | Fixed in [#25](https://github.com/madCode/newspaperss/pull/25) |
| 4 | A Pocket refugee can't finish onboarding with saved links alone; importing Pocket is buried. | Pocket refugee | Backlog |
| 5 | Kobo delivery is a manual Dropbox share every day; the device can't be changed after onboarding. | Kobo | Backlog; Dropbox waits on an app key ([#18](https://github.com/madCode/newspaperss/issues/18)) |
| 6 | Failures and "nothing new" are quiet or mislabelled: no notice when a timed run finds nothing, "nothing new" when every source failed, "checking…" forever offline. | Everyone | Backlog |
| 7 | On a Boox, Open doesn't put the edition in the library, and "I've sent it" makes no sense on the device. | Boox | Opening now counts as delivered ([#23](https://github.com/madCode/newspaperss/pull/23)); folder delivery to Books is in the backlog |
| 8 | Paywalled and summary-only sites give short stubs with no warning; starter packs include metered sites. | Kindle, everyone | Backlog |
| 9 | A site with no feed is a dead end. | Everyone | Backlog: offer to save it to the reading list |
| 10 | TalkBack and large fonts: progress not announced, no "Step 2 of 4", Add clipped at large sizes, no click label. | TalkBack | Fixed in [#39](https://github.com/madCode/newspaperss/pull/39) |

Also noted: with folder delivery, "delivered" means the file was saved, so tt-rss marks
articles read before Syncthing has synced, and old editions pile up in the folder (backlog).

## Day 1 (28 Sep): twelve findings

Most were fixed the same night, in [#6](https://github.com/madCode/newspaperss/pull/6) to
[#9](https://github.com/madCode/newspaperss/pull/9):

- **Fixed:** notification permission asked for during onboarding; Open is the primary action on a
  Boox; check marks on selected chips, so they survive grayscale; spinners replaced with stepped
  text (they smear on e-ink); a "next edition" line and a first-run message on Today; feed jargon
  replaced ("Add", "Which part of this site?"); a Try again button on failed editions; unread
  counts in Sources replaced by freshness ("last new article 2 days ago"); the in-app preview.
- **Changed later:** the "Did it reach your Kindle?" dialog was replaced by counting the share
  sheet choice as delivery (Day 2, #1).
- **Open:** errors are still shown in red alone, which reads as grey on e-ink (part of the
  accessibility audit in the backlog).
