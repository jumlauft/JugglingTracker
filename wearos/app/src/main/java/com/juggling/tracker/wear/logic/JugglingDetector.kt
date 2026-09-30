package com.juggling.tracker.wear.logic

import kotlin.math.sqrt

/**
 * Wear OS port of `connectiq/source/JugglingDetector.mc`, line for line.
 *
 * Like the Garmin detector it takes raw accelerometer samples in **milli-g
 * including gravity** at 25 Hz and converts them to m/s² on entry, so recorded
 * runs from either watch replay through it unchanged. It removes a low-pass
 * gravity estimate, highpasses the magnitude, turns threshold crossings into
 * candidates, merges nearby candidates into bursts and counts every other
 * committed burst as a catch by the watch hand.
 *
 * Keep this in sync with `JugglingDetector.mc`,
 * `android/.../PhoneJugglingDetector.kt` and `simulation/eval_new_watch.py`.
 */
class JugglingDetector(val ballCount: Int) {
    companion object {
        const val SAMPLE_RATE = 25
        const val SAMPLE_PERIOD_MS = 1000L / SAMPLE_RATE
        const val MILLI_G_TO_MS2 = 9.80665 / 1000.0

        // A run of fewer than this many watch-hand catches is a false start.
        // Applied in recordRun(), so a manual stop and an idle timeout are
        // treated the same way (RUN-1).
        const val MIN_RUN_CATCHES = 3

        const val GRAVITY_ALPHA_IDLE = 0.95
        const val GRAVITY_ALPHA_ACTIVE = 0.99

        const val REFRACTORY_MS_3 = 80L
        const val REFRACTORY_MS_4 = 80L
        const val REFRACTORY_MS_5 = 40L
        const val REFRACTORY_MS_6 = 40L
        const val REFRACTORY_MS_7PLUS = 160L

        const val MERGE_WINDOW_MS_3 = 160L
        const val MERGE_WINDOW_MS_4 = 160L
        const val MERGE_WINDOW_MS_5 = 160L
        const val MERGE_WINDOW_MS_6 = 120L
        const val MERGE_WINDOW_MS_7PLUS = 80L

        const val AUTO_FINISH_DELAY_MS = 2000L

        // 2nd-order Butterworth highpass, 0.7 Hz at fs = 25 Hz.
        const val HP_B0 = 0.883002
        const val HP_B1 = -1.766004
        const val HP_B2 = 0.883002
        const val HP_A1 = -1.752268
        const val HP_A2 = 0.779739

        const val HP_THRESHOLD_3 = 2.0
        const val HP_THRESHOLD_4 = 3.0
        const val HP_THRESHOLD_5 = 3.0
        const val HP_THRESHOLD_6 = 5.0
        const val HP_THRESHOLD_7PLUS = 3.0
        const val HP_HYSTERESIS = 0.3

        const val MIN_RAW_MAG_3 = 7.0
        const val MIN_RAW_MAG_4 = 11.0
        const val MIN_RAW_MAG_5 = 13.0
        const val MIN_RAW_MAG_6 = 17.0
        const val MIN_RAW_MAG_7PLUS = 7.0

        // Samples ignored while the gravity estimate settles (DET-4).
        const val WARMUP_SAMPLES = 25
    }

    var currentCount: Int = 0
        private set
    var previousCount: Int = 0
        private set
    var sessionMax: Int = 0
        private set

    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 9.80665
    private var gravityInitialized = false
    private var samplesSeen = 0

    private var lastCandidateTimeMs = 0L
    // Last time a burst committed. Auto-finish keys on this, not on raw
    // motion, so wrist movement after a drop cannot hold a run open (DET-9).
    private var lastActiveTimeMs = 0L

    private var sessionRuns = 0
    private var sessionTotal = 0
    private var runCatches = mutableListOf<Int>()
    private var runDurationsMillis = mutableListOf<Long>()

    private var committedBurstCount = 0
    private var hasFirstCatchTime = false
    private var firstCatchTimeMs = 0L
    private var lastCatchTimeMs = 0L

    private var hpX1 = 0.0
    private var hpX2 = 0.0
    private var hpY1 = 0.0
    private var hpY2 = 0.0

    private var above = false
    private var peakTimeMs = 0L
    private var peakRawMag = 0.0
    private var peakFiltered = 0.0

    private var hasPendingPeak = false
    private var pendingPeakTimeMs = 0L
    private var pendingPeakScore = 0.0
    private var clusterLastCandidateTimeMs = 0L

    val hpThreshold: Double
    val refractoryMs: Long
    val minRawMag: Double
    val mergeWindowMs: Long

