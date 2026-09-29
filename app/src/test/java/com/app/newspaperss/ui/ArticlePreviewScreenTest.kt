package com.app.newspaperss.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ArticlePreviewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theScreenShowsBeforeTheEditionIsReadAndSaysSoIfItsFileIsGone() {
        val file = CompletableDeferred<File?>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file.await() }, position = 0, title = "A story", onBack = {}) }

        // Still reading: the tap has visibly done something, and it isn't reported as missing yet.
        compose.onNodeWithText("A story").assertIsDisplayed()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()

        file.complete(null)
        idleUntil { compose.onAllNodesWithText("file is gone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("file is gone", substring = true).assertIsDisplayed()
    }
}
