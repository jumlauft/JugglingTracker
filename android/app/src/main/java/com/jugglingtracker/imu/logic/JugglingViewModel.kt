package com.jugglingtracker.imu.logic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jugglingtracker.imu.model.SessionSummary
import com.jugglingtracker.imu.data.SessionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

sealed class JugglingEvent {
    data class Announcement(val text: String) : JugglingEvent()
    data class SyncCompleted(val count: Int) : JugglingEvent()
}

class JugglingViewModel(private val repository: SessionRepository? = null) : ViewModel() {
    // Settings
    var isVoiceEnabled by mutableStateOf(value = true)
    var voiceInterval by mutableIntStateOf(10)

    // Event Flow
    private val _events = MutableSharedFlow<JugglingEvent>()
    val events = _events.asSharedFlow()

    val completedSessions = mutableStateListOf<SessionSummary>()

    init {
        // Load any previously stored sessions on startup.
        repository?.getSessions()?.let { sessions ->
            completedSessions.clear()
            completedSessions.addAll(sessions)
        }
    }

    // Import a single finished session transferred from the Garmin watch.
    // Payload shape: { type: "session", balls: Int, timestamp: Long (epoch s),
    // runs: List<Number> }. The watch is the sole session controller; the phone
    // only listens and records what it receives.
    fun importSessionFromWatch(payload: Map<String, Any>) {
        val balls = (payload["balls"] as? Number)?.toInt() ?: return
        val timestamp = (payload["timestamp"] as? Number)?.toLong() ?: return
        @Suppress("UNCHECKED_CAST")
        val runsRaw = payload["runs"] as? List<Any> ?: return
        val runs = runsRaw.mapNotNull { (it as? Number)?.toInt() }
        if (runs.isEmpty()) return

        repository?.importSession(balls, timestamp, runs)

        // Reload sessions from repository so the UI reflects the new data.
        repository?.getSessions()?.let { sessions ->
            completedSessions.clear()
            completedSessions.addAll(sessions)
        }

        viewModelScope.launch {
            _events.emit(JugglingEvent.SyncCompleted(runs.size))
        }
    }

    fun clearAllHistory() {
        completedSessions.clear()
    }

    fun deleteSession(session: SessionSummary) {
        completedSessions.remove(session)
    }
}
