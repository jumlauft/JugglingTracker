package com.juggling.tracker.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneJugglingDetectorTest {
    private companion object {
        const val GRAVITY = 9.80665
        const val PERIOD_MS = PhoneJugglingDetector.SAMPLE_PERIOD_MS
    }

    @Test
    fun `does not count during warmup`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = 0L

        repeat(PhoneJugglingDetector.WARMUP_SAMPLES - 1) {
            detector.processSample(0.0, 0.0, GRAVITY - 50.0, nowMs)
            nowMs += PERIOD_MS
        }

        assertEquals(0, detector.currentCount)
        assertTrue(detector.runCatches().isEmpty())
    }

    @Test
    fun `counts odd committed bursts as counting hand catches`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)

        nowMs = feedBurst(detector, nowMs)
        assertEquals(1, detector.currentCount)

        nowMs = feedBurst(detector, nowMs)
        assertEquals(1, detector.currentCount)

        feedBurst(detector, nowMs)
        assertEquals(2, detector.currentCount)
    }

    @Test
    fun `finish current run records count and duration`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        nowMs = feedCatches(detector, nowMs, 3)

        val finished = detector.finishCurrentRun()

        assertEquals(3, finished)
        assertEquals(0, detector.currentCount)
        assertEquals(3, detector.previousCount)
        assertEquals(listOf(3), detector.runCatches())
        assertEquals(1, detector.runDurationsMillis().size)
        assertTrue(detector.runDurationsMillis()[0] > 0L)
    }

    @Test
    fun `auto finish records idle run from committed bursts`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        nowMs = feedCatches(detector, nowMs, 3)

        // AUTO_FINISH_DELAY_MS is 2000ms, so 400+ samples at 5ms
        feedBaseline(detector, nowMs, 450)

        assertEquals(0, detector.currentCount)
        assertEquals(3, detector.previousCount)
        assertEquals(listOf(3), detector.runCatches())
    }

    // ── False starts ────────────────────────────────────────────────────
    //
    // Mirrors MIN_RUN_CATCHES in JugglingDetector.mc: below any real ball
    // count, catching fewer than three times before dropping is a false start,
    // not a run, and recording it wrecks Prev/Avg/Max and the per-run list.

    @Test
    fun `the false start threshold is three catches`() {
        // Pinned as a literal, and the tests below use literal run lengths
        // too: deriving them from the constant would let lowering it hollow
        // the tests into vacuous passes instead of failing them.
        assertEquals(3, PhoneJugglingDetector.MIN_RUN_CATCHES)
    }

    @Test
    fun `a run under three catches is discarded as a false start`() {
        for (catches in 1..2) {
            val detector = PhoneJugglingDetector(3)
            var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
            nowMs = feedCatches(detector, nowMs, catches)
            assertEquals("expected $catches catches in progress", catches, detector.currentCount)

            feedBaseline(detector, nowMs, 450)   // let it auto-finish

            assertEquals("$catches catches must not be recorded", 0, detector.sessionRuns())
            assertEquals("$catches catches must not become Prev", 0, detector.previousCount)
            assertEquals("$catches catches must not become Max", 0, detector.sessionMax)
            assertTrue("$catches catches must not be listed", detector.runCatches().isEmpty())
            // The live count still resets, ready for the next attempt.
            assertEquals(0, detector.currentCount)
        }
    }

    @Test
    fun `three catches is a real run`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        nowMs = feedCatches(detector, nowMs, 3)

        feedBaseline(detector, nowMs, 450)

        assertEquals(1, detector.sessionRuns())
        assertEquals(3, detector.previousCount)
        assertEquals(3, detector.sessionMax)
        assertEquals(listOf(3), detector.runCatches())
    }

    @Test
    fun `a false start leaves an earlier real run untouched`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        nowMs = feedCatches(detector, nowMs, 5)
        nowMs = feedBaseline(detector, nowMs, 450)

        // Now drop after two catches.
        nowMs = feedCatches(detector, nowMs, 2)
        feedBaseline(detector, nowMs, 450)

        assertEquals("the false start must not add a run", 1, detector.sessionRuns())
        assertEquals("Prev must still be the real run", 5, detector.previousCount)
        assertEquals(5, detector.sessionMax)
        assertEquals(5.0, detector.sessionAverage(), 0.0001)
        assertEquals(listOf(5), detector.runCatches())
    }

    @Test
    fun `raw gate rejects low magnitude candidates for three balls`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)

        // Tuned MIN_RAW_MAG_3 is 7.0
        nowMs = feedBurst(detector, nowMs, amplitude = 4.0)
        feedBaseline(detector, nowMs, 20)

        assertEquals(0, detector.currentCount)
        assertTrue(detector.runCatches().isEmpty())
    }

    // The phone detector is a port of connectiq/source/JugglingDetector.mc and
    // runs at the same 25 Hz. Its constants must match the watch detector; keep
    // both plus simulation/eval_new_watch.py in sync.

    private fun feedBaseline(
        detector: PhoneJugglingDetector,
        startMs: Long,
        samples: Int,
    ): Long {
        var nowMs = startMs
        repeat(samples) {
            detector.processSample(0.0, 0.0, GRAVITY, nowMs)
            detector.checkAutoFinish(nowMs)
            nowMs += PERIOD_MS
        }
        return nowMs
    }

    /**
     * Drives [catches] watch-hand catches into the current run. Only every
     * other burst is the watch hand, so that takes 2 * catches - 1 bursts.
     */
    private fun feedCatches(
        detector: PhoneJugglingDetector,
        startMs: Long,
        catches: Int,
    ): Long {
        var nowMs = startMs
        repeat(catches * 2 - 1) {
            nowMs = feedBurst(detector, nowMs)
        }
        return nowMs
    }

    private fun feedBurst(
        detector: PhoneJugglingDetector,
        startMs: Long,
        amplitude: Double = 50.0,
        pulseSamples: Int = 3,
        settleSamples: Int = 12,
    ): Long {
        var nowMs = startMs
        repeat(pulseSamples) {
            // Pulse Z-axis opposite gravity to create upward acceleration
            detector.processSample(0.0, 0.0, GRAVITY - amplitude, nowMs)
            detector.checkAutoFinish(nowMs)
            nowMs += PERIOD_MS
        }
        repeat(settleSamples) {
            detector.processSample(0.0, 0.0, GRAVITY, nowMs)
            detector.checkAutoFinish(nowMs)
            nowMs += PERIOD_MS
        }
        return nowMs
    }

}
