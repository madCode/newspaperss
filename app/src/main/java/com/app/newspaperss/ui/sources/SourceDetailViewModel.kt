package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.core.ttrss.TtrssCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [source] is null once the source is gone, e.g. removed from here.
 * [defaultMax] is the edition's own per-source cap, which [SourceEntity.maxArticles] replaces.
 */
data class SourceDetail(val source: SourceEntity?, val articles: List<ArticleEntity>, val defaultMax: Int)

/** The tt-rss category chooser: loading, the choices, or why they couldn't be loaded. */
sealed interface CategoryPicker {
    data object Loading : CategoryPicker
    data class Choosing(val categories: List<TtrssCategory>) : CategoryPicker
    data class Failed(val message: String) : CategoryPicker
}

/**
 * @param onSourceChanged asks for a sync, after a change to what the source fetches.
 */
class SourceDetailViewModel(
    private val repository: SourceRepository,
    private val id: Long,
    defaultMax: Flow<Int>,
    private val ttrss: TtrssRepository? = null,
    private val onSourceChanged: () -> Unit = {},
) : ViewModel() {
    /** Null until loaded. */
    val detail: StateFlow<SourceDetail?> =
        combine(repository.observe(id), repository.observeRecentArticles(id), defaultMax) { source, articles, max -> SourceDetail(source, articles, max) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Gives this source its own number of articles per edition, one more or fewer than it has now.
     * Never below 1, so "fewer" at the edition's default of 1 makes that soft number a hard limit.
     */
    fun stepMaxArticles(delta: Int) {
        val default = detail.value?.defaultMax ?: return
        viewModelScope.launch { repository.stepMaxArticles(id, delta, default, SettingsViewModel.MAX_PER_SOURCE) }
    }

    /** Back to the edition's own number, following it when it changes. */
    fun followEditionMax() {
        viewModelScope.launch { repository.setMaxArticles(id, null) }
    }

    fun togglePaused() {
        val source = detail.value?.source ?: return
        viewModelScope.launch { repository.setPaused(source.id, !source.paused) }
    }

    fun chooseContentMode(mode: ContentMode) {
        viewModelScope.launch { repository.chooseContentMode(id, mode) }
    }

    private val _categories = MutableStateFlow<CategoryPicker?>(null)
    /** The open tt-rss category chooser, or null when it's closed. */
    val categories: StateFlow<CategoryPicker?> = _categories.asStateFlow()
    private var loadingCategories: Job? = null

    fun openCategories() {
        val repo = ttrss ?: return
        _categories.value = CategoryPicker.Loading
        loadingCategories = viewModelScope.launch {
            _categories.value = when (val result = repo.categories()) {
                is TtrssRepository.Categories.Loaded -> CategoryPicker.Choosing(result.categories)
                is TtrssRepository.Categories.Failed -> CategoryPicker.Failed(result.message)
            }
        }
    }

    fun closeCategories() {
        loadingCategories?.cancel()
        _categories.value = null
    }

    /** Null takes unread articles from every feed. */
    fun chooseCategory(category: TtrssCategory?) {
        val repo = ttrss ?: return
        _categories.value = null
        viewModelScope.launch {
            repo.chooseCategory(id, category)
            onSourceChanged()
        }
    }

    fun setMarkReadOnServer(markRead: Boolean) {
        val repo = ttrss ?: return
        viewModelScope.launch { repo.setMarkRead(id, markRead) }
    }

    /** The screen leaves by itself once [detail] shows the source gone. */
    fun remove() {
        val source = detail.value?.source ?: return
        viewModelScope.launch {
            if (source.kind == SourceKind.TTRSS && ttrss != null) ttrss.forget(source) else repository.remove(source)
        }
    }
}
