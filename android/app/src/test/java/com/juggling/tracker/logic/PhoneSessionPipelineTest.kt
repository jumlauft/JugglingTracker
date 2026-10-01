package com.juggling.tracker.logic

import com.juggling.tracker.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Drives a real recorded run through the whole phone session path --
 * `startPhoneSession` then `processPhoneSample(s)` -- the way
 * PhoneAccelerometerSource feeds it on the device.
 *
 * PhoneDetectorCorpusTest proves the detector counts real juggling correctly
 * when fed directly. This covers everything between the sensor callback and the
 * detector: the 25 Hz throttle, the timestamp handling, and the UI state the
 * screen reads. A phone session that stays on "Ready" forever fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSessionPipelineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private companion object {
        const val MILLI_G_TO_MS2 = 9.80665 / 1000.0

        /** What PhoneAccelerometerSource asks the sensor for: SAMPLE_PERIOD_US = 5_000. */
        const val PHONE_SAMPLE_PERIOD_MS = 5L

        const val RUN_ID = "20260603_201719"
        const val BALLS = 3
        const val EXPECTED_CATCHES = 20
    }

    private fun dataDir(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "connectiq/data").isDirectory) {
            dir = dir.parentFile
        }
        return File(dir, "connectiq/data")
    }

    private fun loadSamples(runId: String): List<Triple<Double, Double, Double>> {
        val file = File(dataDir(), "$runId.csv")
        check(file.isFile) { "recording not found: $file" }
        val out = mutableListOf<Triple<Double, Double, Double>>()
        file.forEachLine { line ->
            if (line.startsWith("#") || line.startsWith("x,")) return@forEachLine
            val parts = line.trim().split(",")
            if (parts.size < 3) return@forEachLine
            out.add(
                Triple(
                    parts[0].toDouble() * MILLI_G_TO_MS2,
                    parts[1].toDouble() * MILLI_G_TO_MS2,
                    parts[2].toDouble() * MILLI_G_TO_MS2,
                )
            )
        }
        return out
    }

    /**
     * The corpus is 25 Hz; a phone delivers ~200 Hz. Hold each corpus sample for
     * the 8 phone samples that cover its 40 ms, so the ViewModel sees the same
     * arrival pattern it gets on the device and its throttle has to pick one
     * sample per 40 ms back out.
     */
    @Test
    fun `phone session counts catches from a real run delivered at phone sample rate`() = runTest {
        val viewModel = JugglingViewModel()
        val samples = loadSamples(RUN_ID)

        viewModel.startPhoneSession(BALLS, startedAtMillis = 0L)

        // Android hands out SensorEvent.timestamp in nanoseconds since boot.
        var tNanos = 1_000_000_000L
        val perCorpusSample = PhoneJugglingDetector.SAMPLE_PERIOD_MS / PHONE_SAMPLE_PERIOD_MS
        for ((ax, ay, az) in samples) {
            for (i in 0 until perCorpusSample) {
                viewModel.processPhoneSample(ax, ay, az, tNanos)
                tNanos += PHONE_SAMPLE_PERIOD_MS * 1_000_000L
            }
        }

        val state = viewModel.phoneSessionState
        val counted = state.completedRuns.sum() + state.currentCount

        assertTrue(
            "phone session never left the idle status: " +
                "status=\"${state.statusMessage}\" currentCount=${state.currentCount} " +
                "runs=${state.completedRuns}",
            counted > 0,
        )
        assertEquals(
            "phone session counted $counted catches on $RUN_ID, expected $EXPECTED_CATCHES",
            EXPECTED_CATCHES,
            counted,
        )
    }

    /**
     * Real phones stamp events a few ms either side of 5 ms, and
     * PhoneAccelerometerSource hands them over in ~100 ms batches. The 25 Hz
     * throttle has to stay on its 40 ms grid through the jitter: counting
     * 40 ms from the last kept sample drifted to ~42 ms (23.8 Hz) and skipped
     * about one corpus sample in twenty.
     */
    @Test
    fun `phone session counts the same with jittered timestamps in batches`() = runTest {
        val viewModel = JugglingViewModel()
        val samples = loadSamples(RUN_ID)
        viewModel.startPhoneSession(BALLS, startedAtMillis = 0L)

        val gapsMs = longArrayOf(4, 6, 5, 7, 3, 6, 4)
        val startMs = 1_000L
        val endMs = startMs + samples.size * PhoneJugglingDetector.SAMPLE_PERIOD_MS
        val batch = mutableListOf<PhoneAccelSample>()
        var tMs = startMs
        var i = 0
        while (tMs < endMs) {
            val (ax, ay, az) = samples[((tMs - startMs) / PhoneJugglingDetector.SAMPLE_PERIOD_MS).toInt()]
            batch += PhoneAccelSample(ax, ay, az, tMs * 1_000_000L)
            if (batch.size == 20) {
                viewModel.processPhoneSamples(batch.toList())
                batch.clear()
            }
            tMs += gapsMs[i++ % gapsMs.size]
        }
        viewModel.processPhoneSamples(batch.toList())

        val state = viewModel.phoneSessionState
        val counted = state.completedRuns.sum() + state.currentCount
        assertEquals(
            "batched, jittered phone session counted $counted catches on $RUN_ID",
            EXPECTED_CATCHES,
            counted,
        )
    }
}
