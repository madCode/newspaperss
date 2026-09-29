package com.app.newspaperss.ui.readinglist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.feed.MarkdownChecklist
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ReadingListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadingListViewModel(private val list: ReadingListRepository) : ViewModel() {
    val items: StateFlow<List<ArticleEntity>?> = list.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    /** A one-line result to show the reader, e.g. after an import. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    /** Returns false (and saves nothing) if [text] has no web address in it. */
    fun add(text: String): Boolean {
        val url = MarkdownChecklist.firstUrl(text) ?: return false
        viewModelScope.launch { if (!list.save(url)) _message.value = "That link is already on your list." }
        return true
    }

    fun remove(article: ArticleEntity) {
        viewModelScope.launch { list.remove(article) }
    }

    fun import(text: String) {
        viewModelScope.launch {
            val added = list.importMarkdown(text)
            _message.value = if (added == 1) "Added 1 link." else "Added $added links."
        }
    }

    suspend fun exportText(): String = list.exportMarkdown()
}
