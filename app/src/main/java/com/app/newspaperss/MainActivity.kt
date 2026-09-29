package com.app.newspaperss

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.app.newspaperss.data.EditionEntity
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
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.work.EditionWorker
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.readinglist.ReadingListScreen
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
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
                            OnboardingViewModel(container.settings, container.sources, container.feedFinder, createSavedStateHandle()) { saved ->
                                container.appScope.launch { EditionScheduler.reschedule(context, saved) }
                                EditionWorker.buildNow(context)
                            }
                        }
                        val sources = viewModel { SourcesViewModel(container.sources, container.feedFinder, container.ttrss) { SyncWorker.syncNow(context) } }
                        OnboardingScreen(vm, sources)
                    }
                    true -> App(container, preferOpen = settings?.device == com.app.newspaperss.settings.Device.BOOX)
                }
            }
        }
    }
}

@Composable
private fun App(container: AppContainer, preferOpen: Boolean) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val route = current?.destination?.route
                    val inTab = route == tab.route || (tab == Tab.SOURCES && (route == READING_LIST || route == SOURCE)) || (tab == Tab.TODAY && (route == EDITION || route == ARTICLE))
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
            modifier = Modifier.padding(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Tab.TODAY.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel { TodayViewModel(container.editions, EditionWorker.observe(context), container.settings.settings, lastDue = { EditionScheduler.lastDue(context) }) { EditionWorker.buildNow(context) } }
                TodayScreen(vm, onOpenEdition = { nav.navigate("edition/$it") { launchSingleTop = true } })
            }
            composable(EDITION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val vm = viewModel { EditionDetailViewModel(container.editions, id, container.editionNotes) }
                EditionDetailScreen(
                    vm,
                    preferOpen = preferOpen,
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
                val edition by produceState<EditionEntity?>(null, id) { value = container.editions.byId(id) }
                val contents by container.editions.observeContents(id).collectAsState(initial = emptyList())
                val loaded = edition
                if (loaded != null) {
                    ArticlePreviewScreen(
                        file = container.editions.fileOf(loaded),
                        position = position,
                        title = contents.getOrNull(position)?.entry?.title ?: loaded.title,
                        onBack = { nav.navigateUp() },
                    )
                }
            }
            composable(Tab.SOURCES.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel { SourcesViewModel(container.sources, container.feedFinder, container.ttrss) { SyncWorker.syncNow(context) } }
                SourcesScreen(
                    vm,
                    onOpenReadingList = { nav.navigate(READING_LIST) },
                    onOpenSource = { nav.navigate("source/$it") { launchSingleTop = true } },
                )
            }
            composable(SOURCE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val context = LocalContext.current.applicationContext
                val vm = viewModel { SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }, container.ttrss) { SyncWorker.syncNow(context) } }
                SourceDetailScreen(vm, onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(SOURCE, inclusive = true) })
            }
            composable(READING_LIST) {
                val vm = viewModel { ReadingListViewModel(container.readingList) }
                ReadingListScreen(vm, onBack = { nav.navigateUp() })
            }
            composable(Tab.SETTINGS.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel { SettingsViewModel(container.settings) { container.appScope.launch { EditionScheduler.reschedule(context, it) } } }
                SettingsScreen(vm)
            }
        }
    }
}
