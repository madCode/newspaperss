package com.app.newspaperss.ui.readinglist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.feed.MarkdownChecklist
import com.app.newspaperss.core.feed.ReadingListFile
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ReadingListRepository
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    // Files come from the system picker, often a cloud provider: reads and writes can be slow or fail.
    fun import(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IOException("empty")
                }
                importMessage(list.import(text))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't read that file."
            }
        }
    }

    private fun importMessage(result: ReadingListRepository.Imported): String {
        val from = when (result.format) {
            ReadingListFile.Format.MARKDOWN -> ""
            ReadingListFile.Format.POCKET_HTML, ReadingListFile.Format.POCKET_CSV -> " from Pocket"
            ReadingListFile.Format.INSTAPAPER_CSV -> " from Instapaper"
        }
        // An export of only archived links adds nothing to read: say so, or it looks like it worked
        // while onboarding still won't go on.
        val read = when {
            result.added == 0 || result.unread > 0 -> ""
            result.added == 1 -> ", already read"
            else -> ", all already read"
        }
        return "Added ${plural(result.added, "link")}$from$read."
    }

    fun export(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = list.exportMarkdown()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
                "Reading list saved."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't save the file."
            }
        }
    }
}
