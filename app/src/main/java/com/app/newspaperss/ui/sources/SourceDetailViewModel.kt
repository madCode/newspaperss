package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleHistory
import com.app.newspaperss.data.PaidOnlyCount
import com.app.newspaperss.data.MarkedRead
import com.app.newspaperss.data.StarBatch
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.data.FeedChoice
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
 * [defaultMax] is the edition's own per-source cap, which [PublicationEntity.maxArticles] replaces.
 * [text] is the publication shown: its settings and what the full-text check found; null until
 * it has any.
 */
data class SourceDetail(
    val source: SourceEntity?,
    val articles: List<ArticleEntity>,
    val defaultMax: Int,
    val text: PublicationEntity? = null,
    /** When each delivered article went out, and which edition holds each one in an unsent edition. */
    val history: Map<Long, ArticleHistory> = emptyMap(),
    /** Its paid posts with next to nothing free so far, and how many were left out. */
    val paidOnly: PaidOnlyCount = PaidOnlyCount(0, 0),
)

/** The tt-rss category chooser: loading, the choices, or why they couldn't be loaded. */
sealed interface CategoryPicker {
    data object Loading : CategoryPicker
    data class Choosing(val categories: List<TtrssCategory>) : CategoryPicker
    data class Failed(val message: String) : CategoryPicker
}

/**
 * A source's page, or with [key] one of a tt-rss account's feeds: the same settings about the
 * writing, on its [PublicationEntity].
 *
 * @param onSourceChanged asks for a sync, after a change to what the source fetches.
 */
