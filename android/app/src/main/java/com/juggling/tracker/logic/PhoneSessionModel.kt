package com.juggling.tracker.logic

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.shared.JugglingDetector
import com.juggling.tracker.shared.SampleThrottle
import com.juggling.tracker.shared.normalizeRunDurations

/**
 * What the phone session screen says about the session, as a key the screen
 * turns into text (ui/StatusTexts.kt), so the wording lives in strings.xml.
 * The iPhone app has the same cases.
 */
enum class PhoneSessionStatus {
    READY,
    MOUNT_PHONE,
    RUN_ACTIVE,
    WAITING_FOR_RUN,
    SAVED,
    CANCELLED,
    NO_RUNS,
    SENSOR_UNAVAILABLE,
    SENSOR_FROZEN,
}

data class PhoneSessionUiState(
    val selectedBallCount: Int = 3,
    val isRecording: Boolean = false,
    val currentCount: Int = 0,
    val previousCount: Int = 0,
    val completedRuns: List<Int> = emptyList(),
    val runDurationsMillis: List<Long> = emptyList(),
    val sessionRunCount: Int = 0,
    val sessionAverage: Double = 0.0,
    val sessionMax: Int = 0,
    val elapsedSeconds: Long = 0L,
    val status: PhoneSessionStatus = PhoneSessionStatus.READY,
    /** Why the last session ended without being saved, shown in red. */
    val sensorError: PhoneSessionStatus? = null,
)

/**
 * A juggling session counted with the phone's own accelerometer, strapped to
 * the wrist: the detector, the frozen-sensor check and the session's stats.
 * Counterpart of the iPhone app's PhoneSessionModel.
 */
