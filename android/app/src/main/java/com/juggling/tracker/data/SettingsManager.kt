package com.juggling.tracker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("juggling_settings", Context.MODE_PRIVATE)

    var isAnalyticsEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_ANALYTICS, true))
        private set

    var isVoiceEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_VOICE, true))
        private set

    var voiceInterval: Int by mutableIntStateOf(prefs.getInt(KEY_VOICE_INTERVAL, 10))
        private set

    /** Who juggles, as last entered when exporting recordings; blank until then. */
    var jugglerName: String by mutableStateOf(prefs.getString(KEY_JUGGLER_NAME, null) ?: "")
        private set

    /** The wrist the watch is on, as last picked when exporting; null until then. */
    var watchHand: String? by mutableStateOf(prefs.getString(KEY_WATCH_HAND, null))
        private set

    fun updateExportDetails(name: String, hand: String) {
        jugglerName = name
        watchHand = hand
        prefs.edit().putString(KEY_JUGGLER_NAME, name).putString(KEY_WATCH_HAND, hand).apply()
    }

    fun updateAnalyticsEnabled(enabled: Boolean) {
        isAnalyticsEnabled = enabled
        prefs.edit().putBoolean(KEY_ANALYTICS, enabled).apply()
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        isVoiceEnabled = enabled
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun updateVoiceInterval(interval: Int) {
        voiceInterval = interval
        prefs.edit().putInt(KEY_VOICE_INTERVAL, interval).apply()
    }

    companion object {
        private const val KEY_ANALYTICS = "analytics_enabled"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_INTERVAL = "voice_interval"
        private const val KEY_JUGGLER_NAME = "juggler_name"
        private const val KEY_WATCH_HAND = "watch_hand"
    }
}
