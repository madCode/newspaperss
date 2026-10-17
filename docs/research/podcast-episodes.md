# Podcast episodes: research plan

How to find out what a good answer to podcast episodes in feeds is, before building it. The
proposal is in [BACKLOG.md](../BACKLOG.md) under "Podcast episodes in feeds"; this is how to test
it, and the corpus that keeps it tested.

## What we know

From one real reading list (91 feeds, about 1,450 recent posts), measured in October 2026:

- About 4% of posts carry podcast audio, in 12 feeds.
- They come in six shapes:
  - audio-only feeds;
  - episode posts with only notes;
  - transcripts;
  - episodes with a real write-up;
  - the same episode posted twice;
  - look-alikes that aren't podcasts.
- Today the paper prints notes, transcripts and duplicates as articles, and an audio-only feed
  comes out as one jumble of every episode's notes.
- Transcripts are rare (about 0.6% of posts) but long (7,600–25,000 words). Most come from one
  show that posts a transcript beside every episode.
- Twelve posts were shaped like transcripts and weren't: written interviews, fiction in
  dialogue, conference write-ups.
- Neither the feed parser nor the tt-rss sync keeps audio attachments today.

One reading list isn't enough to decide on. The plan below widens it, and keeps what's learnt.

## Questions to answer

1. **Detection.** From what the app can see, can each post's kind be told reliably?
   - What the app can see: the feed item, what tt-rss passes on, and the post's page.
   - The kinds: article, episode notes, transcript, write-up, or the audio version of another post.
2. **Mistakes.** What does a wrong call cost, and which way should doubt fall? The proposal says
   an article when unsure. Is that right for every shape?
3. **Duplicates.** How do two posts of one episode show they belong together, across platforms?
4. **tt-rss.** What does tt-rss keep of an episode? Its attachments, their type and length, and
   whether it keeps the item's duration.
5. **The paper.** What should an episode look like in an edition, on e-ink, so it's useful and
   not noise? And how long can a transcript be before it should be capped?
6. **Listening.** Is playing an episode's own audio in Listen worth building, and what stands in
   the way? Download size, ads stitched in per listener, paid feeds.

## The episode corpus

A fixed set of real posts, each labelled with what it is. Every change to detection runs against
it, and every mistake found later is added to it. It's the thing to fall back to.

- **Built like the platform corpus.** It lives in `core/src/test/resources/episodes`, one folder
  per feed, collected by `EpisodeCorpus` (`./gradlew :core:episodeCorpus`). A folder keeps the
  feed's newest items as one snapshot, with enclosures and podcast tags, because pairs and
  audio-only feeds only show across a feed's posts. What tt-rss passes on, and each post's page,
  are still to add (R4).
- **Labelled.** Each folder's `labels.json` gives every item in the snapshot:
  - its kind: article, notes, transcript, or audio-version (the audio of a written post);
  - its shape, the row of the table below it stands for, which the test tallies by;
  - its pair, if it has one (the other post of the same episode);
  - why, in a line.
  The label is the claim the test checks, so a reader can tell what each case is for. A
  narrated essay or an episode's write-up is an article: it's printed as one.
- **Scrambled, as the platform corpus is.** Words are swapped for made-up ones so no one's
  writing is republished. The snapshot keeps only what detection reads (titles, links, guids,
  enclosures, the post's HTML stripped to links and players), so the names and addresses feeds
  carry elsewhere stay out. Detection gives the same answer on the scrambled snapshot as on the
  real feed, which is worth checking again whenever a rule starts comparing words with
  addresses: titles are scrambled, addresses aren't. The words detection reads must survive the scramble: "transcript",
  "episode", "listen", "show notes", the hosts' names in embeds, and timestamps (numbers already
  survive).
- **Public, well-known feeds only.** Taken from public directories of podcasts and newsletters,
  never from anyone's own reading list, which stays out of the repository.
- **Every shape, and its look-alikes.**

  | Shape | Examples to find |
  |---|---|
  | Audio-only feed | a podcast host's own feed (Spotify for Podcasters, Buzzsprout, Transistor) |
  | Notes only | a Substack podcast; an interview show's notes post with sponsors and timestamps |
  | Transcript, own post | an interview show that posts it separately |
  | Transcript, in the episode post | a newsletter interview with its audio |
  | Write-up | a newsletter whose episode post is an essay |
  | Same episode twice | notes plus transcript; a serial's audio post beside its chapter |
  | Narrated article | a newsletter with "listen to this post" audio |
  | Video podcast | YouTube embed plus timestamps |
  | Not podcasts | music embeds (Spotify tracks, SoundCloud); written Q&A interviews; dialogue fiction; conference write-ups; link roundups with "Name:" lines |

