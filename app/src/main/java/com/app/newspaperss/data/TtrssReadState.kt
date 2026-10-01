package com.app.newspaperss.data

import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * What tt-rss said about some articles' unread flags, by guid. [answered] are those whose feed
 * tt-rss answered for: one of them missing from [unread] is gone from tt-rss. The rest it wasn't
 * asked about, or couldn't say: their feed failed, or the guid or feed isn't one tt-rss gave.
 */
internal class ReadStates(val unread: Map<String, Boolean>, val answered: Set<String>) {
    /** Of [refs] just set to [read], those tt-rss confirms. One gone from tt-rss counts: there's nothing left to change. */
    fun confirmed(refs: Collection<TtrssRef>, read: Boolean): List<String> =
        // Set to read, it's confirmed unless still unread; set to unread, unless still read.
        refs.map { it.guid }.filter { it in answered && unread[it] != read }
}

/**
 * Asks tt-rss for each of [refs]' unread flags, one feed at a time. A feed tt-rss can't serve (an
 * error, a timeout) is passed over, as the sync's fetch passes it over, rather than failing the sync
 * for every other feed; its articles just aren't [ReadStates.answered].
 */
internal suspend fun TtrssClient.readStates(refs: Collection<TtrssRef>): ReadStates {
    val byFeed = refs.mapNotNull { ref ->
        val feed = ref.originId?.toIntOrNull() ?: return@mapNotNull null
        val id = ref.guid.removePrefix(FeedSync.TTRSS_GUID_PREFIX).toLongOrNull() ?: return@mapNotNull null
        Triple(feed, id, ref.guid)
    }.groupBy { it.first }
    val unread = mutableMapOf<String, Boolean>()
    val answered = mutableSetOf<String>()
    for ((feed, articles) in byFeed) {
        val states = try {
            unreadStates(feed, sinceId = articles.minOf { it.second } - 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException.LoginFailed) {
            throw e
        } catch (e: TtrssException.ApiDisabled) {
            throw e
        } catch (e: TtrssException.ApiError) {
            if (e.code == "NOT_LOGGED_IN") throw e
            continue
        } catch (e: TtrssException) {
            continue
        } catch (e: IOException) {
            continue
        }
        for ((_, id, guid) in articles) {
            answered += guid
            states[id]?.let { unread[guid] = it }
        }
    }
    return ReadStates(unread, answered)
}

/**
 * Of [refs] just set to [read] in tt-rss, the guids it now confirms: read back rather than taken on
 * trust, since the next sync treats a differing flag as a change made in tt-rss.
 */
internal suspend fun TtrssClient.confirmed(refs: Collection<TtrssRef>, read: Boolean): List<String> =
    if (refs.isEmpty()) emptyList() else readStates(refs).confirmed(refs, read)
