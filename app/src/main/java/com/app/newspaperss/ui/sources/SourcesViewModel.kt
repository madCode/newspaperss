package com.app.newspaperss.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.core.feed.FoundFeed
import com.app.newspaperss.core.lists.CuratedList
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.data.TtrssSubscriptions
import com.app.newspaperss.core.ttrss.TtrssCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import com.app.newspaperss.data.FeedChoice
import com.app.newspaperss.data.sortTitle
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A row in Sources: the source, its newest article, and its own publication (its settings, and what the full-text check found). */
data class SourceRow(
    val source: SourceEntity,
    val lastNew: Instant?,
    val text: PublicationEntity? = null,
)

/**
 * Sources in the server setup, below what's on this phone.
 *
 * @property account the tt-rss account's source; null while there's none to show (signed out).
 * @property categories the feeds in the paper, under the server's categories.
 * @property leftOut how many of them the reader left out.
 * @property outside the account's feeds in categories the paper doesn't take from, by category.
 */
data class ServerSources(
    val account: SourceEntity?,
    val categories: List<FeedCategory> = emptyList(),
    val leftOut: Int = 0,
    val outside: List<FeedCategory> = emptyList(),
) {
    val inPaper get() = categories.sumOf { it.feeds.size }

    /**
     * A category was chosen and its feeds haven't been listed yet. Until they are, the feeds
     * known are from before, and many aren't in the new category: none are shown but the
     * left-out ones.
     */
    val waitingForList get() = account?.ttrssCategoryId != null && account.feedsListedAt == null
}

/** Sources as shown: [rows] on this phone, and [server] in the server setup (null in the phone setup). */
data class SourcesList(val rows: List<SourceRow>, val server: ServerSources?, val needsSignIn: Boolean)

/** A server category and its feeds, A to Z; [name] null is Uncategorized. */
data class FeedCategory(val name: String?, val feeds: List<FeedChoice>)

const val UNCATEGORIZED = "Uncategorized"

internal fun outsideFeed(p: PublicationEntity) = FeedChoice(p.key, p.title ?: p.key, inPaper = false, p)

/**
 * Feeds under their categories, A to Z with Uncategorized last. A feed with no category, or
 * tt-rss's own "Uncategorized" (category 0), goes there: Google Reader servers can give none at all.
 */
internal fun byCategory(feeds: List<FeedChoice>): List<FeedCategory> =
    feeds.groupBy { f -> f.publication?.category?.trim()?.takeIf { it.isNotEmpty() && it != UNCATEGORIZED } }
        .map { (name, fs) -> FeedCategory(name, fs.sortedBy { sortTitle(it.title) }) }
        .sortedWith(compareBy<FeedCategory> { it.name == null }.thenBy { it.name?.lowercase() })

/**
 * The categories to subscribe a feed into: A to Z, then Uncategorized, always there and under
 * the app's own name, as Sources shows it, whatever language the server speaks.
 */
internal fun pickerCategories(categories: List<TtrssCategory>): List<TtrssCategory> =
    categories.filter { it.id != UNCATEGORIZED_ID && it.title.isNotBlank() }.sortedBy { it.title.lowercase() } +
        TtrssCategory(UNCATEGORIZED_ID, UNCATEGORIZED)

/** Why a feed already in tt-rss isn't in the paper, if it isn't. */
internal fun alreadyNote(s: AddState.AlreadyIn): String? = when {
    s.leftOut -> "You left it out of your paper. It's under Left out on Sources."
    s.outsidePaper -> "It's outside the category your paper takes articles from."
    else -> null
}

/** A feed already in tt-rss, said in one line. */
internal fun alreadyLine(s: AddState.AlreadyIn): String =
    listOfNotNull("${s.title} is already in your tt-rss, in ${s.category ?: UNCATEGORIZED}.", alreadyNote(s)).joinToString(" ")

/**
 * The Add dialog. Each state with a `page` can save that page to the reading list instead: an
 * article the reader typed, not a site's front page.
 */
sealed interface AddState {
    data object Closed : AddState
    /** @property page a page with no feed, which can be saved to the reading list instead. */
    data class Editing(val input: String = "", val error: String? = null, val page: String? = null) : AddState
    data class Searching(val input: String) : AddState
    data class Choosing(val input: String, val feeds: List<FoundFeed>, val page: String? = null) : AddState

    /**
     * The server setup: [feed] found, to subscribe to in tt-rss in the category [chosen].
     * [categories] is null while loading; [error] says why only Uncategorized is offered.
     */
    data class Subscribing(
        val feed: FoundFeed,
        val page: String?,
        val categories: List<TtrssCategory>? = null,
        val chosen: Int = UNCATEGORIZED_ID,
        val error: String? = null,
    ) : AddState

