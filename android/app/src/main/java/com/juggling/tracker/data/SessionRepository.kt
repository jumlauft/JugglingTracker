package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import androidx.core.content.edit
import com.juggling.tracker.model.SessionSummary
import androidx.compose.runtime.mutableStateListOf
import kotlin.math.sqrt

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

    // Import a single finished session transferred from the Garmin watch.
    // The watch sends the ball count, a timestamp, the watch-hand catch count and
    // first-to-last-catch duration of every run, plus total session duration.
    // Each transfer becomes one SessionSummary. Transfers are de-duplicated by timestamp.
    fun importSession(
        ballCount: Int,
        timestamp: Long,
        runs: List<Int>,
        durationSeconds: Long = 0L,
        runDurationsMillis: List<Long> = emptyList(),
    ) {
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
            runHistory = runs,
            durationSeconds = durationSeconds,
            runDurationsMillis = normalizeRunDurations(runs.size, runDurationsMillis),
        )
        sessionsCache.add(0, session)
        saveSessionsToStorage()
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
            sharedPrefs.edit { putString(sessionsKey, "[$json]") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions to storage", e)
            FirebaseCrashlytics.getInstance().recordException(e)
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
                        id = obj.getInt("id"),
                        timestamp = obj.getLong("timestamp"),
                        ballCount = obj.getInt("ballCount"),
                        runCount = obj.getInt("runCount"),
                        avgThrows = obj.getDouble("avgThrows"),
                        stdDevThrows = obj.getDouble("stdDevThrows"),
                        avgConsistency = obj.optDouble("avgConsistency", 0.0),
                        bestRun = obj.getInt("bestRun"),
                        totalThrows = obj.getInt("totalThrows"),
                        runHistory = runHistory,
                        durationSeconds = obj.optLong("durationSeconds", 0L),
                        runDurationsMillis = runDurationsMillis,
                    )
                )
            }
            
            sessionsCache.clear()
            sessionsCache.addAll(sessions.sortedByDescending { it.timestamp })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions from storage", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun buildSessionJson(session: SessionSummary): String {
        return try {
            val json = org.json.JSONObject()
            json.put("id", session.id)
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
            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build session JSON", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            "{}"
        }
    }
    
    private fun parseSessionJson(json: org.json.JSONObject): SessionSummary? {
        return try {
            val id = json.getInt("id")
            val timestamp = json.getLong("timestamp")
            val ballCount = json.getInt("ballCount")
            val runCount = json.getInt("runCount")
            val avgThrows = json.getDouble("avgThrows")
            val stdDevThrows = json.getDouble("stdDevThrows")
            val avgConsistency = json.optDouble("avgConsistency", 0.0)
            val bestRun = json.getInt("bestRun")
            val totalThrows = json.getInt("totalThrows")
            val runHistory = json.getJSONArray("runHistory").toList()
            val durationSeconds = json.optLong("durationSeconds", 0L)
            val runDurationsMillis = readLongArray(json, "runDurationsMillis")
            SessionSummary(
                id = id,
                timestamp = timestamp,
                ballCount = ballCount,
                runCount = runCount,
                avgThrows = avgThrows,
                stdDevThrows = stdDevThrows,
                avgConsistency = avgConsistency,
                bestRun = bestRun,
                totalThrows = totalThrows,
                runHistory = runHistory,
                durationSeconds = durationSeconds,
                runDurationsMillis = runDurationsMillis,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session JSON", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            null
        }
    }

    private fun readLongArray(obj: org.json.JSONObject, key: String): List<Long> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index -> array.getLong(index).coerceAtLeast(0L) }
    }

    private fun normalizeRunDurations(runCount: Int, runDurationsMillis: List<Long>): List<Long> {
        val sanitized = runDurationsMillis.take(runCount).map { it.coerceAtLeast(0L) }
        if (sanitized.size == runCount) return sanitized
        return sanitized + List(runCount - sanitized.size) { 0L }
    }
}
