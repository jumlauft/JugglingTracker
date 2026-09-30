package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SHAPE-1..4 for the Wear OS port. The labelled runs in
 * `simulation/regularity_data` go through the real detector, and each must
 * score exactly what `simulation/test_detection.py` pins in `EXPECTED_SHAPE`
 * for the reference, read out of that file so the two cannot drift apart.
 */
class ShapeConsistencyTest {

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "simulation/regularity_data").isDirectory) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun expectedShape(): List<Triple<String, String, Int>> {
        val source = File(repoRoot(), "simulation/test_detection.py").readText()
        val block = source.substringAfter("EXPECTED_SHAPE = [").substringBefore("\n]")
        val entry = Regex("""\("(\d{8}_\d{6})",\s*"([a-z-]+)",\s*(\d+)\)""")
        return entry.findAll(block).map {
            val (id, label, score) = it.destructured
            Triple(id, label, score.toInt())
        }.toList()
    }

    private fun replay(runId: String): Int {
        val lines = File(repoRoot(), "simulation/regularity_data/$runId.csv").readLines()
        val balls = Regex("""balls=(\d+)""").find(lines.first())!!.groupValues[1].toInt()
        val detector = JugglingDetector(balls)
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("x,") }
            .forEachIndexed { i, line ->
                val (x, y, z) = line.trim().split(",").take(3).map { it.trim().toInt() }
                detector.processSample(x, y, z, i * JugglingDetector.SAMPLE_PERIOD_MS)
            }
        detector.finishCurrentRun()
        return detector.shapeConsistencyPercent()
    }

    @Test
    fun `SHAPE-4 labelled runs score exactly what the reference scores`() {
        val expected = expectedShape()
        assertEquals(4, expected.size)
        val failures = expected.mapNotNull { (id, label, score) ->
            val got = replay(id)
            if (got != score) "$id ($label): reference $score, wear port $got" else null
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun periodic(i: Int): IntArray {
        val phase = 2 * PI * i / 20
        return intArrayOf(
            (500 * sin(phase)).roundToInt(),
            (300 * cos(phase)).roundToInt(),
            1000 + (200 * sin(2 * phase)).roundToInt(),
        )
    }

    private var lcg = 12345L
    private fun unrelated(): IntArray {
        val out = IntArray(3) {
            lcg = (lcg * 75 + 74) % 65537
            (lcg % 1001 - 500).toInt()
        }
        out[2] += 1000
        return out
    }

    private fun fed(sample: (Int) -> IntArray, firstCatchMs: Long = 0L): ShapeConsistency {
        val tracker = ShapeConsistency()
        for (i in 0 until 400) {
            val s = sample(i)
            tracker.addSample(s[0], s[1], s[2], i * 40L, true, true, firstCatchMs)
        }
        return tracker
    }

    @Test
    fun `SHAPE-1 periodic motion scores full and unrelated motion scores low`() {
        val periodic = fed(::periodic).apply { onCatch(400 * 40L); commitRun() }
        assertTrue("periodic scored ${periodic.sessionPercent()}", periodic.sessionPercent() >= 99)
        val noise = fed({ unrelated() }).apply { onCatch(400 * 40L); commitRun() }
        assertTrue("unrelated scored ${noise.sessionPercent()}", noise.sessionPercent() in 0..49)
    }

    @Test
    fun `SHAPE-2 only windows confirmed inside the run count`() {
        assertEquals(-1, fed(::periodic).apply { commitRun() }.sessionPercent())
        val early = fed(::periodic, firstCatchMs = (400 - ShapeConsistency.HISTORY + 2) * 40L)
        early.onCatch(400 * 40L)
        early.commitRun()
        assertEquals(-1, early.sessionPercent())
    }

    @Test
    fun `SHAPE-3 discarding the last run removes its score`() {
        val tracker = ShapeConsistency()
        for (i in 0 until 800) {
            val s = if (i < 400) periodic(i) else unrelated()
            tracker.addSample(s[0], s[1], s[2], i * 40L, true, true, if (i < 400) 0L else 400 * 40L)
            if (i == 399) {
                tracker.onCatch(i * 40L)
                tracker.commitRun()
            }
        }
        tracker.onCatch(800 * 40L)
        tracker.commitRun()
        val mixed = tracker.sessionPercent()
        tracker.discardLastRun()
        assertTrue(tracker.sessionPercent() >= 99)
        assertTrue(mixed < 99)
    }

    @Test
    fun `SHAPE-4 the screen shows a percentage or a dash`() {
        assertEquals("-", Format.percentOrDash(-1))
        assertEquals("83%", Format.percentOrDash(83))
    }
}