    /** Waiting for tt-rss's answer, which can take half a minute; closing the dialog doesn't stop it. */
    data class Asking(val request: TtrssSubscriptions.Request) : AddState

    /** Already in the account: [category] null is Uncategorized. */
    data class AlreadyIn(val title: String, val category: String?, val leftOut: Boolean = false, val outsidePaper: Boolean = false) : AddState

    /** The server setup, a site with no feed for tt-rss to follow: [list] a curated list the app can read from it instead. */
    data class NoFeed(val site: String, val page: String?, val list: CuratedList?) : AddState

    /** tt-rss didn't subscribe, [reason] in words. [couldntFetch]: it couldn't download or read the feed the phone found. */
    data class Refused(val title: String, val reason: String, val couldntFetch: Boolean, val page: String?) : AddState
}

/**
 * A line for the snackbar; with [undo], an Undo that unsubscribes the feed just added in tt-rss.
 */
data class Notice(val id: Long, val text: String, val undo: Undone? = null) {
    data class Undone(val request: TtrssSubscriptions.Request, val feedId: Int)
}

/** tt-rss's category for feeds in none. */
const val UNCATEGORIZED_ID = 0

/**
 * @param ttrss with [settings], says when the server setup has no working account.
 * @param subscriptions with [ttrss], adds sites to tt-rss in the server setup.
 */
