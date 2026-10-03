package com.app.newspaperss.data

import androidx.room.withTransaction
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import com.app.newspaperss.core.ttrss.TtrssFeed
import com.app.newspaperss.core.ttrss.TtrssSubscription
import kotlinx.coroutines.flow.first
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.IOException

/**
 * The tt-rss account as the screens show it.
 *
 * @property source the account's source; null when there's none (never signed in, or signed out).
 * @property user the saved username, readable even when the password isn't.
 * @property usable a saved login for [source] whose password can be read.
 */
data class TtrssStatus(val source: SourceEntity?, val user: String?, val usable: Boolean) {
    /** Articles can come from it: there's a source and a login that works for it. */
    val signedIn get() = source != null && usable

    companion object {
        val NONE = TtrssStatus(null, null, false)
    }
}

/** Connecting a tt-rss account, forgetting it, and telling tt-rss what was delivered. */
class TtrssRepository(
    private val db: AppDatabase,
    private val http: HttpClient,
    private val accounts: TtrssAccountStore,
    private val sources: SourceRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Check {
        data class Failed(val message: String) : Check
        /**
         * The login works; [categories] is empty if they couldn't be read, and [feeds] null if
         * they weren't asked for or couldn't be read.
         */
        data class Passed(val categories: List<TtrssCategory>, val feeds: List<TtrssFeed>? = null) : Check
    }

    /** Logs in to check the account and reads its categories and feeds if asked, saving nothing. */
    suspend fun check(address: String, user: String, password: String, readCategories: Boolean = true, readFeeds: Boolean = false): Check {
        val client = TtrssAccount(TtrssClient.apiUrl(address), user.trim(), password).client(http)
        try {
            client.login()
            // Either is only shown to the reader; failing to read one isn't failing to sign in.
            suspend fun <T> optional(read: suspend () -> T): T? = try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val categories = if (!readCategories) emptyList() else optional { client.categories() }.orEmpty()
            val feeds = if (!readFeeds) null else optional { client.allFeeds() }
            return Check.Passed(categories, feeds)
        } catch (e: TtrssException) {
            return Check.Failed(e.message ?: "tt-rss reported an error.")
        } catch (e: IOException) {
            return Check.Failed(
                if (FeedSync.tooSlow(e)) "${address.trim()} took too long to answer. Try again in a moment."
                else "Couldn't reach ${address.trim()}. Check the address and your connection.",
            )
        } finally {
            logOut(client)
        }
    }

    /**
     * Logs in to check the account and, if that works, saves it and adds its source.
     * Returns an error to show the reader, or null on success.
     */
    suspend fun connect(address: String, user: String, password: String): String? {
        val check = check(address, user, password, readCategories = false)
        if (check is Check.Failed) return check.message
        return add(address, user, password, category = null)
    }

    /** What a sign-in found in the account: [feeds] in [categories] (the ones that have feeds). */
    data class Found(val feeds: Int, val categories: Int)

    sealed interface SignIn {
        data class Failed(val message: String) : SignIn
        /** Signed in and saved; [found] is null if the feed list couldn't be read. */
        data class SignedIn(val found: Found?) : SignIn
    }

    /**
     * Checks the login and, if it works, saves it and adds its source, taking articles from all
     * feeds. The same login signing in again keeps its category; another one starts from all.
     *
     * @param saved runs straight after the account is saved, e.g. to record the server setup.
     *   The saving and it can't be cancelled once begun: cut short between them, the account
     *   would be there with nothing saying it was chosen.
     */
    suspend fun signIn(address: String, user: String, password: String, saved: suspend () -> Unit = {}): SignIn {
        val check = check(address, user, password, readCategories = false, readFeeds = true)
        if (check is Check.Failed) return SignIn.Failed(check.message)
        val feeds = (check as Check.Passed).feeds
        withContext(NonCancellable) {
            add(address, user, password, category = null, keepCategory = true)?.let { return@withContext it }
            saved()
            null
        }?.let { return SignIn.Failed(it) }
        return SignIn.SignedIn(feeds?.let { f -> Found(f.size, f.mapNotNull { it.categoryId }.distinct().size) })
    }

    /** Forgets the login and removes the account's source, with its articles. */
    suspend fun signOut() {
        accounts.clear()
        db.sources().ofKind(SourceKind.TTRSS).forEach { sources.remove(it) }
    }

    /** The account's source and whether its saved login can be used, kept up to date. */
    fun observeStatus(): Flow<TtrssStatus> = combine(sources.observe(), accounts.observe()) { all, stored ->
        val source = all.firstOrNull { it.kind == SourceKind.TTRSS }
        when (stored) {
            is StoredAccount.Ready -> TtrssStatus(source, stored.account.user, usable = source != null && stored.account.apiUrl == source.url)
            StoredAccount.Locked -> TtrssStatus(source, accounts.login()?.second, usable = false)
            StoredAccount.None -> TtrssStatus(source, null, usable = false)
        }
    }

    /**
     * Saves an account that [check] passed and adds its source, taking articles from [category]
     * (null for all unread) from the first sync; with [keepCategory], the same login signing in
     * again keeps the one it had. Returns an error to show the reader, or null.
     */
    suspend fun add(address: String, user: String, password: String, category: TtrssCategory?, keepCategory: Boolean = false): String? {
        val account = TtrssAccount(TtrssClient.apiUrl(address), user.trim(), password)
        val previous = try {
            accounts.login()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            accounts.save(account)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Some devices' Keystore throws runtime exceptions of its own.
            return "Couldn't store the password securely on this phone."
        }
        val sourceId = sources.addTtrss(account.apiUrl)
        // Category and feed ids belong to each tt-rss user, and this may be another user on the same
        // server. Another login starts from the category asked for (all, unless chosen); its feeds' publications (left out, what the
        // text check learned) aren't, so they're kept for the same user signing in again, after a
        // lost Keystore key say, and go only when the login is another one or was forgotten.
        val sameLogin = previous == (account.apiUrl to account.user)
        db.withTransaction {
            val kept = if (keepCategory && sameLogin) db.sources().byId(sourceId) else null
            if (kept != null) {
                db.sources().setTtrssCategory(sourceId, kept.ttrssCategoryId, kept.ttrssCategoryTitle)
            } else {
                db.sources().setTtrssCategory(sourceId, category?.id, category?.title)
            }
            if (!sameLogin) db.sources().clearPublications(sourceId)
        }
        return null
    }

    sealed interface Categories {
        data class Loaded(val categories: List<TtrssCategory>) : Categories
        data class Failed(val message: String) : Categories
    }

    /**
     * The account's categories, for choosing which one its source takes articles from, or with
     * [includeEmpty] one to subscribe a feed into.
     */
    suspend fun categories(includeEmpty: Boolean = false): Categories {
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
            Categories.Loaded(client.categories(includeEmpty))
        } catch (e: TtrssException) {
            Categories.Failed(e.message ?: "tt-rss reported an error.")
        } catch (e: IOException) {
            Categories.Failed(FeedSync.ttrssUnreachable(e))
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

    /**
     * For a reader coming back to a big backlog: marks everything that reached tt-rss more than two
     * weeks ago read there, in the source's category if it has one. Null when done, else why not.
     *
     * Articles already waiting here aren't changed here: tt-rss dates this by when it received each
     * article, which isn't kept here, and matching by publication date instead would drop
     * backdated articles tt-rss still has unread. With read sync on, the next sync brings in what
     * it marked read, as for anything read there.
     */
    suspend fun startFresh(sourceId: Long): String? {
        val source = db.sources().byId(sourceId) ?: return "This source has been removed."
        val account = try {
            (accounts.load() as? StoredAccount.Ready)?.account
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return FeedSync.SIGN_IN_AGAIN
        // A category id means nothing on another server, and "everything" would be someone else's.
        if (account.apiUrl != source.url) return FeedSync.SIGN_IN_AGAIN
        val client = account.client(http)
        try {
            val category = source.ttrssCategoryId
            if (category != null) client.markReadOlderThanTwoWeeks(category, isCategory = true) else client.markReadOlderThanTwoWeeks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException) {
            return e.message ?: "tt-rss reported an error."
        } catch (e: IOException) {
            // The catch-up may still be running on the server after the app stops waiting.
            return if (FeedSync.tooSlow(e)) "tt-rss took too long to answer. It may still be working through it: check in tt-rss before trying again." else FeedSync.ttrssUnreachable(e)
        } finally {
            logOut(client)
        }
        return null
    }

    /** A tt-rss login: its API address and username. Feed ids belong to it. */
    data class Login(val apiUrl: String, val user: String)

    /** What subscribing to a feed in tt-rss came to. */
    sealed interface Subscribed {
        /**
         * In tt-rss now, in [category] (null is Uncategorized). [feedId] is null when neither
         * tt-rss nor its feed list said it, so it can't be undone from here. [outsidePaper]: it's
         * outside the category the paper takes articles from.
         */
        data class Added(val feedId: Int?, val category: String?, val outsidePaper: Boolean = false, val login: Login? = null) : Subscribed
        /** [feed] is how it's listed here, or null if it isn't yet. */
        data class Already(val feed: PublicationEntity?) : Subscribed
        /** [couldntFetch]: tt-rss couldn't download or read a feed the phone just could. */
        data class Failed(val reason: String, val couldntFetch: Boolean = false) : Subscribed
    }

    /**
     * The account's feed at [feedUrl], as last listed, or null. A feed unsubscribed in tt-rss is
     * still stored here, unlisted, and doesn't count.
     */
    suspend fun feedAt(feedUrl: String): PublicationEntity? {
        val source = db.sources().ofKind(SourceKind.TTRSS).firstOrNull() ?: return null
        return db.sources().observePublicationsOf(source.id).first().firstOrNull { p ->
            p.feedUrl != null && sameFeed(p.feedUrl, feedUrl) && (p.listed || p.outsideCategory)
        }
    }

    /**
     * Subscribes to [feedUrl] in tt-rss, in [category], then lists the account's feeds straight
     * away rather than at the next daily check, so the feed shows on Sources and its id is known
     * for Undo. tt-rss fetches the feed before it answers, so this can take half a minute; the
     * caller runs it where closing a screen doesn't cancel it.
     */
    suspend fun subscribe(feedUrl: String, category: TtrssCategory): Subscribed {
        val (account, source) = usableAccount() ?: return Subscribed.Failed(FeedSync.SIGN_IN_AGAIN)
        val login = Login(account.apiUrl, account.user)
        // Feeds already listed: on a server that doesn't return the new feed's id, the one to
        // undo is a new row, never a near-duplicate address the reader already had.
        val before = listedKeys(source.id)
        val client = account.client(http)
        val answer = try {
            client.subscribeToFeed(feedUrl, category.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException) {
            logOut(client)
            return Subscribed.Failed(e.message ?: "tt-rss reported an error.")
        } catch (e: IOException) {
            logOut(client)
            // tt-rss may still add it after the app stops waiting.
            return Subscribed.Failed(
                if (FeedSync.tooSlow(e)) "tt-rss took too long to answer. It may still add it: check in tt-rss before trying again." else FeedSync.ttrssUnreachable(e),
            )
        } catch (e: Exception) {
            logOut(client)
            return Subscribed.Failed("Something went wrong asking tt-rss.")
        }
        if (answer is TtrssSubscription.Refused) {
            logOut(client)
            return Subscribed.Failed(answer.reason, answer.couldntFetch)
        }
        // tt-rss has it now: nothing after this may report a failure.
        val id = when (answer) {
            is TtrssSubscription.Added -> answer.feedId
            is TtrssSubscription.AlreadySubscribed -> answer.feedId
            is TtrssSubscription.Refused -> null
        }
        val listed = try {
            // Not if another login signed in while tt-rss was answering: this client would list
            // the old account's feeds under the new one. Read again otherwise, since the subscribe
            // may have taken long enough for the daily list to run.
            if (accounts.login() == login.apiUrl to login.user) db.sources().byId(source.id)?.let { listTtrssFeeds(db, client, it, clock.instant()) }
            id?.let { db.sources().publication(source.id, it.toString()) }?.takeIf { it.listed || it.outsideCategory }
                ?: if (answer is TtrssSubscription.AlreadySubscribed) feedAt(feedUrl) else newFeedAt(source.id, feedUrl, before)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            logOut(client)
        }
        return if (answer is TtrssSubscription.AlreadySubscribed) {
            Subscribed.Already(listed)
        } else {
            Subscribed.Added(
                id ?: listed?.key?.toIntOrNull(),
                if (listed != null) listed.category else category.title.takeIf { category.id != 0 },
                outsidePaper = listed?.outsideCategory == true,
                login = login,
            )
        }
    }

    /**
     * The feed a subscribe to [feedUrl] just added, from a server that didn't say its id: new
     * since [before], at that address exactly, or failing that the only new one at much the same
     * address. Anything less sure is no answer, since Undo would unsubscribe it.
     */
    private suspend fun newFeedAt(sourceId: Long, feedUrl: String, before: Set<String>): PublicationEntity? {
        val new = db.sources().observePublicationsOf(sourceId).first()
            .filter { (it.listed || it.outsideCategory) && it.key !in before && it.feedUrl != null && sameFeed(it.feedUrl, feedUrl) }
        return new.firstOrNull { it.feedUrl == feedUrl } ?: new.singleOrNull()
    }

    private suspend fun listedKeys(sourceId: Long): Set<String> =
        db.sources().observePublicationsOf(sourceId).first().filter { it.listed || it.outsideCategory }.map { it.key }.toSet()

    /**
     * Unsubscribes from the feed [feedId] of [login] in tt-rss, as Undo after [subscribe].
     * The feed is also left out here, and its waiting articles go, except starred ones: tt-rss
     * may have fetched it and a sync brought them in the seconds before Undo, and a sync still
     * running can add more after this, which the planner then skips. Returns an error to show
     * the reader, or null.
     */
    suspend fun unsubscribe(feedId: Int, login: Login?): String? {
        val (account, source) = usableAccount() ?: return FeedSync.SIGN_IN_AGAIN
        // Another login since: the id is someone else's feed, or none.
        if (login != null && login != Login(account.apiUrl, account.user)) return "You've signed in to tt-rss again since. Remove it in tt-rss itself."
        val client = account.client(http)
        try {
            try {
                client.unsubscribeFeed(feedId)
            } catch (e: TtrssException.ApiError) {
                // Both mean there's no such feed: it's already gone, as asked.
                if (e.code != "E_OPERATION_FAILED" && e.code != "FEED_NOT_FOUND") throw e
            }
            val key = feedId.toString()
            db.withTransaction {
                val publication = db.sources().publication(source.id, key) ?: PublicationEntity(source.id, key)
                db.sources().savePublication(publication.copy(leftOut = true))
                db.articles().expireWaitingFromFeed(source.id, key)
            }
            db.sources().byId(source.id)?.let { listTtrssFeeds(db, client, it, clock.instant()) }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException) {
            return e.message ?: "tt-rss reported an error."
        } catch (e: IOException) {
            return FeedSync.ttrssUnreachable(e)
        } catch (e: Exception) {
            return "Something went wrong taking it out."
        } finally {
            logOut(client)
        }
    }

    /** The saved login and the account's source, when the login is for that source's server. */
    private suspend fun usableAccount(): Pair<TtrssAccount, SourceEntity>? {
        val source = db.sources().ofKind(SourceKind.TTRSS).firstOrNull() ?: return null
        // Some devices' Keystore throws runtime exceptions of its own.
        val account = try {
            (accounts.load() as? StoredAccount.Ready)?.account
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        // Feed and category ids mean nothing on another server.
        return if (account.apiUrl == source.url) account to source else null
    }

    /** Nothing in the app pauses a tt-rss account, but one can already be paused: this is the way back. */
    suspend fun resume(sourceId: Long) = sources.setPaused(sourceId, false)

    suspend fun setMarkRead(sourceId: Long, markRead: Boolean) = db.sources().setMarkReadOnServer(sourceId, markRead)

    /**
     * Brings the server in line with the edition: its tt-rss articles read once it's delivered,
     * unread again once it's marked as not sent. Decided when this runs rather than when it was
     * asked for, so a late retry can't undo a newer change. Returns false if it failed in a way
     * that may pass.
     */
    suspend fun syncRead(editionId: Long): Boolean = when (db.editions().byId(editionId)?.status) {
        EditionStatus.DELIVERED -> markRead(editionId)
        EditionStatus.READY, EditionStatus.FAILED -> markUnread(editionId)
        else -> true
    }

    /**
     * Marks the edition's tt-rss articles read on the server. Returns false if it failed in a
     * way that may pass (the server unreachable), after noting the problem on the source.
     */
    suspend fun markRead(editionId: Long): Boolean =
        update(db.articles().ttrssInEdition(editionId), read = true, "Delivered articles weren't marked read in tt-rss.") { markRead(it) }

    /** Marks the tt-rss articles of an edition marked as not sent unread again, like [markRead]. */
    suspend fun markUnread(editionId: Long): Boolean =
        update(db.articles().ttrssUnsentInEdition(editionId), read = false, "Articles from an edition that wasn't sent weren't marked unread in tt-rss.") { markUnread(it) }

    private suspend fun update(articles: List<ArticleEntity>, read: Boolean, failed: String, call: suspend TtrssClient.(List<Long>) -> Unit): Boolean {
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
            client.call(ids)
            // Only what tt-rss confirms: one it didn't take stays as the app last knew it, for the
            // next sync to put right.
            val confirmed = client.confirmed(articles.map { TtrssRef(it.guid, it.originId) }, read).toSet()
            articles.filter { it.guid in confirmed }.groupBy { it.sourceId }
                .forEach { (sourceId, its) -> its.map { it.guid }.chunked(500).forEach { db.articles().setReportedRead(sourceId, it, read) } }
            sourceIds.forEach { db.sources().setServerNote(it, null) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException) {
            e.message to (e is TtrssException.HttpError)
        } catch (e: IOException) {
            FeedSync.ttrssUnreachable(e) to true
        } finally {
            logOut(client)
        }
        sourceIds.forEach { db.sources().setServerNote(it, "$failed $problem") }
        return !retry
    }
}
