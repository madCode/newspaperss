package com.app.newspaperss.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
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
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
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

    @Test
    fun settings() {
        runBlocking { store.update { it.copy(scheduleEnabled = true) } }
        val vm = SettingsViewModel(store) {}
        shoot("07-settings", ready = { vm.settings.value != null }) { SettingsScreen(vm) }
    }
}
