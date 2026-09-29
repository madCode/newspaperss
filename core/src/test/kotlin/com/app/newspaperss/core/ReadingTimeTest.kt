package com.app.newspaperss.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTimeTest {
    @Test
    fun shortPiecesCountTowardTheBudget() {
        assertEquals(0.5, ReadingTime.minutes(119, wpm = 238), 0.01)
    }
}
