package com.app.newspaperss.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.core.feed.StarterFeed
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.ui.ttrss.TtrssForm
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleAddress
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * The steps in order. After [FEEDS_FROM] the path forks: this phone goes to [SOURCES], a server
 * to [SIGN_IN] and [EXTRAS]; both end at [SIZE]. See [OnboardingState.path].
 */
enum class Step { WELCOME, DEVICE, FEEDS_FROM, SOURCES, SIGN_IN, EXTRAS, SIZE, }

data class OnboardingState(
    val step: Step = Step.WELCOME,
    val device: Device? = null,
    val folderUri: String? = null,
    val folderName: String? = null,
    /** A Kindle reader emails editions to their Kindle, unless they choose the Kindle app instead. */
    val kindleByEmail: Boolean = true,
    val kindleEmail: String = "",
    /** The mail app Send opens; null asks each time. */
    val mailApp: String? = null,
    /** Feed URLs chosen from the starter packs or found from a pasted address. */
    val chosen: Set<String> = emptySet(),
    val found: List<StarterFeed> = emptyList(),
    val pasted: String = "",
    val searching: Boolean = false,
    val findError: String? = null,
    val minutes: Int = 30,
    val scheduleEnabled: Boolean = true,
    val time: LocalTime = LocalTime.of(6, 30),
    val finishing: Boolean = false,
    /** Sources already saved, from a tt-rss account or an OPML import made during onboarding. */
    val added: Int = 0,
    /** Links waiting in the reading list, e.g. from a Pocket or Instapaper import made during onboarding. */
    val savedLinks: Int = 0,
    /** Where the reader's sites come from: null until they pick one. */
    val feedsFrom: FeedsFrom? = null,
    /** The tt-rss sign-in, on the server path. The password is never saved with the rest. */
    val signIn: TtrssForm = TtrssForm(),
    /** A tt-rss account is signed in: its source is in the database. */
    val signedIn: Boolean = false,
    /** What signing in found; null before, or if tt-rss's feed list couldn't be read. */
    val serverFound: TtrssRepository.Found? = null,
    /** Curated lists ticked on the server path's extras step, by id. */
    val lists: Set<String> = emptySet(),
    /** Leaving the fork: saving the choice and, for this phone, signing out of tt-rss. */
    val forking: Boolean = false,
    /** Sites added on the phone's sources step (one by one, or from OPML) and saved already. */
    val phoneFeeds: Int = 0,
) {
    val server get() = feedsFrom == FeedsFrom.SERVER

    /**
     * The steps after the welcome, on the path chosen. Before choosing, the phone's: it's the
     * one most people take, and the count only grows if the server is picked.
     */
    val path: List<Step> get() =
        if (server) listOf(Step.DEVICE, Step.FEEDS_FROM, Step.SIGN_IN, Step.EXTRAS, Step.SIZE)
        else listOf(Step.DEVICE, Step.FEEDS_FROM, Step.SOURCES, Step.SIZE)

    /** "Step N of M", counted along [path]; 0 for the welcome, which isn't a step. */
    val stepNumber get() = path.indexOf(step) + 1

    /** KOReader reads from a synced folder, so it's offered folder delivery. */
    val needsFolder get() = device == Device.KOREADER
    /** The Kindle's email setup is showing, so Next needs its address. */
    val emailsKindle get() = device == Device.KINDLE && kindleByEmail
    val canContinue get() = when (step) {
        Step.WELCOME -> true
        Step.DEVICE -> device != null && (!emailsKindle || KindleAddress.isValid(kindleEmail))
        Step.FEEDS_FROM -> feedsFrom != null && !forking
        // Saved links alone are enough: for someone leaving Pocket, they're the paper.
        Step.SOURCES -> chosen.isNotEmpty() || added > 0 || savedLinks > 0
        Step.SIGN_IN -> signedIn
        // The account is the paper; curated lists and saved links are extras.
        Step.EXTRAS -> true
        Step.SIZE -> !finishing
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModel(
    private val settings: SettingsStore,
    private val sources: SourceRepository,
    private val finder: FeedFinder,
    /** Signs in to tt-rss on the server path; null offers only this phone. */
    private val ttrss: TtrssRepository? = null,
    /** Survives process death: the folder picker or the Play Store can get the app killed mid-flow. */
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** How many links wait in the reading list; any is enough to start with. */
    private val savedLinks: Flow<Int> = flowOf(0),
    /** Schedules the timer and starts the first edition once onboarding is saved. */
    private val onFinished: (Settings) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(restore(saved))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.collect { store(it, saved) } }
        viewModelScope.launch { savedLinks.collect { n -> _state.update { it.copy(savedLinks = n) } } }
        // From the database rather than the sign-in's result, so it's right after process death
        // too. Only from the fork on and on the server path: before it there's nothing to find.
        viewModelScope.launch {
            _state.map { it.server || it.signedIn || it.step == Step.FEEDS_FROM }.distinctUntilChanged()
                .flatMapLatest { watch ->
                    if (!watch) flowOf(false to 0)
                    else sources.observe().map { all -> all.any { it.kind == SourceKind.TTRSS } to all.count { it.kind == SourceKind.FEED } }
                }
                .collect { (signedIn, feeds) -> _state.update { it.copy(signedIn = signedIn, phoneFeeds = feeds) } }
        }
    }

    fun next() {
        val s = state.value
        if (!s.canContinue) return
        if (s.step == Step.FEEDS_FROM) return leaveFork(s)
        _state.update { st -> st.copy(step = st.path.getOrElse(st.path.indexOf(st.step) + 1) { st.step }) }
    }

    // Not while signing in: the sign-in would finish after the reader had moved on, perhaps to
    // this phone, and leave an account nobody chose.
    fun back() = _state.update { s ->
        if (s.signIn.testing) return@update s
        val i = s.path.indexOf(s.step)
        s.copy(step = if (i <= 0) Step.WELCOME else s.path[i - 1])
    }

    fun chooseFeedsFrom(choice: FeedsFrom) = _state.update { it.copy(feedsFrom = choice) }

    /**
     * Saves the choice, so the app knows it even if onboarding stops here. Choosing this phone
     * signs out of any account made on the server path first: it wasn't chosen in the end, and
     * nothing has come from it yet. Always, not only when [OnboardingState.signedIn] says so: a
     * sign-in cut short by the app being killed can leave a login saved without its source.
     */
    private fun leaveFork(s: OnboardingState) {
        val choice = s.feedsFrom ?: return
        _state.update { it.copy(forking = true) }
        viewModelScope.launch {
            try {
                if (choice == FeedsFrom.PHONE && ttrss != null) {
                    // A sign-in still running would add its account after the sign-out.
                    signingIn?.cancelAndJoin()
                    ttrss.signOut()
                    _state.update { it.copy(serverFound = null) }
                }
                // Never mixed: sites added on the phone path go when the reader picks the server
                // instead. The step says so before Next.
                if (choice == FeedsFrom.SERVER) sources.observe().first().filter { it.kind == SourceKind.FEED }.forEach { sources.remove(it) }
                settings.update { it.copy(feedsFrom = choice) }
                _state.update { it.copy(step = if (choice == FeedsFrom.SERVER) Step.SIGN_IN else Step.SOURCES) }
            } finally {
                _state.update { it.copy(forking = false) }
            }
        }
    }

    /** "Use this phone instead", from the sign-in step; not while signing in, as for [back]. */
    fun usePhoneInstead() {
        if (state.value.signIn.testing) return
        _state.update { it.copy(feedsFrom = FeedsFrom.PHONE, step = Step.FEEDS_FROM) }
        next()
    }

    private var signingIn: Job? = null

    fun editSignIn(form: TtrssForm) = _state.update { s -> if (s.signIn.testing) s else s.copy(signIn = form.copy(error = null)) }

    fun signIn() {
        val repo = ttrss ?: return
        val form = state.value.signIn.takeIf { it.canSubmit } ?: return
        _state.update { it.copy(signIn = form.copy(testing = true, error = null)) }
        signingIn = viewModelScope.launch {
            when (val result = repo.signIn(form.address, form.user, form.password)) {
                is TtrssRepository.SignIn.Failed -> _state.update { it.copy(signIn = form.copy(error = result.message)) }
                // The password isn't kept here once it's saved in the account.
                is TtrssRepository.SignIn.SignedIn -> _state.update {
                    it.copy(signIn = form.copy(password = ""), serverFound = result.found, signedIn = true)
                }
            }
        }
    }

    fun toggleList(id: String) = _state.update { it.copy(lists = if (id in it.lists) it.lists - id else it.lists + id) }

    fun sourcesAdded(count: Int) = _state.update { it.copy(added = count) }

    fun chooseDevice(device: Device) = _state.update { it.copy(device = device) }
    fun chooseFolder(uri: String, name: String) = _state.update { it.copy(folderUri = uri, folderName = name) }
    fun editKindleEmail(address: String) = _state.update { it.copy(kindleEmail = address) }
    fun chooseMailApp(packageName: String?) = _state.update { it.copy(mailApp = packageName) }
    fun useKindleApp() = _state.update { it.copy(kindleByEmail = false) }
    fun useKindleEmail() = _state.update { it.copy(kindleByEmail = true) }

    fun toggleFeed(url: String) = _state.update { it.copy(chosen = if (url in it.chosen) it.chosen - url else it.chosen + url) }

    fun togglePack(name: String) = _state.update { s ->
        val urls = StarterPacks.all.first { it.name == name }.feeds.map { it.url }.toSet()
        s.copy(chosen = if (s.chosen.containsAll(urls)) s.chosen - urls else s.chosen + urls)
    }

    fun editPasted(text: String) = _state.update { it.copy(pasted = text, findError = null) }

    fun findPasted() {
        val input = state.value.pasted.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(searching = true, findError = null) }
        viewModelScope.launch {
            when (val result = finder.find(input)) {
                is FindResult.NotFound -> _state.update { it.copy(searching = false, findError = result.reason) }
                is FindResult.Found -> _state.update { s ->
                    val feeds = result.feeds.map { StarterFeed(it.title ?: SourceRepository.hostOf(it.url), it.url) }
                    // Several feeds on one site: pick the first, show the rest to tick.
                    s.copy(searching = false, pasted = "", found = (s.found + feeds).distinctBy { it.url }, chosen = s.chosen + feeds.first().url)
                }
            }
        }
    }

    fun setMinutes(minutes: Int) = _state.update { it.copy(minutes = minutes) }
    fun setScheduleEnabled(on: Boolean) = _state.update { it.copy(scheduleEnabled = on) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }

    fun finish() {
        val s = state.value
        // A permission result delivered after process death can land on a fresh, empty state;
        // saving that would finish onboarding with no device and nothing chosen.
        if (s.finishing || s.device == null) return
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            // Picks from the phone path stay behind if the reader went back and chose the server.
            val chosen = if (s.server) emptySet() else s.chosen
            // The database, not state.added, which the screen may not have reported yet.
            val hasFeeds = sources.observe().first().any { it.kind != SourceKind.READING_LIST }
            // The flow, not state.savedLinks: after process death it may not have arrived yet.
            if (chosen.isEmpty() && s.lists.isEmpty() && !hasFeeds && savedLinks.first() == 0) {
                _state.update { it.copy(finishing = false) }
                return@launch
            }
            val titles = (StarterPacks.all.flatMap { it.feeds } + s.found).associate { it.url to it.title }
            chosen.forEach { url -> sources.addFeed(url, titles[url]) }
            if (s.server) CuratedLists.all.filter { it.id in s.lists }.forEach { sources.addList(it) }
            settings.update {
                it.copy(
                    onboarded = true,
                    feedsFrom = s.feedsFrom ?: FeedsFrom.PHONE,
                    device = s.device,
                    edition = it.edition.copy(minutes = s.minutes),
                    scheduleEnabled = s.scheduleEnabled,
                    schedule = Schedule(time = s.time),
                    delivery = when {
                        s.needsFolder && s.folderUri != null -> DeliveryMethod.FOLDER
                        s.emailsKindle && KindleAddress.isValid(s.kindleEmail) -> DeliveryMethod.KINDLE_EMAIL
                        else -> DeliveryMethod.SHARE
                    },
                    folderUri = s.folderUri,
                    folderName = s.folderName,
                    kindleEmail = s.kindleEmail.trim().takeIf { s.emailsKindle && it.isNotEmpty() },
                    mailApp = s.mailApp.takeIf { s.emailsKindle },
                )
            }
            onFinished(settings.current())
        }
    }
}

