package com.app.newspaperss

import android.content.Context
import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.OkHttpHttpClient
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.edition.AndroidImageEncoder
import com.app.newspaperss.edition.ArticleContentProvider
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.edition.EditionRun
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.edition.ExtractorContentProvider
import java.io.File

/** Manual dependency injection: one instance of each service for the app's lifetime. */
class AppContainer(
    context: Context,
    val http: HttpClient = OkHttpHttpClient(),
    val db: AppDatabase = AppDatabase.open(context),
    content: ArticleContentProvider = ExtractorContentProvider(ArticleExtractor(http), http, AndroidImageEncoder()),
) {
    private val editionsDir = File(context.filesDir, "editions")
    val sources = SourceRepository(db)
    val editions = EditionRepository(db, editionsDir)
    val feedFinder = FeedFinder(http)
    val feedSync = FeedSync(db, http)
    val editionBuilder = EditionBuilder(db, content, editionsDir)
    val settings = SettingsStore(context)
    val notifier = Notifier(context)
    val editionRun = EditionRun(settings, feedSync, editionBuilder, editions, FolderDelivery(context.contentResolver), notifier)
}
