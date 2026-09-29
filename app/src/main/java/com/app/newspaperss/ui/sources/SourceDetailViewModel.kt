package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [source] is null once the source is gone, e.g. removed from here. */
data class SourceDetail(val source: SourceEntity?, val articles: List<ArticleEntity>)

class SourceDetailViewModel(
    private val repository: SourceRepository,
    private val id: Long,
    private val ttrss: TtrssRepository? = null,
) : ViewModel() {
    /** Null until loaded. */
    val detail: StateFlow<SourceDetail?> =
        combine(repository.observe(id), repository.observeRecentArticles(id)) { source, articles -> SourceDetail(source, articles) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun togglePaused() {
        val source = detail.value?.source ?: return
        viewModelScope.launch { repository.setPaused(source.id, !source.paused) }
    }

    fun chooseContentMode(mode: ContentMode) {
        viewModelScope.launch { repository.chooseContentMode(id, mode) }
    }

    fun remove(onRemoved: () -> Unit) {
        val source = detail.value?.source ?: return
        viewModelScope.launch {
            if (source.kind == SourceKind.TTRSS && ttrss != null) ttrss.forget(source) else repository.remove(source)
            onRemoved()
        }
    }
}
