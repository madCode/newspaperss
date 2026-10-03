package com.app.newspaperss.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import com.app.newspaperss.ui.sources.LeftOutScreen
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.onboarding.Step
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.settings.FeedsFromViewModel
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.NotInPaperScreen
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders each main screen with real graphics. It catches screens that crash
 * when drawn with realistic data, and leaves PNGs in build/screenshots for
 * reviewing layout changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }
    private val out = File("build/screenshots").apply { mkdirs() }

    /**
     * [act] runs once [ready], before the capture, e.g. to enter a mode through the UI. With
     * [dialog], the open dialog is captured: it's a window of its own, not part of the root.
     */
    private fun shoot(name: String, ready: () -> Boolean = { true }, act: () -> Unit = {}, dialog: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { NewspaperssTheme(content) }
        idleUntil(condition = ready)
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onboarding() = OnboardingViewModel(store, SourceRepository(db), FeedFinder(FakeHttp())) {}

    @Test
    fun onboardingWelcome() = shoot("01-onboarding-welcome") { OnboardingScreen(onboarding()) }

    @Test
    fun onboardingDevice() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com") }
        shoot("02-onboarding-device") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingFeedsFrom() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KOBO); next(); chooseFeedsFrom(FeedsFrom.PHONE) }
        shoot("02b-onboarding-feeds-from") { OnboardingScreen(vm) }
    }

    /** Through the fork to this phone's sources step; saving the choice finishes off the main thread. */
    private fun onboardingOnThePhone(vm: OnboardingViewModel) = vm.apply {
        next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com"); next()
        chooseFeedsFrom(FeedsFrom.PHONE); next()
        idleUntil { state.value.step == Step.SOURCES }
    }

    @Test
    fun onboardingSources() {
        val vm = onboardingOnThePhone(onboarding()).apply { togglePack(StarterPacks.all[1].name) }
        shoot("03-onboarding-sources") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingSize() {
        val vm = onboardingOnThePhone(onboarding()).apply { toggleFeed(StarterPacks.all[0].feeds[0].url); next() }
        shoot("04-onboarding-size") { OnboardingScreen(vm) }
    }

    private val ttrssHttp = FakeHttp()
    private val ttrssAccounts by lazy { TtrssAccountStore(PreferenceDataStoreFactory.create { tmp.newFile("ttrss.preferences_pb") }, testCipher()) }
    private val ttrss by lazy { TtrssRepository(db, ttrssHttp, ttrssAccounts, SourceRepository(db)) }

    /** A sample tt-rss server: 58 feeds in 7 categories. */
    private fun sampleServer() = FakeTtrss(ttrssHttp).apply {
        val names = listOf("News", "Tech", "Science", "Essays", "Arts", "Local", "Comics")
        names.forEachIndexed { i, name -> categories[i + 1] = name }
        (1..58).forEach { feeds[it] = FakeTtrss.Feed("Feed $it", "https://feed$it.example/rss", categoryId = (it % 7) + 1) }
    }

    /** On the server path, at the sign-in step. */
    private fun onboardingOnTheServer(): OnboardingViewModel =
        OnboardingViewModel(store, SourceRepository(db), FeedFinder(ttrssHttp), ttrss) {}.apply {
            next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com"); next()
            chooseFeedsFrom(FeedsFrom.SERVER); next()
            idleUntil { state.value.step == Step.SIGN_IN }
        }

    @Test
    fun onboardingSignIn() {
        val vm = onboardingOnTheServer().apply { editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = "reader")) }
        shoot("03b-onboarding-sign-in") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingFoundFeeds() {
        val server = sampleServer()
        val vm = onboardingOnTheServer().apply {
            editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = server.user, password = server.password))
            signIn()
        }
        shoot("03c-onboarding-found-feeds", ready = { vm.state.value.serverFound != null }) { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingExtras() {
        val server = sampleServer()
        val vm = onboardingOnTheServer().apply {
            editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = server.user, password = server.password))
            signIn()
        }
        idleUntil { vm.state.value.signedIn }
        vm.next()
        val readingList = com.app.newspaperss.ui.readinglist.ReadingListViewModel(com.app.newspaperss.data.ReadingListRepository(db))
        shoot("03d-onboarding-extras") { OnboardingScreen(vm, readingList = readingList) }
    }

    @Test
    fun today() {
        runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = now.minusSeconds(86_400), status = EditionStatus.DELIVERED, articleCount = 7, minutes = 31.0))
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.READY, fileName = "x.epub", articleCount = 8, minutes = 33.4))
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Review"))
            (1..2).forEach { db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "$it", url = "https://example.com/$it", title = "Starred $it", starredAt = now)) }
        }
        val vm = TodayViewModel(EditionRepository(db, tmp.newFolder()), flowOf(null)) {}
        shoot("05-today", ready = { vm.state.value.editions?.isNotEmpty() == true }) { TodayScreen(vm) }
    }

    @Test
    fun editionDetail() {
        val id = runBlocking {
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Review"))
            val titles = listOf(
                "The quiet return of the night train",
                "What a century of weather records says about spring",
                "A short history of the paperback",
                "Why city trees are planted in pairs",
            )
            val articles = titles.mapIndexed { i, title ->
                db.articles().insertIgnoring(
                    ArticleEntity(
                        sourceId = source, guid = "$i", url = "https://example.com/$i", title = title, state = ArticleState.DELIVERED,
                        starredAt = if (i == 1) Instant.parse("2026-09-29T08:00:00Z") else null,
                    ),
                )
            }
            val now = Instant.parse("2026-09-29T06:30:00Z")
            val edition = db.editions().insert(
                EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = 4, minutes = 27.5, deliveredAt = now),
            )
            db.editions().insertArticles(
                titles.mapIndexed { i, title ->
                    EditionArticleEntity(editionId = edition, articleId = articles[i], position = i, title = title, sourceTitle = "The Example Review", minutes = 4.0 + 3 * i, starred = i == 0)
                },
            )
            edition
        }
        val vm = EditionDetailViewModel(EditionRepository(db, tmp.newFolder().apply { resolve("e.epub").writeText("epub") }), id, EditionNotes(db, tmp.newFolder())) {}
        shoot("05b-edition-detail", ready = { vm.detail.value?.contents?.isNotEmpty() == true }) { EditionDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun articlePreview() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "The quiet return of the night train", sourceTitle = "The Example Review", url = "https://example.com/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)
        file.outputStream().use {
            EpubWriter.write(EditionDoc("Tuesday Morning Edition", LocalDate.of(2026, 9, 29), "urn:uuid:1", listOf(EditionSection(null, listOf(article)))), it)
        }
        // The page itself is a WebView, which Robolectric doesn't draw; this shoots the top bar.
        shoot("05c-article-preview", ready = { compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {})
        }
    }

    @Test
    fun sources() {
        val repo = SourceRepository(db)
        runBlocking {
            StarterPacks.all[3].feeds.forEach { repo.addFeed(it.url, it.title) }
            val broken = repo.addFeed("https://broken.example/feed", "A blog that moved")
            db.sources().recordFailure(broken, Instant.now(), "We can't get new articles from this site any more. It may have moved; try adding it again.")
        }
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = store) {}
        shoot("06-sources", ready = { vm.screen.value?.rows?.isNotEmpty() == true }) { SourcesScreen(vm) }
    }

    /** A source with a row in each state the design shows: waiting, starred, marked read, in an unsent edition, delivered (two days ago), got old. */
    private fun sourceWithArticles(): SourceDetailViewModel {
        val repo = SourceRepository(db)
        val id = runBlocking {
            val id = repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
            val titles = listOf(
                "Talks resume after a week of storms",
                "The town that voted to keep its library open",
                "What the census says about who moves where",
                "A short history of the night train",
                "Why city trees are planted in pairs",
                "The quiet return of the paperback",
            )
            db.articles().insertNew(titles.mapIndexed { i, t -> ArticleEntity(sourceId = id, guid = "$i", url = "https://www.theguardian.com/$i", title = t, discoveredAt = Instant.now().minusSeconds(3_600L * (i + 1))) })
            val ids = db.articles().candidates().sortedBy { it.guid }.map { it.id }
            repo.setStarred(ids[1], true)
            db.articles().setState(listOf(ids[2]), ArticleState.SKIPPED)
            db.articles().setState(listOf(ids[3]), ArticleState.IN_EDITION)
            val today = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val edition = db.editions().insert(EditionEntity(title = "$today Morning Edition", status = EditionStatus.READY))
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = ids[3], position = 0, title = titles[3], sourceTitle = "The Guardian: World", minutes = 6.0)))
            db.articles().setState(listOf(ids[4]), ArticleState.DELIVERED)
            db.articles().rememberDelivered(listOf(ids[4]), Instant.now().minus(Duration.ofDays(2)))
            db.articles().setState(listOf(ids[5]), ArticleState.EXPIRED)
            db.sources().recordSuccess(id, Instant.now(), null, "https://www.theguardian.com", "")
            db.sources().savePublication(PublicationEntity(id, PublicationEntity.OWN, ContentMode.PAGE, FullTextEvidence.PAGE_LONGER, 3))
            id
        }
        return SourceDetailViewModel(repo, id, flowOf(1))
    }

    /** Scrolled to the articles, which sit below the source's settings. */
    private fun scrollToArticles() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Recent articles", substring = true))
    }

    @Test
    fun sourceDetail() {
        val vm = sourceWithArticles()
        shoot("06b-source-detail", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun sourceDetailSelecting() {
        val vm = sourceWithArticles()
        shoot(
            "06c-source-detail-selecting",
            ready = { vm.detail.value?.articles?.isNotEmpty() == true },
            act = {
                scrollToArticles()
                compose.onNode(hasText("Talks resume after a week of storms") and hasClickAction()).performTouchInput { longClick() }
                compose.onNode(hasText("The town that voted to keep its library open") and hasClickAction()).performClick()
                compose.onNode(hasText("A short history of the night train") and hasClickAction()).performClick()
            },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourceDetailAtTwiceTheFontSize() {
        val vm = sourceWithArticles()
        shoot("06d-source-detail-200", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourceDetailSelectingAtTwiceTheFontSize() {
        val vm = sourceWithArticles()
        shoot(
            "06e-source-detail-selecting-200",
            ready = { vm.detail.value?.articles?.isNotEmpty() == true },
            act = {
                compose.onNodeWithText("Select").performScrollTo().performClick()
                val first = hasText("Talks resume after a week of storms") and hasClickAction()
                compose.onNode(hasScrollAction()).performScrollToNode(first)
                compose.onNode(first).performClick()
                val second = hasText("The town that voted to keep its library open") and hasClickAction()
                compose.onNode(hasScrollAction()).performScrollToNode(second)
                compose.onNode(second).performClick()
            },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    /**
     * A tt-rss account listed by tt-rss: feeds with settings of their own, one also added on the
     * phone, two left out, and articles from one of them.
     */
    private fun ttrssAccount(repo: SourceRepository): Long = runBlocking {
        repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
        repo.addFeed("https://aeon.co/feed.rss", "Aeon")
        val account = repo.addTtrss("https://rss.example.com/tt-rss/api/")
        db.sources().recordSuccess(account, Instant.now(), null, null, SourceRepository.TTRSS_TITLE)
        fun feed(key: String, title: String, url: String, category: String) =
            PublicationEntity(account, key, title = title, feedUrl = url, category = category, listed = true)
        listOf(
            feed("1", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
            feed("2", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "News").copy(maxArticles = 1),
            feed("3", "Aeon", "https://aeon.co/feed.rss", "Essays"),
            feed("4", "Longreads", "https://longreads.com/feed/", "Essays").copy(chosenMode = ContentMode.PAGE),
            feed("5", "Quanta Magazine", "https://www.quantamagazine.org/feed/", "Science"),
            feed("6", "The Marginalian", "https://www.themarginalian.org/feed/", "Essays"),
            feed("7", "Hacker News", "https://news.ycombinator.com/rss", "Tech").copy(leftOut = true),
            feed("8", "Slashdot", "https://rss.slashdot.org/Slashdot/slashdotMain", "Tech").copy(leftOut = true),
        ).forEach { db.sources().savePublication(it) }
        db.sources().setFeedsListed(account, Instant.now())
        val stories = listOf(
            "How a quiet lab rebuilt the atomic clock",
            "The mathematicians who count the uncountable",
            "Why some ice is older than it should be",
            "A new map of the brain's wiring",
        )
        db.articles().insertNew(
            stories.mapIndexed { i, t ->
                ArticleEntity(
                    sourceId = account, guid = "ttrss:5$i", url = "https://www.quantamagazine.org/$i", title = t,
                    originId = "5", originTitle = "Quanta Magazine", discoveredAt = Instant.now().minusSeconds(3_600L * (i + 1)),
                )
            },
        )
        account
    }

    /**
     * The server setup: a curated list on the phone, and a tt-rss account with 21 well-known
     * public feeds in four categories and Uncategorized, a few with settings of their own and two
     * left out.
     */
    private fun serverSources(category: String? = null): SourcesViewModel {
        val repo = SourceRepository(db)
        runBlocking {
            repo.addList(com.app.newspaperss.core.lists.CuratedLists.all.first())
            val account = repo.addTtrss("https://rss.example.com/tt-rss/api/")
            fun feed(key: Int, title: String, url: String, category: String?) =
                PublicationEntity(account, key.toString(), title = title, feedUrl = url, category = category)
            val feeds = listOf(
                feed(1, "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "News").copy(maxArticles = 2),
                feed(2, "The Guardian: World", "https://www.theguardian.com/world/rss", "News"),
                feed(3, "NPR News", "https://feeds.npr.org/1001/rss.xml", "News"),
                feed(4, "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "News"),
                feed(5, "ProPublica", "https://www.propublica.org/feeds/propublica/main", "News").copy(chosenMode = ContentMode.PAGE),
                feed(6, "Quanta Magazine", "https://www.quantamagazine.org/feed/", "Science"),
                feed(7, "New Scientist", "https://www.newscientist.com/feed/home/", "Science"),
                feed(8, "Nature News", "https://www.nature.com/nature.rss", "Science"),
                feed(9, "NASA Breaking News", "https://www.nasa.gov/news-release/feed/", "Science"),
                feed(10, "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
                feed(11, "The Verge", "https://www.theverge.com/rss/index.xml", "Tech"),
                feed(12, "Wired", "https://www.wired.com/feed/rss", "Tech").copy(skipPaidPosts = true),
                feed(13, "Hacker News", "https://news.ycombinator.com/rss", "Tech").copy(leftOut = true),
                feed(14, "Slashdot", "https://rss.slashdot.org/Slashdot/slashdotMain", "Tech").copy(leftOut = true),
                feed(15, "Aeon", "https://aeon.co/feed.rss", "Essays"),
                feed(16, "The Marginalian", "https://www.themarginalian.org/feed/", "Essays"),
                feed(17, "Longreads", "https://longreads.com/feed/", "Essays"),
                feed(18, "Psyche", "https://psyche.co/feed", "Essays"),
                feed(19, "xkcd", "https://xkcd.com/rss.xml", null),
                feed(20, "Kottke.org", "https://feeds.kottke.org/main", null),
                feed(21, "Atlas Obscura", "https://www.atlasobscura.com/feeds/latest", null),
            )
            feeds.forEach { f ->
                // With a category chosen, the rest are outside it.
                val inside = category == null || f.category == category
                db.sources().savePublication(f.copy(listed = inside, outsideCategory = !inside))
            }
            if (category != null) db.sources().setTtrssCategory(account, 99, category)
            db.sources().setFeedsListed(account, Instant.now())
            db.sources().recordSuccess(account, Instant.now(), null, null, SourceRepository.TTRSS_TITLE)
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        }
        return SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = store) {}
    }

    @Test
    fun sourcesWithAServer() {
        val vm = serverSources()
        shoot("08a-sources-server", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true }) { SourcesScreen(vm) }
    }

    /** The whole list, to its end: Uncategorized and Left out last. */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun sourcesWithAServerWhole() {
        val vm = serverSources()
        shoot("08b-sources-server-whole", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true }) { SourcesScreen(vm) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourcesWithAServerAtTwiceTheFontSize() {
        val vm = serverSources()
        shoot("08f-sources-server-large-text", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true }) { SourcesScreen(vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sourcesWithAServerAndOneCategory() {
        val vm = serverSources(category = "Essays")
        shoot("08j-sources-server-one-category", ready = { vm.screen.value?.server?.outside?.isNotEmpty() == true }) { SourcesScreen(vm) }
    }

    @Test
    fun notInYourPaper() {
        serverSources(category = "Essays")
        val account = runBlocking { db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id }
        val vm = SourceDetailViewModel(SourceRepository(db), account, flowOf(1))
        shoot("08k-not-in-your-paper", ready = { vm.outside.value.isNotEmpty() && vm.detail.value != null }) { NotInPaperScreen(vm, onBack = {}, onOpenAccount = {}) }
    }

    @Test
    fun sourcesWithAServerDown() {
        val vm = serverSources()
        runBlocking {
            val account = db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id
            db.sources().recordFailure(account, Instant.now().minusSeconds(3_600), "Couldn't reach tt-rss.")
        }
        shoot("08l-sources-server-down", ready = { vm.screen.value?.server?.account?.lastError != null }) { SourcesScreen(vm) }
    }

    private fun feedPage(key: String): SourceDetailViewModel {
        val repo = SourceRepository(db)
        val account = ttrssAccount(repo)
        return SourceDetailViewModel(repo, account, flowOf(1), key = key)
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun ttrssFeedPage() {
        val vm = feedPage("5")
        shoot("08c-ttrss-feed-page", ready = { vm.detail.value?.articles?.isNotEmpty() == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedPageLeftOut() {
        val vm = feedPage("7")
        shoot("08d-ttrss-feed-left-out", ready = { vm.detail.value?.text?.leftOut == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedLeaveOutDialog() {
        val vm = feedPage("5")
        shoot(
            "08e-leave-out-dialog", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, dialog = true,
            act = { compose.onNodeWithText("Leave out").performClick() },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedArticleTextDialog() {
        val vm = feedPage("5")
        shoot(
            "08g-article-text-dialog", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, dialog = true,
            act = { compose.onNodeWithText("Article text: Automatic").performClick() },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun leftOutList() {
        val repo = SourceRepository(db)
        val account = ttrssAccount(repo)
        val vm = SourceDetailViewModel(repo, account, flowOf(1))
        shoot("08h-left-out-list", ready = { vm.feeds.value.any { !it.inPaper } }) { LeftOutScreen(vm, onBack = {}, onOpenFeed = {}) }
    }

    /** A phone feed's page from the top: its settings. */
    @Test
    fun phoneFeedSettings() {
        val vm = sourceWithArticles()
        shoot("08i-phone-feed-settings", ready = { vm.detail.value?.articles?.isNotEmpty() == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun settings() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, scheduleEnabled = true, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "name_abc123@kindle.com") } }
        val vm = SettingsViewModel(store) {}
        shoot("07-settings", ready = { vm.settings.value != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun settingsAtLargeText() {
        val vm = SettingsViewModel(store) {}
        shoot("07b-settings-200", ready = { vm.settings.value != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    fun settingsEdition() {
        val vm = SettingsViewModel(store) {}
        shoot("07c-settings-edition", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.EDITION, onBack = {}) }
    }

    @Test
    fun settingsSchedule() {
        runBlocking { store.update { it.copy(scheduleEnabled = true) } }
        val vm = SettingsViewModel(store) {}
        shoot("07d-settings-schedule", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.SCHEDULE, onBack = {}) }
    }

    @Test
    fun settingsDelivery() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "name_abc123@kindle.com") } }
        val vm = SettingsViewModel(store) {}
        shoot("07e-settings-delivery", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.DELIVERY, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun settingsDeliveryAtLargeText() {
        runBlocking { store.update { it.copy(device = Device.BOOX) } }
        val vm = SettingsViewModel(store) {}
        shoot("07g-settings-delivery-200", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.DELIVERY, onBack = {}) }
    }

    /** Settings › Where your feeds come from, signed in to a tt-rss account. */
    private fun feedsFromWithServer(): Pair<SettingsViewModel, FeedsFromViewModel> {
        val server = sampleServer()
        runBlocking {
            ttrss.connect("rss.example.com/tt-rss", server.user, server.password)
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
            db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).forEach { db.sources().recordSuccess(it.id, Instant.now(), null, null, it.title) }
        }
        return SettingsViewModel(store, ttrss.observeStatus()) {} to FeedsFromViewModel(store, ttrss)
    }

    @Test
    fun settingsWithAServer() {
        val (vm, _) = feedsFromWithServer()
        shoot("07h-settings-with-server", ready = { vm.settings.value != null && vm.ttrssStatus.value.source != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    fun settingsFeedsFromServer() {
        val (vm, feeds) = feedsFromWithServer()
        shoot("07i-settings-feeds-from-server", ready = { feeds.state.value?.ttrss?.signedIn == true }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun settingsFeedsFromPhone() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feeds = FeedsFromViewModel(store, ttrss)
        shoot("07j-settings-feeds-from-phone", ready = { feeds.state.value != null }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun settingsLeavingTheServer() {
        val (vm, feeds) = feedsFromWithServer()
        shoot(
            "07k-settings-leave-server", ready = { feeds.state.value?.ttrss?.signedIn == true }, dialog = true,
            act = { compose.onNodeWithText("This phone").performClick() },
        ) { SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds) }
    }

    @Test
    fun settingsServerSignIn() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feeds = FeedsFromViewModel(store, ttrss)
        shoot("07l-settings-server-sign-in", ready = { feeds.state.value != null }, act = { feeds.openSignIn() }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun sourcesSignedOutOfTheServer() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
        val vm = SourcesViewModel(SourceRepository(db), FeedFinder(FakeHttp()), ttrss, settings = store) {}
        shoot("06f-sources-sign-in", ready = { vm.screen.value?.needsSignIn == true }) { SourcesScreen(vm) }
    }

    @Test
    fun settingsNotes() {
        val vm = SettingsViewModel(store) {}
        shoot("07f-settings-notes", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.NOTES, onBack = {}) }
    }
}