    init {
        when {
            ballCount <= 3 -> {
                hpThreshold = HP_THRESHOLD_3
                refractoryMs = REFRACTORY_MS_3
                minRawMag = MIN_RAW_MAG_3
                mergeWindowMs = MERGE_WINDOW_MS_3
            }
            ballCount == 4 -> {
                hpThreshold = HP_THRESHOLD_4
                refractoryMs = REFRACTORY_MS_4
                minRawMag = MIN_RAW_MAG_4
                mergeWindowMs = MERGE_WINDOW_MS_4
            }
            ballCount == 5 -> {
                hpThreshold = HP_THRESHOLD_5
                refractoryMs = REFRACTORY_MS_5
                minRawMag = MIN_RAW_MAG_5
                mergeWindowMs = MERGE_WINDOW_MS_5
            }
            ballCount == 6 -> {
                // Six throws land harder and closer together than five, so the
                // peak that marks a catch clears a higher bar.
                hpThreshold = HP_THRESHOLD_6
                refractoryMs = REFRACTORY_MS_6
                minRawMag = MIN_RAW_MAG_6
                mergeWindowMs = MERGE_WINDOW_MS_6
            }
            else -> {
                // 7+ is a distinctly faster cadence than 5.
                hpThreshold = HP_THRESHOLD_7PLUS
                refractoryMs = REFRACTORY_MS_7PLUS
                minRawMag = MIN_RAW_MAG_7PLUS
                mergeWindowMs = MERGE_WINDOW_MS_7PLUS
            }
        }
    }

    /** Watch-hand catch counts of every completed run this session, in order. */
    fun runCatches(): List<Int> = runCatches.toList()

    /** First-to-last watch-hand catch span of every completed run (DET-10). */
    fun runDurationsMillis(): List<Long> = runDurationsMillis.toList()

    fun sessionRuns(): Int = sessionRuns

    /** Mean over completed runs, 0.0 before any run completes (RUN-3). */
    fun sessionAverage(): Double = if (sessionRuns == 0) 0.0 else sessionTotal.toDouble() / sessionRuns

    fun isRunActive(): Boolean = hasActiveRun()

    /** Feed one raw accelerometer sample in milli-g at time [nowMs]. */
    fun processSample(gxMilliG: Int, gyMilliG: Int, gzMilliG: Int, nowMs: Long) {
        val ax = gxMilliG * MILLI_G_TO_MS2
        val ay = gyMilliG * MILLI_G_TO_MS2
        val az = gzMilliG * MILLI_G_TO_MS2

        // Seed gravity from the first real sample so it matches the watch's
        // actual orientation instead of an assumed "down" (DET-2).
        if (!gravityInitialized) {
            gravityX = ax
            gravityY = ay
            gravityZ = az
            gravityInitialized = true
        } else {
            val alpha = if (hasActiveRun()) GRAVITY_ALPHA_ACTIVE else GRAVITY_ALPHA_IDLE
            gravityX = alpha * gravityX + (1.0 - alpha) * ax
            gravityY = alpha * gravityY + (1.0 - alpha) * ay
            gravityZ = alpha * gravityZ + (1.0 - alpha) * az
        }

        val lx = ax - gravityX
        val ly = ay - gravityY
        val lz = az - gravityZ
        val mag = sqrt(lx * lx + ly * ly + lz * lz)

        samplesSeen += 1

        // Every sample goes through the filter, warmup included, so its state
        // tracks the signal and detection begins without a transient.
        val filtered = applyHighpass(mag)

        if (samplesSeen <= WARMUP_SAMPLES) return

        if (!above) {
            if (filtered > hpThreshold) {
                above = true
                peakTimeMs = nowMs
                peakFiltered = filtered
                peakRawMag = mag
            }
        } else {
            if (filtered > peakFiltered) {
                peakFiltered = filtered
                peakTimeMs = nowMs
            }
            if (mag > peakRawMag) {
                peakRawMag = mag
            }
            if (filtered < hpThreshold * HP_HYSTERESIS) {
                above = false
                // DET-5: refractory period elapsed and the raw gate cleared.
                if (peakTimeMs - lastCandidateTimeMs > refractoryMs && peakRawMag > minRawMag) {
                    addCandidate(peakTimeMs, peakFiltered, nowMs)
                    lastCandidateTimeMs = peakTimeMs
                }
            }
        }

        flushPendingPeak(nowMs)
    }

    /**
     * Finishes the run if it has been idle long enough. Returns the finished
     * run's watch-hand catch count, or 0 if nothing finished.
     */
    fun checkAutoFinish(nowMs: Long): Int {
        flushPendingPeak(nowMs)
        if (currentCount > 0 && lastActiveTimeMs > 0 && nowMs - lastActiveTimeMs > AUTO_FINISH_DELAY_MS) {
            val finished = currentCount
            recordRun(finished)
            currentCount = 0
            clearRunDetectionState()
            return finished
        }
        return 0
    }

