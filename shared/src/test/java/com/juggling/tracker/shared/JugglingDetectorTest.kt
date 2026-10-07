package com.juggling.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Garmin detector tests of `connectiq/test/DetectorTest.mc`, run against
 * the Kotlin port with the same inputs and the same expectations. Each test
 * names the requirement in `connectiq/REQUIREMENTS.md` it secures.
 */
class JugglingDetectorTest {

    private fun warmedUp(d: JugglingDetector): Long = d.feed(Feeds.warmup())

    private fun JugglingDetector.catches(startMs: Long, n: Int) = feed(Feeds.catches(startMs, n))

    private fun JugglingDetector.idle(startMs: Long, samples: Int = 60) = feed(Feeds.baseline(startMs, samples))

    @Test
    fun `DET-1 samples are milli-g converted to m per s squared`() {
        assertEquals(9.80665 / 1000.0, JugglingDetector.MILLI_G_TO_MS2, 0.0)
        assertEquals(25, JugglingDetector.SAMPLE_RATE)
    }

    @Test
    fun `DET-4 no count during warmup`() {
        val d = JugglingDetector(3)
        var t = 0L
        repeat(24) {
            d.processSample(0, 0, -4000, t)
            t += Feeds.PERIOD_MS
        }
        assertEquals(0, d.currentCount)
        assertEquals(0, d.sessionRuns())
    }