class SourceDetailViewModel(
    private val repository: SourceRepository,
    private val id: Long,
    defaultMax: Flow<Int>,
    private val ttrss: TtrssRepository? = null,
    val key: String = PublicationEntity.OWN,
    private val onSourceChanged: () -> Unit = {},
) : ViewModel() {
    /** A tt-rss feed's page rather than a source's. */
    val isFeed get() = key != PublicationEntity.OWN

    /** Null until loaded. */
    val detail: StateFlow<SourceDetail?> =
        combine(
            combine(repository.observe(id), repository.observeRecentArticles(id, key), defaultMax, ::Triple),
            repository.observePublication(id, key), repository.observeHistory(id), repository.observePaidOnly(id),
        ) { (source, articles, max), publication, history, paidOnly ->
            SourceDetail(source, articles, max, publication, history, paidOnly)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Gives this publication its own number of articles per edition, one more or fewer than it has
     * now. Never below 1, so "fewer" at the edition's default of 1 makes that soft number a hard limit.
     */
    fun stepMaxArticles(delta: Int) {
        val default = detail.value?.defaultMax ?: return
        viewModelScope.launch { repository.stepMaxArticles(id, key, delta, default, SettingsViewModel.MAX_PER_SOURCE) }
    }

    /** Back to the edition's own number, following it when it changes. */
    fun followEditionMax() {
        viewModelScope.launch { repository.setMaxArticles(id, key, null) }
    }

    /** A tt-rss feed's page: leaves the feed out of the paper, or brings it back. [title] if its name is known. */
    fun setInPaper(title: String?, inPaper: Boolean) {
        if (!isFeed) return
        viewModelScope.launch {
            repository.setFeedInPaper(id, FeedChoice(key, title ?: key, !inPaper), inPaper)
            if (inPaper) onSourceChanged()
        }
    }

    fun togglePaused() {
        val source = detail.value?.source ?: return
        viewModelScope.launch { repository.setPaused(source.id, !source.paused) }
    }

    fun setSkipPaidPosts(skip: Boolean) {
        viewModelScope.launch { repository.setSkipPaidPosts(id, skip) }
    }

    fun chooseContentMode(mode: ContentMode) {
        viewModelScope.launch { repository.chooseContentMode(id, key, mode) }
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

    /** A tt-rss account's feeds, for leaving some out of the paper. */
    val feeds: StateFlow<List<FeedChoice>> = repository.observeFeeds(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFeedInPaper(feed: FeedChoice, inPaper: Boolean) {
        viewModelScope.launch { repository.setFeedInPaper(id, feed, inPaper) }
    }

    private val _startingFresh = MutableStateFlow(false)
    /** tt-rss is being asked to mark its backlog read. */
    val startingFresh: StateFlow<Boolean> = _startingFresh.asStateFlow()

    fun startFresh() {
        val repo = ttrss ?: return
        if (_startingFresh.value) return
        _startingFresh.value = true
        viewModelScope.launch {
            try {
                val category = detail.value?.source?.ttrssCategoryTitle
                val problem = repo.startFresh(id)
                _notice.value = problem ?: if (category != null) {
                    "Done. $category has only the last two weeks unread in tt-rss now."
                } else {
                    "Done. tt-rss has only the last two weeks unread now."
                }
                if (problem == null) onSourceChanged()
            } finally {
                _startingFresh.value = false
            }
        }
    }

    fun setStarred(articleId: Long, starred: Boolean) {
        viewModelScope.launch { repository.setStarred(articleId, starred) }
    }

    /** An edition is being made: unstarring and marking read wait (see [com.app.newspaperss.data.BUILD_HOLD]). */
    val building: StateFlow<Boolean> = repository.observeBuilding().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** A change the snackbar can undo in one go, however many articles it touched. */
    sealed interface Change {
        data class Read(val marked: List<MarkedRead>) : Change
        data class Stars(val batch: StarBatch) : Change
    }

    /** One snackbar with Undo to show. [seq] makes each change its own offer, even of the same articles twice. */
    data class UndoOffer(val change: Change, val seq: Long)

    private var offers = 0L
    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    /** The Undo to offer now, or null. The screen shows it once and calls [undoOfferEnded]. */
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Why a change didn't (fully) happen, or null. The screen shows it once and calls [noticeShown]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() {
        _notice.value = null
    }

    /** No Undo: tapping again undoes it. */
    fun toggleRead(articleId: Long) {
        viewModelScope.launch { if (repository.toggleRead(articleId) == SourceRepository.Toggled.HELD) _notice.value = HELD_NOTICE }
    }

    fun markRead(articleIds: Collection<Long>) {
        viewModelScope.launch {
            val batch = repository.markRead(articleIds)
            if (batch.marked.isNotEmpty()) _undoOffer.value = UndoOffer(Change.Read(batch.marked), ++offers)
            if (batch.heldBack > 0) _notice.value = HELD_NOTICE
        }
    }

    /** Stars or unstars the selected articles together, with one Undo. */
    fun setStarred(articleIds: Collection<Long>, starred: Boolean) {
        viewModelScope.launch {
            val batch = repository.setStarred(articleIds, starred)
            if (batch.changed.isNotEmpty()) _undoOffer.value = UndoOffer(Change.Stars(batch), ++offers)
            if (batch.heldBack > 0) _notice.value = HELD_NOTICE
        }
    }

    fun undo(offer: UndoOffer) {
        undoOfferEnded(offer)
        viewModelScope.launch {
            val missed = when (val change = offer.change) {
                is Change.Read -> change.marked.size - repository.undoMarkRead(change.marked)
                is Change.Stars -> repository.undoStars(change.batch)
            }
            // Missed: an edition took them in meanwhile, or a build holds the unstarring.
            if (missed > 0) _notice.value = "${plural(missed, "article")} couldn't be changed back: an edition is being made or already has ${if (missed == 1) "it" else "them"}."
        }
    }

    fun undoOfferEnded(offer: UndoOffer) {
        _undoOffer.compareAndSet(offer, null)
    }

    /** The screen leaves by itself once [detail] shows the source gone. */
    fun remove() {
        val source = detail.value?.source ?: return
        viewModelScope.launch {
            if (source.kind == SourceKind.TTRSS && ttrss != null) ttrss.forget(source) else repository.remove(source)
        }
    }
}

/** Shown when a build held back a change the reader asked for, e.g. one that started as she tapped. */
internal const val HELD_NOTICE = "Your edition is being made, so nothing was changed. Try again once it's ready."
