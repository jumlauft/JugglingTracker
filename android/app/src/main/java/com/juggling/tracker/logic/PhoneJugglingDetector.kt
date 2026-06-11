package com.juggling.tracker.logic

import kotlin.math.sqrt

class PhoneJugglingDetector(val ballCount: Int) {
    companion object {
        const val SAMPLE_RATE = 200
        const val SAMPLE_PERIOD_MS = 1000L / SAMPLE_RATE
        const val WARMUP_SAMPLES = 200

        const val GRAVITY_ALPHA_IDLE = 0.99
        const val GRAVITY_ALPHA_ACTIVE = 0.999

        const val REFRACTORY_MS_3 = 40L
        const val REFRACTORY_MS_4 = 80L
        const val REFRACTORY_MS_5PLUS = 40L

        const val MERGE_WINDOW_MS_3 = 60L
        const val MERGE_WINDOW_MS_4 = 60L
        const val MERGE_WINDOW_MS_5PLUS = 60L

        const val AUTO_FINISH_DELAY_MS = 2000L

        const val HP_B0 = 0.96716
        const val HP_B1 = -1.93432
        const val HP_B2 = 0.96716
        const val HP_A1 = -1.93365
        const val HP_A2 = 0.93547

        const val HP_THRESHOLD_3 = 2.5
        const val HP_THRESHOLD_4 = 2.5
        const val HP_THRESHOLD_5PLUS = 2.5
        const val HP_HYSTERESIS = 0.3

        const val MIN_RAW_MAG_3 = 7.0
        const val MIN_RAW_MAG_4 = 5.0
        const val MIN_RAW_MAG_5PLUS = 11.0
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
    private var peakSamples = 0

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
            else -> {
                hpThreshold = HP_THRESHOLD_5PLUS
                refractoryMs = REFRACTORY_MS_5PLUS
                minRawMag = MIN_RAW_MAG_5PLUS
                mergeWindowMs = MERGE_WINDOW_MS_5PLUS
            }
        }
    }

    fun runCatches(): List<Int> = runCatches.toList()

    fun runDurationsMillis(): List<Long> = runDurationsMillis.toList()

    fun sessionRuns(): Int = sessionRuns

    fun sessionAverage(): Double = if (sessionRuns == 0) 0.0 else sessionTotal.toDouble() / sessionRuns

    fun isRunActive(): Boolean = hasActiveRun()

    fun processSample(ax: Double, ay: Double, az: Double, nowMs: Long) {
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

        // Directional Acceleration (Project onto Gravity Vector)
        // Gravity vector points DOWN. The "shock" of a catch points UP.
        // We calculate the component of linear acceleration that is directly opposing gravity.
        // This isolates the vertical impact and ignores horizontal hand movement noise.
        val gMagSq = gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ
        val gMag = sqrt(gMagSq)
        val upwardAccel = if (gMag > 1.0) {
            -(lx * gravityX + ly * gravityY + lz * gravityZ) / gMag
        } else {
            // Fallback to magnitude if gravity estimation is unstable
            sqrt(lx * lx + ly * ly + lz * lz)
        }

        samplesSeen += 1
        // We only care about positive (upward) shocks for catch detection
        val mag = upwardAccel.coerceAtLeast(0.0)
        val filtered = applyHighpass(mag)

        if (samplesSeen <= WARMUP_SAMPLES) return

        if (!above) {
            if (filtered > hpThreshold) {
                above = true
                peakTimeMs = nowMs
                peakFiltered = filtered
                peakRawMag = mag
                peakSamples = 1
            }
        } else {
            peakSamples += 1
            if (filtered > peakFiltered) {
                peakFiltered = filtered
                peakTimeMs = nowMs
            }
            if (mag > peakRawMag) {
                peakRawMag = mag
            }
            if (filtered < hpThreshold * HP_HYSTERESIS) {
                above = false
                // A catch is a physical impact, not a spike.
                // At 200Hz, we expect it to last at least 3 samples (~15ms).
                val isNotASpike = peakSamples >= 3

                if (peakTimeMs - lastCandidateTimeMs > refractoryMs &&
                    peakRawMag > minRawMag &&
                    isNotASpike) {
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
