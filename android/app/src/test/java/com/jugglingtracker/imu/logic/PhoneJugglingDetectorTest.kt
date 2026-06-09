package com.jugglingtracker.imu.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
            detector.processSample(50.0, 0.0, GRAVITY, nowMs)
            nowMs += PERIOD_MS
        }

        assertEquals(0, detector.currentCount)
        assertTrue(detector.runCatches().isEmpty())
    }

    @Test
    fun `counts odd committed bursts as counting hand catches`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, 30)

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
        var nowMs = feedBaseline(detector, 0L, 30)
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
        var nowMs = feedBaseline(detector, 0L, 30)
        nowMs = feedBurst(detector, nowMs)

        feedBaseline(detector, nowMs, 70)

        assertEquals(0, detector.currentCount)
        assertEquals(1, detector.previousCount)
        assertEquals(listOf(1), detector.runCatches())
    }

    @Test
    fun `raw gate rejects low magnitude candidates for three balls`() {
        val detector = PhoneJugglingDetector(3)
        var nowMs = feedBaseline(detector, 0L, 30)

        nowMs = feedBurst(detector, nowMs, amplitude = 4.0)
        feedBaseline(detector, nowMs, 20)

        assertEquals(0, detector.currentCount)
        assertTrue(detector.runCatches().isEmpty())
    }

    @Test
    fun `android detector constants match watch detector constants`() {
        val source = watchDetectorSource() ?: return

        assertEquals(source.doubleConst("HP_THRESHOLD_3"), PhoneJugglingDetector.HP_THRESHOLD_3, 0.000001)
        assertEquals(source.doubleConst("HP_THRESHOLD_4"), PhoneJugglingDetector.HP_THRESHOLD_4, 0.000001)
        assertEquals(source.doubleConst("HP_THRESHOLD_5PLUS"), PhoneJugglingDetector.HP_THRESHOLD_5PLUS, 0.000001)
        assertEquals(source.doubleConst("HP_HYSTERESIS"), PhoneJugglingDetector.HP_HYSTERESIS, 0.000001)

        assertEquals(source.longConst("REFRACTORY_MS_3"), PhoneJugglingDetector.REFRACTORY_MS_3)
        assertEquals(source.longConst("REFRACTORY_MS_4"), PhoneJugglingDetector.REFRACTORY_MS_4)
        assertEquals(source.longConst("REFRACTORY_MS_5PLUS"), PhoneJugglingDetector.REFRACTORY_MS_5PLUS)

        assertEquals(source.doubleConst("MIN_RAW_MAG_3"), PhoneJugglingDetector.MIN_RAW_MAG_3, 0.000001)
        assertEquals(source.doubleConst("MIN_RAW_MAG_4"), PhoneJugglingDetector.MIN_RAW_MAG_4, 0.000001)
        assertEquals(source.doubleConst("MIN_RAW_MAG_5PLUS"), PhoneJugglingDetector.MIN_RAW_MAG_5PLUS, 0.000001)

        assertEquals(source.longConst("MERGE_WINDOW_MS_3"), PhoneJugglingDetector.MERGE_WINDOW_MS_3)
        assertEquals(source.longConst("MERGE_WINDOW_MS_4"), PhoneJugglingDetector.MERGE_WINDOW_MS_4)
        assertEquals(source.longConst("MERGE_WINDOW_MS_5PLUS"), PhoneJugglingDetector.MERGE_WINDOW_MS_5PLUS)
    }

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
            detector.processSample(amplitude, 0.0, GRAVITY, nowMs)
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

    private fun watchDetectorSource(): String? {
        val candidates = listOf(
            File("connectiq/source/JugglingDetector.mc"),
            File("../connectiq/source/JugglingDetector.mc"),
            File("../../connectiq/source/JugglingDetector.mc"),
            File("../../../connectiq/source/JugglingDetector.mc"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
    }

    private fun String.doubleConst(name: String): Double {
        val match = Regex("private const $name\\s*=\\s*([0-9.]+)f?;").find(this)
        return requireNotNull(match) { "Constant $name not found" }.groupValues[1].toDouble()
    }

    private fun String.longConst(name: String): Long = doubleConst(name).toLong()
}
