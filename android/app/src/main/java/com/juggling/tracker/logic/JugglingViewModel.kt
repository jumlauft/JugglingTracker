package com.juggling.tracker.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.RecordingRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
    data class SyncCompleted(val count: Int, val ballCount: Int) : JugglingEvent()
    data class PhoneSessionSaved(val count: Int, val ballCount: Int) : JugglingEvent()
    object SyncStarted : JugglingEvent()
}

enum class GarminConnectionStatus {
    READY,
    RECEIVING,
    NOT_INITIALIZED,
    BLUETOOTH_DISABLED,
    NO_PAIRED_DEVICES,
    SDK_ERROR
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

class JugglingViewModel(
    private val repository: SessionRepository? = null,
    private val recordingRepository: RecordingRepository? = null,
    private val analytics: FirebaseAnalytics? = null,
) : ViewModel() {
    // Garmin Status
    var garminStatus by mutableStateOf(GarminConnectionStatus.NOT_INITIALIZED)
    var statusMessage by mutableStateOf("")

    // Settings
    var isVoiceEnabled by mutableStateOf(value = true)
    var voiceInterval by mutableIntStateOf(10)

    // Event Flow
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    // Recording state
    var recordingCount by mutableIntStateOf(recordingRepository?.recordingCount() ?: 0)
        private set

    var phoneSessionState by mutableStateOf(PhoneSessionUiState())
        private set

    private var phoneDetector: PhoneJugglingDetector? = null
    private var phoneSessionStartedAtMillis: Long? = null
    private var phoneSessionStartSampleMillis: Long? = null
    private var phoneLastProcessedSampleMillis: Long? = null

    val completedSessions = mutableStateListOf<SessionSummary>()

    init {
        // Load any previously stored sessions on startup.
        repository?.getSessions()?.let { sessions ->
            completedSessions.clear()
            completedSessions.addAll(sessions)
        }
    }

    // Import a single finished session transferred from the Garmin watch.
    // Payload shape: { type: "session", countMode: "watch_hand", balls: Int,
    // timestamp: Long (epoch s), durationSeconds: Long,
    // runDurationsMillis: List<Number>, runs: List<Number> }.
    // Runs are watch-hand catch counts; the phone only listens and records what it receives.
    fun importSessionFromWatch(payload: Map<String, Any>) {
        val balls = (payload["balls"] as? Number)?.toInt() ?: return
        // The watch sends epoch seconds; convert to milliseconds for Java Date APIs.
        val timestamp = ((payload["timestamp"] as? Number)?.toLong() ?: return) * 1000L
        val durationSeconds = ((payload["durationSeconds"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
        @Suppress("UNCHECKED_CAST")
        val runsRaw = payload["runs"] as? List<Any> ?: return
        val runs = runsRaw.mapNotNull { (it as? Number)?.toInt() }
        if (runs.isEmpty()) return

        analytics?.logEvent("import_session", Bundle().apply {
            putInt("ball_count", balls)
            putInt("run_count", runs.size)
            putInt("total_throws", runs.sum())
            putString("source", "garmin_watch")
        })

        val runDurationsMillis = normalizeRunDurations(runs.size, parseLongList(payload["runDurationsMillis"]))

        storeFinishedSession(balls, timestamp, runs, durationSeconds, runDurationsMillis)

        viewModelScope.launch {
            _events.emit(JugglingEvent.SyncCompleted(runs.size, balls))
        }
    }

    private fun storeFinishedSession(
        balls: Int,
        timestamp: Long,
        runs: List<Int>,
        durationSeconds: Long,
        runDurationsMillis: List<Long>,
    ) {
        if (repository != null) {
            repository.importSession(balls, timestamp, runs, durationSeconds, runDurationsMillis)

            // Reload sessions from repository so the UI reflects the new data.
            repository.getSessions().let { sessions ->
                completedSessions.clear()
                completedSessions.addAll(sessions)
            }
        } else {
            // No repository (likely unit test), just calculate summary locally
            val avg = runs.average()
            val bestRun = runs.maxOrNull() ?: 0
            val stdDev = if (runs.size > 1) {
                sqrt(runs.sumOf { (it - avg) * (it - avg) } / runs.size)
            } else 0.0

            val summary = SessionSummary(
                id = completedSessions.size + 1,
                timestamp = timestamp,
                ballCount = balls,
                runCount = runs.size,
                avgThrows = avg,
                stdDevThrows = stdDev,
                avgConsistency = 0.0,
                bestRun = bestRun,
                totalThrows = runs.sum(),
                runHistory = runs,
                durationSeconds = durationSeconds,
                runDurationsMillis = runDurationsMillis,
            )
            completedSessions.add(0, summary)
        }
    }


    fun deleteSession(session: SessionSummary) {
        completedSessions.remove(session)
        repository?.deleteSession(session)
    }

    fun getSessionsCsv(): String {
        val builder = StringBuilder()
        builder.append("Date,Ball Count,Run Count,Session Duration Seconds,Watch Hand Average,Watch Hand Best,Watch Hand Total,Run Durations Millis,Watch Hand Run History\n")
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        
        completedSessions.forEach { session ->
            val date = dateFormat.format(java.util.Date(session.timestamp))
            val runHistory = session.runHistory.joinToString(";")
            val runDurationsMillis = session.runDurationsMillis.joinToString(";")
            builder.append("$date,${session.ballCount},${session.runCount},${session.durationSeconds},${"%.2f".format(session.avgThrows)},${session.bestRun},${session.totalThrows},\"$runDurationsMillis\",\"$runHistory\"\n")
        }
        
        return builder.toString()
    }

    private fun parseLongList(value: Any?): List<Long> {
        val raw = value as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? Number)?.toLong()?.coerceAtLeast(0L) }
    }

    private fun normalizeRunDurations(runCount: Int, runDurationsMillis: List<Long>): List<Long> {
        val sanitized = runDurationsMillis.take(runCount)
        if (sanitized.size == runCount) return sanitized
        return sanitized + List(runCount - sanitized.size) { 0L }
    }

    // ── Recording support ──────────────────────────────────────────────

    /** Import a recording payload received from the Garmin watch. Catches are watch-hand catches. */
    fun importRecordingFromWatch(payload: Map<String, Any>) {
        val balls = (payload["balls"] as? Number)?.toInt() ?: return
        val catches = (payload["catches"] as? Number)?.toInt() ?: return
        val detected = (payload["detected"] as? Number)?.toInt() ?: 0
        val sampleRate = (payload["sampleRate"] as? Number)?.toInt() ?: 25
        val timestamp = (payload["timestamp"] as? Number)?.toLong() ?: return

        analytics?.logEvent("import_recording", Bundle().apply {
            putInt("ball_count", balls)
            putInt("catches", catches)
            putString("source", "garmin_watch")
        })

        @Suppress("UNCHECKED_CAST")
        val xRaw = payload["accelX"] as? List<Any> ?: return
        @Suppress("UNCHECKED_CAST")
        val yRaw = payload["accelY"] as? List<Any> ?: return
        @Suppress("UNCHECKED_CAST")
        val zRaw = payload["accelZ"] as? List<Any> ?: return

        val xs = xRaw.mapNotNull { (it as? Number)?.toInt() }
        val ys = yRaw.mapNotNull { (it as? Number)?.toInt() }
        val zs = zRaw.mapNotNull { (it as? Number)?.toInt() }

        recordingRepository?.saveRecording(
            balls = balls,
            catches = catches,
            detected = detected,
            sampleRate = sampleRate,
            timestamp = timestamp,
            accelX = xs,
            accelY = ys,
            accelZ = zs,
        )
        recordingCount = recordingRepository?.recordingCount() ?: 0
    }

    /** Merged CSV of all stored recordings for export. */
    fun getRecordingsCsv(): String {
        return recordingRepository?.exportAllCsv() ?: ""
    }

    /** Delete all stored recordings. */
    fun clearRecordings() {
        recordingRepository?.clearAll()
        recordingCount = 0
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
        phoneSessionState = PhoneSessionUiState(
            selectedBallCount = sanitizedBallCount,
            isRecording = true,
            statusMessage = "Waiting for juggling input",
        )

        analytics?.logEvent("start_phone_session", Bundle().apply {
            putInt("ball_count", sanitizedBallCount)
        })
    }

    fun processPhoneSample(ax: Double, ay: Double, az: Double, timestampNanos: Long) {
        val detector = phoneDetector ?: return
        if (!phoneSessionState.isRecording) return

        val sampleMs = timestampNanos / 1_000_000L
        val lastProcessed = phoneLastProcessedSampleMillis
        if (lastProcessed != null && sampleMs - lastProcessed < PhoneJugglingDetector.SAMPLE_PERIOD_MS) {
            return
        }

        if (phoneSessionStartSampleMillis == null) {
            phoneSessionStartSampleMillis = sampleMs
        }
        phoneLastProcessedSampleMillis = sampleMs

        detector.processSample(ax, ay, az, sampleMs)
        detector.checkAutoFinish(sampleMs)
        updatePhoneSessionStateFromDetector(sampleMs)
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
            else -> "Waiting for juggling input"
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
        phoneSessionState = PhoneSessionUiState(
            selectedBallCount = selectedBallCount,
            statusMessage = statusMessage,
        )
    }
}
