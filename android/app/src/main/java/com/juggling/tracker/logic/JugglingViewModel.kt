package com.juggling.tracker.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.normalizeRunDurations
import com.juggling.tracker.model.summarizeSession
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.data.WatchType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import android.os.Bundle
import java.util.Locale
import com.google.firebase.analytics.FirebaseAnalytics

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
    data class SyncCompleted(val count: Int, val ballCount: Int) : JugglingEvent()
    data class PhoneSessionSaved(val count: Int, val ballCount: Int) : JugglingEvent()
}

enum class GarminConnectionStatus {
    READY,
    RECEIVING,
    NOT_INITIALIZED,
    BLUETOOTH_DISABLED,
    NO_PAIRED_DEVICES,
    CONNECT_IQ_MISSING,
    WATCH_APP_MISSING,
    DISCONNECTED,
    SDK_ERROR
}

/** The Wear OS watch app's link to this phone, over the Data Layer. */
enum class WearConnectionStatus {
    CHECKING,
    READY,
    RECEIVING,
    NO_WATCH,
    WATCH_APP_MISSING,
    UNAVAILABLE;

    companion object {
        /**
         * [connectedWatches] is how many watches the phone is connected to, and
         * [watchesWithApp] how many of them are running a reachable copy of the
         * watch app; null when the Data Layer could not be asked at all.
         */
        fun classify(connectedWatches: Int?, watchesWithApp: Int): WearConnectionStatus = when {
            connectedWatches == null -> UNAVAILABLE
            watchesWithApp > 0 -> READY
            connectedWatches == 0 -> NO_WATCH
            else -> WATCH_APP_MISSING
        }
    }
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
    val statusMessage: String = "Ready to record with phone",
    val sensorError: String? = null,
)

data class RawRecordingUiState(
    val step: RawRecordingStep = RawRecordingStep.IDLE,
    val selectedBallCount: Int = 3,
    val sampleCount: Int = 0,
)

enum class RawRecordingStep {
    IDLE,
    SELECT_BALLS,
    RECORDING,
    ENTER_CATCHES
}

