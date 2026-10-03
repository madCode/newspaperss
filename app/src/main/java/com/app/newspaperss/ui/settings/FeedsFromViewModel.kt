package com.app.newspaperss.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.data.TtrssRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 */
class FeedsFromViewModel(
    private val settings: SettingsStore,
    private val ttrss: TtrssRepository,
    private val onSourcesChanged: () -> Unit = {},
) : ViewModel() {
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
            val result = ttrss.signIn(form.address, form.user, form.password) { settings.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
            when (result) {
                is TtrssRepository.SignIn.Failed -> _form.value = form.copy(error = result.message)
                is TtrssRepository.SignIn.SignedIn -> {
                    _form.value = null
                    onSourcesChanged()
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
        viewModelScope.launch {
            withContext(NonCancellable) {
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
