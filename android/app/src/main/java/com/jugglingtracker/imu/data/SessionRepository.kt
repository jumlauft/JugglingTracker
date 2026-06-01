package com.jugglingtracker.imu.data

import android.content.Context
import com.jugglingtracker.imu.model.JugglingRun
import com.jugglingtracker.imu.model.SessionSummary
import androidx.compose.runtime.mutableStateListOf
import kotlin.math.sqrt

class SessionRepository(context: Context) {
    private val sharedPrefs = context.getSharedPreferences("juggling_sessions", Context.MODE_PRIVATE)
    private val sessionsKey = "sessions_json"
    
    // In-memory cache of sessions
    private val sessionsCache = mutableStateListOf<SessionSummary>()
    
    init {
        loadSessionsFromStorage()
    }
    
    fun getSessions(): List<SessionSummary> = sessionsCache.toList()

    // Import a single finished session transferred from the Garmin watch.
    // The watch sends the ball count, a timestamp, and the throw count of every
    // run in the session. Each transfer becomes one SessionSummary. Transfers
    // are de-duplicated by timestamp so a retransmission is not counted twice.
    fun importSession(ballCount: Int, timestamp: Long, runs: List<Int>) {
        if (runs.isEmpty()) return

        // Ignore a session we already stored (e.g. a retransmission).
        if (sessionsCache.any { it.timestamp == timestamp }) return

        val avg = runs.average()
        val bestRun = runs.maxOrNull() ?: 0
        val stdDev = if (runs.size > 1) {
            sqrt(runs.sumOf { (it - avg) * (it - avg) } / runs.size)
        } else 0.0

        val session = SessionSummary(
            id = sessionsCache.size + 1,
            timestamp = timestamp,
            ballCount = ballCount,
            runCount = runs.size,
            avgThrows = avg,
            stdDevThrows = stdDev,
            avgConsistency = 0.0,
            bestRun = bestRun,
            totalThrows = runs.sum(),
            runHistory = runs
        )
        sessionsCache.add(0, session)
        saveSessionsToStorage()
    }

    // Import a batch of runs from Garmin watch and create/merge sessions
    fun importRunsBatch(
        runs: List<Map<String, Any>>,
        sessionMax: Number?,
        sessionAverage: Number?,
        sessionRuns: Number?
    ) {
        if (runs.isEmpty()) return
        
        // Convert map entries to JugglingRun objects
        val jugglingRuns = runs.mapNotNull { runMap ->
            try {
                JugglingRun(
                    timestamp = (runMap["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    ballCount = (runMap["balls"] as? Number)?.toInt() ?: 3,
                    throws = (runMap["throws"] as? Number)?.toInt() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
        
        if (jugglingRuns.isEmpty()) return
        
        // Check for duplicates based on timestamp
        val existingTimestamps = sessionsCache.map { it.timestamp }.toSet()
        
        val newRuns = jugglingRuns.filter { run ->
            !existingTimestamps.contains(run.timestamp)
        }
        
        if (newRuns.isNotEmpty()) {
            // Group runs by ballCount to create or update sessions
            val runsByBallCount = newRuns.groupBy { it.ballCount }
            
            for ((ballCount, ballRuns) in runsByBallCount) {
                val existingSession = sessionsCache.find { 
                    it.ballCount == ballCount && 
                    it.runHistory.isNotEmpty()
                }
                
                if (existingSession != null) {
                    // Merge with existing session
                    val updatedRunHistory = (existingSession.runHistory + ballRuns.map { it.throws })
                    val avg = updatedRunHistory.average()
                    val bestRun = updatedRunHistory.maxOrNull() ?: 0
                    val stdDev = if (updatedRunHistory.size > 1) {
                        sqrt(updatedRunHistory.sumOf { (it - avg) * (it - avg) } / updatedRunHistory.size)
                    } else 0.0
                    
                    val updatedSession = existingSession.copy(
                        runCount = updatedRunHistory.size,
                        avgThrows = avg,
                        stdDevThrows = stdDev,
                        bestRun = bestRun,
                        totalThrows = updatedRunHistory.sum(),
                        runHistory = updatedRunHistory
                    )
                    
                    sessionsCache[sessionsCache.indexOf(existingSession)] = updatedSession
                } else {
                    // Create new session
                    val throwsList = ballRuns.map { it.throws }
                    val avg = throwsList.average()
                    val bestRun = throwsList.maxOrNull() ?: 0
                    val stdDev = if (throwsList.size > 1) {
                        sqrt(throwsList.sumOf { (it - avg) * (it - avg) } / throwsList.size)
                    } else 0.0
                    
                    val newSession = SessionSummary(
                        id = sessionsCache.size + 1,
                        timestamp = ballRuns.first().timestamp,
                        ballCount = ballCount,
                        runCount = throwsList.size,
                        avgThrows = avg,
                        stdDevThrows = stdDev,
                        avgConsistency = 0.0,
                        bestRun = bestRun,
                        totalThrows = throwsList.sum(),
                        runHistory = throwsList
                    )
                    sessionsCache.add(0, newSession)
                }
            }
            
            saveSessionsToStorage()
        }
    }
    
    fun addSession(session: SessionSummary) {
        sessionsCache.add(0, session)
        saveSessionsToStorage()
    }

    fun deleteSession(session: SessionSummary) {
        if (sessionsCache.remove(session)) {
            saveSessionsToStorage()
        }
    }
    
    private fun saveSessionsToStorage() {
        try {
            val json = sessionsCache.joinToString(",") { session ->
                buildSessionJson(session)
            }
            sharedPrefs.edit().putString(sessionsKey, "[$json]").apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }
    
    private fun loadSessionsFromStorage() {
        try {
            val jsonString = sharedPrefs.getString(sessionsKey, "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(jsonString)
            val sessions = mutableListOf<SessionSummary>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val runHistoryArray = obj.getJSONArray("runHistory")
                val runHistory = mutableListOf<Int>()
                for (j in 0 until runHistoryArray.length()) {
                    runHistory.add(runHistoryArray.getInt(j))
                }
                
                sessions.add(
                    SessionSummary(
                        id = obj.getInt("id"),
                        timestamp = obj.getLong("timestamp"),
                        ballCount = obj.getInt("ballCount"),
                        runCount = obj.getInt("runCount"),
                        avgThrows = obj.getDouble("avgThrows"),
                        stdDevThrows = obj.getDouble("stdDevThrows"),
                        avgConsistency = obj.optDouble("avgConsistency", 0.0),
                        bestRun = obj.getInt("bestRun"),
                        totalThrows = obj.getInt("totalThrows"),
                        runHistory = runHistory
                    )
                )
            }
            
            sessionsCache.clear()
            sessionsCache.addAll(sessions.sortedByDescending { it.timestamp })
        } catch (e: Exception) {
            // Handle error
        }
    }
    
    private fun buildSessionJson(session: SessionSummary): String {
        val runHistoryJson = session.runHistory.joinToString(",")
        return """{
            "id":${session.id},
            "timestamp":${session.timestamp},
            "ballCount":${session.ballCount},
            "runCount":${session.runCount},
            "avgThrows":${session.avgThrows},
            "stdDevThrows":${session.stdDevThrows},
            "bestRun":${session.bestRun},
            "totalThrows":${session.totalThrows},
            "runHistory":[$runHistoryJson]
        }"""
    }
}
