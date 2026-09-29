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
import kotlinx.coroutines.flow.Flow
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

class SourceDetailViewModel(
    private val repository: SourceRepository,
    private val id: Long,
    defaultMax: Flow<Int>,
    private val ttrss: TtrssRepository? = null,
) : ViewModel() {
    /** Null until loaded. */
    val detail: StateFlow<SourceDetail?> =
        combine(repository.observe(id), repository.observeRecentArticles(id), defaultMax) { source, articles, max -> SourceDetail(source, articles, max) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Gives this source its own number of articles per edition, one more or fewer than it has now. */
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

    /** The screen leaves by itself once [detail] shows the source gone. */
    fun remove() {
        val source = detail.value?.source ?: return
        viewModelScope.launch {
            if (source.kind == SourceKind.TTRSS && ttrss != null) ttrss.forget(source) else repository.remove(source)
        }
    }
}
