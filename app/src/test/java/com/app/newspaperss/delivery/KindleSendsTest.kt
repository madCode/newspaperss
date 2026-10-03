package com.app.newspaperss.delivery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class KindleSendsTest {
    /** A clock that moves with the test's virtual time, so the note's delay and its age agree. */
    private fun TestScope.clock() = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    // "A few minutes" is a promise for now, not for whenever the reader next looks.
    @Test
    fun theNoteGoesOnceItsTimeIsUpWithoutAnotherSend() = runTest {
        val sends = KindleSends(clock())
        val seen = mutableListOf<Map<Long, KindleSend>>()
        backgroundScope.launch { sends.recent.toList(seen) }
        sends.record(1L, EditionIntents.KINDLE_PACKAGE)
        advanceTimeBy(KindleSends.NOTE_FOR.toMillis() / 2)
        sends.record(2L, EditionIntents.KINDLE_PACKAGE)
        runCurrent()
        assertEquals(setOf(1L, 2L), seen.last().keys)

        advanceTimeBy(KindleSends.NOTE_FOR.toMillis() / 2 + 1)
        assertEquals("the first send's note has gone, the second's stays", setOf(2L), seen.last().keys)
        advanceTimeBy(KindleSends.NOTE_FOR.toMillis() / 2)
        assertEquals(emptySet<Long>(), seen.last().keys)
    }

    // The note says how it went: the Kindle app puts it in the library, an email delivers it to the device.
    @Test
    fun eachSendIsRememberedAsTheWayItWentAndAnotherAppClearsIt() = runTest {
        val sends = KindleSends(clock())
        val seen = mutableListOf<Map<Long, KindleSend>>()
        backgroundScope.launch { sends.recent.toList(seen) }
        sends.record(1L, KindleSend.EMAIL)
        sends.record(2L, EditionIntents.KINDLE_PACKAGE)
        runCurrent()
        assertEquals(mapOf(1L to KindleSend.EMAIL, 2L to KindleSend.APP), seen.last())

        // Sent again with the Kindle app, then with an app that isn't Kindle.
        sends.record(1L, EditionIntents.KINDLE_PACKAGE)
        sends.record(2L, "com.dropbox.android")
        runCurrent()
        assertEquals(mapOf(1L to KindleSend.APP), seen.last())
    }
}
