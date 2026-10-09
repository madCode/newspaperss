package com.app.newspaperss.listen

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the podcast's making did, run by run (when each run started and stopped and why, what it
 * kept, the sentences that took long or sound wrong), and where each play started and what
 * stopped one. Kept in [file] (the last [keep] entries) so a phone
 * without a computer attached can show it, and written to logcat for one with.
 *
 * @param file null to write to logcat only.
 */
class PodcastLog(private val file: File?, private val keep: Int = 400, private val now: () -> Long = System::currentTimeMillis) {
    // Not the phone's locale: the log is read by people fixing the app, and pasted to them.
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    fun add(message: String) {
        Log.i(TAG, message)
        val file = file ?: return
        synchronized(this) {
            try {
                file.appendText("${time.format(Date(now()))}  $message\n")
                // Trimmed in batches, not on every line: rewriting the file each time costs more.
                if (file.length() > keep * BYTES_PER_LINE * 2) {
                    val lines = file.readLines()
                    if (lines.size > keep) file.writeText(lines.takeLast(keep).joinToString("\n", postfix = "\n"))
                }
            } catch (e: Exception) {
                // A full disk: the log is the first thing to give up.
            }
        }
    }

    /** The entries kept, oldest first. */
    fun read(): List<String> = synchronized(this) {
        try {
            file?.takeIf { it.exists() }?.readLines()?.takeLast(keep).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val TAG = "PodcastLog"
        const val BYTES_PER_LINE = 100
    }
}
