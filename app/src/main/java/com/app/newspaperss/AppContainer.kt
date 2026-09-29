package com.app.newspaperss

import android.content.Context
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.OkHttpHttpClient
import com.app.newspaperss.data.AesGcmCipher
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.ReadingListTitles
import com.app.newspaperss.data.SecretCipher
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.edition.AndroidImageEncoder
import com.app.newspaperss.edition.ArticleContentProvider
import com.app.newspaperss.edition.CoverRenderer
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.edition.EditionRun
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.edition.ExtractorContentProvider
import com.app.newspaperss.work.ReadingListTitleWorker
import com.app.newspaperss.work.TtrssMarkReadWorker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection: one instance of each service for the app's lifetime. */
class AppContainer(
    context: Context,
    val http: HttpClient = OkHttpHttpClient(),
    val db: AppDatabase = AppDatabase.open(context),
    content: ArticleContentProvider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder(), SourceRepository(db)::recordFullText),
    cipher: SecretCipher = AesGcmCipher.androidKeystore(),
    markTtrssRead: (editionId: Long) -> Unit = { TtrssMarkReadWorker.enqueue(context, it) },
    fetchReadingListTitles: (articleIds: List<Long>) -> Unit = { ReadingListTitleWorker.enqueue(context, it) },
) {
    private val editionsDir = File(context.filesDir, "editions")
    /** For work that must outlive the screen that started it, like saving a shared link. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val sources = SourceRepository(db)
    val readingList = ReadingListRepository(db, onUntitled = fetchReadingListTitles)
    val readingListTitles = ReadingListTitles(db, http)
    val editions = EditionRepository(db, editionsDir, onTtrssDelivered = markTtrssRead)
    val feedFinder = FeedFinder(http)
    private val ttrssAccounts = TtrssAccountStore(context, cipher)
    val ttrss = TtrssRepository(db, http, ttrssAccounts, sources)
    val feedSync = FeedSync(db, http, ttrssAccounts = ttrssAccounts)
    val editionBuilder = EditionBuilder(db, content, editionsDir, cover = CoverRenderer()::render)
    val settings = SettingsStore(context)
    val notifier = Notifier(context)
    val editionRun = EditionRun(settings, feedSync, editionBuilder, editions, FolderDelivery(context.contentResolver), notifier)
}
