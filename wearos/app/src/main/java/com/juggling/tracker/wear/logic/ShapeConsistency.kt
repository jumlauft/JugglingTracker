package com.juggling.tracker.wear.logic

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Shape consistency: how alike each hand cycle is to the one before it.
 * Wear OS port of `connectiq/source/ShapeConsistency.mc`, line for line.
 *
 * Every sample is highpassed per axis (the detector's 0.7 Hz filter, which also
 * removes gravity) and kept in a short history. Once a second during a run, the
 * last [WINDOW] samples are compared with the [WINDOW] samples one cycle
 * earlier, for every lag from [MIN_LAG] to [MAX_LAG] (0.36 s to 1.4 s, one hand
 * cycle at 3 to 7+ balls). The score is the best normalised 3-axis correlation
 * over those lags: 1.0 when the wrist repeats exactly the same motion every
 * cycle, near 0 when one cycle says nothing about the next.
 *
 * Only windows lying wholly between the run's first and last watch-hand catch
 * count, so the start-up throws and the drop at the end do not. A window is
 * held as pending until a later catch confirms it. The session score weights
 * each run by its windows and is reported as a whole percentage.
 *
 * Reference: `simulation/shape_consistency.py`. Keep the three in step.
 */
class ShapeConsistency {
    companion object {
        const val SAMPLE_PERIOD_MS = 40L
        const val WINDOW = 50
        const val MIN_LAG = 9
        const val MAX_LAG = 35
        const val HISTORY = WINDOW + MAX_LAG
        const val SCORE_EVERY = 25
        const val MAX_PENDING = 8

        // Same 0.7 Hz highpass as JugglingDetector.
        const val HP_B0 = 0.883002
        const val HP_B1 = -1.766004
        const val HP_B2 = 0.883002
        const val HP_A1 = -1.752268
        const val HP_A2 = 0.779739
    }

    private val histX = DoubleArray(HISTORY)
    private val histY = DoubleArray(HISTORY)
    private val histZ = DoubleArray(HISTORY)
    private var head = 0
    private var samples = 0

    // Highpass state per axis: x[n-1], x[n-2], y[n-1], y[n-2].
    private val hpX = DoubleArray(4)
    private val hpY = DoubleArray(4)
    private val hpZ = DoubleArray(4)

    private val pendingEnd = ArrayDeque<Long>()
    private val pendingScore = ArrayDeque<Double>()

    private var runSum = 0.0
    private var runCount = 0
    private val runSums = mutableListOf<Double>()
    private val runCounts = mutableListOf<Int>()

    private fun highpass(s: DoubleArray, v: Double): Double {
        val out = HP_B0 * v + HP_B1 * s[0] + HP_B2 * s[1] - HP_A1 * s[2] - HP_A2 * s[3]
        s[1] = s[0]
        s[0] = v
        s[3] = s[2]
        s[2] = out
        return out
    }

    /** Feed one raw sample (milli-g); the detector calls this before detection. */
    fun addSample(
        x: Int, y: Int, z: Int, nowMs: Long,
        runActive: Boolean, hasFirstCatch: Boolean, firstCatchMs: Long,
    ) {
        histX[head] = highpass(hpX, x.toDouble())
        histY[head] = highpass(hpY, y.toDouble())
        histZ[head] = highpass(hpZ, z.toDouble())
        head = (head + 1) % HISTORY
        samples += 1

        if (samples < HISTORY || samples % SCORE_EVERY != 0) return
        if (!runActive || !hasFirstCatch) return
        if (nowMs - (HISTORY - 1) * SAMPLE_PERIOD_MS < firstCatchMs) return
        if (pendingEnd.size >= MAX_PENDING) {
            pendingEnd.removeFirst()
            pendingScore.removeFirst()
        }
        pendingEnd.addLast(nowMs)
        pendingScore.addLast(score())
    }

    private fun score(): Double {
        // Newest first: ax[k] is the sample k steps back.
        val ax = DoubleArray(HISTORY)
        val ay = DoubleArray(HISTORY)
        val az = DoubleArray(HISTORY)
        val e = DoubleArray(HISTORY)
        for (k in 0 until HISTORY) {
            val i = (head - 1 - k + HISTORY) % HISTORY
            ax[k] = histX[i]
            ay[k] = histY[i]
            az[k] = histZ[i]
            e[k] = ax[k] * ax[k] + ay[k] * ay[k] + az[k] * az[k]
        }
        var e0 = 0.0
        for (k in 0 until WINDOW) e0 += e[k]
        if (e0 <= 0.0) return 0.0
        var el = 0.0
        for (k in MIN_LAG until MIN_LAG + WINDOW) el += e[k]
        var best = 0.0
        for (lag in MIN_LAG..MAX_LAG) {
            if (lag > MIN_LAG) el += e[lag + WINDOW - 1] - e[lag - 1]
            var cross = 0.0
            for (k in 0 until WINDOW) {
                val j = k + lag
                cross += ax[k] * ax[j] + ay[k] * ay[j] + az[k] * az[j]
            }
            val denom = e0 * el
            if (denom > 0.0) {
                val c = cross / sqrt(denom)
                if (c > best) best = c
            }
        }
        return if (best > 1.0) 1.0 else best
    }

    /** A watch-hand catch at [catchMs] confirms every window ending by then. */
    fun onCatch(catchMs: Long) {
        while (pendingEnd.isNotEmpty() && pendingEnd.first() <= catchMs) {
            runSum += pendingScore.removeFirst()
            runCount += 1
            pendingEnd.removeFirst()
        }
    }

    fun commitRun() {
        runSums.add(runSum)
        runCounts.add(runCount)
        clearRun()
    }

    fun clearRun() {
        runSum = 0.0
        runCount = 0
        pendingEnd.clear()
        pendingScore.clear()
    }

    fun discardLastRun() {
        if (runSums.isNotEmpty()) {
            runSums.removeAt(runSums.size - 1)
            runCounts.removeAt(runCounts.size - 1)
        }
    }

    /** Whole percentage over the session, or -1 before any window counts. */
    fun sessionPercent(): Int {
        val count = runCounts.sum()
        if (count == 0) return -1
        return floor(100.0 * runSums.sum() / count + 0.5).toInt()
    }
}
