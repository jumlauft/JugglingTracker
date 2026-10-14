package com.juggling.tracker.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.shared.JugglingDetector
import com.juggling.tracker.shared.Juggler
import com.juggling.tracker.shared.SampleThrottle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RawRecordingUiState(
    val step: RawRecordingStep = RawRecordingStep.IDLE,
    val selectedBallCount: Int = 3,
    val sampleCount: Int = 0,
    /** What the detector counts during capture, shown live. */
    val liveCount: Int = 0,
)

enum class RawRecordingStep {
    IDLE,
    SELECT_BALLS,
    RECORDING,
    ENTER_CATCHES
}

/**
 * Raw accelerometer recordings for the detection corpus: the record flow
 * (balls, capture, typed catch count) and the stored recordings, whether
 * captured here or sent by a watch. Counterpart of the iPhone app's
 * RawRecordingModel.
 */
class RawRecordingModel(
    private val scope: CoroutineScope,
    private val repository: RecordingRepository? = null,
    /** Who juggles now; new recordings and the export are tagged with it. */
    private val currentJuggler: () -> Juggler? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Called with the new count whenever a catch is counted. */
    private val onCatch: (Int) -> Unit = {},
) {
    var state by mutableStateOf(RawRecordingUiState())
        private set

    // Recordings on disk, filled in by refresh.
    var recordingCount by mutableIntStateOf(0)
        private set
    var recordings by mutableStateOf(emptyList<RecordingRepository.RecordingSummary>())
        private set

    /** Stored recordings saved before the juggler was stored with each run. */
    val recordingsWithoutJuggler get() = recordings.count { it.juggler == null }

    // Keeps recording saves, clears and refreshes on the IO dispatcher in the
    // order they were asked for, so a refresh never shows a cleared run.
    private val files = Mutex()

    private val rawX = mutableListOf<Int>()
    private val rawY = mutableListOf<Int>()
    private val rawZ = mutableListOf<Int>()
    private var startedAtMillis: Long? = null
    // Sensor timestamps of the first and last captured sample, so the saved
    // file carries the rate the accelerometer actually delivered rather than
    // the rate requested from it, which Android treats only as a hint.
    private var firstSampleNanos: Long? = null
    private var lastSampleNanos: Long? = null

    // The detector runs during capture so the screen can show a live count.
    private var detector: JugglingDetector? = null
    private val throttle = SampleThrottle()

    init {
        refresh()
    }

    /** Re-read the recordings list and count on the IO dispatcher. */
    fun refresh() {
        val repo = repository ?: return
        scope.launch {
            val (count, list) = files.withLock {
                withContext(ioDispatcher) { repo.recordingCount() to repo.listRecordings() }
            }
            recordingCount = count
            recordings = list
        }
    }

    /**
     * Zip of all stored recordings for export. Each run keeps the juggler saved
     * with it; runs without one are tagged with the current juggler.
     */
    suspend fun writeZip(out: java.io.OutputStream) {
        val repo = repository ?: return
        val fallback = currentJuggler()
        withContext(ioDispatcher) { repo.exportAllZip(out, fallback = fallback) }
    }

    /** Delete all stored recordings. */
    fun clear() {
        recordingCount = 0
        recordings = emptyList()
        val repo = repository ?: return
        scope.launch {
            files.withLock { withContext(ioDispatcher) { repo.clearAll() } }
        }
    }

    // ── The record flow ────────────────────────────────────────────────

    fun startFlow() {
        state = RawRecordingUiState(step = RawRecordingStep.SELECT_BALLS)
    }

    fun confirmBalls(balls: Int) {
        state = RawRecordingUiState(step = RawRecordingStep.RECORDING, selectedBallCount = balls)
        clearBuffers()
        startedAtMillis = System.currentTimeMillis()
        detector = JugglingDetector(balls)
        throttle.reset()
    }

    fun stop() {
        if (state.step != RawRecordingStep.RECORDING) return
        state = state.copy(step = RawRecordingStep.ENTER_CATCHES)
    }

    /** Takes a batch of raw phone accelerometer events while capturing. */
    fun process(samples: List<PhoneAccelSample>) {
        if (samples.isEmpty() || state.step != RawRecordingStep.RECORDING) return
        for (s in samples) {
            // Convert m/s^2 to milli-g
            rawX.add((s.ax * 1000.0 / 9.80665).toInt())
            rawY.add((s.ay * 1000.0 / 9.80665).toInt())
            rawZ.add((s.az * 1000.0 / 9.80665).toInt())
        }
        if (firstSampleNanos == null) firstSampleNanos = samples.first().timestampNanos
        lastSampleNanos = samples.last().timestampNanos

        val detector = detector
        if (detector != null) {
            for (s in samples) {
                val sampleMs = s.timestampNanos / 1_000_000L
                if (!throttle.accept(sampleMs)) continue
                val oldCount = detector.currentCount
                detector.processSampleMs2(s.ax, s.ay, s.az, sampleMs)
                val newCount = detector.currentCount
                detector.checkAutoFinish(sampleMs)
                if (newCount > oldCount) onCatch(newCount)
            }
        }
        state = state.copy(sampleCount = rawX.size, liveCount = detector?.currentCount ?: 0)
    }

    fun save(actualCatches: Int) {
        val timestamp = startedAtMillis ?: System.currentTimeMillis()
        val detected = detector?.currentCount ?: 0
        val balls = state.selectedBallCount
        val juggler = currentJuggler()
        val sampleRate = measuredSampleRate(rawX.size, firstSampleNanos, lastSampleNanos)
        // Copied because cancel clears the buffers before the write on the
        // IO dispatcher gets to them.
        val xs = rawX.toList()
        val ys = rawY.toList()
        val zs = rawZ.toList()
        repository?.let { repo ->
            scope.launch {
                files.withLock {
                    withContext(ioDispatcher) {
                        repo.saveRecording(
                            balls = balls,
                            catches = actualCatches,
                            detected = detected,
                            sampleRate = sampleRate,
                            timestamp = timestamp / 1000L, // store as epoch seconds to match watch
                            accelX = xs,
                            accelY = ys,
                            accelZ = zs,
                            source = RecordingRepository.SOURCE_PHONE,
                            juggler = juggler,
                        )
                    }
                }
                refresh()
            }
        }
        cancel()
    }

    fun cancel() {
        state = RawRecordingUiState(step = RawRecordingStep.IDLE)
        clearBuffers()
        detector = null
    }

    private fun clearBuffers() {
        rawX.clear()
        rawY.clear()
        rawZ.clear()
        firstSampleNanos = null
        lastSampleNanos = null
    }

    companion object {
        /** The rate MainActivity asks the accelerometer for, in Hz. */
        const val PHONE_REQUESTED_SAMPLE_RATE = 200

        /**
         * The whole-Hz rate [count] samples arrived at between the sensor
         * timestamps of the first and last. Falls back to the requested rate
         * when there are too few samples, or no time between them, to measure.
         */
        fun measuredSampleRate(count: Int, firstNanos: Long?, lastNanos: Long?): Int {
            if (count < 2 || firstNanos == null || lastNanos == null || lastNanos <= firstNanos) {
                return PHONE_REQUESTED_SAMPLE_RATE
            }
            val rate = (count - 1) * 1_000_000_000.0 / (lastNanos - firstNanos)
            return Math.round(rate).toInt().coerceAtLeast(1)
        }
    }
}
