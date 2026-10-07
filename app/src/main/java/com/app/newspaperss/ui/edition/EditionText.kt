package com.app.newspaperss.ui.edition

import com.app.newspaperss.core.plural
import kotlin.math.roundToInt

/** "12 min": whole minutes, never 0 for something that takes any time. */
internal fun minutesLabel(minutes: Double): String = "${minutes.roundToInt().coerceAtLeast(1)} min"

/** "8 articles · about 33 min", as Today's cards and the edition page say it. */
internal fun articlesAndMinutes(count: Int, minutes: Double): String = "${plural(count, "article")} · about ${minutesLabel(minutes)}"