private const val KEY = "onboarding."

private fun store(s: OnboardingState, saved: SavedStateHandle) {
    saved[KEY + "step"] = s.step.name
    saved[KEY + "device"] = s.device?.name
    saved[KEY + "folderUri"] = s.folderUri
    saved[KEY + "folderName"] = s.folderName
    saved[KEY + "kindleByEmail"] = s.kindleByEmail
    saved[KEY + "kindleEmail"] = s.kindleEmail
    saved[KEY + "mailApp"] = s.mailApp
    saved[KEY + "chosen"] = ArrayList(s.chosen)
    saved[KEY + "foundUrls"] = ArrayList(s.found.map { it.url })
    saved[KEY + "foundTitles"] = ArrayList(s.found.map { it.title })
    saved[KEY + "minutes"] = s.minutes
    saved[KEY + "scheduleEnabled"] = s.scheduleEnabled
    saved[KEY + "time"] = s.time.toString()
    saved[KEY + "feedsFrom"] = s.feedsFrom?.name
    saved[KEY + "address"] = s.signIn.address
    saved[KEY + "user"] = s.signIn.user
    saved[KEY + "foundFeeds"] = s.serverFound?.feeds
    saved[KEY + "foundCategories"] = s.serverFound?.categories
    saved[KEY + "lists"] = ArrayList(s.lists)
}

