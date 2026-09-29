package com.app.newspaperss.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.work.EditionWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface BuildState {
    data object Idle : BuildState
    data object Syncing : BuildState
    data class Fetching(val done: Int) : BuildState
    data object NothingNew : BuildState
    data class Failed(val reason: String) : BuildState
}

data class TodayState(val editions: List<EditionEntity>?, val build: BuildState)

class TodayViewModel(
    private val editions: EditionRepository,
    work: Flow<WorkInfo?>,
    private val startBuild: () -> Unit,
) : ViewModel() {
    val state: StateFlow<TodayState> = combine(editions.observeAll(), work) { list, info ->
        TodayState(list, buildStateOf(info))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(null, BuildState.Idle))

    fun makeOneNow() = startBuild()

    fun fileOf(edition: EditionEntity): File? = editions.fileOf(edition)

    fun markSent(edition: EditionEntity) {
        viewModelScope.launch { editions.markDelivered(edition.id) }
    }

    companion object {
        fun buildStateOf(info: WorkInfo?): BuildState = when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BuildState.Syncing
            WorkInfo.State.RUNNING ->
                if (info.progress.getString(EditionWorker.STAGE) == EditionWorker.STAGE_FETCHING) {
                    BuildState.Fetching(info.progress.getInt(EditionWorker.FETCHED, 0))
                } else BuildState.Syncing
            WorkInfo.State.SUCCEEDED ->
                if (info.outputData.getBoolean(EditionWorker.NOTHING_NEW, false)) BuildState.NothingNew else BuildState.Idle
            WorkInfo.State.FAILED -> BuildState.Failed(info.outputData.getString(EditionWorker.ERROR) ?: "Something went wrong.")
            WorkInfo.State.CANCELLED, null -> BuildState.Idle
        }
    }
}
