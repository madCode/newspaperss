package com.app.newspaperss.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.ui.sources.PhoneFeedMover
import com.app.newspaperss.data.TtrssStatus
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.ui.ttrss.TtrssForm
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The offer to move the phone's feeds after signing in; [found] says what the sign-in found. */
data class MoveOfferState(val found: String?)

/** The setup chosen, and the tt-rss account as it is now. */
data class FeedsFromState(val choice: FeedsFrom, val ttrss: TtrssStatus)

/** The tt-rss category chooser: loading, the choices, or why they couldn't be loaded. */
sealed interface CategoryPicker {
    data object Loading : CategoryPicker
    data class Choosing(val categories: List<TtrssCategory>) : CategoryPicker
    data class Failed(val message: String) : CategoryPicker
}

/**
 * Settings › Where your feeds come from: this phone or the reader's tt-rss, and with tt-rss, the
 * account's settings. Picking the other setup changes nothing until it's confirmed: signing in
 * one way, the leaving dialog the other.
 *
 * @param onSourcesChanged asks for a sync, after a change to what the account fetches.
 * @param moves with [sources], offers to move phone feeds into tt-rss straight after signing in.
 */
class FeedsFromViewModel(
    private val settings: SettingsStore,
    private val ttrss: TtrssRepository,
    private val moves: FeedMoves? = null,
    sources: SourceRepository? = null,
    private val onSourcesChanged: () -> Unit = {},
) : ViewModel() {
    /** Moving phone feeds into tt-rss; null where that isn't offered. */
    val mover: PhoneFeedMover? = if (moves != null && sources != null) PhoneFeedMover(moves, ttrss, settings, sources, viewModelScope) else null

    private val _offer = MutableStateFlow<MoveOfferState?>(null)
    /** Offering to move the phone's feeds, after signing in from the phone setup; null once answered. */
    val offer: StateFlow<MoveOfferState?> = _offer.asStateFlow()

    fun notNow() { _offer.value = null }

    fun moveFromOffer() {
        _offer.value = null
        mover?.open()
    }

    /** Null until loaded. */
    val state: StateFlow<FeedsFromState?> = combine(settings.settings, ttrss.observeStatus()) { s, status ->
        FeedsFromState(s.feedsFrom(hasServer = status.source != null), status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _form = MutableStateFlow<TtrssForm?>(null)
    /** The sign-in form while it's open, in place of the page. */
    val form: StateFlow<TtrssForm?> = _form.asStateFlow()
    private var signingIn: Job? = null

    /** Filled in from the saved login, so signing in again is the password alone. */
    fun openSignIn() {
        val status = state.value?.ttrss
        val address = status?.source?.url?.removeSuffix("/")?.removeSuffix("/api").orEmpty()
        _form.value = TtrssForm(address = address, user = status?.user.orEmpty())
    }

    fun closeSignIn() {
        signingIn?.cancel()
        _form.value = null
    }

    fun editSignIn(form: TtrssForm) {
        if (_form.value?.testing == false) _form.value = form.copy(error = null)
    }

    fun signIn() {
        val form = _form.value?.takeIf { it.canSubmit } ?: return
        _form.value = form.copy(testing = true, error = null)
        signingIn = viewModelScope.launch {
            val fromPhone = settings.current().feedsFrom(hasServer = ttrss.observeStatus().first().source != null) == FeedsFrom.PHONE
            val before = ttrss.savedLogin()
            val result = ttrss.signIn(form.address, form.user, form.password) { settings.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
            when (result) {
                is TtrssRepository.SignIn.Failed -> _form.value = form.copy(error = result.message)
                is TtrssRepository.SignIn.SignedIn -> {
                    // Another account: feeds moved into the last one, kept for their stars, aren't
                    // in this one, so they're phone feeds again, to move here if wanted.
                    if (before != null && before != ttrss.savedLogin()) withContext(NonCancellable) { moves?.restore() }
                    _form.value = null
                    onSourcesChanged()
                    // Only on the way from the phone: signing in again isn't the moment to ask.
                    if (fromPhone && mover != null) {
                        val found = result.found?.takeIf { it.feeds > 0 }?.let { " ${plural(it.feeds, "feed")} in ${plural(it.categories, "category", "categories")}." }
                        _offer.value = MoveOfferState("Signed in.${found.orEmpty()}")
                    }
                }
            }
        }
    }

    private val _leaving = MutableStateFlow(false)
    /** The dialog that says what switching to this phone does, before it's done. */
    val leaving: StateFlow<Boolean> = _leaving.asStateFlow()

    /**
     * With no account there's nothing to lose, so it switches at once (clearing any login saved
     * without its source); otherwise it asks first.
     */
    fun usePhone() {
        if (state.value?.ttrss?.source == null) {
            viewModelScope.launch {
                ttrss.signOut()
                settings.update { it.copy(feedsFrom = FeedsFrom.PHONE) }
            }
        } else {
            _leaving.value = true
        }
    }

    fun cancelLeaving() { _leaving.value = false }

    /** Signs out of tt-rss and removes its source and articles; the setup is this phone after. */
    fun leave() {
        _leaving.value = false
        // Not cancelled by leaving the page straight after: the reader asked for this phone.
        // Signed out first: if the app dies between, it's the server setup with no account,
        // which says so; the other way round would leave a phone setup still fetching tt-rss.
        _offer.value = null
        viewModelScope.launch {
            withContext(NonCancellable) {
                // Moved feeds still kept for their stars are phone feeds again: with no server
                // to come from, they'd otherwise stay hidden until deleted.
                moves?.restore()
                ttrss.signOut()
                settings.update { it.copy(feedsFrom = FeedsFrom.PHONE) }
            }
        }
    }

    private val _categories = MutableStateFlow<CategoryPicker?>(null)
    /** The open category chooser, or null when it's closed. */
    val categories: StateFlow<CategoryPicker?> = _categories.asStateFlow()
    private var loadingCategories: Job? = null

    fun openCategories() {
        _categories.value = CategoryPicker.Loading
        loadingCategories = viewModelScope.launch {
            _categories.value = when (val result = ttrss.categories()) {
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
        val source = state.value?.ttrss?.source ?: return
        _categories.value = null
        viewModelScope.launch {
            ttrss.chooseCategory(source.id, category)
            onSourcesChanged()
        }
    }

    fun resume() {
        val source = state.value?.ttrss?.source ?: return
        viewModelScope.launch {
            ttrss.resume(source.id)
            onSourcesChanged()
        }
    }

    fun setMarkRead(markRead: Boolean) {
        val source = state.value?.ttrss?.source ?: return
        viewModelScope.launch { ttrss.setMarkRead(source.id, markRead) }
    }

    private val _startingFresh = MutableStateFlow(false)
    /** tt-rss is being asked to mark its backlog read. */
    val startingFresh: StateFlow<Boolean> = _startingFresh.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** What Start fresh did, or why it didn't. The screen shows it once and calls [noticeShown]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() { _notice.value = null }

    fun startFresh() {
        val source = state.value?.ttrss?.source ?: return
        if (_startingFresh.value) return
        _startingFresh.value = true
        viewModelScope.launch {
            try {
                val category = source.ttrssCategoryTitle
                val problem = ttrss.startFresh(source.id)
                _notice.value = problem ?: if (category != null) {
                    "Done. $category has only the last two weeks unread in tt-rss now."
                } else {
                    "Done. tt-rss has only the last two weeks unread now."
                }
                if (problem == null) onSourcesChanged()
            } finally {
                _startingFresh.value = false
            }
        }
    }
}
