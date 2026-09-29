package com.app.newspaperss.core.edition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test
    fun laterTodayOrTomorrow() {
        val daily = Schedule(LocalTime.of(6, 30))
        assertEquals(at("2026-09-29T06:30"), daily.nextAfter(at("2026-09-29T05:00")))
        assertEquals("exactly at the time means the next one", at("2026-09-30T06:30"), daily.nextAfter(at("2026-09-29T06:30")))
    }

    @Test
    fun onlyChosenDays() {
        val weekend = Schedule(LocalTime.of(8, 0), setOf(SATURDAY, SUNDAY))
        assertEquals(at("2026-10-03T08:00"), weekend.nextAfter(at("2026-09-29T09:00")))
        val mondays = Schedule(LocalTime.of(8, 0), setOf(MONDAY))
        assertEquals("a full week ahead", at("2026-10-05T08:00"), mondays.nextAfter(at("2026-09-28T08:00")))
    }

    @Test
    fun noDaysMeansNoSchedule() {
        assertNull(Schedule(days = emptySet()).nextAfter(at("2026-09-29T05:00")))
    }

    @Test
    fun keepsLocalTimeAcrossDaylightSaving() {
        // US clocks go back on 2026-11-01.
        val next = Schedule(LocalTime.of(6, 30)).nextAfter(at("2026-10-31T07:00"))!!
        assertEquals(LocalTime.of(6, 30), next.toLocalTime())
        assertEquals(java.time.ZoneOffset.ofHours(-5), next.offset)
    }
}
