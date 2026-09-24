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
    }
}