- **Size to start:** about 60 posts, at least five per shape, and as many look-alikes as
  episodes. Look-alikes are where a detector embarrasses itself.
- **How it grows:** a misclassified post seen in a real edition, or in a sweep, is added with its
  correct label before it's fixed. The fix is done when the corpus passes.
- **What the test checks:**
  - each post's kind against its label;
  - pairs found;
  - the one rule that can't bend: **no article is ever classed as something else**.
  Errors are reported per shape, so a gain in one isn't hiding a loss in another.

## Phases

### R1. Build the corpus (about a cycle)
- Write the collector, extending `PlatformCorpus` with enclosures, podcast tags and labels.
- Find public examples of each shape. Label them by reading each one.
- Exit: about 60 labelled posts covering every row of the table; the test runs, with no
  detector yet, as a list of what's expected.
- **Done, October 2026:** 421 posts from 41 public feeds, 139 of them episode posts or
  transcripts and the rest look-alikes and ordinary posts.

### R2. A detector as a tool, not in the app (about a cycle)
- A classifier in `core` that the corpus test runs, using the signals measured so far:
  - audio attached from a known host;
  - the same audio on two posts;
  - "Transcript" in the title;
  - ten or more timestamps;
  - Spotify episode against track;
  - text length.
- Tune against the corpus.
- Exit:
  - no article misclassed;
  - at least 9 in 10 notes posts and transcripts caught;
  - pairs found where they exist.
- **Done, October 2026:** `EpisodeDetector`, in core's tests. No article misclassed; 73 of 75
  notes, 51 of 53 transcripts and all 21 pairs found. Every miss errs toward an article. What
  the corpus taught it:
  - audio counts only from a podcast host, or in a podcast's own feed: blogs attach video clips
    and music sessions the same way;
  - audio uploaded into a Substack post is a reading or a song as often as a show;
  - a player for someone else's episode is a mention, unless a list of timestamps comes with it;
  - pairs show as the same audio file, a stub linking to its post, the same title less "(audio)",
    a transcript titled after its episode, or the same video;
  - a transcript inside an episode post shows as a conversation: "Name:" turns, names on their
    own lines, or questions as headings or in bold. Without the episode's audio or player in the
    post, that never makes a transcript;
  - timestamps count only as a list gives them, opening a line or in brackets: a clock time in a
    sentence isn't one.
- **Still missed:** transcripts whose audio is only in a paid feed, a solo episode's transcript,
  and players the feed strips but the page keeps. The last wants the post's page.

### R3. Sweep wider (a few days)
- Run the detector over `tools/sweep-feeds.opml` and a public list of podcast-heavy feeds.
- A reader can also run it over their own feed list, locally; its results stay out of the
  repository.
- Every surprise becomes a corpus post.
- Exit: a sweep with no new shape and no article misclassed.

### R4. tt-rss (a day or two)
- Through a tt-rss install, record what its API returns for each corpus feed. Check the
  attachments' type, length and duration, and whether its sanitizer strips the players.
- Add the tt-rss version of each post to the corpus, as the platform corpus does.
- Exit: detection gives the same answer through tt-rss as from the feed, or the differences are
  known and labelled.

### R5. How it looks in the paper (a cycle, with mockups)
- Mock the "Episodes" list and the source page's line, on phone and e-ink.
- Settle:
  - the long-read cap for one-off transcripts, measured against real edition lengths;
  - the wording for a show's transcript opt-in.
- Try a week of editions on a real device with detection on but nothing hidden, then a week with
  it on.
- Exit: you've read both weeks and chosen.

### R6. Listening to the real audio (research only, later)
- How big episodes are, and how long they take to download on Wi-Fi while charging.
- Whether ads stitched in per listener (common on big hosts) make a downloaded file differ from
  what streams.
- Whether paid feeds' audio needs sign-in.
- How Listen would hand over from a podcast's audio to the next article in Kokoro or the phone's
  voice.
- Exit: a short write-up of whether it's worth building and what it would take.

## What stays out of the repository
- Anyone's own feed list, and sweep results drawn from it.
- Unscrambled post text.
- Audio files: the corpus keeps attachment links and metadata only.
