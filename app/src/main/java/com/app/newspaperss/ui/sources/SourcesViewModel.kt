package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.core.feed.FoundFeed
import com.app.newspaperss.core.lists.CuratedList
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.core.ttrss.TtrssCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A row in Sources: the source, its newest article, and what the full-text check found about its own feed. */
data class SourceRow(val source: SourceEntity, val lastNew: Instant?, val text: PublicationEntity? = null)

sealed interface AddState {
    data object Closed : AddState
    /** @property page a page with no feed, which can be saved to the reading list instead. */
    data class Editing(val input: String = "", val error: String? = null, val page: String? = null) : AddState
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
    /** Set once the login is checked: its categories, to choose from before the account is added. */
    val categories: List<TtrssCategory>? = null,
    /** The chosen category; null is all unread articles. */
    val category: TtrssCategory? = null,
)

/**
 * @param ttrss null hides the tt-rss account option.
 */
class SourcesViewModel(
    private val repository: SourceRepository,
    private val finder: FeedFinder,
    private val ttrss: TtrssRepository? = null,
    /** Saves a page to the reading list; null where that isn't offered. */
    private val saveToReadingList: (suspend (url: String) -> Boolean)? = null,
    private val onSourcesChanged: () -> Unit,
) : ViewModel() {
    val rows: StateFlow<List<SourceRow>?> = combine(repository.observe(), repository.observeActivity(), repository.observeOwnPublications()) { sources, activity, texts ->
        val bySource = activity.associate { it.sourceId to it.lastNew }
        sources.filter { it.kind != SourceKind.READING_LIST }.map { SourceRow(it, bySource[it.id], texts[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The curated lists not added yet, offered in the add dialog. */
    val curatedLists: StateFlow<List<CuratedList>> = repository.observe().map { sources ->
        val added = sources.map { it.url }.toSet()
        CuratedLists.all.filter { CuratedLists.sourceUrl(it) !in added }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                is FindResult.NotFound -> AddState.Editing(input, result.reason, result.page?.takeIf { saveToReadingList != null })
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

    /** A site with no feed: its page goes to the reading list instead, so the reader isn't left at a dead end. */
    fun saveInstead() {
        val page = (add.value as? AddState.Editing)?.page ?: return
        val save = saveToReadingList ?: return
        _add.value = AddState.Closed
        viewModelScope.launch {
            _message.value = if (save(page)) "Saved to your reading list" else "It's already in your reading list"
        }
    }

    fun choose(feed: FoundFeed) {
        viewModelScope.launch {
            subscribe(feed)
            _add.value = AddState.Closed
        }
    }

    fun addList(list: CuratedList) {
        viewModelScope.launch {
            repository.addList(list)
            _add.value = AddState.Closed
            onSourcesChanged()
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
            when (val check = repo.check(form.address, form.user, form.password)) {
                is TtrssRepository.Check.Failed -> _ttrssForm.value = form.copy(error = check.message)
                // Nothing's saved until the reader has chosen, so no sync can take articles from
                // the wrong place first. With only Uncategorized there's nothing to choose.
                is TtrssRepository.Check.Passed ->
                    if (check.categories.none { it.id != 0 }) add(form, category = null)
                    else _ttrssForm.value = form.copy(testing = false, categories = check.categories)
            }
        }
    }

    fun pickTtrssCategory(category: TtrssCategory?) {
        _ttrssForm.value = _ttrssForm.value?.takeIf { it.categories != null && !it.testing }?.copy(category = category)
    }

    fun finishTtrss() {
        val form = _ttrssForm.value?.takeIf { it.categories != null && !it.testing } ?: return
        _ttrssForm.value = form.copy(testing = true)
        connecting = viewModelScope.launch { add(form, form.category) }
    }

    private suspend fun add(form: TtrssForm, category: TtrssCategory?) {
        val repo = ttrss ?: return
        val error = repo.add(form.address, form.user, form.password, category)
        if (error == null) {
            _ttrssForm.value = null
            onSourcesChanged()
        } else {
            _ttrssForm.value = form.copy(testing = false, categories = null, category = null, error = error)
        }
    }

    fun togglePaused(source: SourceEntity) {
        viewModelScope.launch { repository.setPaused(source.id, !source.paused) }
    }

    fun chooseContentMode(source: SourceEntity, mode: ContentMode) {
        viewModelScope.launch { repository.chooseContentMode(source.id, mode) }
    }

    fun refresh() = onSourcesChanged()

    private val _message = MutableStateFlow<String?>(null)
    /** A one-line result to show the reader, e.g. after an import. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    // Files come from the system picker, often a cloud provider: reads and writes can be slow or fail.
    fun importOpml(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IOException("empty")
                }
                val added = repository.importOpml(text)
                if (added > 0) onSourcesChanged()
                when (added) {
                    0 -> "No new sites in that file."
                    1 -> "Added 1 site."
                    else -> "Added $added sites."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't read that file. Is it an OPML export from another reader?"
            }
        }
    }

    fun exportOpml(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = repository.exportOpml()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
                "Your sites are saved."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't save the file."
            }
        }
    }

    private suspend fun subscribe(feed: FoundFeed) {
        repository.addFeed(feed.url, feed.title)
        onSourcesChanged()
    }
}
