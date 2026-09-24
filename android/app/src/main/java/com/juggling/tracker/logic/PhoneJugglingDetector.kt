package com.juggling.tracker.logic

import kotlin.math.sqrt

/**
 * Phone port of the watch catch detector in
 * `connectiq/source/JugglingDetector.mc`. It is deliberately the same
 * algorithm: gravity removal, a 25 Hz highpass, threshold-crossing candidates,
 * delayed burst clustering, and counting every other committed burst as a
 * catch by the counting hand. Samples arrive already in m/s² (the watch
 * converts from milli-g first), so `processSample` takes them directly.
 *
 * Keep this in sync with `JugglingDetector.mc` and `simulation/eval_new_watch.py`
 * whenever detector parameters or semantics change.
 */
class PhoneJugglingDetector(val ballCount: Int) {
    companion object {
        // The detector is designed for 25 Hz; the ViewModel throttles the
        // phone's higher-rate sensor down to this period before feeding it.
        const val SAMPLE_RATE = 25
        const val SAMPLE_PERIOD_MS = 1000L / SAMPLE_RATE
        const val WARMUP_SAMPLES = 25

        const val GRAVITY_ALPHA_IDLE = 0.95
        const val GRAVITY_ALPHA_ACTIVE = 0.99

        const val REFRACTORY_MS_3 = 80L
        const val REFRACTORY_MS_4 = 80L
        const val REFRACTORY_MS_5PLUS = 40L
        const val REFRACTORY_MS_7PLUS = 160L

        const val MERGE_WINDOW_MS_3 = 160L
        const val MERGE_WINDOW_MS_4 = 160L
        const val MERGE_WINDOW_MS_5PLUS = 160L
        const val MERGE_WINDOW_MS_7PLUS = 80L

        const val AUTO_FINISH_DELAY_MS = 2000L

        // 2nd-order Butterworth highpass, 0.7 Hz cutoff at fs = 25 Hz.
        const val HP_B0 = 0.883002
        const val HP_B1 = -1.766004
        const val HP_B2 = 0.883002
        const val HP_A1 = -1.752268
        const val HP_A2 = 0.779739

        const val HP_THRESHOLD_3 = 2.0
        const val HP_THRESHOLD_4 = 3.0
        const val HP_THRESHOLD_5PLUS = 3.0
        const val HP_THRESHOLD_7PLUS = 3.0
        const val HP_HYSTERESIS = 0.3

        const val MIN_RAW_MAG_3 = 7.0
        const val MIN_RAW_MAG_4 = 11.0
        const val MIN_RAW_MAG_5PLUS = 13.0
        const val MIN_RAW_MAG_7PLUS = 7.0
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
    private var lastActiveTimeMs = 0L

    private var sessionRuns = 0
    private var sessionTotal = 0
    private val runCatches = mutableListOf<Int>()
    private val runDurationsMillis = mutableListOf<Long>()

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

    private val hpThreshold: Double
    private val refractoryMs: Long
    private val minRawMag: Double
    private val mergeWindowMs: Long

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
            ballCount <= 6 -> {
                hpThreshold = HP_THRESHOLD_5PLUS
                refractoryMs = REFRACTORY_MS_5PLUS
                minRawMag = MIN_RAW_MAG_5PLUS
                mergeWindowMs = MERGE_WINDOW_MS_5PLUS
            }
            else -> {
                // 7+ has a distinctly faster cadence than 5-ball.
                hpThreshold = HP_THRESHOLD_7PLUS
                refractoryMs = REFRACTORY_MS_7PLUS
                minRawMag = MIN_RAW_MAG_7PLUS
                mergeWindowMs = MERGE_WINDOW_MS_7PLUS
            }
        }
    }

    fun runCatches(): List<Int> = runCatches.toList()

    fun runDurationsMillis(): List<Long> = runDurationsMillis.toList()

    fun sessionRuns(): Int = sessionRuns

    fun sessionAverage(): Double = if (sessionRuns == 0) 0.0 else sessionTotal.toDouble() / sessionRuns

    fun isRunActive(): Boolean = hasActiveRun()

    /** Feed one accelerometer sample in m/s² at time [nowMs]. */
    fun processSample(ax: Double, ay: Double, az: Double, nowMs: Long) {
        // Seed the gravity estimate from the first sample so it matches the
        // watch's actual orientation instead of an assumed "down".
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

        // Feed every sample into the filter, including warmup, so its state
        // tracks the signal from the start and detection begins cleanly.
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
                if (peakTimeMs - lastCandidateTimeMs > refractoryMs && peakRawMag > minRawMag) {
                    addCandidate(peakTimeMs, peakFiltered, nowMs)
                    lastCandidateTimeMs = peakTimeMs
                }
            }
        }

        flushPendingPeak(nowMs)
    }

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

    private fun hasActiveRun(): Boolean {
        return currentCount > 0 || hasPendingPeak || committedBurstCount > 0
    }

    private fun recordRun(catches: Int) {
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
