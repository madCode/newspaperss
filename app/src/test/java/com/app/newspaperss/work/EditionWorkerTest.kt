package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun anUnexpectedErrorFailsTheWorkWithAReasonToShow() = runTest {
        // A broken database stands in for any error nothing below the worker handles.
        app.container.db.openHelper.writableDatabase.execSQL("DROP TABLE sources")

        val result = TestListenableWorkerBuilder<EditionWorker>(app).build().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val error = result.outputData.getString(EditionWorker.ERROR)
        assertTrue(error, error!!.startsWith("Something went wrong"))
    }
}
