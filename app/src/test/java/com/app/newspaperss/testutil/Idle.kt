package com.app.newspaperss.testutil

import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * Waits for work that finishes on another thread and then resumes on the main
 * looper (DataStore, Room). Compose's waitUntil doesn't run those resumptions
 * under Robolectric, so this idles the looper itself between checks.
 *
 * [timeoutMs] only guards against hanging: a condition that comes true returns at once, so a
 * generous deadline costs a passing test nothing. Five seconds sat close enough to the time a
 * loaded two-core CI runner takes that correct code failed there now and again.
 */
fun idleUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMs ms" }
        Thread.sleep(10)
    }
}
