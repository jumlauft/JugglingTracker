package com.jugglingtracker.imu.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugglingtracker.imu.model.SessionSummary
import com.jugglingtracker.imu.data.SessionRepository
import com.jugglingtracker.imu.data.RecordingRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
    data class SyncCompleted(val count: Int, val ballCount: Int) : JugglingEvent()
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

class JugglingViewModel(
    private val repository: SessionRepository? = null,
    private val recordingRepository: RecordingRepository? = null,
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
        val runDurationsMillis = normalizeRunDurations(runs.size, parseLongList(payload["runDurationsMillis"]))

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

        viewModelScope.launch {
            _events.emit(JugglingEvent.SyncCompleted(runs.size, balls))
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
}
