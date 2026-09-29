package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleThrottleTest {
    private fun keptPerSecond(intervalMs: Long, seconds: Int = 10): Double {
        val throttle = SampleThrottle()
        var kept = 0
        var t = 0L
        while (t < seconds * 1000L) {
            if (throttle.accept(t)) kept += 1
            t += intervalMs
        }
        return kept.toDouble() / seconds
    }

    @Test
    fun `DET-1 faster sensors are thinned to 25 Hz`() {
        for (interval in listOf(5L, 10L, 20L, 30L, 38L)) {
            assertEquals("sensor every $interval ms", 25.0, keptPerSecond(interval), 1.0)
        }
    }

    @Test
    fun `a sensor already at 25 Hz keeps every sample`() {
        assertEquals(25.0, keptPerSecond(40), 0.0)
    }

    @Test
    fun `a slower sensor keeps every sample it gets`() {
        assertEquals(20.0, keptPerSecond(50), 0.0)
    }
}