    @Test
    fun `DET-5 raw magnitude gate rejects weak motion`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.feed(Feeds.burst(t, 4.0))
        d.idle(t, 20)
        assertEquals(0, d.currentCount)
    }

    @Test
    fun `DET-6 parameters are per ball count`() {
        val expected = mapOf(
            3 to listOf(2.0, 80.0, 7.0, 160.0),
            4 to listOf(3.0, 80.0, 11.0, 160.0),
            5 to listOf(3.0, 40.0, 13.0, 160.0),
            6 to listOf(5.0, 40.0, 17.0, 120.0),
            7 to listOf(2.5, 160.0, 16.0, 80.0),
            9 to listOf(2.5, 160.0, 16.0, 80.0),
        )
        for ((balls, p) in expected) {
            val d = JugglingDetector(balls)
            assertEquals(
                "$balls balls",
                p,
                listOf(d.hpThreshold, d.refractoryMs.toDouble(), d.minRawMag, d.mergeWindowMs.toDouble()),
            )
        }
    }

    @Test
    fun `DET-6 the gate is per ball count`() {
        val three = JugglingDetector(3)
        three.feed(Feeds.burst(warmedUp(three), 12.0))
        assertEquals("12 m/s2 clears the 3-ball gate of 7.0", 1, three.currentCount)

        val six = JugglingDetector(6)
        six.feed(Feeds.burst(warmedUp(six), 12.0))
        assertEquals("12 m/s2 must not clear the 6-ball gate of 17.0", 0, six.currentCount)
    }

    @Test
    fun `DET-8 every other burst is a watch hand catch`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.feed(Feeds.burst(t))
        assertEquals(1, d.currentCount)
        t = d.feed(Feeds.burst(t))
        assertEquals(1, d.currentCount)
        d.feed(Feeds.burst(t))
        assertEquals(2, d.currentCount)
    }

    @Test
    fun `DET-9 run auto finishes after idle delay`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.catches(t, 3)
        assertEquals(3, d.currentCount)
        d.idle(t)
        assertEquals(0, d.currentCount)
        assertEquals(3, d.previousCount)
        assertEquals(1, d.sessionRuns())
    }

    @Test
    fun `DET-9 auto finish keys on committed bursts not low level motion`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.catches(t, 3)
        // Wrist motion under the gate keeps arriving but never commits a burst.
        repeat(6) { t = d.feed(Feeds.burst(t, 4.0)) }
        assertEquals("low-level motion must not hold the run open", 0, d.currentCount)
        assertEquals(1, d.sessionRuns())
    }

    @Test
    fun `DET-10 run duration spans first to last catch`() {
        val d = JugglingDetector(3)
        val t = warmedUp(d)
        d.catches(t, 3)
        d.finishCurrentRun()
        val durations = d.runDurationsMillis()
        assertEquals(1, durations.size)
        // Five bursts, 15 samples apart: first to last watch-hand catch is 4 bursts.
        assertEquals(4 * 15 * Feeds.PERIOD_MS, durations[0])
    }

    @Test
    fun `RUN-1 false starts are not recorded`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.catches(t, 2)
        assertEquals(2, d.currentCount)
        d.idle(t)
        assertEquals(0, d.sessionRuns())
        assertEquals(0, d.previousCount)
        assertEquals(0, d.sessionMax)
        assertTrue(d.runCatches().isEmpty())
        assertEquals(0, d.currentCount)
    }

    @Test
    fun `RUN-1 three catches is a real run`() {
        val d = JugglingDetector(3)
        d.idle(d.catches(warmedUp(d), 3))
        assertEquals(1, d.sessionRuns())
        assertEquals(3, d.previousCount)
        assertEquals(3, d.sessionMax)
    }

    @Test
    fun `RUN-3 session average is over completed runs`() {
        val d = JugglingDetector(3)
        assertEquals(0.0, d.sessionAverage(), 0.0)
        var t = warmedUp(d)
        t = d.idle(d.catches(t, 3))
        d.idle(d.catches(t, 5))
        assertEquals(2, d.sessionRuns())
        assertEquals(4.0, d.sessionAverage(), 0.0)
        assertEquals(5, d.sessionMax)
    }

    @Test
    fun `RUN-4 discard drops the run in progress`() {
        val d = JugglingDetector(3)
        d.catches(warmedUp(d), 4)
        assertTrue(d.isRunActive())
        assertTrue(d.discardLastRun())
        assertEquals(0, d.currentCount)
        assertEquals(0, d.sessionRuns())
        assertEquals(0, d.previousCount)
    }

    @Test
    fun `RUN-4 discard removes last completed run and recomputes`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.idle(d.catches(t, 5))
        t = d.idle(d.catches(t, 3))
        d.idle(d.catches(t, 7))
        assertEquals(3, d.sessionRuns())
        assertEquals(7, d.sessionMax)
        assertEquals(5.0, d.sessionAverage(), 0.0)

        assertTrue(d.discardLastRun())
        assertEquals(2, d.sessionRuns())
        assertEquals(3, d.previousCount)
        assertEquals(5, d.sessionMax)
        assertEquals(4.0, d.sessionAverage(), 0.0)
        assertEquals(2, d.runCatches().size)
        assertEquals(2, d.runDurationsMillis().size)
    }

    @Test
    fun `RUN-4 discard takes the last run not a matching earlier one`() {
        val d = JugglingDetector(3)
        var t = warmedUp(d)
        t = d.idle(d.catches(t, 4))
        t = d.idle(d.catches(t, 6))
        d.idle(d.catches(t, 4))
        d.discardLastRun()
        assertEquals(listOf(4, 6), d.runCatches())
        assertEquals(6, d.sessionMax)
    }

    @Test
    fun `RUN-5 discard reports failure when there is nothing to discard`() {
        val d = JugglingDetector(3)
        assertFalse(d.discardLastRun())
        d.idle(warmedUp(d), 20)
        assertFalse(d.discardLastRun())
    }

    @Test
    fun `RUN-6 finishing records the run in progress`() {
        val d = JugglingDetector(3)
        d.catches(warmedUp(d), 4)
        assertEquals(4, d.finishCurrentRun())
        assertEquals(0, d.currentCount)
        assertEquals(1, d.sessionRuns())
        assertEquals(4, d.previousCount)
    }

    @Test
    fun `RUN-6 finishing with no run in progress records nothing`() {
        val d = JugglingDetector(3)
        warmedUp(d)
        assertEquals(0, d.finishCurrentRun())
        assertEquals(0, d.sessionRuns())
    }
}
