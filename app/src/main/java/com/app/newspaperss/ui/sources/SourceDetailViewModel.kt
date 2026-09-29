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

    /**
     * Sets how many articles this source may have in an edition. Landing back on the edition's
     * own number clears the override, so the source follows that setting again when it changes.
     */
    fun setMaxArticles(max: Int) {
        val default = detail.value?.defaultMax ?: return
        val value = max.coerceIn(1, SettingsViewModel.MAX_PER_SOURCE)
        viewModelScope.launch { repository.setMaxArticles(id, value.takeIf { it != default }) }
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