class JugglingViewModel(
    private val repository: SessionRepository? = null,
    private val recordingRepository: RecordingRepository? = null,
    private val analytics: FirebaseAnalytics? = null,
    private val settings: SettingsManager? = null,
    // The app's own inbox, shared with the Wear OS listener service and the
    // Garmin link; it must hold the same repositories as this view model.
    // Without one (tests) the view model gets an inbox of its own.
    inbox: WatchInbox? = null,
) : ViewModel() {
    private val inbox = inbox ?: WatchInbox(repository, recordingRepository, analytics)

    // Garmin Status
    var garminStatus by mutableStateOf(GarminConnectionStatus.NOT_INITIALIZED)
    var statusMessage by mutableStateOf("")

    // Wear OS status
    var wearStatus by mutableStateOf(WearConnectionStatus.CHECKING)

    // Settings
    val isAnalyticsEnabled get() = settings?.isAnalyticsEnabled ?: true
    val isVoiceEnabled get() = settings?.isVoiceEnabled ?: true
    val voiceInterval get() = settings?.voiceInterval ?: 10
    val watchType get() = settings?.watchType ?: WatchType.GARMIN

    fun setWatchType(type: WatchType) {
        settings?.updateWatchType(type)
    }

    val jugglerName get() = settings?.jugglerName ?: ""
    val watchHand get() = settings?.watchHand
    val firstThrowHand get() = settings?.firstThrowHand

    /** Remember who juggled, the watch wrist and the first-throw hand; the next export writes them. */
    fun setExportDetails(name: String, hand: String, firstThrow: String) {
        settings?.updateExportDetails(name.trim(), hand, firstThrow)
    }

    fun toggleAnalytics(enabled: Boolean) {
        settings?.updateAnalyticsEnabled(enabled)
        analytics?.setAnalyticsCollectionEnabled(enabled)
        // Note: Crashlytics collection is usually set via MainActivity as it requires 
        // a restart or is easier to manage there, but we can set it here too if possible.
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        } catch (e: Exception) {
            // Ignored in tests
        }
    }

    fun toggleVoice(enabled: Boolean) {
        settings?.updateVoiceEnabled(enabled)
    }

    fun setVoiceInterval(interval: Int) {
        settings?.updateVoiceInterval(interval)
    }

    // Event Flow
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    // Recording state
    var recordingCount by mutableIntStateOf(recordingRepository?.recordingCount() ?: 0)
        private set
    var recordings by mutableStateOf(recordingRepository?.listRecordings() ?: emptyList())
        private set

    var phoneSessionState by mutableStateOf(PhoneSessionUiState())
        private set

    var rawRecordingState by mutableStateOf(RawRecordingUiState())
        private set

    /**
     * True while the accelerometer has to keep streaming, so the UI can hold the
     * screen awake for exactly that long.
     *
     * MainActivity unregisters the sensor listener in onPause, and the display
     * timeout triggers onPause: nobody touches the screen while juggling, so any
     * session outlasting the timeout silently stopped counting, and the gap in
     * samples then auto-finished the run that was in progress. Deriving this
     * from the recording state rather than toggling a flag at the call sites
     * means it cannot drift out of step with the sensor.
     */
    val shouldKeepScreenOn: Boolean
        get() = phoneSessionState.isRecording ||
            rawRecordingState.step == RawRecordingStep.RECORDING

    private val rawAccelX = mutableListOf<Int>()
    private val rawAccelY = mutableListOf<Int>()
    private val rawAccelZ = mutableListOf<Int>()
    private var rawRecordingStartedAtMillis: Long? = null
    // Sensor timestamps of the first and last captured sample, so the saved
    // file carries the rate the accelerometer actually delivered rather than
    // the rate requested from it, which Android treats only as a hint.
    private var rawFirstSampleNanos: Long? = null
    private var rawLastSampleNanos: Long? = null

    private var phoneDetector: PhoneJugglingDetector? = null
    private var phoneSessionStartedAtMillis: Long? = null
    private var phoneSessionStartSampleMillis: Long? = null
    private var phoneLastProcessedSampleMillis: Long? = null

    // A wedged accelerometer keeps delivering events at full rate, but every
    // one carries the identical vector. The detector then sees zero linear
    // acceleration and counts nothing, which looked exactly like "the user has
    // not started juggling yet": the session sat on "Ready" forever with no
    // hint that anything was wrong. Consecutive byte-identical samples are the
    // signature -- a genuinely still phone still jitters in the low bits, so
    // this cannot fire just because the phone is resting.
    private var phoneFrozenSamples = 0
    private var phoneLastRawX = Double.NaN
    private var phoneLastRawY = Double.NaN
    private var phoneLastRawZ = Double.NaN

    val completedSessions = mutableStateListOf<SessionSummary>()

    // What the watches deliver reaches this screen through the inbox, which
    // may have stored it while no screen was open at all.
    private val inboxListener: (WatchInbox.Event) -> Unit = { event ->
        when (event) {
            is WatchInbox.Event.Receiving -> showReceiving(event.source)
            is WatchInbox.Event.SessionStored -> {
                showStoredSession(event.session)
                viewModelScope.launch {
                    _events.emit(JugglingEvent.SyncCompleted(event.session.runCount, event.session.ballCount))
                }
            }
            WatchInbox.Event.RecordingStored -> refreshRecordings()
        }
    }

    init {
        // Load any previously stored sessions on startup.
        repository?.getSessions()?.let { sessions ->
            completedSessions.clear()
            completedSessions.addAll(sessions)
        }
        this.inbox.addListener(inboxListener)
    }

    override fun onCleared() {
        inbox.removeListener(inboxListener)
        super.onCleared()
    }

    // The watch card shows "receiving" for a moment, then goes back to ready.
    private fun showReceiving(source: WatchInbox.Source) {
        when (source) {
            WatchInbox.Source.GARMIN -> garminStatus = GarminConnectionStatus.RECEIVING
            WatchInbox.Source.WEAR_OS -> wearStatus = WearConnectionStatus.RECEIVING
        }
        viewModelScope.launch {
            delay(RECEIVING_DISPLAY_MS)
            if (garminStatus == GarminConnectionStatus.RECEIVING) garminStatus = GarminConnectionStatus.READY
            if (wearStatus == WearConnectionStatus.RECEIVING) wearStatus = WearConnectionStatus.READY
        }
    }

    /**
     * Feeds a finished session straight into the inbox, as if [source] had
     * sent it. See [WatchInbox.importSession] for the payload.
     */
    fun importSessionFromWatch(payload: Map<String, Any>, source: WatchInbox.Source = WatchInbox.Source.GARMIN) {
        inbox.importSession(payload, source)
    }

    private fun storeFinishedSession(
        balls: Int,
        timestamp: Long,
        runs: List<Int>,
        durationSeconds: Long,
        runDurationsMillis: List<Long>,
        shapeConsistency: Int? = null,
    ) {
        val session = repository?.importSession(balls, timestamp, runs, durationSeconds, runDurationsMillis, shapeConsistency)
            ?: summarizeSession(timestamp, balls, runs, durationSeconds, runDurationsMillis, shapeConsistency)
        showStoredSession(session)
    }

    private fun showStoredSession(session: SessionSummary) {
        if (repository != null) {
            // Reload from the repository so the list matches what is stored.
            completedSessions.clear()
            completedSessions.addAll(repository.getSessions())
            return
        }
        // No repository (unit tests): keep the list the way the repository
        // would, one session per timestamp, a resend replacing the old copy.
        val existing = completedSessions.indexOfFirst { it.timestamp == session.timestamp }
        if (existing >= 0) {
            completedSessions[existing] = session
        } else {
            completedSessions.add(0, session)
        }
    }


    fun deleteSession(session: SessionSummary) {
        completedSessions.remove(session)
        repository?.deleteSession(session)
    }

    // Numbers and dates are written the same way whatever the phone's
    // language: a German phone would otherwise write 12,50 for the average,
    // and that comma splits the row into one column too many.
    fun getSessionsCsv(): String {
        val builder = StringBuilder()
        builder.append("Date,Ball Count,Run Count,Session Duration Seconds,Watch Hand Average,Watch Hand Best,Watch Hand Total,Run Durations Millis,Watch Hand Run History,Regularity Percent\n")

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        completedSessions.forEach { session ->
            val date = dateFormat.format(java.util.Date(session.timestamp))
            val average = String.format(Locale.US, "%.2f", session.avgThrows)
            val runHistory = session.runHistory.joinToString(";")
            val runDurationsMillis = session.runDurationsMillis.joinToString(";")
            // Empty when the session has no Regularity score.
            val regularity = session.shapeConsistency?.toString() ?: ""
            builder.append("$date,${session.ballCount},${session.runCount},${session.durationSeconds},$average,${session.bestRun},${session.totalThrows},\"$runDurationsMillis\",\"$runHistory\",$regularity\n")
        }

        return builder.toString()
    }

    // ── Recording support ──────────────────────────────────────────────

    // A watch's recorded run arrives as rec_start, rec_chunk parts and
    // rec_end, and the inbox reassembles it. These feed it directly, as if a
    // watch had sent them; see WatchInbox for the rules.

    fun startRecordingTransfer(payload: Map<String, Any>) = inbox.startRecordingTransfer(payload)

    fun appendRecordingChunk(payload: Map<String, Any>) = inbox.appendRecordingChunk(payload)

    /** Returns true when a complete run was written. */
    fun finishRecordingTransfer(payload: Map<String, Any>, source: WatchInbox.Source = WatchInbox.Source.GARMIN): Boolean =
        inbox.finishRecordingTransfer(payload, source)

    private fun refreshRecordings() {
        recordingCount = recordingRepository?.recordingCount() ?: 0
        recordings = recordingRepository?.listRecordings() ?: emptyList()
    }

    /**
     * Zip of all stored recordings for export, each header tagged with the
     * juggler, watch hand and first-throw hand from [setExportDetails].
     */
    fun writeRecordingsZip(out: java.io.OutputStream) {
        recordingRepository?.exportAllZip(
            out,
            juggler = jugglerName,
            hand = watchHand ?: RecordingRepository.HAND_LEFT,
            firstThrow = firstThrowHand ?: RecordingRepository.HAND_RIGHT,
        )
    }

    /** Delete all stored recordings. */
    fun clearRecordings() {
        recordingRepository?.clearAll()
        recordingCount = 0
        recordings = emptyList()
    }

    // ── Raw Data Recording support ─────────────────────────────────────

    fun startRawRecordingFlow() {
        rawRecordingState = RawRecordingUiState(step = RawRecordingStep.SELECT_BALLS)
    }

    fun confirmRawRecordingBalls(balls: Int) {
        rawRecordingState = rawRecordingState.copy(
            step = RawRecordingStep.RECORDING,
            selectedBallCount = balls
        )
        rawAccelX.clear()
        rawAccelY.clear()
        rawAccelZ.clear()
        rawFirstSampleNanos = null
        rawLastSampleNanos = null
        rawRecordingStartedAtMillis = System.currentTimeMillis()

        // Run the detector during capture so the UI can show a live count.
        phoneDetector = PhoneJugglingDetector(balls)
        phoneSessionStartSampleMillis = null
        phoneLastProcessedSampleMillis = null
    }

    fun stopRawRecording() {
        if (rawRecordingState.step != RawRecordingStep.RECORDING) return
        rawRecordingState = rawRecordingState.copy(step = RawRecordingStep.ENTER_CATCHES)
    }

    fun saveRawRecording(actualCatches: Int) {
        val timestamp = rawRecordingStartedAtMillis ?: System.currentTimeMillis()
        val detector = phoneDetector
        val detected = detector?.currentCount ?: 0
        
        recordingRepository?.saveRecording(
            balls = rawRecordingState.selectedBallCount,
            catches = actualCatches,
            detected = detected,
            sampleRate = measuredSampleRate(rawAccelX.size, rawFirstSampleNanos, rawLastSampleNanos),
            timestamp = timestamp / 1000L, // store as epoch seconds to match watch
            accelX = rawAccelX,
            accelY = rawAccelY,
            accelZ = rawAccelZ,
            source = RecordingRepository.SOURCE_PHONE,
        )
        
        refreshRecordings()
        cancelRawRecording()
    }

    fun cancelRawRecording() {
        rawRecordingState = RawRecordingUiState(step = RawRecordingStep.IDLE)
        rawAccelX.clear()
        rawAccelY.clear()
        rawAccelZ.clear()
        rawFirstSampleNanos = null
        rawLastSampleNanos = null
        phoneDetector = null
    }

    // ── Phone IMU session support ──────────────────────────────────────

    fun selectPhoneBallCount(ballCount: Int) {
        if (phoneSessionState.isRecording) return
        phoneSessionState = phoneSessionState.copy(selectedBallCount = ballCount.coerceIn(3, 9))
    }

    fun startPhoneSession(ballCount: Int, startedAtMillis: Long = System.currentTimeMillis()) {
        val sanitizedBallCount = ballCount.coerceIn(3, 9)

        phoneDetector = PhoneJugglingDetector(sanitizedBallCount)
        phoneSessionStartedAtMillis = startedAtMillis
        phoneSessionStartSampleMillis = null
        phoneLastProcessedSampleMillis = null
        resetFrozenSensorTracking()
        phoneSessionState = PhoneSessionUiState(
            selectedBallCount = sanitizedBallCount,
            isRecording = true,
            statusMessage = "Mount the phone to your wrist and start juggling",
        )

        analytics?.logEvent("start_phone_session", Bundle().apply {
            putInt("ball_count", sanitizedBallCount)
        })
    }

    fun processPhoneSample(ax: Double, ay: Double, az: Double, timestampNanos: Long) {
        // Handle raw recording capture
        if (rawRecordingState.step == RawRecordingStep.RECORDING) {
            // Convert m/s^2 to milli-g
            val mx = (ax * 1000.0 / 9.80665).toInt()
            val my = (ay * 1000.0 / 9.80665).toInt()
            val mz = (az * 1000.0 / 9.80665).toInt()
            rawAccelX.add(mx)
            rawAccelY.add(my)
            rawAccelZ.add(mz)
            if (rawFirstSampleNanos == null) rawFirstSampleNanos = timestampNanos
            rawLastSampleNanos = timestampNanos
            rawRecordingState = rawRecordingState.copy(sampleCount = rawAccelX.size)
        }

        val detector = phoneDetector ?: return
        if (!phoneSessionState.isRecording && rawRecordingState.step != RawRecordingStep.RECORDING) return

        val sampleMs = timestampNanos / 1_000_000L
        val lastProcessed = phoneLastProcessedSampleMillis
        if (lastProcessed != null && sampleMs - lastProcessed < PhoneJugglingDetector.SAMPLE_PERIOD_MS) {
            return
        }

        if (phoneSessionStartSampleMillis == null) {
            phoneSessionStartSampleMillis = sampleMs
        }
        phoneLastProcessedSampleMillis = sampleMs

        if (phoneSessionState.isRecording && isSensorStreamFrozen(ax, ay, az)) {
            markPhoneSensorUnavailable(
                "Accelerometer is not responding. Restart the phone and try again."
            )
            return
        }

        val oldCount = detector.currentCount
        detector.processSample(ax, ay, az, sampleMs)
        val newCount = detector.currentCount
        detector.checkAutoFinish(sampleMs)
        
        if (newCount > oldCount) {
            checkVoiceAnnouncement(newCount)
        }

        updatePhoneSessionStateFromDetector(sampleMs)
    }

    /**
     * True once the accelerometer has repeated the exact same vector for
     * [FROZEN_SENSOR_SECONDS] of throttled samples. Compared bit-for-bit on
     * purpose: real readings always jitter, so only a stuck sensor can hold
     * all three axes identical for seconds at a time.
     */
    private fun isSensorStreamFrozen(ax: Double, ay: Double, az: Double): Boolean {
        if (ax == phoneLastRawX && ay == phoneLastRawY && az == phoneLastRawZ) {
            phoneFrozenSamples += 1
        } else {
            phoneFrozenSamples = 0
            phoneLastRawX = ax
            phoneLastRawY = ay
            phoneLastRawZ = az
        }
        return phoneFrozenSamples >= FROZEN_SENSOR_SAMPLES
    }

    private fun checkVoiceAnnouncement(count: Int) {
        if (!isVoiceEnabled) return
        if (count > 0 && count % voiceInterval == 0) {
            viewModelScope.launch {
                _events.emit(JugglingEvent.Announcement(count.toString()))
            }
        }
    }

    fun stopPhoneSessionAndSave(stoppedAtMillis: Long = System.currentTimeMillis()): Boolean {
        val detector = phoneDetector ?: return false
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
            resetPhoneSession("No phone runs to save")
            phoneSessionState = phoneSessionState.copy(sensorError = "No phone runs to save")
            return false
        }

        val startedAtMillis = phoneSessionStartedAtMillis ?: stoppedAtMillis
        val durationSeconds = ((stoppedAtMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
        storeFinishedSession(
            balls = selectedBallCount,
            timestamp = startedAtMillis,
            runs = runs,
            durationSeconds = durationSeconds,
            runDurationsMillis = normalizeRunDurations(runs.size, detector.runDurationsMillis()),
        )
        resetPhoneSession("Phone session saved")

        viewModelScope.launch {
            _events.emit(JugglingEvent.PhoneSessionSaved(runs.size, selectedBallCount))
        }
        return true
    }

    fun cancelPhoneSession() {
        resetPhoneSession("Phone session cancelled")
    }

    fun markPhoneSensorUnavailable(message: String) {
        resetPhoneSession(message)
        phoneSessionState = phoneSessionState.copy(sensorError = message)
    }

    private fun updatePhoneSessionStateFromDetector(sampleMs: Long) {
        val detector = phoneDetector ?: return
        val startSampleMs = phoneSessionStartSampleMillis ?: sampleMs
        val elapsedSeconds = ((sampleMs - startSampleMs) / 1000L).coerceAtLeast(0L)
        val statusMessage = when {
            detector.currentCount > 0 || detector.isRunActive() -> "Run active"
            detector.sessionRuns() > 0 -> "Waiting for next run"
            else -> "Mount the phone to your wrist and start juggling"
        }

        phoneSessionState = phoneSessionState.copy(
            currentCount = detector.currentCount,
            previousCount = detector.previousCount,
            completedRuns = detector.runCatches(),
            runDurationsMillis = detector.runDurationsMillis(),
            sessionRunCount = detector.sessionRuns(),
            sessionAverage = detector.sessionAverage(),
            sessionMax = detector.sessionMax,
            elapsedSeconds = elapsedSeconds,
            statusMessage = statusMessage,
            sensorError = null,
        )
    }

    private fun resetPhoneSession(statusMessage: String = "Ready to record with phone") {
        val selectedBallCount = phoneSessionState.selectedBallCount
        phoneDetector = null
        phoneSessionStartedAtMillis = null
        phoneSessionStartSampleMillis = null
        phoneLastProcessedSampleMillis = null
        resetFrozenSensorTracking()
        phoneSessionState = PhoneSessionUiState(
            selectedBallCount = selectedBallCount,
            statusMessage = statusMessage,
        )
    }

    private fun resetFrozenSensorTracking() {
        phoneFrozenSamples = 0
        phoneLastRawX = Double.NaN
        phoneLastRawY = Double.NaN
        phoneLastRawZ = Double.NaN
    }

    companion object {
        /** How long the watch card shows "receiving" after a message. */
        const val RECEIVING_DISPLAY_MS = 1500L

        /** Seconds of an unchanging accelerometer before calling it broken. */
        const val FROZEN_SENSOR_SECONDS = 5L
        const val FROZEN_SENSOR_SAMPLES =
            (FROZEN_SENSOR_SECONDS * PhoneJugglingDetector.SAMPLE_RATE).toInt()

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
