# newspaperss

Your own newspaper, on your e-reader.

newspaperss turns the feeds and links you choose into a finite daily
edition (say, *about 30 minutes, every morning at 6:30*) and delivers it as
a clean EPUB to your Kindle, Kobo, Boox, PocketBook or KOReader device. No
algorithm, no account, no endless feed: it arrives, it ends, you're done.

It's the app version of
[rss-to-e-reader](https://github.com/madCode/rss-to-e-reader), for people
who'd rather not set up Python, a server and a scheduler.

**Status:** early development. See [docs/DESIGN.md](docs/DESIGN.md) for the
design and [docs/BACKLOG.md](docs/BACKLOG.md) for the plan.

## Building

JDK 21 and the Android SDK (API 37):

    ./gradlew build
    ./gradlew installDebug
