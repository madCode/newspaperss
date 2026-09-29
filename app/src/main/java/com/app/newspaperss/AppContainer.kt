package com.app.newspaperss

import android.content.Context
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.OkHttpHttpClient
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.FeedSync
import com.app.newspaperss.data.SourceRepository

/** Manual dependency injection: one instance of each service for the app's lifetime. */
class AppContainer(
    context: Context,
    val http: HttpClient = OkHttpHttpClient(),
    val db: AppDatabase = AppDatabase.open(context),
) {
    val sources = SourceRepository(db)
    val feedFinder = FeedFinder(http)
    val feedSync = FeedSync(db, http)
}
