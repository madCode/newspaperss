package com.app.newspaperss.ui

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.app.newspaperss.ui.today.BuildState
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.work.EditionWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class TodayViewModelTest {
    private fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), output, progress)

    @Test
    fun buildStateFollowsTheWorker() {
        assertEquals(BuildState.Idle, TodayViewModel.buildStateOf(null))
        assertEquals(BuildState.Syncing, TodayViewModel.buildStateOf(info(WorkInfo.State.ENQUEUED)))
        assertEquals(
            BuildState.Fetching(3),
            TodayViewModel.buildStateOf(
                info(WorkInfo.State.RUNNING, progress = workDataOf(EditionWorker.STAGE to EditionWorker.STAGE_FETCHING, EditionWorker.FETCHED to 3)),
            ),
        )
        assertEquals(
            BuildState.NothingNew,
            TodayViewModel.buildStateOf(info(WorkInfo.State.SUCCEEDED, workDataOf(EditionWorker.NOTHING_NEW to true))),
        )
        assertEquals(BuildState.Idle, TodayViewModel.buildStateOf(info(WorkInfo.State.SUCCEEDED)))
        assertEquals(
            BuildState.Failed("None of the articles could be read."),
            TodayViewModel.buildStateOf(info(WorkInfo.State.FAILED, workDataOf(EditionWorker.ERROR to "None of the articles could be read."))),
        )
    }
}