    /**
     * Ends a run still in progress, committing any pending burst first, exactly
     * as an idle timeout would (RUN-6). Returns its watch-hand catch count.
     */
    fun finishCurrentRun(): Int {
        if (hasPendingPeak) {
            commitPendingPeak(pendingPeakTimeMs)
        }
        if (currentCount > 0) {
            val finished = currentCount
            recordRun(finished)
            currentCount = 0
            clearRunDetectionState()
            return finished
        }
        return 0
    }

    /**
     * Drops the run in progress, or else retroactively removes the last
     * completed run and recomputes the session from the runs that remain
     * (RUN-4). Returns false when there was nothing to discard (RUN-5).
     */
    fun discardLastRun(): Boolean {
        if (hasActiveRun()) {
            currentCount = 0
            clearRunDetectionState()
            return true
        }

        val n = runCatches.size
        if (n == 0) return false

        // Remove by index: removing by value would take an earlier run that
        // happens to have the same catch count.
        val removed = runCatches.removeAt(n - 1)
        runDurationsMillis.removeAt(n - 1)
        sessionRuns -= 1
        sessionTotal -= removed

        // Max and previous depend on which runs remain, so recompute them.
        sessionMax = runCatches.maxOrNull() ?: 0
        previousCount = runCatches.lastOrNull() ?: 0
        return true
    }

    private fun hasActiveRun(): Boolean =
        currentCount > 0 || hasPendingPeak || committedBurstCount > 0

    private fun recordRun(catches: Int) {
        if (catches < MIN_RUN_CATCHES) {
            // False start: leave previous and every session statistic exactly
            // as they were. The caller still resets currentCount.
            return
        }
        previousCount = catches
        sessionRuns += 1
        sessionTotal += catches
        if (catches > sessionMax) sessionMax = catches
        runCatches.add(catches)
        runDurationsMillis.add(currentRunDurationMillis())
    }

    private fun currentRunDurationMillis(): Long {
        if (!hasFirstCatchTime || lastCatchTimeMs < firstCatchTimeMs) return 0L
        return lastCatchTimeMs - firstCatchTimeMs
    }

    private fun clearRunDetectionState() {
        lastCandidateTimeMs = 0L
        lastActiveTimeMs = 0L
        above = false
        peakTimeMs = 0L
        peakRawMag = 0.0
        peakFiltered = 0.0
        hasPendingPeak = false
        pendingPeakTimeMs = 0L
        pendingPeakScore = 0.0
        clusterLastCandidateTimeMs = 0L
        committedBurstCount = 0
        hasFirstCatchTime = false
        firstCatchTimeMs = 0L
        lastCatchTimeMs = 0L
    }

    private fun commitPendingPeak(nowMs: Long) {
        if (!hasPendingPeak) return
        committedBurstCount += 1
        // DET-8: hands alternate, so only odd-numbered bursts are the watch hand.
        if (committedBurstCount % 2 == 1) {
            currentCount += 1
            if (!hasFirstCatchTime) {
                firstCatchTimeMs = pendingPeakTimeMs
                hasFirstCatchTime = true
            }
            lastCatchTimeMs = pendingPeakTimeMs
        }
        lastActiveTimeMs = nowMs
        hasPendingPeak = false
        pendingPeakTimeMs = 0L
        pendingPeakScore = 0.0
        clusterLastCandidateTimeMs = 0L
    }

    private fun addCandidate(candidateTimeMs: Long, score: Double, nowMs: Long) {
        if (!hasPendingPeak) {
            hasPendingPeak = true
            pendingPeakTimeMs = candidateTimeMs
            pendingPeakScore = score
            clusterLastCandidateTimeMs = candidateTimeMs
            return
        }

        // DET-7: candidates inside the merge window are lobes of one catch.
        if (candidateTimeMs - clusterLastCandidateTimeMs < mergeWindowMs) {
            if (score > pendingPeakScore) {
                pendingPeakTimeMs = candidateTimeMs
                pendingPeakScore = score
            }
            clusterLastCandidateTimeMs = candidateTimeMs
            return
        }

        commitPendingPeak(nowMs)
        hasPendingPeak = true
        pendingPeakTimeMs = candidateTimeMs
        pendingPeakScore = score
        clusterLastCandidateTimeMs = candidateTimeMs
    }

    private fun flushPendingPeak(nowMs: Long) {
        if (hasPendingPeak && !above && nowMs - clusterLastCandidateTimeMs >= mergeWindowMs) {
            commitPendingPeak(nowMs)
        }
    }

    private fun applyHighpass(x: Double): Double {
        val y = HP_B0 * x + HP_B1 * hpX1 + HP_B2 * hpX2 - HP_A1 * hpY1 - HP_A2 * hpY2
        hpX2 = hpX1
        hpX1 = x
        hpY2 = hpY1
        hpY1 = y
        return y
    }
}
