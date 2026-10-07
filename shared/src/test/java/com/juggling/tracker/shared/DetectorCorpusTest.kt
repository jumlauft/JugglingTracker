package com.juggling.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DET-11: replays every labelled recording in `connectiq/data` through the
 * Kotlin port and requires the exact count `simulation/test_detection.py` pins
 * for the Garmin detector, run by run. The expectations are read out of that
 * file rather than copied, so the two cannot drift apart.
 */
class DetectorCorpusTest {

    private data class Expected(val runId: String, val balls: Int, val actual: Int, val detected: Int)

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "connectiq/data").isDirectory) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun expectedRuns(): List<Expected> {
        val source = File(repoRoot(), "simulation/test_detection.py").readText()
        val block = source.substringAfter("EXPECTED_RUNS = [").substringBefore("\n]")
        val entry = Regex("""\("(\d{8}_\d{6})",\s*(\d+),\s*(\d+),\s*(\d+)\)""")
        return entry.findAll(block).map {
            val (id, balls, actual, detected) = it.destructured
            Expected(id, balls.toInt(), actual.toInt(), detected.toInt())
        }.toList()
    }

    private fun loadRun(runId: String): List<IntArray> {
        val file = File(repoRoot(), "connectiq/data/$runId.csv")
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("x,") }
            .map { line -> line.trim().split(",").take(3).map { it.trim().toInt() }.toIntArray() }
    }

    /**
     * Counts the way the reference simulation does: one pass over the whole
     * recording at 40 ms per sample with no idle split, then the trailing
     * pending burst flushed.
     */
    private fun detect(samples: List<IntArray>, balls: Int): Int {
        val d = JugglingDetector(balls)
        samples.forEachIndexed { i, s ->
            d.processSample(s[0], s[1], s[2], i * JugglingDetector.SAMPLE_PERIOD_MS)
        }
        return d.finishCurrentRun()
    }

    @Test
    fun `DET-11 every recording counts exactly what the Garmin reference counts`() {
        val runs = expectedRuns()
        assertTrue("expected the full corpus, parsed ${runs.size}", runs.size >= 100)

        val failures = runs.mapNotNull { e ->
            val got = detect(loadRun(e.runId), e.balls)
            if (got != e.detected) "${e.runId} (${e.balls}b): reference ${e.detected}, wear port $got" else null
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * DET-9: four 7-ball runs recorded back to back split into four runs on
     * the Juggle screen, as `test_back_to_back_runs_split_at_the_right_places`
     * pins for the reference. Samples arrive in one-second batches with the
     * auto-finish checked after each, as the watches feed them.
     */
    @Test
    fun `DET-9 back to back runs split where the reference splits them`() {
        val samples = loadRun("20261007_184543")
        val d = JugglingDetector(7)
        samples.forEachIndexed { i, s ->
            val t = i * JugglingDetector.SAMPLE_PERIOD_MS
            d.processSample(s[0], s[1], s[2], t)
            if ((i + 1) % JugglingDetector.SAMPLE_RATE == 0) d.checkAutoFinish(t)
        }
        d.finishCurrentRun()
        assertEquals(listOf(9, 8, 10, 13), d.runCatches())
    }

    @Test
    fun `DET-11 every recording on disk is covered`() {
        val onDisk = File(repoRoot(), "connectiq/data").listFiles { f -> f.name.endsWith(".csv") }!!
            .map { it.name.removeSuffix(".csv") }.toSet()
        assertEquals(onDisk, expectedRuns().map { it.runId }.toSet())
    }
}
