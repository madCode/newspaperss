package com.app.newspaperss.listen

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where listening is in an edition: the [page] (article, or the closing page last) and the sentence [line] in it. */
data class ListenPosition(val page: Int = 0, val line: Int = 0)

/** Where listening stopped in each recent edition, and which were heard to the end. */
interface ListenProgress {
    fun get(editionId: Long): ListenPosition?
    fun set(editionId: Long, position: ListenPosition)
    fun finish(editionId: Long)
    fun finished(editionId: Long): Boolean

    /** Editions started and not finished, the most recently heard first. */
    fun unfinished(): List<Long>
}

/** In SharedPreferences, for the few most recent editions: older ones are forgotten. */
class StoredListenProgress(context: Context, private val now: () -> Long = System::currentTimeMillis) : ListenProgress {
    private val prefs: SharedPreferences = context.getSharedPreferences("listening", Context.MODE_PRIVATE)

    override fun get(editionId: Long): ListenPosition? = prefs.getString(key(editionId), null)?.let(::decode)?.position

    override fun set(editionId: Long, position: ListenPosition) = write(editionId, Entry(position, finished = false, now()))

    override fun finish(editionId: Long) = write(editionId, Entry(ListenPosition(), finished = true, now()))

    override fun finished(editionId: Long): Boolean = prefs.getString(key(editionId), null)?.let(::decode)?.finished == true

    override fun unfinished(): List<Long> = entries().filter { !it.second.finished }.sortedByDescending { it.second.at }.map { it.first }

    private fun write(editionId: Long, entry: Entry) {
        prefs.edit {
            putString(key(editionId), "${entry.position.page}:${entry.position.line}:${if (entry.finished) 1 else 0}:${entry.at}")
            entries().filter { it.first != editionId }.sortedByDescending { it.second.at }.drop(KEEP - 1).forEach { remove(key(it.first)) }
        }
    }

    private fun entries(): List<Pair<Long, Entry>> = prefs.all.mapNotNull { (k, v) ->
        val id = k.removePrefix(PREFIX).takeIf { k.startsWith(PREFIX) }?.toLongOrNull() ?: return@mapNotNull null
        (v as? String)?.let(::decode)?.let { id to it }
    }

    private class Entry(val position: ListenPosition, val finished: Boolean, val at: Long)

    private fun decode(value: String): Entry? {
        val parts = value.split(':')
        if (parts.size != 4) return null
        return Entry(ListenPosition(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null), parts[2] == "1", parts[3].toLongOrNull() ?: 0)
    }

    private fun key(editionId: Long) = PREFIX + editionId

    private companion object {
        const val PREFIX = "edition-"
        const val KEEP = 10
    }
}
