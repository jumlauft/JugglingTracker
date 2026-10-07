package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.juggling.tracker.util.CrashlyticsUtils
import androidx.core.content.edit
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.parseShapeConsistency
import com.juggling.tracker.model.summarizeSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The finished-session history.
 *
 * Sessions live in [file] as JSON Lines: one session object per line, oldest
 * first. Importing a session appends one line, so the common case no longer
 * re-serialises the whole history. Only a delete rewrites the file (to a temp
 * file, then renamed over it, so a crash mid-write leaves the old history).
 *
 * Disk writes run in order on [writer], off the caller's thread; the in-memory
 * list is updated at once, so [getSessions] never waits on the disk.
 *
 * Older builds kept the whole history as one JSON array string under
 * `sessions_json` in [sharedPrefs]. The first load moves it into [file] and
 * removes the key, keeping it if the move fails.
 */
class SessionRepository(
    private val sharedPrefs: SharedPreferences,
    private val file: File,
    private val writer: Executor = Executors.newSingleThreadExecutor(),
) {
    companion object {
        private const val TAG = "SessionRepository"
        private const val PREFS_NAME = "juggling_sessions"
        private const val FILE_NAME = "sessions.jsonl"
        private const val LEGACY_SESSIONS_KEY = "sessions_json"
    }

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        File(context.filesDir, FILE_NAME),
    )

    // In-memory copy of the history, newest first. Guarded by `this`.
    private val sessionsCache = mutableListOf<SessionSummary>()

    init {
        loadSessionsFromStorage()
    }

    @Synchronized
    fun getSessions(): List<SessionSummary> = sessionsCache.toList()

    // Import a single finished session transferred from a watch.
    // The watch sends the ball count, a timestamp, the watch-hand catch count and
    // first-to-last-catch duration of every run, plus total session duration.
    // Each transfer becomes one SessionSummary, keyed by its timestamp: a
    // transfer under a timestamp already stored replaces that session. A
    // plain retransmission replaces it with the same data, and a session the
    // user carried on with after a lost ack comes back with more runs.
    // Returns the stored summary, or null when there were no runs to store.
    @Synchronized
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
            // Replacing a stored session rewrites the file; the common case,
            // a new session, only appends.
            sessionsCache[existing] = session
            saveSessionsToStorage()
        } else {
            sessionsCache.add(0, session)
            appendToStorage(session)
        }
        return session
    }

    /**
     * Add the sessions of a backup that are not stored yet (see
     * [SessionCsv.newSessions]); sessions already here are kept as they are.
     * Returns how many were added.
     */
    @Synchronized
    fun restoreSessions(backup: List<SessionSummary>): Int {
        val added = SessionCsv.newSessions(backup, sessionsCache)
        if (added.isEmpty()) return 0
        sessionsCache.addAll(added)
        sessionsCache.sortByDescending { it.timestamp }
        saveSessionsToStorage()
        return added.size
    }

    @Synchronized
    fun deleteSession(session: SessionSummary) {
        if (sessionsCache.remove(session)) {
            saveSessionsToStorage()
        }
    }
    
    /** Queue one new session to be appended to [file]. Call with the lock held. */
    private fun appendToStorage(session: SessionSummary) {
        val line = buildSessionJson(session) + "\n"
        writer.execute {
            try {
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append session to storage", e)
                CrashlyticsUtils.recordException(e)
            }
        }
    }

    /** Queue a rewrite of [file] with the whole history. Call with the lock held. */
    private fun saveSessionsToStorage() {
        val lines = sessionsCache.asReversed().map { buildSessionJson(it) }
        writer.execute {
            try {
                writeAll(lines)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save sessions to storage", e)
                CrashlyticsUtils.recordException(e)
            }
        }
    }

    private fun writeAll(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            lines.forEach { w.write(it); w.newLine() }
        }
        if (!tmp.renameTo(file)) {
            // renameTo does not replace an existing file on every platform.
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}")
        }
    }

    private fun loadSessionsFromStorage() {
        val sessions = if (file.exists()) {
            readSessionsFile()
        } else {
            migrateLegacySessions()
        }
        // A key left by an earlier move that wrote the file but did not get to
        // remove the key.
        if (file.exists() && sharedPrefs.contains(LEGACY_SESSIONS_KEY)) {
            sharedPrefs.edit { remove(LEGACY_SESSIONS_KEY) }
        }

        sessionsCache.clear()
        // distinctBy timestamp, not just sort: importSession never stores a
        // second session under one timestamp, but storage written by an
        // older build can still hold one, and the timestamp is the
        // session's identity.
        sessionsCache.addAll(
            sessions.sortedByDescending { it.timestamp }.distinctBy { it.timestamp }
        )
    }

    /** Every readable line of [file]. A line cut short by a crash is skipped. */
    private fun readSessionsFile(): List<SessionSummary> {
        return try {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try {
                    parseSession(JSONObject(line))
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping unreadable session line", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions from storage", e)
            CrashlyticsUtils.recordException(e)
            emptyList()
        }
    }

    /**
     * Read the history an older build stored in SharedPreferences and write it
     * to [file]. Runs once, on the first load without [file].
     */
    private fun migrateLegacySessions(): List<SessionSummary> {
        val jsonString = sharedPrefs.getString(LEGACY_SESSIONS_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val sessions = List(jsonArray.length()) { i -> parseSession(jsonArray.getJSONObject(i)) }
            // Synchronous on purpose: the key is only removed once the file
            // holds every session, and this happens once per install.
            writeAll(sessions.sortedBy { it.timestamp }.map { buildSessionJson(it) })
            sharedPrefs.edit { remove(LEGACY_SESSIONS_KEY) }
            sessions
        } catch (e: Exception) {
            // Keep the key: a later build may still be able to read it.
            Log.e(TAG, "Failed to move sessions out of SharedPreferences", e)
            CrashlyticsUtils.recordException(e)
            emptyList()
        }
    }

    private fun parseSession(obj: JSONObject): SessionSummary {
        val runHistoryArray = obj.getJSONArray("runHistory")
        val runHistory = List(runHistoryArray.length()) { j -> runHistoryArray.getInt(j) }
        return SessionSummary(
            timestamp = obj.getLong("timestamp"),
            ballCount = obj.getInt("ballCount"),
            runCount = obj.getInt("runCount"),
            avgThrows = obj.getDouble("avgThrows"),
            stdDevThrows = obj.getDouble("stdDevThrows"),
            bestRun = obj.getInt("bestRun"),
            totalThrows = obj.getInt("totalThrows"),
            runHistory = runHistory,
            durationSeconds = obj.optLong("durationSeconds", 0L),
            runDurationsMillis = readLongArray(obj, "runDurationsMillis"),
            shapeConsistency = if (obj.has("shapeConsistency")) {
                parseShapeConsistency(obj.getInt("shapeConsistency"))
            } else null,
        )
    }

    private fun buildSessionJson(session: SessionSummary): String {
        return try {
            val json = JSONObject()
            json.put("timestamp", session.timestamp)
            json.put("ballCount", session.ballCount)
            json.put("runCount", session.runCount)
            json.put("avgThrows", session.avgThrows)
            json.put("stdDevThrows", session.stdDevThrows)
            json.put("bestRun", session.bestRun)
            json.put("totalThrows", session.totalThrows)
            json.put("runHistory", JSONArray(session.runHistory))
            json.put("durationSeconds", session.durationSeconds)
            json.put("runDurationsMillis", JSONArray(session.runDurationsMillis))
            session.shapeConsistency?.let { json.put("shapeConsistency", it) }
            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build session JSON", e)
            CrashlyticsUtils.recordException(e)
            "{}"
        }
    }
    
    private fun readLongArray(obj: JSONObject, key: String): List<Long> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index -> array.getLong(index).coerceAtLeast(0L) }
    }
}
