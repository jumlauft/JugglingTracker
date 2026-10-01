package com.juggling.tracker.logic

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
    fun `faster sensors are thinned to 25 Hz`() {
        for (interval in listOf(5L, 10L, 20L, 30L, 38L)) {
            assertEquals("sensor every $interval ms", 25.0, keptPerSecond(interval), 1.0)
        }
    }

    @Test
    fun `a jittery 200 Hz sensor still yields 25 Hz`() {
        // Real phones stamp events 4-7 ms apart around a 5 ms mean. Counting
        // 40 ms from the last kept sample kept 23.8 a second here.
        val throttle = SampleThrottle()
        val gaps = longArrayOf(4, 6, 5, 7, 3, 6, 4)
        var kept = 0
        var t = 0L
        var i = 0
        while (t < 60_000L) {
            if (throttle.accept(t)) kept += 1
            t += gaps[i++ % gaps.size]
        }
        assertEquals(25.0, kept / 60.0, 0.2)
    }

    @Test
    fun `a sensor already at 25 Hz keeps every sample`() {
        assertEquals(25.0, keptPerSecond(40), 0.0)
    }

    @Test
    fun `a slower sensor keeps every sample it gets`() {
        assertEquals(20.0, keptPerSecond(50), 0.0)
    }

    @Test
    fun `reset starts a fresh grid`() {
        val throttle = SampleThrottle()
        assertEquals(true, throttle.accept(1_000))
        assertEquals(false, throttle.accept(1_010))
        throttle.reset()
        assertEquals(true, throttle.accept(1_010))
    }
}
