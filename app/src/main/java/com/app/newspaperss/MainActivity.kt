package com.app.newspaperss

import android.net.Uri

import com.app.newspaperss.settings.offersOpen
import com.app.newspaperss.settings.PreviewTextSize
import androidx.compose.runtime.remember
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.app.newspaperss.ui.sources.LeftOutScreen
import com.app.newspaperss.ui.sources.NotInPaperScreen
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.work.Connectivity
import com.app.newspaperss.work.EditionWorker
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.readinglist.ReadingListScreen
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
import com.app.newspaperss.ui.settings.FeedsFromViewModel
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.work.SyncWorker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Default.Newspaper),
    SOURCES("sources", "Sources", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

private const val READING_LIST = "reading-list"
private const val EDITION = "edition/{id}"
private const val ARTICLE = "edition/{id}/article/{position}"
private const val SOURCE = "source/{id}"
/** One of a tt-rss account's feeds: the account's source id and the feed's id there. */
private const val FEED = "source/{id}/feed/{key}"
private const val LEFT_OUT = "source/{id}/left-out"
private const val NOT_IN_PAPER = "source/{id}/not-in-paper"
private const val SETTINGS_PAGE = "settings/{page}"
private val FEEDS_FROM = "settings/${SettingsPage.FEEDS.slug}"

class MainActivity : ComponentActivity() {
    @Volatile private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NewspaperssApp).container
        setContent {
            NewspaperssTheme {
                val settings by container.settings.settings.collectAsState(initial = null)
                if (settings != null) settingsLoaded = true
                when (settings?.onboarded) {
                    null -> {}
                    false -> {
                        val context = LocalContext.current.applicationContext
                        val vm = viewModel {
                            OnboardingViewModel(
                                container.settings, container.sources, container.feedFinder, container.ttrss, createSavedStateHandle(),
                                savedLinks = container.readingList.observeWaiting(),
                            ) { saved ->
                                container.appScope.launch { EditionScheduler.reschedule(context, saved) }
                                EditionWorker.buildNow(context)
                            }
                        }
                        val sources = viewModel { SourcesViewModel(container.sources, container.feedFinder) { SyncWorker.syncNow(context) } }
                        val readingList = viewModel { ReadingListViewModel(container.readingList) }
                        OnboardingScreen(vm, sources, readingList)
                    }
                    true -> App(container, preferOpen = settings?.device == com.app.newspaperss.settings.Device.BOOX, offerOpen = settings?.device.offersOpen, kindleEmail = settings?.kindleEmailTarget)
                }
            }
        }
    }
}

