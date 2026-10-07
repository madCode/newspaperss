package com.app.newspaperss.core.listen

import com.app.newspaperss.core.listen.PodcastPace.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTest {
    private fun matches(file: KokoroFile, bytes: ByteArray) = file.matches(file.digest().apply { update(bytes) })

    @Test
    fun aSmallFileIsCheckedAgainstItsGitBlobHash() {
        // `printf 'hello\n' | git hash-object --stdin`
        val file = KokoroFile("tokens.txt", 6, "ce013625030ba8dba906f756967f9e9ca394464a")
        assertTrue(matches(file, "hello\n".toByteArray()))
        assertFalse(matches(file, "hellO\n".toByteArray()))
    }

    @Test
    fun aLargeFileIsCheckedAgainstItsSha256() {
        // `printf 'hello\n' | sha256sum`
        val file = KokoroFile("model.onnx", 6, "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03")
        assertTrue(matches(file, "hello\n".toByteArray()))
        assertFalse(matches(file, "hello".toByteArray()))
    }

    @Test
    fun theManifestSkipsCommentsAndKeepsPathsWithSpaces() {
        val files = KokoroManifest.parse("# pinned\nmodel.onnx\t325560556\tb40f\nespeak-ng-data/voices/!v/Mr serious\t428\tab12\n\n")
        assertEquals(listOf("model.onnx", "espeak-ng-data/voices/!v/Mr serious"), files.map { it.path })
        assertEquals(325560556L, files[0].size)
    }

    @Test
    fun aPixel8CanMakeA30MinutePaper() {
        // 0.8× real time cool, measured on a Pixel 8.
        val pace = PodcastPace.fromSample(0.8)
        assertEquals(54.0, PodcastPace.minutesToMake(30, pace), 1.0)
        assertEquals(Verdict.CAN, PodcastPace.verdict(30, pace))
    }

    @Test
    fun aSlowerPhoneIsSlowButFineThenTooSlow() {
        assertEquals(Verdict.SLOW, PodcastPace.verdict(30, 2.5))
        assertEquals(Verdict.TOO_SLOW, PodcastPace.verdict(30, 4.0))
        // Too slow for 30 minutes, but a shorter paper fits.
        assertEquals(25, PodcastPace.longestPaper(4.0))
        assertEquals(Verdict.SLOW, PodcastPace.verdict(PodcastPace.longestPaper(4.0), 4.0))
    }
}
