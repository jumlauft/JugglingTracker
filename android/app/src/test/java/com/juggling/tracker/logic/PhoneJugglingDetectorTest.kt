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
        nowMs = feedBurst(detector, nowMs)
        nowMs = feedBurst(detector, nowMs)
        feedBurst(detector, nowMs)

        val finished = detector.finishCurrentRun()

        assertEquals(2, finished)
        assertEquals(0, detector.currentCount)
        assertEquals(2, detector.previousCount)
        assertEquals(listOf(2), detector.runCatches())
        assertEquals(1, detector.runDurationsMillis().size)
        assertTrue(detector.runDurationsMillis()[0] > 0L)
    }

    @Test
    fun `auto finish records idle run from committed bursts`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        nowMs = feedBurst(detector, nowMs)

        // AUTO_FINISH_DELAY_MS is 2000ms, so 400+ samples at 5ms
        feedBaseline(detector, nowMs, 450)

        assertEquals(0, detector.currentCount)
        assertEquals(1, detector.previousCount)
        assertEquals(listOf(1), detector.runCatches())
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