class PhoneSessionModel(
    private val history: HistoryModel,
    private val analytics: FirebaseAnalytics? = null,
    /** Called with the new count whenever a catch is counted. */
    private val onCatch: (Int) -> Unit = {},
) {
    var state by mutableStateOf(PhoneSessionUiState())
        private set

    private var detector: JugglingDetector? = null
    private var startedAtMillis: Long? = null
    private var startSampleMillis: Long? = null
    private val throttle = SampleThrottle()

    // A wedged accelerometer keeps delivering events at full rate, but every
    // one carries the identical vector. The detector then sees zero linear
    // acceleration and counts nothing, which looked exactly like "the user has
    // not started juggling yet": the session sat on "Ready" forever with no
    // hint that anything was wrong. Consecutive byte-identical samples are the
    // signature -- a genuinely still phone still jitters in the low bits, so
    // this cannot fire just because the phone is resting.
    private var frozenSamples = 0
    private var lastRawX = Double.NaN
    private var lastRawY = Double.NaN
    private var lastRawZ = Double.NaN

    fun selectBallCount(ballCount: Int) {
        if (state.isRecording) return
        state = state.copy(selectedBallCount = ballCount.coerceIn(3, 9))
    }

    fun start(ballCount: Int, startedAtMillis: Long = System.currentTimeMillis()) {
        val sanitizedBallCount = ballCount.coerceIn(3, 9)

        detector = JugglingDetector(sanitizedBallCount)
        this.startedAtMillis = startedAtMillis
        startSampleMillis = null
        throttle.reset()
        resetFrozenSensorTracking()
        state = PhoneSessionUiState(
            selectedBallCount = sanitizedBallCount,
            isRecording = true,
            status = PhoneSessionStatus.MOUNT_PHONE,
        )

        analytics?.logEvent("start_phone_session", Bundle().apply {
            putInt("ball_count", sanitizedBallCount)
        })
    }

    /**
     * Takes a batch of raw phone accelerometer events (PhoneAccelerometerSource
     * delivers about ten a second) and writes the Compose state once per batch
     * rather than once per 200 Hz sample.
     */
    fun process(samples: List<PhoneAccelSample>) {
        var lastProcessedMs: Long? = null
        for (s in samples) {
            val detector = detector ?: break
            if (!state.isRecording) break

            val sampleMs = s.timestampNanos / 1_000_000L
            if (!throttle.accept(sampleMs)) continue

            if (startSampleMillis == null) {
                startSampleMillis = sampleMs
            }

            if (isSensorStreamFrozen(s.ax, s.ay, s.az)) {
                markSensorUnavailable(PhoneSessionStatus.SENSOR_FROZEN)
                return
            }

            val oldCount = detector.currentCount
            detector.processSampleMs2(s.ax, s.ay, s.az, sampleMs)
            val newCount = detector.currentCount
            detector.checkAutoFinish(sampleMs)

            if (newCount > oldCount) {
                onCatch(newCount)
            }
            lastProcessedMs = sampleMs
        }

        lastProcessedMs?.let { updateStateFromDetector(it) }
    }

    /**
     * True once the accelerometer has repeated the exact same vector for
     * [FROZEN_SENSOR_SECONDS] of throttled samples. Compared bit-for-bit on
     * purpose: real readings always jitter, so only a stuck sensor can hold
     * all three axes identical for seconds at a time.
     */
    private fun isSensorStreamFrozen(ax: Double, ay: Double, az: Double): Boolean {
        if (ax == lastRawX && ay == lastRawY && az == lastRawZ) {
            frozenSamples += 1
        } else {
            frozenSamples = 0
            lastRawX = ax
            lastRawY = ay
            lastRawZ = az
        }
        return frozenSamples >= FROZEN_SENSOR_SAMPLES
    }

    /**
     * Ends the session and stores it in the history. Returns the number of
     * runs saved, or null when there was no session or no run to save.
     */
    fun stopAndSave(stoppedAtMillis: Long = System.currentTimeMillis()): Int? {
        val detector = detector ?: return null
        detector.finishCurrentRun()

        val runs = detector.runCatches()
        val selectedBallCount = detector.ballCount.coerceIn(3, 9)

        analytics?.logEvent("stop_phone_session", Bundle().apply {
            putInt("ball_count", selectedBallCount)
            putInt("run_count", runs.size)
            putInt("total_throws", runs.sum())
            putBoolean("success", true)
        })

        if (runs.isEmpty()) {
            markSensorUnavailable(PhoneSessionStatus.NO_RUNS)
            return null
        }

        val startedAtMillis = startedAtMillis ?: stoppedAtMillis
        val durationSeconds = ((stoppedAtMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
        history.store(
            balls = selectedBallCount,
            timestamp = startedAtMillis,
            runs = runs,
            durationSeconds = durationSeconds,
            runDurationsMillis = normalizeRunDurations(runs.size, detector.runDurationsMillis()),
        )
        reset(PhoneSessionStatus.SAVED)
        return runs.size
    }

    fun cancel() {
        reset(PhoneSessionStatus.CANCELLED)
    }

    /** Ends the session without saving it and shows [reason] in red. */
    fun markSensorUnavailable(reason: PhoneSessionStatus = PhoneSessionStatus.SENSOR_UNAVAILABLE) {
        reset(reason)
        state = state.copy(sensorError = reason)
    }

    private fun updateStateFromDetector(sampleMs: Long) {
        val detector = detector ?: return
        val startSampleMs = startSampleMillis ?: sampleMs
        val elapsedSeconds = ((sampleMs - startSampleMs) / 1000L).coerceAtLeast(0L)
        val status = when {
            detector.currentCount > 0 || detector.isRunActive() -> PhoneSessionStatus.RUN_ACTIVE
            detector.sessionRuns() > 0 -> PhoneSessionStatus.WAITING_FOR_RUN
            else -> PhoneSessionStatus.MOUNT_PHONE
        }

        state = state.copy(
            currentCount = detector.currentCount,
            previousCount = detector.previousCount,
            completedRuns = detector.runCatches(),
            runDurationsMillis = detector.runDurationsMillis(),
            sessionRunCount = detector.sessionRuns(),
            sessionAverage = detector.sessionAverage(),
            sessionMax = detector.sessionMax,
            elapsedSeconds = elapsedSeconds,
            status = status,
            sensorError = null,
        )
    }

    private fun reset(status: PhoneSessionStatus) {
        detector = null
        startedAtMillis = null
        startSampleMillis = null
        throttle.reset()
        resetFrozenSensorTracking()
        state = PhoneSessionUiState(
            selectedBallCount = state.selectedBallCount,
            status = status,
        )
    }

    private fun resetFrozenSensorTracking() {
        frozenSamples = 0
        lastRawX = Double.NaN
        lastRawY = Double.NaN
        lastRawZ = Double.NaN
    }

    companion object {
        /** Seconds of an unchanging accelerometer before calling it broken. */
        const val FROZEN_SENSOR_SECONDS = 5L
        const val FROZEN_SENSOR_SAMPLES =
            (FROZEN_SENSOR_SECONDS * JugglingDetector.SAMPLE_RATE).toInt()
    }
}
