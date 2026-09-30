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

    @Test
    fun aQueuedBuildWithNoConnectionIsWaitingForOneNotCheckingSources() {
        assertEquals(BuildState.WaitingForNetwork, TodayViewModel.buildStateOf(info(WorkInfo.State.ENQUEUED), online = false))
        // Once running it has the connection it needed.
        assertEquals(BuildState.Syncing, TodayViewModel.buildStateOf(info(WorkInfo.State.RUNNING), online = false))
    }

    @Test
    fun nextEditionReadsLikeASentence() {
        val now = java.time.ZonedDateTime.of(2026, 9, 29, 7, 0, 0, 0, java.time.ZoneOffset.UTC)
        val on = com.app.newspaperss.settings.Settings(scheduleEnabled = true)
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
        assertEquals("Tomorrow at 6:30\u202fAM \u00b7 about 30 min", TodayViewModel.nextEdition(on, now))
        assertEquals(null, TodayViewModel.nextEdition(on.copy(scheduleEnabled = false), now))
        // At 6:10, today's 6:30 edition has already been started early.
        val started = now.withHour(6).withMinute(30).toInstant().toEpochMilli()
        assertEquals("Tomorrow at 6:30\u202fAM \u00b7 about 30 min", TodayViewModel.nextEdition(on, now.withHour(6).withMinute(10), started))
        val weekends = on.copy(schedule = on.schedule.copy(days = setOf(java.time.DayOfWeek.SATURDAY)))
        assertEquals("Saturday at 6:30\u202fAM \u00b7 about 30 min", TodayViewModel.nextEdition(weekends, now))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
