package com.app.newspaperss.data

import androidx.room.withTransaction
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Connecting a tt-rss account, forgetting it, and telling tt-rss what was delivered. */
class TtrssRepository(
    private val db: AppDatabase,
    private val http: HttpClient,
    private val accounts: TtrssAccountStore,
    private val sources: SourceRepository,
) {
    /**
     * Logs in to check the account and, if that works, saves it and adds its source.
     * Returns an error to show the reader, or null on success.
     */
    suspend fun connect(address: String, user: String, password: String): String? {
        val account = TtrssAccount(TtrssClient.apiUrl(address), user.trim(), password)
        val client = account.client(http)
        try {
            client.login()
        } catch (e: TtrssException) {
            return e.message
        } catch (e: IOException) {
            return "Couldn't reach ${address.trim()}. Check the address and your connection."
        } finally {
            logOut(client)
        }
        try {
            accounts.save(account)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Some devices' Keystore throws runtime exceptions of its own.
            return "Couldn't store the password securely on this phone."
        }
        val sourceId = sources.addTtrss(account.apiUrl)
        // Category ids belong to each tt-rss user, and this may be another user on the same server.
        db.sources().setTtrssCategory(sourceId, null, null)
        return null
    }

    sealed interface Categories {
        data class Loaded(val categories: List<TtrssCategory>) : Categories
        data class Failed(val message: String) : Categories
    }

    /** The account's categories, for choosing which one its source takes articles from. */
    suspend fun categories(): Categories {
        // Some devices' Keystore throws runtime exceptions of its own.
        val account = try {
            (accounts.load() as? StoredAccount.Ready)?.account
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Categories.Failed(FeedSync.SIGN_IN_AGAIN)
        val client = account.client(http)
        return try {
            Categories.Loaded(client.categories())
        } catch (e: TtrssException) {
            Categories.Failed(e.message ?: "tt-rss reported an error.")
        } catch (e: IOException) {
            Categories.Failed("Couldn't reach tt-rss.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Categories.Failed("Couldn't load your tt-rss categories.")
        } finally {
            logOut(client)
        }
    }

    /**
     * Takes unread articles from [category] only, or from all feeds when it's null. Articles
     * waiting from before go when a category is chosen: which category each came from isn't kept.
     */
    suspend fun chooseCategory(sourceId: Long, category: TtrssCategory?) = db.withTransaction {
        db.sources().setTtrssCategory(sourceId, category?.id, category?.title)
        if (category != null) db.articles().expireWaiting(sourceId)
    }

    suspend fun setMarkRead(sourceId: Long, markRead: Boolean) = db.sources().setMarkReadOnServer(sourceId, markRead)

    /** Removes the account's source and its saved login. */
    suspend fun forget(source: SourceEntity) {
        accounts.clear()
        sources.remove(source)
    }

    /**
     * Marks the edition's tt-rss articles read on the server. Returns false if it failed in a
     * way that may pass (the server unreachable), after noting the problem on the source.
     */
    suspend fun markRead(editionId: Long): Boolean {
        val articles = db.articles().ttrssInEdition(editionId)
        if (articles.isEmpty()) return true
        val sourceIds = articles.map { it.sourceId }.distinct()
        val account = (accounts.load() as? StoredAccount.Ready)?.account
        if (account == null) {
            sourceIds.forEach { db.sources().setServerNote(it, FeedSync.SIGN_IN_AGAIN) }
            return true
        }
        val ids = articles.mapNotNull { it.guid.removePrefix(FeedSync.TTRSS_GUID_PREFIX).toLongOrNull() }
        val client = account.client(http)
        val (problem, retry) = try {
            client.markRead(ids)
            sourceIds.forEach { db.sources().setServerNote(it, null) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException) {
            e.message to (e is TtrssException.HttpError)
        } catch (e: IOException) {
            "Couldn't reach tt-rss." to true
        } finally {
            logOut(client)
        }
        sourceIds.forEach { db.sources().setServerNote(it, "Delivered articles weren't marked read in tt-rss. $problem") }
        return !retry
    }
}
