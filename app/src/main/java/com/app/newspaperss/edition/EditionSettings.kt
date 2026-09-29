package com.app.newspaperss.edition

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Budget
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.edition.PlanRules

data class EditionSettings(
    val minutes: Int = 30,
    val maxPerSource: Int = 1,
    val ordering: Ordering = Ordering.TAKE_TURNS,
    val wordsPerMinute: Int = ReadingTime.DEFAULT_WPM,
) {
    val rules get() = PlanRules(Budget.Minutes(minutes.toDouble()), maxPerSource, ordering)
}
