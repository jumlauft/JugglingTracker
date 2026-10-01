package com.juggling.tracker.shared

/** One raw accelerometer sample in milli-g, as a watch's sensor delivers it. */
data class Sample(val x: Int, val y: Int, val z: Int, val timeMs: Long)

/**
 * Synthetic accelerometer input, the same shapes `connectiq/test/DetectorTest.mc`
 * uses: a still watch with gravity on Z, and catch-like impulses against it.
 * Everything is in milli-g at 25 Hz, as the sensor delivers it. The Wear OS
 * app's tests have their own copy in `wearos/.../logic/Feeds.kt`, built on its
 * `AccelSample`.
 */
object Feeds {
    const val MILLI_G_PER_MS2 = 1000.0 / 9.80665
    const val BASELINE_Z = 1000
    const val PERIOD_MS = JugglingDetector.SAMPLE_PERIOD_MS

    fun baseline(startMs: Long, samples: Int): List<Sample> =
        (0 until samples).map { Sample(0, 0, BASELINE_Z, startMs + it * PERIOD_MS) }

    /** One impulse: three spiked samples, then twelve still ones to commit it. */
    fun burst(startMs: Long, amplitudeMs2: Double = 50.0): List<Sample> {
        val spikeZ = BASELINE_Z - (amplitudeMs2 * MILLI_G_PER_MS2).toInt()
        return (0 until 3).map { Sample(0, 0, spikeZ, startMs + it * PERIOD_MS) } +
            baseline(startMs + 3 * PERIOD_MS, 12)
    }

    /** `catches` watch-hand catches need 2 * catches - 1 bursts (DET-8). */
    fun catches(startMs: Long, catches: Int): List<Sample> {
        val out = mutableListOf<Sample>()
        var t = startMs
        repeat(catches * 2 - 1) {
            val b = burst(t)
            out += b
            t = b.last().timeMs + PERIOD_MS
        }
        return out
    }

    fun warmup(): List<Sample> = baseline(0, 55)

    fun next(samples: List<Sample>): Long = samples.last().timeMs + PERIOD_MS
}

/** Drives a detector the way the Garmin test helpers do, one sample at a time. */
fun JugglingDetector.feed(samples: List<Sample>): Long {
    for (s in samples) {
        processSample(s.x, s.y, s.z, s.timeMs)
        checkAutoFinish(s.timeMs)
    }
    return Feeds.next(samples)
}
