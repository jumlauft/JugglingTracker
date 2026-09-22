package com.juggling.tracker.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SettingsManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import org.tensorflow.lite.Interpreter
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.content.Context

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
    CONNECT_IQ_MISSING,
    DISCONNECTED,
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
) : ViewModel() {
    // ML State
    private var interpreter3: Interpreter? = null
    private var interpreter4: Interpreter? = null
    private var interpreter5: Interpreter? = null
    
    private var norm3: Pair<FloatArray, FloatArray>? = null
    private var norm4: Pair<FloatArray, FloatArray>? = null
    private var norm5: Pair<FloatArray, FloatArray>? = null

    // Garmin Status
    var garminStatus by mutableStateOf(GarminConnectionStatus.NOT_INITIALIZED)
    var statusMessage by mutableStateOf("")

    // Settings
    val isAnalyticsEnabled get() = settings?.isAnalyticsEnabled ?: true
    val isVoiceEnabled get() = settings?.isVoiceEnabled ?: true
    val voiceInterval get() = settings?.voiceInterval ?: 10
    val isMlEnabled get() = settings?.isMlEnabled ?: true

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

    fun toggleMl(enabled: Boolean) {
        settings?.updateMlEnabled(enabled)
    }

    // Event Flow
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    fun initML(context: android.content.Context) {
        try {
            interpreter3 = Interpreter(loadModelFile(context, "catch_detector_3.tflite"))
            interpreter4 = Interpreter(loadModelFile(context, "catch_detector_4.tflite"))
            interpreter5 = Interpreter(loadModelFile(context, "catch_detector_5.tflite"))

            norm3 = loadNormParams(context, "norm_params_3.json")
            norm4 = loadNormParams(context, "norm_params_4.json")
            norm5 = loadNormParams(context, "norm_params_5.json")
            
            statusMessage = "Specialized ML models ready"
        } catch (e: Exception) {
            android.util.Log.e("JugglingViewModel", "Failed to init specialized ML detectors", e)
        }
    }

    private fun loadNormParams(context: android.content.Context, fileName: String): Pair<FloatArray, FloatArray> {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        val meanArray = json.getJSONArray("mean")
        val stdArray = json.getJSONArray("std")
        val mean = FloatArray(meanArray.length()) { i -> meanArray.getDouble(i).toFloat() }
        val std = FloatArray(stdArray.length()) { i -> stdArray.getDouble(i).toFloat() }
        return Pair(mean, std)
    }

    private fun loadModelFile(context: android.content.Context, modelName: String): java.nio.MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // Recording state
    var recordingCount by mutableIntStateOf(recordingRepository?.recordingCount() ?: 0)
        private set
    var recordings by mutableStateOf(recordingRepository?.listRecordings() ?: emptyList())
        private set

    var phoneSessionState by mutableStateOf(PhoneSessionUiState())
        private set

    var rawRecordingState by mutableStateOf(RawRecordingUiState())
        private set

    private val rawAccelX = mutableListOf<Int>()
    private val rawAccelY = mutableListOf<Int>()
    private val rawAccelZ = mutableListOf<Int>()
    private var rawRecordingStartedAtMillis: Long? = null

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
    // ── Chunked recording transfer ──────────────────────────────────────
    //
    // The watch cannot send a dictionary holding hundreds of numbers, so a run
    // arrives as rec_start, a series of rec_chunk parts, then rec_end. Only
    // rec_end is acknowledged, after the run has been written.

    private class IncomingRecording(
        val id: Long,
        val balls: Int,
        val catches: Int,
        val detected: Int,
        val sampleRate: Int,
        val totalSamples: Int,
        val totalChunks: Int,
    ) {
        val x = mutableListOf<Int>()
        val y = mutableListOf<Int>()
        val z = mutableListOf<Int>()
        var nextChunk = 0
    }

    private var incoming: IncomingRecording? = null

    fun startRecordingTransfer(payload: Map<String, Any>) {
        val id = (payload["id"] as? Number)?.toLong() ?: return
        incoming = IncomingRecording(
            id = id,
            balls = (payload["balls"] as? Number)?.toInt() ?: return,
            catches = (payload["catches"] as? Number)?.toInt() ?: return,
            detected = (payload["detected"] as? Number)?.toInt() ?: 0,
            sampleRate = (payload["sampleRate"] as? Number)?.toInt() ?: 25,
            totalSamples = (payload["samples"] as? Number)?.toInt() ?: 0,
            totalChunks = (payload["chunks"] as? Number)?.toInt() ?: 0,
        )
    }

    fun appendRecordingChunk(payload: Map<String, Any>) {
        val run = incoming ?: return
        if ((payload["id"] as? Number)?.toLong() != run.id) return

        val index = (payload["i"] as? Number)?.toInt() ?: return
        if (index < run.nextChunk) {
            // Already have this one. The transport can deliver a message more
            // than once, which must not be mistaken for corruption.
            return
        }
        if (index > run.nextChunk) {
            // A chunk was lost; the run would be corrupt, so drop it rather
            // than write bad training data.
            incoming = null
            return
        }

        @Suppress("UNCHECKED_CAST")
        val xs = (payload["x"] as? List<Any>)?.mapNotNull { (it as? Number)?.toInt() } ?: return
        @Suppress("UNCHECKED_CAST")
        val ys = (payload["y"] as? List<Any>)?.mapNotNull { (it as? Number)?.toInt() } ?: return
        @Suppress("UNCHECKED_CAST")
        val zs = (payload["z"] as? List<Any>)?.mapNotNull { (it as? Number)?.toInt() } ?: return

        run.x.addAll(xs)
        run.y.addAll(ys)
        run.z.addAll(zs)
        run.nextChunk = index + 1
    }

    /** Returns true when a complete run was written. */
    fun finishRecordingTransfer(payload: Map<String, Any>): Boolean {
        val run = incoming ?: return false
        incoming = null
        if ((payload["id"] as? Number)?.toLong() != run.id) return false
        if (run.nextChunk != run.totalChunks) return false
        if (run.x.size != run.totalSamples) return false

        analytics?.logEvent("import_recording", Bundle().apply {
            putInt("ball_count", run.balls)
            putInt("catches", run.catches)
            putString("source", "garmin_watch")
        })

        recordingRepository?.saveRecording(
            balls = run.balls,
            catches = run.catches,
            detected = run.detected,
            sampleRate = run.sampleRate,
            timestamp = run.id,
            accelX = run.x,
            accelY = run.y,
            accelZ = run.z,
        )
        recordingCount = recordingRepository?.recordingCount() ?: 0
        recordings = recordingRepository?.listRecordings() ?: emptyList()
        return true
    }

    /** Merged CSV of all stored recordings for export. */
    fun getRecordingsCsv(): String {
        return recordingRepository?.exportAllCsv() ?: ""
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
        rawRecordingStartedAtMillis = System.currentTimeMillis()
        
        // Also start a detector just to show counts in UI if we want
        val useMl = isMlEnabled
        val (interpreter, norm) = getMLAssets(balls)
        
        phoneDetector = PhoneJugglingDetector(
            balls, 
            if (useMl) interpreter else null, 
            if (useMl) norm?.first else null, 
            if (useMl) norm?.second else null
        )
        phoneSessionStartSampleMillis = null
        phoneLastProcessedSampleMillis = null
    }

    private fun getMLAssets(balls: Int): Pair<Interpreter?, Pair<FloatArray, FloatArray>?> {
        return when {
            balls <= 3 -> Pair(interpreter3, norm3)
            balls == 4 -> Pair(interpreter4, norm4)
            else -> Pair(interpreter5, norm5)
        }
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
            sampleRate = 200, // Phone target rate is 200Hz
            timestamp = timestamp / 1000L, // store as epoch seconds to match watch
            accelX = rawAccelX,
            accelY = rawAccelY,
            accelZ = rawAccelZ
        )
        
        recordingCount = recordingRepository?.recordingCount() ?: 0
        recordings = recordingRepository?.listRecordings() ?: emptyList()
        cancelRawRecording()
    }

    fun cancelRawRecording() {
        rawRecordingState = RawRecordingUiState(step = RawRecordingStep.IDLE)
        rawAccelX.clear()
        rawAccelY.clear()
        rawAccelZ.clear()
        phoneDetector = null
    }

    // ── Phone IMU session support ──────────────────────────────────────

    fun selectPhoneBallCount(ballCount: Int) {
        if (phoneSessionState.isRecording) return
        phoneSessionState = phoneSessionState.copy(selectedBallCount = ballCount.coerceIn(3, 9))
    }

    fun startPhoneSession(ballCount: Int, startedAtMillis: Long = System.currentTimeMillis()) {
        val sanitizedBallCount = ballCount.coerceIn(3, 9)
        val useMl = isMlEnabled
        val (interpreter, norm) = getMLAssets(sanitizedBallCount)
        
        phoneDetector = PhoneJugglingDetector(
            sanitizedBallCount, 
            if (useMl) interpreter else null, 
            if (useMl) norm?.first else null, 
            if (useMl) norm?.second else null
        )
        phoneSessionStartedAtMillis = startedAtMillis
        phoneSessionStartSampleMillis = null
        phoneLastProcessedSampleMillis = null
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

        val oldCount = detector.currentCount
        detector.processSample(ax, ay, az, sampleMs)
        val newCount = detector.currentCount
        detector.checkAutoFinish(sampleMs)
        
        if (newCount > oldCount) {
            checkVoiceAnnouncement(newCount)
        }

        updatePhoneSessionStateFromDetector(sampleMs)
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
        phoneSessionState = PhoneSessionUiState(
            selectedBallCount = selectedBallCount,
            statusMessage = statusMessage,
        )
    }
}