@Composable
private fun App(container: AppContainer, preferOpen: Boolean, offerOpen: Boolean, kindleEmail: com.app.newspaperss.settings.KindleEmail?) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val route = current?.destination?.route
                    val inTab = route == tab.route || (tab == Tab.SOURCES && (route == READING_LIST || route == SOURCE || route == FEED || route == LEFT_OUT || route == NOT_IN_PAPER)) ||
                        (tab == Tab.TODAY && (route == EDITION || route == ARTICLE)) || (tab == Tab.SETTINGS && route == SETTINGS_PAGE)
                    NavigationBarItem(
                        selected = inTab,
                        onClick = {
                            // Tapping the tab you're in goes back to its top screen; restoring
                            // saved state would otherwise reopen the detail screen you're on.
                            if (inTab) nav.popBackStack(tab.route, inclusive = false) else nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // No transitions: animations smear on e-ink readers, and a calm app doesn't need them.
        NavHost(
            nav,
            startDestination = Tab.TODAY.route,
            // Consumed as well as applied: otherwise each screen's own Scaffold and top bar pad
            // for the status and navigation bars a second time.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Tab.TODAY.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel { TodayViewModel(container.editions, EditionWorker.observe(context), container.settings.settings, online = Connectivity.online(context), lastDue = { EditionScheduler.lastDue(context) }, sentToKindle = container.kindleSends.recent) { EditionWorker.buildNow(context) } }
                TodayScreen(vm, onOpenEdition = { nav.navigate("edition/$it") { launchSingleTop = true } })
            }
            composable(EDITION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val vm = viewModel { EditionDetailViewModel(container.editions, id, container.editionNotes, container.kindleSends.recent, container.notifier::dismissFor) }
                EditionDetailScreen(
                    vm,
                    preferOpen = preferOpen,
                    offerOpen = offerOpen,
                    kindleEmail = kindleEmail,
                    onBack = { nav.navigateUp() },
                    onReadArticle = { position -> nav.navigate("edition/$id/article/$position") { launchSingleTop = true } },
                )
            }
            composable(
                ARTICLE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("position") { type = NavType.IntType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val position = entry.arguments?.getInt("position") ?: 0
                val contents by container.editions.observeContents(id).collectAsState(initial = emptyList())
                val editionTitle by produceState<String?>(null, id) { value = container.editions.byId(id)?.title }
                val textSize by remember { container.settings.settings.map { it.previewTextSize } }.collectAsState(initial = null)
                ArticlePreviewScreen(
                    loadFile = { container.editions.byId(id)?.let(container.editions::fileOf) },
                    position = position,
                    title = contents.getOrNull(position)?.entry?.title ?: editionTitle.orEmpty(),
                    onBack = { nav.navigateUp() },
                    textSize = textSize ?: PreviewTextSize.DEFAULT,
                    onTextSize = { size -> container.appScope.launch { container.settings.update { it.copy(previewTextSize = size) } } },
                )
            }
            composable(Tab.SOURCES.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel {
                    SourcesViewModel(container.sources, container.feedFinder, container.ttrss, saveToReadingList = { container.readingList.save(it) }, settings = container.settings) {
                        SyncWorker.syncNow(context)
                    }
                }
                SourcesScreen(
                    vm,
                    onOpenReadingList = { nav.navigate(READING_LIST) },
                    onOpenSource = { nav.navigate("source/$it") { launchSingleTop = true } },
                    onOpenFeed = { id, key -> nav.navigate("source/$id/feed/${Uri.encode(key)}") { launchSingleTop = true } },
                    onOpenLeftOut = { nav.navigate("source/$it/left-out") { launchSingleTop = true } },
                    onOpenNotInPaper = { nav.navigate("source/$it/not-in-paper") { launchSingleTop = true } },
                    onOpenAccount = { nav.navigate(FEEDS_FROM) { launchSingleTop = true } },
                )
            }
            composable(FEED, arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("key") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val key = entry.arguments?.getString("key").orEmpty()
                val context = LocalContext.current.applicationContext
                val vm = viewModel {
                    SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }, key) { SyncWorker.syncNow(context) }
                }
                SourceDetailScreen(vm, onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(FEED, inclusive = true) })
            }
            composable(LEFT_OUT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val vm = viewModel { SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }) }
                LeftOutScreen(vm, onBack = { nav.navigateUp() }, onOpenFeed = { nav.navigate("source/$id/feed/${Uri.encode(it)}") { launchSingleTop = true } })
            }
            composable(NOT_IN_PAPER, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val vm = viewModel { SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }) }
                NotInPaperScreen(vm, onBack = { nav.navigateUp() }, onOpenAccount = { nav.navigate(FEEDS_FROM) { launchSingleTop = true } })
            }
            composable(SOURCE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val context = LocalContext.current.applicationContext
                val vm = viewModel { SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }) { SyncWorker.syncNow(context) } }
                SourceDetailScreen(vm, onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(SOURCE, inclusive = true) })
            }
            composable(READING_LIST) {
                val vm = viewModel { ReadingListViewModel(container.readingList) }
                ReadingListScreen(vm, onBack = { nav.navigateUp() })
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(settingsViewModel(container), onOpen = { nav.navigate("settings/${it.slug}") { launchSingleTop = true } })
            }
            composable(SETTINGS_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) { entry ->
                val page = SettingsPage.of(entry.arguments?.getString("page")) ?: SettingsPage.EDITION
                val context = LocalContext.current.applicationContext
                val feedsFrom = if (page != SettingsPage.FEEDS) null else viewModel { FeedsFromViewModel(container.settings, container.ttrss) { SyncWorker.syncNow(context) } }
                SettingsPageScreen(settingsViewModel(container), page, onBack = { nav.navigateUp() }, feedsFrom = feedsFrom)
            }
        }
    }
}

@Composable
private fun settingsViewModel(container: AppContainer): SettingsViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel { SettingsViewModel(container.settings, container.ttrss.observeStatus()) { container.appScope.launch { EditionScheduler.reschedule(context, it) } } }
}
