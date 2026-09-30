package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class NotesWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun anEditionThatIsGoneIsNotRetried() = runTest {
        app.container.settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
        val result = TestListenableWorkerBuilder<NotesWorker>(app)
            .setInputData(workDataOf(NotesWorker.EDITION_ID to 42L))
            .build()
            .doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
