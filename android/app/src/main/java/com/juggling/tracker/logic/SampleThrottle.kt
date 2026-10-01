package com.juggling.tracker.logic

import com.juggling.tracker.shared.JugglingDetector

/**
 * Thins the phone's ~200 Hz accelerometer to the detector's 25 Hz. It keeps
 * one sample per 40 ms slot on a fixed grid; measuring 40 ms from the last
 * kept sample instead lets every bit of timestamp jitter push the next slot
 * later, so a 5 ms sensor with a few ms of jitter kept about 23.8 samples a
 * second and skipped one 40 ms slot in twenty.
 *
 * Same logic as the Wear OS app's SampleThrottle (wearos/.../logic).
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
