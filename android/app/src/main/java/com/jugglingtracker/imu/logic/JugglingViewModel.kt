package com.jugglingtracker.imu.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugglingtracker.imu.model.SessionSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
}

class JugglingViewModel : ViewModel() {
    // Settings
    var isVoiceEnabled by mutableStateOf(value = true)
    var voiceInterval by mutableIntStateOf(10)

    // Event Flow
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    // Session State
    var isSessionActive by mutableStateOf(value = false)
        private set
    
    var ballCount by mutableIntStateOf(3)
        private set

    val completedSessions = mutableStateListOf<SessionSummary>()
    
    // Current Run Data (Last received from watch)
    var lastRunThrows by mutableIntStateOf(0)
        private set
    
    val runHistory = mutableStateListOf<Int>()
    
    // Live Graph Data (Received raw IMU data from watch if available)
    val history = mutableStateListOf<FloatArray>()
    private val maxHistorySize = 100

    fun onRunFinished(throws: Int) {
        if (!isSessionActive) return
        
        lastRunThrows = throws
        runHistory.add(throws)
        
        // Announcement Logic
        if (isVoiceEnabled && throws >= voiceInterval) {
            viewModelScope.launch {
                _events.emit(JugglingEvent.Announcement(throws.toString()))
            }
        }
    }

    fun onRawImuData(x: Float, y: Float, z: Float) {
        // Calculate magnitude or vertical acceleration if possible. 
        // For now, let's just use magnitude for visualization.
        val magnitude = sqrt(x * x + y * y + z * z)
        history.add(floatArrayOf(x, y, z, magnitude))
        if (history.size > maxHistorySize) {
            history.removeAt(0)
        }
    }

    fun startSession(balls: Int) {
        ballCount = balls
        runHistory.clear()
        lastRunThrows = 0
        isSessionActive = true
    }

    fun finishSession() {
        if (runHistory.isNotEmpty()) {
            val avg = runHistory.average()
            val stdDev = if (runHistory.size > 1) {
                sqrt(runHistory.sumOf { (it - avg) * (it - avg) } / runHistory.size)
            } else 0.0

            completedSessions.add(
                index = 0,
                element = SessionSummary(
                    id = completedSessions.size + 1,
                    timestamp = System.currentTimeMillis(),
                    ballCount = ballCount,
                    runCount = runHistory.size,
                    avgThrows = avg,
                    stdDevThrows = stdDev,
                    avgConsistency = 0.0,
                    bestRun = runHistory.maxOrNull() ?: 0,
                    totalThrows = runHistory.sum(),
                    runHistory = runHistory.toList(),
                ),
            )
        }
        isSessionActive = false
        lastRunThrows = 0
        runHistory.clear()
    }

    fun clearAllHistory() {
        completedSessions.clear()
    }

    fun deleteSession(session: SessionSummary) {
        completedSessions.remove(session)
    }
}