class SourcesViewModel(
    private val repository: SourceRepository,
    private val finder: FeedFinder,
    private val ttrss: TtrssRepository? = null,
    /** Saves a page to the reading list; null where that isn't offered. */
    private val saveToReadingList: (suspend (url: String) -> Boolean)? = null,
    private val settings: SettingsStore? = null,
    private val subscriptions: TtrssSubscriptions? = null,
    private val onSourcesChanged: () -> Unit,
) : ViewModel() {
    /**
     * The sources on this phone, in the reader's order, as the edition takes them. A tt-rss
     * account is never among them: in the server setup it has its own part of the screen.
     */
    private val phoneRows: Flow<List<SourceRow>> =
        combine(repository.observe(), repository.observeActivity(), repository.observeOwnPublications()) { sources, activity, texts ->
            val bySource = activity.associate { it.sourceId to it.lastNew }
            sources.filter { it.kind != SourceKind.READING_LIST && it.kind != SourceKind.TTRSS }.map { s -> SourceRow(s, bySource[s.id], texts[s.id]) }
        }

    /** The phone's sources alone, for onboarding's count; Sources itself shows [screen]. */
    val rows: StateFlow<List<SourceRow>?> = phoneRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The server setup, with its account's id if there is one. */
    private data class ServerSetup(val accountId: Long?)

    /**
     * The tt-rss part of Sources in the server setup; null in the phone setup, where nothing of
     * tt-rss shows even if an account was somehow left behind.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverPart: Flow<ServerSources?> =
        (if (settings == null) flowOf(null) else combine(settings.settings, repository.observe()) { s, sources ->
            val account = sources.firstOrNull { it.kind == SourceKind.TTRSS }
            if (s.feedsFrom(hasServer = account != null) == FeedsFrom.SERVER) ServerSetup(account?.id) else null
        }).distinctUntilChanged().flatMapLatest { setup ->
            val id = setup?.accountId
            when {
                setup == null -> flowOf(null)
                id == null -> flowOf(ServerSources(null))
                else -> combine(repository.observe(id), repository.observeFeeds(id), repository.observeOutsideCategory(id)) { source, feeds, outside ->
                    // Left out still counts: its list is how a feed left out comes back.
                    if (ServerSources(source).waitingForList) return@combine ServerSources(source, leftOut = feeds.count { !it.inPaper })
                    ServerSources(
                        source,
                        byCategory(feeds.filter { it.inPaper }),
                        leftOut = feeds.count { !it.inPaper },
                        outside = byCategory(outside.map(::outsideFeed)),
                    )
                }
            }
        }

    /** The curated lists not added yet, offered in the add dialog. */
    val curatedLists: StateFlow<List<CuratedList>> = repository.observe().map { sources ->
        val added = sources.map { it.url }.toSet()
        CuratedLists.all.filter { CuratedLists.sourceUrl(it) !in added }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _add = MutableStateFlow<AddState>(AddState.Closed)
    val add: StateFlow<AddState> = _add.asStateFlow()

    /**
     * Sites go to tt-rss rather than this phone: the server setup, as Sources shows it. Read
     * from [screen], which the screen collects.
     */
    private val toServer get() = screen.value?.server != null && ttrss != null && subscriptions != null

    fun openAdd() { _add.value = AddState.Editing() }
    private var search: Job? = null

    fun closeAdd() {
        search?.cancel()
        _add.value = AddState.Closed
    }
    fun editInput(text: String) { _add.value = AddState.Editing(text) }

    fun find() {
        val input = (add.value as? AddState.Editing)?.input ?: return
        val server = toServer
        _add.value = AddState.Searching(input)
        search = viewModelScope.launch {
            when (val result = finder.find(input)) {
                is FindResult.NotFound -> {
                    val page = result.page?.takeIf { saveToReadingList != null }
                    val list = if (server) curatedListAt(input) else null
                    _add.value = if (server && (page != null || list != null)) AddState.NoFeed(SourceRepository.hostOf(input), page, list) else AddState.Editing(input, result.reason, page)
                }
                is FindResult.Found -> {
                    val page = result.page?.takeIf { saveToReadingList != null }
                    when {
                        result.feeds.size > 1 -> _add.value = AddState.Choosing(input, result.feeds, page)
                        server -> toSubscribe(result.feeds.single(), page)
                        else -> {
                            subscribe(result.feeds.single())
                            _add.value = AddState.Closed
                        }
                    }
                }
            }
        }
    }

    /** A curated list the app can read whose site is [input], not added yet. */
    private fun curatedListAt(input: String): CuratedList? {
        val host = SourceRepository.hostOf(input).lowercase()
        return curatedLists.value.firstOrNull { SourceRepository.hostOf(it.pageUrl).lowercase() == host }
    }

    /**
     * Unless tt-rss already has it, offers to subscribe to [feed] there, in the category last
     * used, else the one the paper takes articles from, else Uncategorized.
     */
    private suspend fun toSubscribe(feed: FoundFeed, page: String?) {
        val ttrss = ttrss ?: return
        ttrss.feedAt(feed.url)?.let { known ->
            _add.value = AddState.AlreadyIn(known.title ?: feed.title ?: SourceRepository.hostOf(feed.url), known.category, known.leftOut, known.outsideCategory)
            return
        }
        _add.value = AddState.Subscribing(feed, page)
        val loaded = ttrss.categories(includeEmpty = true)
        val categories = pickerCategories((loaded as? TtrssRepository.Categories.Loaded)?.categories.orEmpty())
        val ids = categories.map { it.id }.toSet()
        val chosen = listOfNotNull(settings?.current()?.lastCategoryId, screen.value?.server?.account?.ttrssCategoryId).firstOrNull { it in ids } ?: UNCATEGORIZED_ID
        _add.value = AddState.Subscribing(feed, page, categories, chosen, (loaded as? TtrssRepository.Categories.Failed)?.message)
    }

    fun chooseCategory(id: Int) {
        val s = add.value as? AddState.Subscribing ?: return
        if (s.categories?.any { it.id == id } == true) _add.value = s.copy(chosen = id)
    }

    /** Asks tt-rss to subscribe, once: the dialog moves on to Asking, so a second tap finds nothing to do. */
    fun subscribeInTtrss() {
        val s = add.value as? AddState.Subscribing ?: return
        val category = s.categories?.firstOrNull { it.id == s.chosen } ?: return
        val subscriptions = subscriptions ?: return
        val request = TtrssSubscriptions.Request(s.feed.url, s.feed.title, category, s.page)
        _add.value = AddState.Asking(request)
        // False when it's being asked already, from a dialog closed earlier: that answer comes here too.
        subscriptions.subscribe(request)
        viewModelScope.launch { settings?.update { it.copy(lastCategoryId = category.id) } }
    }

    /** Outcomes from tt-rss that haven't been shown; [take] shows one. */
    val subscribeResults: StateFlow<List<TtrssSubscriptions.Result>> = subscriptions?.results ?: MutableStateFlow(emptyList())

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    /** Snackbar lines waiting to be shown, oldest first; the screen calls [noticeShown] after each. */
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()
    private var nextNotice = 0L

    fun noticeShown(notice: Notice) { _notices.value -= notice }

    private fun notify(text: String, undo: Notice.Undone? = null) { _notices.value += Notice(nextNotice++, text, undo) }

    /**
     * Shows what tt-rss answered: in the dialog if it's still open on that feed, else as a
     * snackbar line. A feed added says so in the snackbar either way, with Undo.
     */
    fun take(result: TtrssSubscriptions.Result) {
        subscriptions?.shown(result.id)
        when (val outcome = result.outcome) {
            is TtrssSubscriptions.Outcome.Subscribed -> {
                val request = outcome.request
                val open = (add.value as? AddState.Asking)?.request?.feedUrl == request.feedUrl
                val title = request.title ?: SourceRepository.hostOf(request.feedUrl)
                when (val answer = outcome.answer) {
                    is TtrssRepository.Subscribed.Added -> {
                        if (open) _add.value = AddState.Closed
                        val outside = screen.value?.server?.account?.ttrssCategoryTitle?.takeIf { answer.outsidePaper }
                        notify(
                            "Added to your tt-rss, in ${answer.category ?: UNCATEGORIZED}." + outside?.let { " Your paper takes articles from $it only." }.orEmpty(),
                            answer.feedId?.let { Notice.Undone(request, it) },
                        )
                    }
                    is TtrssRepository.Subscribed.Already -> {
                        val feed = answer.feed
                        val known = AddState.AlreadyIn(feed?.title ?: title, feed?.category, feed?.leftOut == true, feed?.outsideCategory == true)
                        if (open) _add.value = known else notify(alreadyLine(known))
                    }
                    is TtrssRepository.Subscribed.Failed ->
                        if (open) _add.value = AddState.Refused(title, answer.reason, answer.couldntFetch, request.page)
                        else notify("tt-rss didn't add $title. ${answer.reason}")
                }
            }
            is TtrssSubscriptions.Outcome.Unsubscribed -> {
                val title = outcome.request.title ?: SourceRepository.hostOf(outcome.request.feedUrl)
                notify(outcome.error?.let { "Couldn't take $title out of your tt-rss. $it" } ?: "Took $title out of your tt-rss.")
            }
        }
    }

    /** Undo for a feed just added: unsubscribes it in tt-rss. */
    fun undo(undone: Notice.Undone) {
        subscriptions?.unsubscribe(undone.request, undone.feedId)
    }

    /** A site with no feed, or one tt-rss can't follow: its page goes to the reading list instead, so the reader isn't left at a dead end. */
    fun saveInstead() {
        val page = when (val s = add.value) {
            is AddState.Editing -> s.page
            is AddState.NoFeed -> s.page
            is AddState.Refused -> s.page
            else -> null
        } ?: return
        val save = saveToReadingList ?: return
        _add.value = AddState.Closed
        viewModelScope.launch {
            _message.value = if (save(page)) "Saved to your reading list" else "It's already in your reading list"
        }
    }

    fun choose(feed: FoundFeed) {
        val choosing = add.value as? AddState.Choosing ?: return
        val server = toServer
        search = viewModelScope.launch {
            if (server) {
                toSubscribe(feed, choosing.page)
            } else {
                subscribe(feed)
                _add.value = AddState.Closed
            }
        }
    }

    fun addList(list: CuratedList) {
        viewModelScope.launch {
            repository.addList(list)
            _add.value = AddState.Closed
            onSourcesChanged()
        }
    }

    /**
     * The server setup without a working account to take articles from: never signed in,
     * signed out, or its password unreadable. Sources says so at the top.
     */
    private val signInNeeded: Flow<Boolean> =
        (if (settings == null || ttrss == null) flowOf(false) else combine(settings.settings, ttrss.observeStatus()) { s, status ->
            s.feedsFrom(hasServer = status.source != null) == FeedsFrom.SERVER && !status.signedIn
        })

    /**
     * Everything the list shows, null until all of it has loaded. One value rather than three:
     * a part arriving late (the account row, the sign-in banner) would land above rows already
     * shown, and the list keeps its first visible row in place, so it would open scrolled past it.
     */
    val screen: StateFlow<SourcesList?> = combine(phoneRows, serverPart, signInNeeded, ::SourcesList)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() = onSourcesChanged()

    private val _message = MutableStateFlow<String?>(null)
    /** A one-line result to show the reader, e.g. after an import. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    // Files come from the system picker, often a cloud provider: reads and writes can be slow or fail.
    fun importOpml(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IOException("empty")
                }
                val added = repository.importOpml(text)
                if (added > 0) onSourcesChanged()
                when (added) {
                    0 -> "No new sites in that file."
                    1 -> "Added 1 site."
                    else -> "Added $added sites."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't read that file. Is it an OPML export from another reader?"
            }
        }
    }

    fun exportOpml(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = repository.exportOpml()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
                "Your sites are saved."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Couldn't save the file."
            }
        }
    }

    private suspend fun subscribe(feed: FoundFeed) {
        repository.addFeed(feed.url, feed.title)
        onSourcesChanged()
    }
}
