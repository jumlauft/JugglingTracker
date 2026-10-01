package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.juggling.tracker.util.CrashlyticsUtils
import androidx.core.content.edit
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.parseShapeConsistency
import com.juggling.tracker.model.summarizeSession
import androidx.compose.runtime.mutableStateListOf

class SessionRepository(private val sharedPrefs: SharedPreferences) {
    companion object {
        private const val TAG = "SessionRepository"
        private const val PREFS_NAME = "juggling_sessions"
    }

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val sessionsKey = "sessions_json"
    
    // In-memory cache of sessions
    private val sessionsCache = mutableStateListOf<SessionSummary>()
    
    init {
        loadSessionsFromStorage()
    }
    
    fun getSessions(): List<SessionSummary> = sessionsCache.toList()

    // Import a single finished session transferred from a watch.
    // The watch sends the ball count, a timestamp, the watch-hand catch count and
    // first-to-last-catch duration of every run, plus total session duration.
    // Each transfer becomes one SessionSummary, keyed by its timestamp: a
    // transfer under a timestamp already stored replaces that session. A
    // plain retransmission replaces it with the same data, and a session the
    // user carried on with after a lost ack comes back with more runs.
    // Returns the stored summary, or null when there were no runs to store.
    fun importSession(
        ballCount: Int,
        timestamp: Long,
        runs: List<Int>,
        durationSeconds: Long = 0L,
        runDurationsMillis: List<Long> = emptyList(),
        shapeConsistency: Int? = null,
    ): SessionSummary? {
        if (runs.isEmpty()) return null

        val session = summarizeSession(
            timestamp, ballCount, runs, durationSeconds, runDurationsMillis, shapeConsistency,
        )
        val existing = sessionsCache.indexOfFirst { it.timestamp == timestamp }
        if (existing >= 0) {
            sessionsCache[existing] = session
        } else {
            sessionsCache.add(0, session)
        }
        saveSessionsToStorage()
        return session
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
            sharedPrefs.edit { putString(sessionsKey, "[$json]") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions to storage", e)
            CrashlyticsUtils.recordException(e)
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
                val runDurationsMillis = readLongArray(obj, "runDurationsMillis")
                
                sessions.add(
                    SessionSummary(
                        timestamp = obj.getLong("timestamp"),
                        ballCount = obj.getInt("ballCount"),
                        runCount = obj.getInt("runCount"),
                        avgThrows = obj.getDouble("avgThrows"),
                        stdDevThrows = obj.getDouble("stdDevThrows"),
                        bestRun = obj.getInt("bestRun"),
                        totalThrows = obj.getInt("totalThrows"),
                        runHistory = runHistory,
                        durationSeconds = obj.optLong("durationSeconds", 0L),
                        runDurationsMillis = runDurationsMillis,
                        shapeConsistency = if (obj.has("shapeConsistency")) {
                            parseShapeConsistency(obj.getInt("shapeConsistency"))
                        } else null,
                    )
                )
            }
            
            sessionsCache.clear()
            // distinctBy timestamp, not just sort: importSession never stores
            // a second session under one timestamp, but storage written by an
            // older build can still hold one, and the timestamp is the
            // session's identity.
            sessionsCache.addAll(
                sessions.sortedByDescending { it.timestamp }.distinctBy { it.timestamp }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions from storage", e)
            CrashlyticsUtils.recordException(e)
        }
    }
    
    private fun buildSessionJson(session: SessionSummary): String {
        return try {
            val json = org.json.JSONObject()
            json.put("timestamp", session.timestamp)
            json.put("ballCount", session.ballCount)
            json.put("runCount", session.runCount)
            json.put("avgThrows", session.avgThrows)
            json.put("stdDevThrows", session.stdDevThrows)
            json.put("bestRun", session.bestRun)
            json.put("totalThrows", session.totalThrows)
            json.put("runHistory", org.json.JSONArray(session.runHistory))
            json.put("durationSeconds", session.durationSeconds)
            json.put("runDurationsMillis", org.json.JSONArray(session.runDurationsMillis))
            session.shapeConsistency?.let { json.put("shapeConsistency", it) }
            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build session JSON", e)
            CrashlyticsUtils.recordException(e)
            "{}"
        }
    }
    
    private fun readLongArray(obj: org.json.JSONObject, key: String): List<Long> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index -> array.getLong(index).coerceAtLeast(0L) }
    }
}
