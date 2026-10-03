package com.app.newspaperss.ui

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isRoot
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
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
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
import org.robolectric.util.ReflectionHelpers
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

    /** [act] runs once [ready], before the capture, e.g. to enter a mode through the UI. */
    private fun shoot(name: String, ready: () -> Boolean = { true }, act: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent { NewspaperssTheme(content) }
        idleUntil(condition = ready)
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        File(out, "$name.png").outputStream().use { capture().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The screen with any open menu or dialog drawn over it where it sits. They are windows of their
     * own, and Robolectric captures another window's root as the screen's pixels, so each is drawn
     * from its view instead.
     */
    private fun capture(): Bitmap {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        if (roots.size == 1) return compose.onRoot().captureToImage().asAndroidBitmap()
        val screen = roots.first()
        val bitmap = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val windows = ReflectionHelpers.getField<List<View>>(global, "mViews")
        roots.drop(1).zip(windows.takeLast(roots.size - 1)).forEach { (root, view) ->
            canvas.save()
            canvas.translate(root.positionOnScreen.x - screen.positionOnScreen.x, root.positionOnScreen.y - screen.positionOnScreen.y)
            view.draw(canvas)
            canvas.restore()
        }
        return bitmap
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
    fun onboardingSources() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KOBO); next(); togglePack(StarterPacks.all[1].name) }
        shoot("03-onboarding-sources") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingSize() {
        val vm = onboarding().apply {
            next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com"); next(); toggleFeed(StarterPacks.all[0].feeds[0].url); next()
        }
        shoot("04-onboarding-size") { OnboardingScreen(vm) }
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

    private fun installKindle() = installApp(
        ApplicationProvider.getApplicationContext(),
        packageName = EditionIntents.KINDLE_PACKAGE,
        label = "Kindle",
        filters = listOf(IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }),
    )

    /** Today with yesterday's edition and today's, both sent, for a reader with [device]. */
    private fun todaySent(name: String, device: Device, kindleNote: Boolean = false, act: () -> Unit = {}) {
        if (device == Device.KINDLE) installKindle()
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val latest = runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = now.minusSeconds(86_400), status = EditionStatus.DELIVERED, articleCount = 7, minutes = 31.0))
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.DELIVERED, fileName = "x.epub", articleCount = 8, minutes = 33.4, deliveredAt = now))
        }
        val sent = if (kindleNote) mapOf(latest to KindleSend.APP) else emptyMap()
        val vm = TodayViewModel(EditionRepository(db, files), flowOf(null), settings = flowOf(Settings(device = device)), sentToKindle = flowOf(sent)) {}
        shoot(name, ready = { vm.state.value.editions?.isNotEmpty() == true }, act = act) { TodayScreen(vm) }
    }

    /** Opens a sent edition's menu, where there is one (not before it was added). */
    private fun openMenu(description: String) {
        val menu = hasContentDescription(description)
        if (compose.onAllNodes(menu).fetchSemanticsNodes().isNotEmpty()) compose.onNode(menu).performClick()
    }

    @Test
    fun todaySentKindle() = todaySent("05d-today-sent-kindle", Device.KINDLE, kindleNote = true)

    @Test
    fun todaySentBoox() = todaySent("05e-today-sent-boox", Device.BOOX)

    @Test
    fun todaySentMenu() = todaySent("05f-today-sent-menu", Device.KINDLE) { openMenu("More options for Tuesday Morning Edition") }

    @Test
    fun editionDetail() {
        val vm = sentEdition()
        shoot("05b-edition-detail", ready = { vm.detail.value?.contents?.isNotEmpty() == true }) { EditionDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun editionDetailKindleMenu() {
        installKindle()
        val vm = sentEdition()
        shoot("05g-edition-detail-kindle-menu", ready = { vm.detail.value?.contents?.isNotEmpty() == true }, act = { openMenu("More options") }) {
            EditionDetailScreen(vm, onBack = {}, offerOpen = false, kindleReader = true)
        }
    }

    private fun sentEdition(): EditionDetailViewModel {
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
        return EditionDetailViewModel(EditionRepository(db, tmp.newFolder().apply { resolve("e.epub").writeText("epub") }), id, EditionNotes(db, tmp.newFolder())) {}
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
        val vm = SourcesViewModel(repo, FeedFinder(FakeHttp())) {}
        shoot("06-sources", ready = { vm.rows.value?.isNotEmpty() == true }) { SourcesScreen(vm) }
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
            db.sources().setFullText(id, ContentMode.PAGE, FullTextEvidence.PAGE_LONGER, 3, null)
            id
        }
        return SourceDetailViewModel(repo, id, flowOf(1))
    }

    /** Scrolled to the articles, which sit below the source's settings. */
    private fun scrollToArticles() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("articles", substring = true) and isHeading())
    }

    @Test
    fun sourceDetail() {
        val vm = sourceWithArticles()
        shoot("06b-source-detail", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    /** A busy source lists only its newest articles, and the heading says so rather than looking like a total. */
    @Test
    fun sourceDetailNewest() {
        val repo = SourceRepository(db)
        val id = runBlocking {
            val id = repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
            db.articles().insertNew((1..40).map { i ->
                ArticleEntity(sourceId = id, guid = "$i", url = "https://www.theguardian.com/$i", title = "Morning briefing, part $i", discoveredAt = Instant.now().minusSeconds(600L * i))
            })
            db.sources().recordSuccess(id, Instant.now(), null, "https://www.theguardian.com", "")
            id
        }
        val vm = SourceDetailViewModel(repo, id, flowOf(1))
        shoot("06f-source-detail-newest", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
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

    @Test
    fun settingsNotes() {
        val vm = SettingsViewModel(store) {}
        shoot("07f-settings-notes", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.NOTES, onBack = {}) }
    }
}
