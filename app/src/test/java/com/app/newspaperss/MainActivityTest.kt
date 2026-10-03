package com.app.newspaperss

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.app.newspaperss.settings.FeedsFrom
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(app)

    @After fun closeWork() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun launchWith(onboarded: Boolean) {
        runBlocking { app.container.settings.update { it.copy(onboarded = onboarded) } }
        ActivityScenario.launch(MainActivity::class.java)
    }

    private fun shows(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aFirstRunStartsWithOnboarding() {
        launchWith(onboarded = false)
        shows("Get started")
    }

    /** A screen inside the tabs clears the status bar once, not again on top of the tabs' own padding. */
    @Test
    fun aScreensTopBarSitsJustBelowTheStatusBar() {
        runBlocking { app.container.settings.update { it.copy(onboarded = true) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shows("Sources")
        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("About 30 minutes")
        val density = app.resources.displayMetrics.density
        val statusBar = (40 * density).toInt()
        scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()

        val title = compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        // Counted twice, the title would sit a whole status bar lower.
        assertTrue("title at $title", title > statusBar && title < 2 * statusBar)
    }

    @Test
    fun aSettingsPageOpensFromTheSummaryAndTheTabGoesBackToIt() {
        launchWith(onboarded = true)
        shows("Sources")
        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("Reading notes")
        compose.onNodeWithText("Reading notes").performScrollTo().performClick()
        shows("Save notes for each edition")

        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("About 30 minutes")
        compose.onNodeWithText("Save notes for each edition").assertDoesNotExist()
    }

    @Test
    fun anUpgradedTtrssReaderWhosePasswordIsGoneIsAskedToSignInFromSources() = try {
        // tt-rss was added before there was a choice, and its login didn't survive (a restore, say).
        runBlocking {
            app.container.sources.addTtrss("https://rss.example.com/tt-rss/api/")
            app.container.settings.update { it.copy(feedsFrom = null) }
            assertEquals(FeedsFrom.SERVER, app.container.settleFeedsFrom())
        }
        launchWith(onboarded = true)
        compose.onAllNodes(hasText("Sources") and hasClickAction()).onFirst().performClick()
        shows("Sign in to your tt-rss")
        compose.onNodeWithText("Sign in").performClick()
        shows("Where your feeds live")
        shows("Sign in again")
    } finally {
        runBlocking { app.container.settings.update { it.copy(feedsFrom = null) } }
    }

    @Test
    fun onceOnboardedTheAppOpensOnToday() {
        launchWith(onboarded = true)
        shows("Make an edition now")
        shows("Sources")
    }
}
