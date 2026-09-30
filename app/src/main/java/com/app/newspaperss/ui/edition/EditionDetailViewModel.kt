package com.app.newspaperss.ui.edition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.edition.EditionNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class EditionDetail(
    /** Null if the edition doesn't exist (any more). */
    val edition: EditionEntity?,
    val contents: List<EditionContent>,
    /** The EPUB, or null if it was never written or has been deleted. */
    val file: File?,
) {
    /**
     * A delivered edition's articles can be starred to bring them back. Not one already in an
     * unsent edition, which is going out anyway, nor one whose source was removed.
     */
    fun canStar(content: EditionContent): Boolean =
        edition?.status == EditionStatus.DELIVERED && content.entry.articleId != null &&
            content.state != null && content.state != ArticleState.IN_EDITION

    fun isStarred(content: EditionContent): Boolean = canStar(content) && content.starredAt != null
}

class EditionDetailViewModel(
    private val editions: EditionRepository,
    private val id: Long,
    private val notes: EditionNotes,
    /** Takes down this edition's notification once it's deleted. */
    private val dismissNotification: (Long) -> Unit,
) : ViewModel() {
    val detail: StateFlow<EditionDetail?> = combine(editions.observe(id), editions.observeContents(id)) { edition, contents ->
        EditionDetail(edition, contents, edition?.let(editions::fileOf))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    private val _notesFile = MutableStateFlow<File?>(null)
    /** A notes file waiting for the share sheet; the screen calls [notesShared] once it opens it. */
    val notesFile: StateFlow<File?> = _notesFile.asStateFlow()

    fun writeNotes() {
        viewModelScope.launch {
            val file = try {
                notes.write(id)
            } catch (_: IOException) {
                null
            }
            if (file != null) _notesFile.value = file else _message.value = "Couldn't make notes for this edition"
        }
    }

    fun notesShared() { _notesFile.value = null }

    fun setStarred(articleId: Long, starred: Boolean) {
        viewModelScope.launch { editions.setStarred(articleId, starred) }
    }

    /** Deletes this edition, then [onDeleted] (to leave the screen) if it was deleted. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (editions.delete(id)) {
                dismissNotification(id)
                onDeleted()
            }
        }
    }

    fun markSent() {
        viewModelScope.launch { editions.markSent(id) }
    }
}
