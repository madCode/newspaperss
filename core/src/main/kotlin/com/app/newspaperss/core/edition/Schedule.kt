package com.app.newspaperss.core.edition

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

data class Schedule(
    val time: LocalTime = LocalTime.of(6, 30),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    /**
     * The first scheduled moment strictly after [now], in [now]'s zone. Null
     * when no days are chosen.
     *
     * On a daylight-saving gap day the time moves forward with the clock
     * (6:30 stays 6:30 local, 2:30 becomes 3:30), as ZonedDateTime resolves it.
     */
    fun nextAfter(now: ZonedDateTime): ZonedDateTime? {
        if (days.isEmpty()) return null
        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in days) continue
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}

sealed interface TimerAction {
    data object Keep : TimerAction
    data object Cancel : TimerAction
    data class Arm(val at: Instant) : TimerAction
}

object ScheduleTimer {
    /**
     * What to do with the edition timer, given the one already pending.
     *
     * A pending time that has passed is kept: its timer is about to run, or
     * Doze is holding it, and replacing it with tomorrow's would skip today's
     * edition. An unchanged target is kept so that unrelated settings edits
     * don't restart the timer.
     */
    fun decide(pending: Instant?, target: Instant?, now: Instant): TimerAction = when {
        target == null -> if (pending == null) TimerAction.Keep else TimerAction.Cancel
        pending != null && !pending.isAfter(now) -> TimerAction.Keep
        pending == target -> TimerAction.Keep
        else -> TimerAction.Arm(target)
    }
}
