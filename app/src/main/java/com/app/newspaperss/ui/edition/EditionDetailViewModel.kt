package com.app.newspaperss.ui.edition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class EditionDetail(
    /** Null if the edition doesn't exist (any more). */
    val edition: EditionEntity?,
    val contents: List<EditionContent>,
    /** The EPUB, or null if it was never written or has been deleted. */
    val file: File?,
) {
    /**
     * Only articles that were delivered can come back: one still in an unsent
     * edition would be marked delivered again when that edition is sent.
     */
    fun canBringBack(content: EditionContent): Boolean =
        edition?.status == EditionStatus.DELIVERED && content.entry.articleId != null && content.state == ArticleState.DELIVERED

    /** An unsent edition's articles also go back to NEW when it's abandoned, so NEW only means brought back once sent. */
    fun wasBroughtBack(content: EditionContent): Boolean =
        edition?.status == EditionStatus.DELIVERED && content.state == ArticleState.NEW
}

class EditionDetailViewModel(private val editions: EditionRepository, private val id: Long) : ViewModel() {
    val detail: StateFlow<EditionDetail?> = combine(editions.observe(id), editions.observeContents(id)) { edition, contents ->
        EditionDetail(edition, contents, edition?.let(editions::fileOf))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    /** Article ids ticked for bringing back. */
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    fun toggle(articleId: Long) {
        _selected.value = _selected.value.let { if (articleId in it) it - articleId else it + articleId }
    }

    fun markSent() {
        viewModelScope.launch { editions.markDelivered(id) }
    }

    fun bringBack() {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        _selected.value = emptySet()
        viewModelScope.launch {
            editions.bringBack(ids)
            _message.value = if (ids.size == 1) "1 article will be in your next edition" else "${ids.size} articles will be in your next edition"
        }
    }
}
