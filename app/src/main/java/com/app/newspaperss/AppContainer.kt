package com.app.newspaperss

import android.content.Context
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.OkHttpHttpClient
import com.app.newspaperss.data.AesGcmCipher
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.ReadingListTitles
import com.app.newspaperss.data.SecretCipher
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.data.TtrssSubscriptions
import com.app.newspaperss.edition.AndroidImageEncoder
import com.app.newspaperss.edition.CoverRenderer
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.edition.EditionNotes
import com.app.newspaperss.edition.EditionRun
import com.app.newspaperss.edition.NotesSaver
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.delivery.KindleSends
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.edition.ExtractorContentProvider
import com.app.newspaperss.listen.ListenBook
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenService
import com.app.newspaperss.listen.Listening
import com.app.newspaperss.listen.Speaker
import com.app.newspaperss.listen.StoredListenProgress
import com.app.newspaperss.listen.SystemSpeaker
import com.app.newspaperss.listen.KokoroDownload
import com.app.newspaperss.listen.KokoroEngine
import com.app.newspaperss.listen.KokoroInstall
import com.app.newspaperss.listen.PodcastSetup
import com.app.newspaperss.listen.PodcastLog
import com.app.newspaperss.listen.PodcastMaker
import com.app.newspaperss.listen.PodcastStore
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.listen.AacEncoder
import com.app.newspaperss.work.PodcastWorker
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.work.KokoroWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.app.newspaperss.work.MoveFeedsWorker
import com.app.newspaperss.work.NotesWorker
import com.app.newspaperss.work.ReadingListTitleWorker
import com.app.newspaperss.work.TtrssMarkReadWorker
import java.io.File
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.listen.MediaPlayerAudio
import com.app.newspaperss.listen.MediaPlayerChime
import com.app.newspaperss.listen.PodcastSpeaker

