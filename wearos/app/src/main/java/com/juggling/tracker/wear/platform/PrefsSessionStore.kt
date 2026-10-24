package com.juggling.tracker.wear.platform

import android.content.Context
import com.juggling.tracker.wear.logic.KeptSession
import com.juggling.tracker.wear.logic.SessionStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A session kept with Continue later, as JSON in SharedPreferences, so it
 * survives the app closing and the watch restarting (LATER-1). The
 * counterpart of the Garmin's Application.Storage.
 */
class PrefsSessionStore(context: Context) : SessionStore {
    companion object {
        private const val PREFS = "kept_session"
        private const val KEY = "session"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): KeptSession? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(text)
            KeptSession(
                balls = json.getInt("balls"),
                elapsedSeconds = json.getLong("elapsedSeconds"),
                runs = json.getJSONArray("runs").let { a -> List(a.length()) { a.getInt(it) } },
                runDurationsMillis = json.getJSONArray("runDurationsMillis").let { a -> List(a.length()) { a.getLong(it) } },
                shapeSums = json.getJSONArray("shapeSums").let { a -> List(a.length()) { a.getDouble(it) } },
                shapeCounts = json.getJSONArray("shapeCounts").let { a -> List(a.length()) { a.getInt(it) } },
                timestamp = if (json.has("timestamp")) json.getLong("timestamp") else null,
            )
        } catch (e: JSONException) {
            null
        }
    }

    override fun save(session: KeptSession) {
        val json = JSONObject()
            .put("balls", session.balls)
            .put("elapsedSeconds", session.elapsedSeconds)
            .put("runs", JSONArray(session.runs))
            .put("runDurationsMillis", JSONArray(session.runDurationsMillis))
            .put("shapeSums", JSONArray(session.shapeSums))
            .put("shapeCounts", JSONArray(session.shapeCounts))
        session.timestamp?.let { json.put("timestamp", it) }
        // commit, not apply: the app may close right after.
        prefs.edit().putString(KEY, json.toString()).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }
}