private fun restore(saved: SavedStateHandle): OnboardingState {
    val d = OnboardingState()
    val step = saved.get<String>(KEY + "step") ?: return d
    val urls = saved.get<ArrayList<String>>(KEY + "foundUrls").orEmpty()
    val titles = saved.get<ArrayList<String>>(KEY + "foundTitles").orEmpty()
    return d.copy(
        step = runCatching { Step.valueOf(step) }.getOrDefault(Step.WELCOME),
        device = saved.get<String>(KEY + "device")?.let { runCatching { Device.valueOf(it) }.getOrNull() },
        folderUri = saved[KEY + "folderUri"],
        folderName = saved[KEY + "folderName"],
        kindleByEmail = saved[KEY + "kindleByEmail"] ?: d.kindleByEmail,
        kindleEmail = saved[KEY + "kindleEmail"] ?: d.kindleEmail,
        mailApp = saved[KEY + "mailApp"],
        chosen = saved.get<ArrayList<String>>(KEY + "chosen").orEmpty().toSet(),
        found = urls.zip(titles) { url, title -> StarterFeed(title, url) },
        minutes = saved[KEY + "minutes"] ?: d.minutes,
        scheduleEnabled = saved[KEY + "scheduleEnabled"] ?: d.scheduleEnabled,
        time = saved.get<String>(KEY + "time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.time,
        feedsFrom = saved.get<String>(KEY + "feedsFrom")?.let { runCatching { FeedsFrom.valueOf(it) }.getOrNull() },
        signIn = TtrssForm(address = saved[KEY + "address"] ?: "", user = saved[KEY + "user"] ?: ""),
        serverFound = saved.get<Int>(KEY + "foundFeeds")?.let { TtrssRepository.Found(it, saved[KEY + "foundCategories"] ?: 0) },
        lists = saved.get<ArrayList<String>>(KEY + "lists").orEmpty().toSet(),
    )
}
