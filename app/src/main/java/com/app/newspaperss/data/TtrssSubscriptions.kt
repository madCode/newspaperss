package com.app.newspaperss.data

import com.app.newspaperss.core.ttrss.TtrssCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Subscribing and unsubscribing in tt-rss for the Add dialog. tt-rss fetches a feed before it
 * answers, which can take half a minute, so each request runs in [scope], the app's own: closing
 * the dialog or leaving Sources doesn't cut it short. Outcomes wait in [results] until Sources
 * shows them, whichever screen asked.
 */
class TtrssSubscriptions(private val ttrss: TtrssRepository, private val scope: CoroutineScope) {
    /** A feed to subscribe to: [title] as the phone found it, and [page] to save instead if tt-rss can't. */
    data class Request(val feedUrl: String, val title: String?, val category: TtrssCategory, val page: String? = null)

    sealed interface Outcome {
        data class Subscribed(val request: Request, val answer: TtrssRepository.Subscribed) : Outcome
        /** Undo of [request]; [error] is null once it's gone from tt-rss. */
        data class Unsubscribed(val request: Request, val error: String?) : Outcome
    }

    /** [id] tells two outcomes for the same feed apart. */
    data class Result(val id: Long, val outcome: Outcome)

    private val _results = MutableStateFlow<List<Result>>(emptyList())
    /** Outcomes not yet shown, oldest first; [shown] takes one off. */
    val results: StateFlow<List<Result>> = _results.asStateFlow()

    /** Feed addresses tt-rss is being asked about. */
    private val asking = MutableStateFlow<Set<String>>(emptySet())
    private var nextId = 0L

    /**
     * Asks tt-rss to subscribe. Returns false, starting nothing, while the same feed is already
     * being asked about: a second tap would otherwise send a second request, and the second
     * answer, "already subscribed", would replace the first's Undo.
     */
    fun subscribe(request: Request): Boolean {
        if (!claim(request.feedUrl)) return false
        scope.launch {
            try {
                post(Outcome.Subscribed(request, ttrss.subscribe(request.feedUrl, request.category)))
            } finally {
                asking.update { it - request.feedUrl }
            }
        }
        return true
    }

    /** Undo: unsubscribes [feedId], the feed [request] added to [login]. */
    fun unsubscribe(request: Request, feedId: Int, login: TtrssRepository.Login?) {
        if (!claim(request.feedUrl)) return
        scope.launch {
            try {
                post(Outcome.Unsubscribed(request, ttrss.unsubscribe(feedId, login)))
            } finally {
                asking.update { it - request.feedUrl }
            }
        }
    }

    fun shown(id: Long) = _results.update { all -> all.filterNot { it.id == id } }

    private fun claim(feedUrl: String): Boolean = synchronized(this) {
        if (feedUrl in asking.value) return false
        asking.value += feedUrl
        true
    }

    private fun post(outcome: Outcome) = _results.update { it + Result(nextId++, outcome) }
}