/** Manual dependency injection: one instance of each service for the app's lifetime. */
class AppContainer(
    context: Context,
    val http: HttpClient = OkHttpHttpClient(OkHttpHttpClient.defaultClient(File(context.cacheDir, "http"))),
    val db: AppDatabase = AppDatabase.open(context),
    cipher: SecretCipher = AesGcmCipher.androidKeystore(),
    markTtrssRead: (editionId: Long) -> Unit = { TtrssMarkReadWorker.enqueue(context, it) },
    fetchReadingListTitles: (articleIds: List<Long>) -> Unit = { ReadingListTitleWorker.enqueue(context, it) },
    saveNotes: (editionId: Long) -> Unit = { NotesWorker.enqueue(context, it) },
    moveFeeds: () -> Unit = { MoveFeedsWorker.enqueue(context) },
    speaker: () -> Speaker = { SystemSpeaker(context) },
    private val connectListening: () -> Unit = { ListenService.connect(context) },
    private val kokoroInstall: KokoroInstall = KokoroInstall.of(context),
    // Its own client: the shared one's cache would try to keep a 325 MB model. A test passes one
    // pointed at its own server, since the real files are 325 MB and come from Hugging Face.
    private val newKokoroDownload: (KokoroInstall) -> KokoroDownload =
        { KokoroDownload(OkHttpClient.Builder().readTimeout(1, TimeUnit.MINUTES).build(), it) },
    installedBuild: Int? = DebugUpdates.buildOf(runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()),
) {
    private val editionsDir = File(context.filesDir, "editions")
    /** For work that must outlive the screen that started it, like saving a shared link. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            // Nothing waits on these launches: a failure (a full disk, a database error) is logged
            // rather than crashing the app in the background.
            CoroutineExceptionHandler { _, e -> Log.w("newspaperss", "Background work failed: ${e.javaClass.name}") },
    )
    val sources = SourceRepository(db)
    val readingList = ReadingListRepository(db, onUntitled = fetchReadingListTitles)
    val readingListTitles = ReadingListTitles(db, http)
    val notifier = Notifier(context)
    val kindleSends = KindleSends()
    // A sent edition's Ready notification comes down: its Send would offer an edition already sent.
    val editions = EditionRepository(db, editionsDir, onDelivered = { notifier.dismissFor(it); saveNotes(it) }, onTtrssChanged = markTtrssRead, kindleSends = kindleSends,
        // Only if Listen has started: it isn't made just to be told.
        onFileGone = { id ->
            if (listenMade.isInitialized()) appScope.launch { listen.forget(id) }
            appScope.launch(Dispatchers.IO) { podcastStore.delete(id) }
        },
    )
    val feedFinder = FeedFinder(http)
    private val ttrssAccounts = TtrssAccountStore(context, cipher)
    val ttrss = TtrssRepository(db, http, ttrssAccounts, sources)
    val ttrssSubscriptions = TtrssSubscriptions(ttrss, appScope)
    val feedMoves = FeedMoves(context, db, ttrss, moveFeeds)
    val feedSync = FeedSync(db, http, ttrssAccounts = ttrssAccounts, onUntitled = fetchReadingListTitles)
    private val content = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder(), onPaidOnly = sources::markPaidOnly) { sourceId, originId, evidence, text ->
        sources.recordFullText(sourceId, originId, evidence, text.check, text.day)
    }
    val editionBuilder = EditionBuilder(db, content, editionsDir, cover = CoverRenderer()::render, retiring = feedMoves::retiringIds)
    val settings = SettingsStore(context)
    val debugUpdates = DebugUpdates(http, context.getSharedPreferences("debug-updates", Context.MODE_PRIVATE), installedBuild)
    val editionNotes = EditionNotes(db, File(context.filesDir, "notes"))
    private val folderDelivery = FolderDelivery(context.contentResolver)
    val editionRun = EditionRun(settings, feedSync, editionBuilder, editions, folderDelivery, notifier,
        // Scheduled editions only: one made by hand gets its podcast when asked.
        onBuilt = { id, scheduled -> if (scheduled) podcasts.request(id) },
    )
    val notesSaver = NotesSaver(settings, editions, editionNotes, folderDelivery, notifier)

    /** Reading editions aloud. Made on first use: the voice takes a moment to start. */
    private val listenProgress = StoredListenProgress(context)
    private val listenSettings by lazy { settings.settings.stateIn(appScope, SharingStarted.Eagerly, null) }

    /**
     * Playback's entries in the podcast log, written off the main thread (the maker may hold the
     * log's lock), one at a time so they stay in order.
     */
    private val logWriter = Dispatchers.IO.limitedParallelism(1)
    private val logPlaying: (String) -> Unit = { message -> appScope.launch(logWriter) { podcastLog.add(message) } }

    /** Listen's voice: a made podcast where there is one, the phone's elsewhere. */
    private val listenSpeaker by lazy {
        PodcastSpeaker(speaker(), podcastStore, MediaPlayerAudio(logPlaying), log = logPlaying) {
            // Just after the app starts, before the store's first value: read it, or a made
            // article would play in the phone's voice.
            (listenSettings.value ?: runCatching { runBlocking { settings.current() } }.getOrNull())?.listenVoice == ListenVoice.PODCAST
        }
    }

    /** The podcast's voice while an article plays from it; null in the phone's voice. */
    val podcastPlaying: StateFlow<PodcastVoice?> get() = listenSpeaker.voice

    /** Why an article plays in the phone's voice in an edition that has a podcast. */
    val podcastInstead: StateFlow<PodcastSpeaker.Instead?> get() = listenSpeaker.instead

    private val listenMade: Lazy<ListenPlayer> = lazy {
        ListenPlayer(
            listenSpeaker, listenProgress, open = { ListenBook.open(editions, it) }, appScope,
            savedSpeed = settings.settings.map { it.listenSpeed }.distinctUntilChanged(),
            saveSpeed = { speed -> settings.update { it.copy(listenSpeed = speed) } },
            chime = MediaPlayerChime(context),
        )
    }
    val listen: ListenPlayer by listenMade
    val listening: Listening by lazy {
        Listening(listen, listenProgress, editions, podcasts) { connectListening() }
    }

    /** Getting the podcast's voice onto the phone. */
    val podcastSetup: PodcastSetup by lazy {
        PodcastSetup(
            kokoroInstall, settings, KokoroWorker.observe(context),
            start = { KokoroWorker.enqueue(context, it) },
            stop = { KokoroWorker.cancel(context) },
            supported = KokoroInstall.supported,
            engine = podcastEngine,
            podcasts = podcasts,
            log = podcastLog,
        )
    }

    /** What the podcast's making did, shown in Settings › Listening while the podcast is debug-only. */
    val podcastLog = PodcastLog(File(context.filesDir, "podcast-log.txt"))
    private val podcastEngine = { voice: PodcastVoice -> KokoroEngine(kokoroInstall, voice) }
    private val podcastStore = PodcastStore(File(context.filesDir, "podcasts"))
    private val podcasts = Podcasts(podcastStore, settings, kokoroInstall, start = { PodcastWorker.enqueue(context) }, log = podcastLog)
    val podcastMaker: PodcastMaker by lazy {
        PodcastMaker(editions, podcastStore, kokoroInstall, settings, podcastEngine, AacEncoder, log = podcastLog)
    }

    fun kokoroDownload() = newKokoroDownload(kokoroInstall)

    /** See [SettingsStore.settleFeedsFrom]: a tt-rss source from before the choice means the server setup. */
    suspend fun settleFeedsFrom() = settings.settleFeedsFrom { db.sources().ofKind(SourceKind.TTRSS).isNotEmpty() }
}
