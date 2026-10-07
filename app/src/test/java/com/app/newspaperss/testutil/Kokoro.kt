package com.app.newspaperss.testutil

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.listen.KokoroInstall
import java.io.File
import java.util.UUID

/**
 * Kokoro in [dir] as a manifest of one file, `tokens.txt`, downloaded and verified unless not
 * [installed]. The manifest's [size] is what Settings shows as the download's.
 */
fun kokoroInstall(
    dir: File,
    installed: Boolean = true,
    size: Long = 6,
    sha1: String = "ce013625030ba8dba906f756967f9e9ca394464a",
): KokoroInstall = KokoroInstall(dir) { listOf(KokoroFile("tokens.txt", size, sha1)) }.also { if (installed) it.finishDownload() }

/** Puts the manifest's one file in place and marks it verified, as a finished download does. */
fun KokoroInstall.finishDownload() {
    file("tokens.txt").writeText("hello\n")
    markVerified("tokens.txt")
}

/** Work that's running, with [progress] as it last reported it. */
fun runningWork(vararg progress: Pair<String, Any>) =
    WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet(), progress = workDataOf(*progress))
