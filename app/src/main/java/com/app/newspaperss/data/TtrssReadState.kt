package com.app.newspaperss.data

import com.app.newspaperss.core.ttrss.TtrssClient
import com.app.newspaperss.core.ttrss.TtrssException
import kotlinx.coroutines.CancellationException

/**
 * tt-rss's unread flag for each of [refs], by guid, asked one feed at a time. Missing where tt-rss
 * didn't say: the article is gone from it, the guid or feed isn't one tt-rss gave, or the feed
 * couldn't be read. One feed tt-rss can't serve is passed over, as the sync's fetch passes it over,
 * rather than failing the sync for every other feed.
 */
internal suspend fun TtrssClient.unreadByGuid(refs: Collection<TtrssRef>): Map<String, Boolean> {
    val byFeed = refs.mapNotNull { ref ->
        val feed = ref.originId?.toIntOrNull() ?: return@mapNotNull null
        val id = ref.guid.removePrefix(FeedSync.TTRSS_GUID_PREFIX).toLongOrNull() ?: return@mapNotNull null
        Triple(feed, id, ref.guid)
    }.groupBy { it.first }
    val states = mutableMapOf<String, Boolean>()
    for ((feed, articles) in byFeed) {
        val unread = try {
            unreadStates(feed, sinceId = articles.minOf { it.second } - 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtrssException.HttpError) {
            continue
        } catch (e: TtrssException.ApiError) {
            if (e.code == "NOT_LOGGED_IN") throw e
            continue
        }
        for ((_, id, guid) in articles) unread[id]?.let { states[guid] = it }
    }
    return states
}

/**
 * Of [refs] just set to [read] in tt-rss, the guids it now confirms: read back rather than taken on
 * trust, since the next sync treats a differing flag as a change made in tt-rss. One tt-rss no
 * longer has counts as done: there's nothing left to change.
 */
internal suspend fun TtrssClient.confirmed(refs: Collection<TtrssRef>, read: Boolean): List<String> {
    if (refs.isEmpty()) return emptyList()
    val states = unreadByGuid(refs)
    return refs.map { it.guid }.filter { guid -> states[guid]?.let { unread -> unread != read } ?: true }
}
