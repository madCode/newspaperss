package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.core.feed.FoundFeed
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SourceRow(val source: SourceEntity, val waiting: Int)

sealed interface AddState {
    data object Closed : AddState
    data class Editing(val input: String = "", val error: String? = null) : AddState
    data class Searching(val input: String) : AddState
    data class Choosing(val input: String, val feeds: List<FoundFeed>) : AddState
}

/** The "Add tt-rss account" form. */
data class TtrssForm(
    val address: String = "",
    val user: String = "",
    val password: String = "",
    val testing: Boolean = false,
    val error: String? = null,
)

/**
 * @param ttrss null hides the tt-rss account option.
 */
class SourcesViewModel(
    private val repository: SourceRepository,
    private val finder: FeedFinder,
    private val ttrss: TtrssRepository? = null,
    private val onSourcesChanged: () -> Unit,
) : ViewModel() {
    val rows: StateFlow<List<SourceRow>?> = combine(repository.observe(), repository.observeWaitingCounts()) { sources, counts ->
        val bySource = counts.associate { it.sourceId to it.count }
        sources.filter { it.kind != SourceKind.READING_LIST }.map { SourceRow(it, bySource[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _add = MutableStateFlow<AddState>(AddState.Closed)
    val add: StateFlow<AddState> = _add.asStateFlow()

    fun openAdd() { _add.value = AddState.Editing() }
    private var search: Job? = null

    fun closeAdd() {
        search?.cancel()
        _add.value = AddState.Closed
    }
    fun editInput(text: String) { _add.value = AddState.Editing(text) }

    fun find() {
        val input = (add.value as? AddState.Editing)?.input ?: return
        _add.value = AddState.Searching(input)
        search = viewModelScope.launch {
            _add.value = when (val result = finder.find(input)) {
                is FindResult.NotFound -> AddState.Editing(input, result.reason)
                is FindResult.Found ->
                    if (result.feeds.size == 1) {
                        subscribe(result.feeds.single())
                        AddState.Closed
                    } else {
                        AddState.Choosing(input, result.feeds)
                    }
            }
        }
    }

    fun choose(feed: FoundFeed) {
        viewModelScope.launch {
            subscribe(feed)
            _add.value = AddState.Closed
        }
    }

    fun remove(source: SourceEntity) {
        viewModelScope.launch {
            if (source.kind == SourceKind.TTRSS && ttrss != null) ttrss.forget(source) else repository.remove(source)
        }
    }

    val canAddTtrss get() = ttrss != null

    private val _ttrssForm = MutableStateFlow<TtrssForm?>(null)
    /** The open tt-rss form, or null when it's closed. */
    val ttrssForm: StateFlow<TtrssForm?> = _ttrssForm.asStateFlow()
    private var connecting: Job? = null

    fun openTtrss() { _ttrssForm.value = TtrssForm() }

    fun closeTtrss() {
        connecting?.cancel()
        _ttrssForm.value = null
    }

    fun editTtrss(form: TtrssForm) {
        if (_ttrssForm.value?.testing == false) _ttrssForm.value = form.copy(error = null)
    }

    fun connectTtrss() {
        val form = _ttrssForm.value?.takeIf { !it.testing && it.address.isNotBlank() } ?: return
        val repo = ttrss ?: return
        _ttrssForm.value = form.copy(testing = true, error = null)
        connecting = viewModelScope.launch {
            val error = repo.connect(form.address, form.user, form.password)
            if (error == null) {
                _ttrssForm.value = null
                onSourcesChanged()
            } else {
                _ttrssForm.value = form.copy(error = error)
            }
        }
    }

    fun togglePaused(source: SourceEntity) {
        viewModelScope.launch { repository.setPaused(source.id, !source.paused) }
    }

    fun refresh() = onSourcesChanged()

    private suspend fun subscribe(feed: FoundFeed) {
        repository.addFeed(feed.url, feed.title)
        onSourcesChanged()
    }
}
