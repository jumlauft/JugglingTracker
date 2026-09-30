package com.juggling.tracker.wear.logic

/**
 * Thins a faster sensor stream to the detector's 25 Hz (DET-1). It keeps one
 * sample per 40 ms slot on a fixed grid; measuring 40 ms from the last kept
 * sample instead would drop a sensor running at, say, 30 ms intervals to
 * about 17 Hz.
 */
class SampleThrottle(private val periodMs: Long = JugglingDetector.SAMPLE_PERIOD_MS) {
    private var nextDueMs: Long? = null

    /** Whether the sample stamped [timeMs] should go to the detector. */
    fun accept(timeMs: Long): Boolean {
        val due = nextDueMs
        if (due != null && timeMs < due) return false
        nextDueMs = if (due == null || timeMs - due >= periodMs) {
            // First sample, or the stream stalled: restart the grid here.
            timeMs + periodMs
        } else {
            due + periodMs
        }
        return true
    }

    fun reset() {
        nextDueMs = null
    }
}
