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
     * known are from before, and many aren't in the new category: none are shown.
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

/** The same feed address, give or take its scheme, "www." and a trailing slash. */
internal fun sameFeed(a: String, b: String): Boolean {
    fun plain(url: String) = url.trim().lowercase().substringAfter("://").removePrefix("www.").trimEnd('/')
    return plain(a) == plain(b)
}

sealed interface AddState {
    data object Closed : AddState
    /** @property page a page with no feed, which can be saved to the reading list instead. */
    data class Editing(val input: String = "", val error: String? = null, val page: String? = null) : AddState
    data class Searching(val input: String) : AddState
    data class Choosing(val input: String, val feeds: List<FoundFeed>) : AddState
}

/**
 * @param ttrss with [settings], says when the server setup has no working account.
 */
class SourcesViewModel(
    private val repository: SourceRepository,
    private val finder: FeedFinder,
    private val ttrss: TtrssRepository? = null,
    /** Saves a page to the reading list; null where that isn't offered. */
    private val saveToReadingList: (suspend (url: String) -> Boolean)? = null,
    private val settings: SettingsStore? = null,
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
                    if (ServerSources(source).waitingForList) return@combine ServerSources(source)
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

    fun openAdd() { _add.value = AddState.Editing() }
    private var search: Job? = null

    fun closeAdd() {
        search?.cancel()
        _add.value = AddState.Closed
    }
    fun editInput(text: String) { _add.value = AddState.Editing(text) }

    fun find() {
        val input = (add.value as? AddState.Editing)?.input ?: return
        _add.value = AddState.Searching(input)
        search = viewModelScope.launch {
            _add.value = when (val result = finder.find(input)) {
                is FindResult.NotFound -> AddState.Editing(input, result.reason, result.page?.takeIf { saveToReadingList != null })
                is FindResult.Found ->
                    if (result.feeds.size == 1) {
                        subscribe(result.feeds.single())
                        AddState.Closed
                    } else {
                        AddState.Choosing(input, result.feeds)
                    }
            }
        }
    }

    /** A site with no feed: its page goes to the reading list instead, so the reader isn't left at a dead end. */
    fun saveInstead() {
        val page = (add.value as? AddState.Editing)?.page ?: return
        val save = saveToReadingList ?: return
        _add.value = AddState.Closed
        viewModelScope.launch {
            _message.value = if (save(page)) "Saved to your reading list" else "It's already in your reading list"
        }
    }

    fun choose(feed: FoundFeed) {
        viewModelScope.launch {
            subscribe(feed)
            _add.value = AddState.Closed
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
